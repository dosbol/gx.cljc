(ns k16.gx.beta.core
  (:refer-clojure :exclude [ref])
  #?(:cljs (:require-macros [k16.gx.beta.error-context :refer [with-err-ctx]]))
  (:require [malli.core :as m]
            [malli.error :as me]
            [promesa.core :as p]
            [k16.gx.beta.impl :as impl]
            [k16.gx.beta.schema :as gx.schema]
            [k16.gx.beta.errors :as gx.err]
            #?(:clj [k16.gx.beta.error-context :refer [with-err-ctx]]))
  (:import #?(:clj [clojure.lang ExceptionInfo])))

(def default-context
  {:initial-state :uninitialised
   :normalize {;; signal, whish is default for auto-component nodes
               :auto-signal :gx/start
               :props-signals #{:gx/start}}
   :signal-mapping {}
   :signals {:gx/start {:from-states #{:stopped :uninitialised}
                        :to-state :started}

             :gx/suspend {:from-states #{:started}
                          :deps-from :gx/start
                          :to-state :suspended
                          :order :reverse}

             :gx/resume {:from-states #{:suspended}
                         :deps-from :gx/start
                         :to-state :started}

             :gx/stop {:from-states #{:started :suspended}
                       :to-state :stopped
                       :deps-from :gx/start
                       :order :reverse}}})

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
          (let [{:keys [form env]} (impl/form->runnable signal-def)]
            {:gx/processor (fn auto-signal-processor [{:keys [props]}]
                             (impl/postwalk-evaluate props form))
             :gx/deps env
             :gx/resolved-props (->> env
                                     (map (fn [dep]
                                            [dep (list 'gx/ref dep)]))
                                     (into {}))}))
        resolved-props-fn (some-> with-pushed-down-form
                                  :gx/props-fn
                                  (impl/resolve-symbol))
        with-resolved-props
        (if (:gx/resolved-props with-pushed-down-form)
          with-pushed-down-form
          (let [{:keys [form env]} (impl/form->runnable
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
    (with-err-ctx {:error-type :normalize-node
                   :node-key node-key
                   :node-contents (node-key initial-graph)}
      (let [{:keys [initial-state]} context
            {:keys [auto-signal]} (:normalize context)
            ;; set of signals defined in the graph
            signals (set (keys (:signals context)))
            ;; is this map a map based def, or a runnable form
            def? (and (map? node-definition)
                      (some (into #{} (conj signals :gx/component))
                            (keys node-definition)))
            with-pushed-down-form
            (if def?
              node-definition
              (->> (disj signals auto-signal)
                   (map (fn [other-signal]
                          [other-signal
                           {;; :value just passes value and
                            ;; supports state transitions of
                            ;; auto-components for all other signals
                            :gx/processor :value}]))
                   (into {auto-signal node-definition})))
            component (some->> with-pushed-down-form
                               :gx/component
                               (impl/resolve-component context))
            ;; merge in component
            with-component (impl/deep-merge
                            component (dissoc with-pushed-down-form
                                              :gx/component))
            normalized-def (merge
                            (impl/push-down-props context with-component)
                            {:gx/state initial-state
                             :gx/value nil})

            signal-defs (select-keys normalized-def signals)
            normalised-signal-defs
            (update-vals signal-defs normalize-signal-def)]
        (merge normalized-def
               normalised-signal-defs
               ;; Useful information, but lets consider semantics before
               ;; using the value to determine behaviour
               {:gx/type (if def? :component :static)
                :gx/normalized? true})))))

(defn- signal-dependencies
  [{:keys [signals]}]
  (->> signals
       (map (fn [[k v]]
              [k (if-let [f (:deps-from v)]
                   #{f}
                   #{})]))
       (into {})))

(defn with-context-failures
  "Validates context against schema and checks signal dependency errors"
  [{:keys [context] :as gx-map}]
  (with-err-ctx {:error-type :context}
    (let [schema-errors (gx.schema/validate-context context)
          signal-deps-errors (let [deps (signal-dependencies context)]
                               (->> deps
                                    (impl/sccs)
                                    (impl/dependency-errors deps)
                                    (map impl/human-render-dependency-error)
                                    (seq)))
          all-errors (->> (apply vector schema-errors signal-deps-errors)
                          (filter identity)
                          (seq))]
      (if all-errors
        (update gx-map :failures conj (gx.err/gx-err-data
                                       "GX Context failure"
                                       {:errors all-errors}))
        gx-map))))

(defn graph-dependencies [graph signal-key]
  (update-vals graph (fn [node] (->> node signal-key :gx/deps set))))

(defn topo-sort
  "Sorts graph nodes according to signal ordering, returns vector of
   [error, sorted-nodes]"
  [{:keys [context graph]} signal-key]
  (with-err-ctx {:error-type :deps-sort :signal-key signal-key}
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
            (gx.err/throw-gx-err "Dependency errors" {:errors errors}))
          [nil
           (let [natural (map first sorted-raw)]
             (if (= :reverse (:order signal-config))
               (reverse natural)
               natural))])
        (gx.err/throw-gx-err
         (str "Unknown signal key '" signal-key "'")))
      (catch ExceptionInfo e
        [(assoc (ex-data e) :message (ex-message e))]))))

(defn with-deps-failures
  [gx-map]
  (if-let [failures (->> (-> gx-map :context :signals keys)
                         (map #(topo-sort gx-map %))
                         (filter first)
                         (flatten)
                         (seq))]
    (update gx-map :failures concat failures)
    gx-map))

(defn normalize
  "Given a graph definition and config, return a normalised form. Idempotent.
   This acts as the static analysis step of the graph.
   Returns tuple of error explanation (if any) and normamized graph."
  [{:keys [context graph initial-graph]
    :or {context default-context} :as gx-map}]
  (let [gx-map' (cond-> gx-map
                  (not initial-graph) (assoc :initial-graph graph)
                  :always (assoc :context context)
                  :always (dissoc :failures))]
    (try
      (->> graph
           (map (fn [[k v]] [k (-> (normalize-node-def gx-map' k v)
                                   ;; cleanup previous failures
                                   (dissoc :gx/failure))]))
           (into {})
           (assoc gx-map' :graph)
           (with-deps-failures)
           (with-context-failures))
      (catch ExceptionInfo e
        (update gx-map' :failures conj (gx.err/ex->gx-err-data e))))))

(defn get-component-props
  [graph property-key]
  (update-vals graph property-key))

(defn failures [gx-map]
  (get-component-props (:graph gx-map) :gx/failure))

(defn values [gx-map]
  (get-component-props (:graph gx-map) :gx/value))

(defn states [gx-map]
  (get-component-props (:graph gx-map) :gx/state))

(defn- wrap-error
  ([e arg-map]
   (wrap-error e nil arg-map))
  ([e msg arg-map]
   (gx.err/gx-err-data (or msg "Signal processor error")
                       {:ex-message (gx.err/gather-error-messages e)
                        :args arg-map}
                       e)))

(defn validate-props
  [schema props]
  (with-err-ctx {:error-type :props-validation}
    (try
      (when-let [error (and schema (m/explain schema props))]
        (gx.err/gx-err-data "Props validation error"
                            {:props-value props
                             :props-schema schema
                             :schema-error (me/humanize error)}))
      (catch #?(:clj Exception :cljs :default) e
        (wrap-error e "Props validation error" {:props-value props
                                                :props-schema schema})))))

(defn- run-props-fn
  [props-fn arg-map]
  (try
    (props-fn arg-map)
    (catch #?(:clj Throwable :cljs :default) e
      (gx.err/throw-gx-err "Props function error"
                           {:ex-message (gx.err/gather-error-messages e)
                            :args arg-map}))))

#?(:cljs
   (defn- wrap-error-cljs
     [e arg-map err-ctx]
     (with-err-ctx err-ctx
       (wrap-error e arg-map))))

#?(:clj
   (defn- run-processor
     [processor arg-map]
     (try
       [nil @(p/do (processor arg-map))]
       (catch Throwable e
         [(wrap-error (or (ex-cause e) e) arg-map) nil]))))

#?(:cljs
   (defn- run-processor
     "CLJS version with error context propagation"
     [processor arg-map err-ctx]
     (try
       (-> (processor arg-map)
           (p/then (fn [v] [nil v]))
           (p/catch (fn [e] [(wrap-error-cljs e arg-map err-ctx) nil])))
       (catch :default e
         [(wrap-error-cljs e arg-map err-ctx) nil]))))

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
        node (-> (get graph node-key) (dissoc :gx/failure))
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
        dep-nodes-vals (values {:graph dep-nodes})
        failed-dep-node-keys (->> {:graph dep-nodes}
                                  (failures)
                                  (filter second)
                                  (map first))]
    (with-err-ctx {:node-contents (node-key initial-graph)}
      (cond
        (or ;; signal isn't defined for this state transition
         (not (contains? from-states node-state))
            ;; node is already in to-state
         (= node-state to-state))
        node

        (seq failed-dep-node-keys)
        (assoc node :gx/failure (gx.err/gx-err-data
                                 "Failure in dependencies"
                                 {:dep-node-keys failed-dep-node-keys}))
        (ifn? processor)
        ;; Binding vars is not passed to nested async code
        ;; Workaround for CLJS: propagating error context manually
        (let [err-ctx gx.err/*err-ctx*]
          (p/let [props-result
                  (if (fn? resolved-props-fn)
                    (run-props-fn resolved-props-fn dep-nodes-vals)
                    (impl/postwalk-evaluate dep-nodes-vals resolved-props))
                  validate-error (with-err-ctx err-ctx
                                   (validate-props props-schema props-result))
                  [error result] (when-not validate-error
                                   (run-processor
                                    processor
                                    {:props props-result
                                     :value (:gx/value node)
                                     :state (:gx/state node)
                                     :instance (:gx/instance node)}
                                    #?(:cljs err-ctx)))
                  new-value (or (:gx/value result) result)]
            (if-let [e (or validate-error error)]
              (assoc node :gx/failure e)
              (merge node {:gx/value new-value
                           :gx/instance (:gx/instance result)
                           :gx/state to-state}))))

        :else (assoc node :gx/state to-state)))))

(defn merge-node-failure
  [gx-map node]
  (if-let [failure (:gx/failure node)]
    (update gx-map :failures conj failure)
    gx-map))

(defn selector-with-deps
  "Returns a node with its dependencies as a list of keys
   for a specific selector (signal-key + node-key)"
  [{:keys [graph context] :as gx-map} signal-key node-key]
  (let [signal (signal-key (:signals context))
        deps-from (:deps-from signal)
        order (:order signal)
        signal-key' (or deps-from signal-key)
        signal-deps (-> graph node-key signal-key' :gx/deps set)
        dep-nodes (->> (update-vals graph signal-key')
                       (filter (fn [[k {:gx/keys [deps]}]]
                                 (if (= :reverse order)
                                   (contains? (set deps) node-key)
                                   (contains? signal-deps k))))
                       (map first))]
    (-> (map #(selector-with-deps gx-map signal-key %) dep-nodes)
        (flatten)
        (conj node-key))))

(defn- create-partial-selector
  [gx-map signal-key selector]
  (some->>
   (seq selector)
   (map #(selector-with-deps gx-map signal-key %))
   (flatten)
   (set)))

(defn signal
  "Send signal to the graph or to its subset if selectors are passed.
   Selectors is a set of top level graph keys, signal will be executed on
   selectors and its dependencies according to signal ordering."
  ([gx-map signal-key]
   (signal gx-map signal-key nil))
  ([gx-map signal-key selector]
   (let [normalized (normalize (dissoc gx-map :failures))
         partial-selector (create-partial-selector gx-map signal-key selector)
         [error sorted] (topo-sort normalized signal-key)
         validated (if error
                     (update normalized :failures conj error)
                     normalized)]
     (if (seq (:failures validated))
       (p/resolved validated)
       (p/loop [gxm validated
                sorted sorted]
         (cond
           (seq sorted)
           (p/let [node-key (first sorted)
                   node (when (or (not partial-selector)
                                  (contains? partial-selector node-key))
                          (with-err-ctx {:error-type :node-signal
                                         :signal-key signal-key
                                         :node-key node-key}
                            (node-signal gxm node-key signal-key)))
                   next-gxm (if node
                              (assoc-in gxm [:graph node-key] node)
                              gxm)]
             (p/recur (merge-node-failure next-gxm node) (rest sorted)))

           :else gxm))))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(comment
  (def graph {:options {:port 8080}
              :router {:some "router"}
              :handler {:router '(gx/ref :router)}
              :server {:opts '(gx/ref :options)
                       :handler '(gx/ref :handler)}
              :config-logger {:some "logger config"}
              :logger {:config '(gx/ref :config-logger)}})

  (def norm (normalize {:graph graph}))

  (selector-with-deps norm :gx/stop :server))
