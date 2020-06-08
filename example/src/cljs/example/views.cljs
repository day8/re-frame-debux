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
"(let [a 10
       b (+ a 20)]
   (+ a b))"]]
    [:td
     [re-com/button
      :label "let"
      :on-click #(re-frame/dispatch [::events/let])]]])

(defn cond-example
  []
  [:tr
   [:td
    [:pre 
"(cond
   (and true false) 5
   (and true true) (inc 5))"]]
    [:td
     [re-com/button
      :label "cond"
      :on-click #(re-frame/dispatch [::events/cond])]]])

(defn condp-example
  []
  [:tr 
   [:td {:width "200px"} 
    [:pre 
"(condp = 4
   (inc 2) 5
   4       (inc 5)
   10)"]]
   [:td
    [re-com/button
      :label "condp"
      :on-click #(re-frame/dispatch [::events/condp])]]])

(defn ->-example
  []
  [:tr
   [:td
    [:pre 
"(-> 5
     inc)"]]
    [:td
     [re-com/button
      :label "->"
      :on-click #(re-frame/dispatch [::events/->])]]])

(defn main-panel []
  [re-com/v-box
   :height "100%"
   :children [[title]
              [:table
                 [let-example]
                 [cond-example]
                 [condp-example]
                 [->-example]]]])
