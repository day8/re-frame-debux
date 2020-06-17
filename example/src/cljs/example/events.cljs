(ns example.events
  (:require
   [re-frame.core :as re-frame]
   [example.db :as db]
   [day8.re-frame.tracing :refer-macros [fn-traced]]
   ))

(re-frame/reg-event-db
 ::initialize-db
 (fn-traced [_ _]
   db/default-db))


(re-frame/reg-event-db
 ::let
 (fn-traced [db _]
   (let [a 10
         b (+ a 20)]
     (assoc db ::let (+ a b)))))

(re-frame/reg-event-db
 ::cond
 (fn-traced [db _]
   (assoc db ::cond
          (cond
          (and true false) 5
          (and true true) (inc 5)))))

(re-frame/reg-event-db
 ::condp
 (fn-traced [db _]
   (assoc db ::condp
          (condp = 4
           (inc 2) 5
           4       (inc 5)
           10))))

(re-frame/reg-event-db
 ::->
 (fn-traced [db _]
   (-> db
       (assoc ::-> (inc 5)))))

(re-frame/reg-event-db
 ::cond->
 (fn-traced [db _]
   (cond-> db
       true             (assoc ::-> (inc 5))
       (and true false) (assoc ::-> (dec 5)))))

(re-frame/reg-event-db
 ::cond->>
 (fn-traced [db _]
   (cond->> db
       true             (#(assoc %1 ::->> (inc 5)))
       (and true false) (#(assoc %1 ::->> (dec 5))))))


(re-frame/reg-event-db
 ::tricky  ; shows a bunch problems with the 10x display
 (fn-traced [db _]
   (let [res (-> [1 2 3 4 5]
                 (->> (map (fn [val] (condp = val
                                        3 33
                                        100 100
                                        5 55
                                        val))))
                 vec)]
      (assoc db ::tricky res))))
     
(re-frame/reg-event-db
 ::some->
 (fn-traced [db _]
   (assoc db ::some-> (some-> {:a 1} :a inc))))