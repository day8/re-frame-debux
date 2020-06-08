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
     (+ a b))
   db))

(re-frame/reg-event-db
 ::cond
 (fn-traced [db _]
   (cond
     (and true false) 5
     (and true true) (inc 5))
   db))

(re-frame/reg-event-db
 ::condp
 (fn-traced [db _]
   (condp = 4
     (inc 2) 5
     4       (inc 5)
     10)
   db))

(re-frame/reg-event-db
 ::->
 (fn-traced [db _]
   (-> 5
       inc)
   db))
