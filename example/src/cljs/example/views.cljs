(ns example.views
  (:require
   [re-frame.core :as re-frame]
   [re-com.core :as re-com]
   [example.subs :as subs]
   [example.events :as events]
   ))

(defn title []
  (let [name (re-frame/subscribe [::subs/name])]
    [re-com/title
     :label (str "Hello from " @name)
     :level :level1]))

(defn let-example
  []
  [:tr
   [:td
    [:pre 
"(fn-traced [db _]
   (let [a 10
         b (+ a 20)]
     (assoc db ::let (+ a b))))"]]
    [:td
     [re-com/button
      :label "let"
      :on-click #(re-frame/dispatch [::events/let])]]])

(defn cond-example
  []
  [:tr
   [:td
    [:pre 
"(fn-traced [db _]
   (assoc db ::cond 
          (cond
          (and true false) 5
          (and true true) (inc 5))))"]]
    [:td
     [re-com/button
      :label "cond"
      :on-click #(re-frame/dispatch [::events/cond])]]])

(defn condp-example
  []
  [:tr 
   [:td {:width "100px"} 
    [:pre 
"(fn-traced [db _]
   (assoc db ::condp
          (condp = 4
           (inc 2) 5
           4       (inc 5)
           10)))"]]
   [:td
    [re-com/button
      :label "condp"
      :on-click #(re-frame/dispatch [::events/condp])]]])

(defn ->-example
  []
  [:tr
   [:td
    [:pre 
"(fn-traced [db _]
   (-> db
       (assoc ::-> (inc 5))))"]]
    [:td
     [re-com/button
      :label "->"
      :on-click #(re-frame/dispatch [::events/->])]]])


(defn cond->-example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::cond->
 (fn-traced [db _]
   (cond-> db
       true             (assoc ::-> (inc 5))
       (and true false) (assoc ::-> (dec 5)))))"]]
    [:td
     [re-com/button
      :label "cond->"
      :on-click #(re-frame/dispatch [::events/cond->])]]])


      
(defn cond->>-example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::cond->>
 (fn-traced [db _]
   (cond->> db
       true             (#(assoc %1 ::->> (inc 5)))
       (and true false) (#(assoc %1 ::->> (dec 5))))))"]]
    [:td
     [re-com/button
      :label "cond->>"
      :on-click #(re-frame/dispatch [::events/cond->>])]]])

(defn tricky-example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::tricky
 (fn-traced [db _]
   (let [res (-> [1 2 3 4 5]
                 (->> (map (fn [val] (condp = val
                                        3 33
                                        100 100
                                        5 55
                                        val))))
                 vec)]
      (assoc db ::tricky res))))"]]
    [:td
     [re-com/button
      :label "tricky"
      :on-click #(re-frame/dispatch [::events/tricky])]]])

(defn some->example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::some->
 (fn-traced [db _]
   (assoc db ::some-> (some-> {:a 1} :a inc)))"]]
    [:td
     [re-com/button
      :label "some->"
      :on-click #(re-frame/dispatch [::events/some->])]]])
      
(defn everything->example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::everything->
 (fn-traced [db _]
     (-> db
         (assoc ::everything-> (-> [10 11]
                                   (cond->
                                    true       (conj 12)
                                    true       (as-> xs (map - xs [3 2 1]))
                                    true       (->> (map inc))
                                    true       (some->> (map inc))
                                    false      (reverse)))))))"]]
    [:td
     [re-com/button
      :label "everything->"
      :on-click #(re-frame/dispatch [::events/everything->])]]])
      
(defn dot-example
  []
  [:tr
   [:td
    [:pre 
"(re-frame/reg-event-db
 ::dot
 (fn-traced [db _]
     (-> db
         (assoc ::dot \"abc\")
         (update ::dot #(.. % toUpperCase (concat \"ABC\"))))))"]]
    [:td
     [re-com/button
      :label "dot"
      :on-click #(re-frame/dispatch [::events/dot])]]])

(defn main-panel []
  [re-com/v-box
   :height "100%"
   :children [[title]
              [:table
               [:tbody
                 [let-example]
                 [cond-example]
                 [condp-example]
                 [->-example]
                 [cond->-example]
                 [cond->>-example]
                 [some->example]
                 [tricky-example]
                 [everything->example]
                 [dot-example]]]]])
