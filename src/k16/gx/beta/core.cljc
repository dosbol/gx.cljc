(ns k16.gx.beta.core
  (:refer-clojure :exclude [ref])
  (:require [clojure.walk :as walk]
            [k16.gx.beta.impl :as impl]
            [k16.gx.beta.schema :as gx.schema]
            [malli.core :as m]
            [malli.error :as me]
            [promesa.core :as p])
  (:import #?(:clj [clojure.lang ExceptionInfo])))

(defonce INITIAL_STATE :uninitialized)

(def locals #{'gx/ref 'gx/ref-maps 'gx/ref-map 'gx/ref-path})

(defn local-form?
  [form]
  (and (seq? form)
       (locals (first form))))

(def default-context
  {:signals {:gx/start {:order :topological
                        :from-states #{:stopped INITIAL_STATE}
                        :to-state :started}
             :gx/stop {:order :reverse-topological
                       :from-states #{:started}
                       :to-state :stopped
                       :deps-from :gx/start}}})

(defn resolve-symbol
  [sym]
  (when (symbol? sym)
    #?(:cljs (impl/namespace-symbol sym)
       :clj (some-> sym
                    (impl/namespace-symbol)
                    (requiring-resolve)
                    (var-get)))))

(defn ref
  [key]
  (list 'gx/ref key))

(defn ref-map
  [key]
  (list 'gx/ref-map key))

(defn ref-maps
  [& keys]
  (apply list (conj keys 'gx/ref-maps)))

(defn ref-path
  [& keys]
  (apply list (conj keys 'gx/ref-path)))

(defn parse-local
  [env form]
  (cond
    (= 'gx/ref (first form))
    (get env (second form))

    (= 'gx/ref-map (first form))
    {(second form) (get env (second form))}

    (= 'gx/ref-maps (first form))
    (select-keys env (rest form))

    (= 'gx/ref-path (first form))
    (get-in env [(second form) (nth form 2)])))

(defn postwalk-evaluate
  "A postwalk runtime signal processor evaluator, works most of the time.
  Doesn't support special symbols and macros, basically just function application.
  For cljs, consider compiled components or sci-evaluator, would require allowing
  for swappable evaluation stategies. Point to docs, to inform how to swap evaluator,
  or alternative ways to specify functions (that get compiled) that can be used."
  [env form]
  (walk/postwalk
   (fn [x]
     (cond
       (local-form? x)
       (parse-local env x)

       (and (seq? x) (ifn? (first x)))
       (apply (first x) (rest x))

       :else x))
   form))

(defn throw-parse-error
  [msg form-def token]
  (throw (ex-info (str msg " '" (pr-str token) "'")
                  {:type :parse-error
                   :form form-def
                   :expr token})))

(defn form->runnable [form-def]
  (let [props* (atom #{})
        resolved-form
        (->> form-def
             (walk/postwalk
              (fn [sub-form]
                (cond
                  (locals sub-form) sub-form

                  (special-symbol? sub-form)
                  (throw-parse-error "Special forms are not supported"
                                     form-def
                                     sub-form)

                  (resolve-symbol sub-form) (resolve-symbol sub-form)

                  (symbol? sub-form)
                  (throw-parse-error "Unable to resolve symbol"
                                     form-def
                                     sub-form)

                  (local-form? sub-form)
                  (do (swap! props* concat (rest sub-form))
                      sub-form)

                  :else sub-form))))]
    {:env @props*
     :form resolved-form}))

(defn normalize-signal-def [node-key signal-def signal-key]
  (let [;; is this map a map based def, or a runnable form
        def? (and (map? signal-def)
                  (some #{:gx/props :gx/props-fn
                          :gx/processor :gx/deps
                          :gx/resolved-props}
                        (keys signal-def)))
        with-pushed-down-form
        (if def?
          signal-def
          (let [{:keys [form env]} (form->runnable signal-def)]
            {:gx/processor (fn auto-signal-processor [{:keys [props]}]
                             (postwalk-evaluate props form))
             :gx/deps env
             :gx/resolved-props (->> env
                                     (map (fn [dep]
                                            [dep (list 'gx/ref dep)]))
                                     (into {}))}))
        resolved-props-fn (some-> with-pushed-down-form
                                  :gx/props-fn
                                  (resolve-symbol))
        with-resolved-props
        (if (:gx/resolved-props with-pushed-down-form)
          with-pushed-down-form
          (let [{:keys [form env]} (form->runnable
                                    (:gx/props with-pushed-down-form))]
            (merge with-pushed-down-form
                   {:gx/resolved-props form
                    :gx/resolved-props-fn resolved-props-fn
                    :gx/deps env})))]
    with-resolved-props))

(defn get-initial-signal
  "Finds first signal, which is launched on normalized graph with
   :uninitialized nodes. Used on static nodes."
  [context]
  (->> context
       :signals
       (filter (fn [[_ body]]
                 ((:from-states body) INITIAL_STATE)))
       (map first)
       first))

(defn normalize-node-def
  "Given a component definition, "
  [context node-key node-definition]
  (if (:gx/normalized? node-definition)
    node-definition
    (let [;; set of signals defined in the graph
          signals (set (keys (:signals context)))
          ;; is this map a map based def, or a runnable form
          def? (and (map? node-definition)
                    (some (into #{} (concat signals [:gx/component]))
                          (keys node-definition)))
          initial-signal (get-initial-signal context)
          with-pushed-down-form (if def?
                                  node-definition
                                  {initial-signal node-definition})
          component (some-> with-pushed-down-form :gx/component resolve-symbol)
          ;; merge in component
          with-component (impl/deep-merge
                          component (dissoc with-pushed-down-form :gx/component))
          normalized-def (merge
                          with-component
                          {:gx/state INITIAL_STATE
                           :gx/value nil})
          signal-defs (select-keys normalized-def signals)
          normalised-signal-defs
          (->> signal-defs
               (map (fn [[signal-key signal-def]]
                      [signal-key (normalize-signal-def
                                   node-key signal-def signal-key)]))
               (into {}))]
      (merge normalized-def
             normalised-signal-defs
           ;; Useful information, but lets consider semantics before
           ;; using the value to determine behaviour
             {:gx/type (if def? :component :static)
              :gx/normalized? true}))))

;; - any state should have only one signal to transition from it
(defn normalize
  "Given a graph definition and config, return a normalised form. Idempotent.
   This acts as the static analysis step of the graph.
   Returns tuple of error explanation (if any) and normamized graph."
  [{:keys [context graph] :as gx-map}]
  (let [graph-issues (gx.schema/validate-graph graph)
        config-issues (gx.schema/validate-graph-config context)
        ;; remove previous normalization errors
        gx-map' (cond-> gx-map
                  (not (:initial-graph gx-map)) (assoc :initial-graph graph)
                  :always (dissoc :failures))]
    (try
      (cond
        config-issues (throw (ex-info "Graph config error" config-issues))
        graph-issues (throw (ex-info "Graph definition error", graph-issues))
        :else (->> graph
                   (map (fn [[k v]]
                          [k (normalize-node-def context k v)]))
                   (into {})
                   (assoc gx-map' :graph)))
      (catch ExceptionInfo e
        (assoc gx-map' :failures {:message (ex-message e)
                                  :data (ex-data e)})))))

(defn graph-dependencies [graph signal-key]
  (->> graph
       (map (fn [[k node]]
              (let [deps (-> node signal-key :gx/deps)]
                [k (into #{} deps)])))
       (into {})))

(defn topo-sort [{:keys [context graph]} signal-key]
  (if-let [signal-config (get-in context [:signals signal-key])]
    (let [deps-from (or (:deps-from signal-config)
                        signal-key)
          graph-deps (graph-dependencies graph deps-from)
          sorted-raw (impl/sccs graph-deps)]
      (when-let [errors (->> sorted-raw
                             (impl/dependency-errors graph-deps)
                             (map impl/human-render-dependency-error)
                             (seq))]
        (throw (ex-info (str errors) {:errors errors})))

      (let [topo-sorted (map first sorted-raw)]
        (if (= :topological (:order signal-config))
          topo-sorted
          (reverse topo-sorted))))
    (throw (ex-info (str "Unknown signal key '" signal-key "'")
                    {:signal-key signal-key}))))

(defn system-property
  [{:keys [graph]} property-key]
  (->> graph
       (map (fn [[k node]]
              [k (get node property-key)]))
       (into {})))

(defn system-failure [gx-map]
  (system-property gx-map :gx/failure))

(defn system-value [gx-map]
  (system-property gx-map :gx/value))

(defn system-state [gx-map]
  (system-property gx-map :gx/state))

(defn validate-props
  [schema props]
  (when-let [error (and schema (m/explain schema props))]
    (me/humanize error)))

(defn- run-processor
  [processor arg-map]
  (try
    [nil (processor arg-map)]
    (catch #?(:clj Exception :cljs js/Error) e
      [(ex-info "Processor error" {:message (impl/error-message e)
                                   :args arg-map})
       nil])))

(defn validate-signal
  [context graph node-key signal-key]
  (let [{:keys [from-states to-state deps-from]}
        (-> context :signals signal-key)
        node (get graph node-key)
        node-state (:gx/state node)
        {:keys [props processor]} (get node signal-key)]
    (assert (get from-states node-state)
            (str "Incompatible from-states '" node-state
                 "', expected one of '" from-states "'"))
    {:to-state to-state
     :deps-from deps-from
     :props props
     :processor processor
     :node node}))

(defn node-signal
  "Trigger a signal through a node, assumes dependencies have been run.
   Subsequent signal calls is supported, but it should be handled in it's
   implementation. For example, http server component checks that it
   already started and does nothing to prevent port taken error or it
   can restart itself by taking recalculated properties from deps.
   Static nodes just recalculates its values.
   If node does not support signal then do nothing."
  [{:keys [context graph]} node-key signal-key]
  (let [signal-config (-> context :signals signal-key)
        {:keys [_deps-from from-states to-state]} signal-config
        node (get graph node-key)
        node-state (:gx/state node)
        signal-def (get node signal-key)
        {:gx/keys [processor resolved-props
                   resolved-props-fn deps props-schema]} signal-def
        ;; _ (validate-signal graph node-key signal-key graph-config)
        ;;
        ;; :deps-from is ignored if component have :props
        ;; props (if (and (not props) deps-from)
        ;;         (-> node deps-from :gx/props)
        ;;         props)
        ;; TODO: add props validation using malli schema
        dep-nodes (system-value {:graph (select-keys graph deps)})]
        ;; props-falures (->> dep-nodes
        ;;                    (system-failure)
        ;;                    (filter :gx/failure))]
    (cond
      ;; Non subsequent signal and node-state != from-states
      ;; ignore signal, return node
      (and (not (from-states node-state))
           (not= node-state to-state)) node
          ;; (seq props-falures)
          ;; (assoc node :gx/failure {:deps-failures props-falures})
      ;; TODO Check that we are actually turning symbols into resolved functions
      ;; in the normalisation step
      (ifn? processor)
      ;; either use resolved-props, or call props-fn and pass in (system-value graph deps), result
      ;; of props-fn, should be validated against props-schema
      (let [props-result (if (fn? resolved-props-fn)
                           (resolved-props-fn dep-nodes)
                           (postwalk-evaluate dep-nodes resolved-props))
            [error data] (if-let [e (validate-props props-schema props-result)]
                           [{:props-value props-result
                             :malli-schema props-schema
                             :malli-error e}]
                           (run-processor
                            processor {:props props-result
                                       :value (:gx/value node)}))]
        (if error
          (assoc node :gx/failure error)
          (-> node
              (assoc :gx/value data)
              (assoc :gx/state to-state))))

      :else node)))

(defn gather-node-failure
  [gx-map node node-key signal-key]
  (if (:gx/failure node)
    (assoc-in gx-map [:failures node-key]
              {:signal-key signal-key
               :message "Signal processor error"
               :initial-node (get-in gx-map [:initial-graph node-key])
               :internal-message (-> node
                                     :gx/failure
                                     ex-data
                                     :message)})
    gx-map))

(defn signal [gx-map signal-key]
  (let [gx-map' (normalize gx-map)
        sorted (topo-sort gx-map' signal-key)]
    (p/loop [gxm gx-map'
             sorted sorted]
      (cond
        (:gx/failure gxm) gxm

        (seq sorted)
        (p/let [node-key (first sorted)
                node (node-signal gxm node-key signal-key)
                next-gxm (assoc-in gxm [:graph node-key] node)]
          (p/recur (gather-node-failure next-gxm node node-key signal-key)
                   (rest sorted)))

        :else gxm))))


(comment
  ;; throw
  (let [graph {:a {:nested-a 1}
               :z '(get (gx/ref :a) :nested-a)
               :d '(throw "starting")
               :b {:gx/start '(+ (gx/ref :z) 2)
                   :gx/stop '(println"stopping")}}
        gx-norm (normalize {:graph graph
                            :context default-context})]
    (normalize gx-norm))


  (let [graph {:a {:nested-a 1}
               :z '(get (gx/ref :a) :nested-a)
               :d '(println "starting")
               :b {:gx/start '(+ (gx/ref :z) :foo)
                   :gx/stop '(println "stopping")}}
        gx-norm (normalize {:graph graph
                            :context default-context})]
    @(signal gx-norm :gx/start)))