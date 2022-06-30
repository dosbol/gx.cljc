(ns k16.gx.beta.system
  (:require [k16.gx.beta.core :as gx]
            [k16.gx.beta.errors :as gx.errors]
            [promesa.core :as p]))

(defonce registry* (atom {}))

(defn- filter-nodes
  [graph selector]
  (if (seq selector)
    (select-keys graph selector)
    graph))

(defn states
  ([system-name]
   (states system-name nil))
  ([system-name selector]
   (when-let [gx-map (get @registry* system-name)]
     (filter-nodes (gx/states gx-map) selector))))

(defn values
  ([system-name]
   (values system-name nil))
  ([system-name selector]
   (when-let [gx-map (get @registry* system-name)]
     (filter-nodes (gx/values gx-map) selector))))

(defn failures
  [system-name]
  (when-let [gx-map (get @registry* system-name)]
    (seq (:failures gx-map))))

(defn failures-humanized
  "Returns all failures as single humanized formatted string (ready for output)"
  [system-name]
  (when-let [failures (failures system-name)]
    (gx.errors/humanize-all failures)))

(defn register! [system-name gx-map]
  (let [normalized (gx/normalize gx-map)]
    (swap! registry* assoc system-name normalized)
    (if-let [failures (seq (:failures normalized))]
      (throw (ex-info (gx.errors/humanize-all failures)
                      {:failures failures}))
      normalized)))

(defn get-by-name
  [system-name]
  (get @registry* system-name))

(defn signal!
  "Sends signal to system and updates it in registry.
   Returns a new system on success or throws exception on signal failures"
  ([system-name signal-key]
   (signal! system-name signal-key nil))
  ([system-name signal-key selector]
   (when-let [gx-map (get @registry* system-name)]
     (-> (gx/signal gx-map signal-key selector)
         (p/then (fn [g]
                   (swap! registry* assoc system-name g)
                   (if-let [failures (failures-humanized system-name)]
                     (throw (ex-info (str "Signal failed!\n" failures)
                                     {:failures failures}))
                     g)))))))

(comment
  (register! :sys {:graph {:a '(gx/ref :b)
                           :b '(gx/ref :a)
                           :c '(gx/ref :z)
                           :z {:gx/component 'non.existend/component}}})
  ;; clj
  @(signal! :sys :gx/start)

  ;; cljs
  (-> (signal! :sys :gx/start)
      ;; (p/then js/console.log)
      (p/catch #(js/console.log (.-message %))))

  ;; common
  (println
   (failures-humanized :sys))

  (values :sys)
  (first (failures :sys))
  )
