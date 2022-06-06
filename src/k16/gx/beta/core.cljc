(ns k16.gx.beta.core
  (:refer-clojure :exclude [ref])
  (:require [clojure.walk :as walk]
            [k16.gx.beta.impl :as impl]
            [k16.gx.beta.schema :as gx.schema]
            [malli.core :as m]
            [malli.error :as me]
            [promesa.core :as p])
  (:import #?(:clj [clojure.lang ExceptionInfo])))

(defrecord ErrorContext [error-type node-key node-contents signal-key])

(def ->err-ctx map->ErrorContext)

(def ^:dynamic *err-ctx*
  (->err-ctx {:error-type :general}))

(defn ->gx-error-data
  ([internal-data]
   (->gx-error-data nil internal-data))
  ([message internal-data]
   (->> *err-ctx*
        (filter (fn [[_ v]] v))
        (into (if message {:message message} {}))
        (merge {:internal-data internal-data}))))

(defn throw-gx-error
  ([message]
   (throw-gx-error message nil))
  ([message internal-data]
   (throw (ex-info message (->gx-error-data
                            message
                            internal-data)))))

(defn gx-error->map
  [ex]
  (->> (ex-data ex)
       (merge *err-ctx*)
       (filter (fn [[_ v]] v))
       (into {:message (ex-message ex)})))

(def locals #{'gx/ref 'gx/ref-keys})

;; local forms which create deps between graph nodes
(def deps-locals (disj locals 'gx/ref-env))

(defn local-form?
  [form]
  (and (seq? form)
       (locals (first form))))

(defn deps-form?
  [form]
  (and (local-form? form)
       (deps-locals (first form))))

(def default-context
  {:initial-state :uninitialised
   :normalize {:auto-signal :gx/start
               ;; TODO implement pushdown-props
               :props-signal #{:gx/start :gx/stop :gx/pause}}
   :signal-mapping {}
   :signals {:gx/start {:order :topological
                        :from-states #{:stopped :uninitialised}
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

(defn ref-keys
  [& keys]
  (apply list (conj keys 'gx/ref-keys)))

(defn parse-local
  [env form]
  (condp = (first form)
    'gx/ref (get env (second form))

    'gx/ref-keys (select-keys env (second form))))

(defn postwalk-evaluate
  "A postwalk runtime signal processor evaluator, works most of the time.
  Doesn't support special symbols and macros, basically just function application.
  For cljs, consider compiled components or sci-evaluator, would require allowing
  for swappable evaluation stategies. Point to docs, to inform how to swap evaluator,
  or alternative ways to specify functions (that get compiled) that can be used."
  [props form]
  (walk/postwalk
   (fn [x]
     (cond
       (local-form? x)
       (parse-local props x)

       (and (seq? x) (ifn? (first x)))
       (apply (first x) (rest x))

       :else x))
   form))

(defn form->runnable [form-def]
  (let [props* (atom #{})
        resolved-form
        (->> form-def
             (walk/postwalk
              (fn [sub-form]
                (cond
                  (locals sub-form) sub-form

                  (deps-form? sub-form)
                  (do (swap! props* concat (-> sub-form rest flatten))
                      sub-form)

                  (local-form? sub-form) sub-form

                  (special-symbol? sub-form)
                  (throw-gx-error "Special forms are not supported"
                                  {:form-def form-def
                                   :token sub-form})

                  (resolve-symbol sub-form) (resolve-symbol sub-form)

                  (symbol? sub-form)
                  (throw-gx-error "Unable to resolve symbol"
                                  {:form-def form-def
                                   :token sub-form})

                  :else sub-form))))]
    {:env @props*
     :form resolved-form}))

(defn normalize-signal-def [signal-def]
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

(defn normalize-node-def
  "Given a component definition, "
  [{:keys [context initial-graph]} node-key node-definition]
  (if (:gx/normalized? node-definition)
    node-definition
    (let [{:keys [initial-state]} context
          {:keys [auto-signal]} (:normalize context)
          ;; set of signals defined in the graph
          signals (set (keys (:signals context)))
          ;; is this map a map based def, or a runnable form
          def? (and (map? node-definition)
                    (some (into #{} (concat signals [:gx/component]))
                          (keys node-definition)))
          with-pushed-down-form (if def?
                                  node-definition
                                  (->> (disj signals auto-signal)
                                       (map (fn [other-signal]
                                              [other-signal
                                               {;; :value just passes value and
                                                ;; supports state transitions of
                                                ;; auto-components for all other signals
                                                :gx/processor :value
                                                ;; TODO update signal processing
                                                ;; to accept nil props
                                                :gx/resolved-props {}}]))
                                       (into {auto-signal node-definition})))
          component (some-> with-pushed-down-form :gx/component resolve-symbol)
          ;; merge in component
          ;; TODO support nested gx/component
          ;; TODO support signal-mapping
          with-component (impl/deep-merge
                          component (dissoc with-pushed-down-form :gx/component))
          normalized-def (merge
                          with-component
                          {:gx/state initial-state
                           :gx/value nil})
          signal-defs (select-keys normalized-def signals)
          normalised-signal-defs
          (binding [*err-ctx* (->err-ctx
                               {:error-type :normalize-node
                                :node-key node-key
                                :node-contents (node-key initial-graph)})]
            (->> signal-defs
                 (map (fn [[signal-key signal-def]]
                        [signal-key (normalize-signal-def signal-def)]))
                 (into {})))]
      (merge normalized-def
             normalised-signal-defs
           ;; Useful information, but lets consider semantics before
           ;; using the value to determine behaviour
             {:gx/type (if def? :component :static)
              :gx/normalized? true}))))

(defn normalize
  "Given a graph definition and config, return a normalised form. Idempotent.
   This acts as the static analysis step of the graph.
   Returns tuple of error explanation (if any) and normamized graph."
  [{:keys [context graph] :as gx-map}]
  (let [config-issues (gx.schema/validate-graph-config context)
        ;; remove previous normalization errors
        gx-map' (cond-> gx-map
                  (not (:initial-graph gx-map)) (assoc :initial-graph graph)
                  :always (dissoc :failures))]
    (try
      (cond
        config-issues (throw (ex-info "GX Context error" config-issues))
        :else (->> graph
                   (map (fn [[k v]]
                          [k (normalize-node-def gx-map' k v)]))
                   (into {})
                   (assoc gx-map' :graph)))
      (catch ExceptionInfo e
        (update gx-map' :failures conj (gx-error->map e))))))

(defn graph-dependencies [graph signal-key]
  (->> graph
       (map (fn [[k node]]
              (let [deps (-> node signal-key :gx/deps)]
                [k (into #{} deps)])))
       (into {})))

(defn topo-sort
  "Sorts graph nodes according to signal topology, returns vector of
   [error, sorted nodes]"
  [{:keys [context graph]} signal-key]
  (binding [*err-ctx* (->err-ctx {:error-type :deps-sort
                                  :signal-key signal-key})]
    (try
      (if-let [signal-config (get-in context [:signals signal-key])]
        (let [deps-from (or (:deps-from signal-config)
                            signal-key)
              graph-deps (graph-dependencies graph deps-from)
              sorted-raw (impl/sccs graph-deps)]
          (when-let [errors (->> sorted-raw
                                 (impl/dependency-errors graph-deps)
                                 (map impl/human-render-dependency-error)
                                 (seq))]
            (throw-gx-error "Dependency errors" {:errors errors}))
          [nil
           (let [topo-sorted (map first sorted-raw)]
             (if (= :topological (:order signal-config))
               topo-sorted
               (reverse topo-sorted)))])
        (throw-gx-error (str "Unknown signal key '" signal-key "'")))
      (catch ExceptionInfo e
        [(assoc (ex-data e) :message (ex-message e))]))))

(defn get-component-props
  [graph property-key]
  (->> graph
       (map (fn [[k node]]
              [k (get node property-key)]))
       (into {})))

(defn system-failure [gx-map]
  (get-component-props (:graph gx-map) :gx/failure))

(defn system-value [gx-map]
  (get-component-props (:graph gx-map) :gx/value))

(defn system-state [gx-map]
  (get-component-props (:graph gx-map) :gx/state))

(defn reset-node
  "Override normalized graph's node from :initial-graph, it's used when
   node's component changes it's internals. new-contents - is optional argment
   which is taken over value of :initial-graph. Does nothig if normalized
   graph doesn't have a node-key."
  ([gx-map node-key]
   (reset-node gx-map node-key nil))
  ([gx-map node-key new-contents]
   (if (node-key (:graph gx-map))
     (assoc-in gx-map
               [:graph node-key]
               (or new-contents (-> gx-map :initial-graph node-key)))
     gx-map)))

(defn props-validate-error
  [schema props]
  (when-let [error (and schema (m/explain schema props))]
    (binding [*err-ctx* (assoc *err-ctx* :error-type :props-validation)]
      (->gx-error-data "Props validation error"
                       {:props-value props
                        :props-schema schema
                        :shema-error (me/humanize error)}))))

(defn- run-props-fn
  [props-fn arg-map]
  (try
    (props-fn arg-map)
    (catch #?(:clj Exception :cljs js/Error) e
      (throw-gx-error "Props function error"
                      {:ex-message (impl/error-message e)
                       :args arg-map}))))

(defn- run-processor
  [processor arg-map]
  (try
    [nil (processor arg-map)]
    (catch #?(:clj Exception :cljs js/Error) e
      [(->gx-error-data "Signal processor error"
                        {:ex-message (impl/error-message e)
                         :args arg-map})
       nil])))

(defn node-signal
  "Trigger a signal through a node, assumes dependencies have been run.
   Subsequent signal calls is supported, but it should be handled in it's
   implementation. For example, http server component checks that it
   already started and does nothing to prevent port taken error or it
   can restart itself by taking recalculated properties from deps.
   Static nodes just recalculates its values.
   If node does not support signal then do nothing."
  [{:keys [context graph initial-graph]} node-key signal-key]
  (let [signal-config (-> context :signals signal-key)
        {:keys [deps-from from-states to-state]} signal-config
        node (get graph node-key)
        node-state (:gx/state node)
        signal-def (get node signal-key)
        {:gx/keys [processor props-schema resolved-props]} signal-def
        ;; take deps from another signal of node if current signal has deps-from
        ;; and does not have resolved props
        {:gx/keys [resolved-props resolved-props-fn deps]}
        (if (and deps-from (not resolved-props))
          (get node deps-from)
          signal-def)
        ;; _ (validate-signal graph node-key signal-key graph-config)
        ;;
        ;; :deps-from is ignored if component have :props
        ;; props (if (and (not props) deps-from)
        ;;         (-> node deps-from :gx/props)
        ;;         props)
        dep-nodes (select-keys graph deps)
        dep-nodes-vals (system-value {:graph dep-nodes})
        failed-dep-node-keys (->> {:graph dep-nodes}
                                  (system-failure)
                                  (filter (fn [[_ v]] v))
                                  (map first))]
    (binding [*err-ctx* (assoc *err-ctx* :node-contents (node-key initial-graph))]
      (cond
        ;; Signal is not called more than once or node-state != from-states
        ;; => ignore signal, return node

        (or ;; signal isn't defined for this state transition
            (not (contains? from-states node-state))
            ;; node is already in to-state
            (= node-state to-state))
        node

        (seq failed-dep-node-keys)
        (assoc node :gx/failure (->gx-error-data
                                 "Failure in dependencies"
                                 {:dep-node-keys failed-dep-node-keys}))
        (ifn? processor)
        (let [props-result (if (fn? resolved-props-fn)
                             (run-props-fn resolved-props-fn dep-nodes-vals)
                             (postwalk-evaluate dep-nodes-vals resolved-props))
              [error data] (if-let [validate-error (props-validate-error
                                                    props-schema props-result)]
                             [validate-error]
                             (run-processor
                              processor {:props props-result
                                         :value (:gx/value node)}))]
          (if error
            (assoc node :gx/failure error)
            (-> node
                (assoc :gx/value data)
                (assoc :gx/state to-state))))

        :else node))))

(defn merge-node-failure
  [gx-map node]
  (if-let [failure (:gx/failure node)]
    (update gx-map :failures conj failure)
    gx-map))

(defn signal [gx-map signal-key]
  (let [gx-map (normalize (dissoc gx-map :failures))
        [error sorted] (topo-sort gx-map signal-key)
        gx-map (if error
                 (update gx-map :failures conj error)
                 gx-map)]
    (if (seq (:failures gx-map))
      (p/resolved gx-map)
      (p/loop [gxm gx-map
               sorted sorted]
        (cond
          (seq sorted)
          (p/let [node-key (first sorted)
                  node (binding [*err-ctx* (->err-ctx
                                            {:error-type :node-signal
                                             :signal-key signal-key
                                             :node-key node-key})]
                         (node-signal gxm node-key signal-key))
                  next-gxm (assoc-in gxm [:graph node-key] node)]
            (p/recur (merge-node-failure next-gxm node) (rest sorted)))

          :else gxm)))))
