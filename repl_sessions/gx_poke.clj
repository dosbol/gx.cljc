(ns gx-poke
  (:require [k16.gx.beta.system :as gx-sys]
            [promesa.core :as p]))

(def compo {:gx/start {:gx/processor (fn [_] (println "starting foo"))}})
(def compo2 {:gx/start {:gx/processor (fn [_] (throw (Exception. "x")))}})
(def compo3 {:gx/start {:gx/processor (fn [_] (println "starting 3"))}
             :gx/stop {:gx/processor (fn [_] (println "stopping 3"))}})

(gx-sys/register!
 ::x
 {:graph {:foo {:gx/component 'gx.poke/compo
                :gx/props {:bar '(gx/ref :bar)}}
          :bar {:gx/component 'gx-poke/compo2
                :gx/props {:bar '(gx/ref :baq)}}
          :baq {:gx/component `compo3}}})

(comment
  (gx-sys/failures ::x)
  (gx-sys/failures-humanized ::x)
  (gx-sys/signal! ::x :gx/start)
  (gx-sys/states ::x)
  )
