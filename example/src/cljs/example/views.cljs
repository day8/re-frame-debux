(ns example.views
  (:require
   [example.events :as events]
   [example.subs :as subs]
   [re-com.core :as re-com]
   [re-frame.core :as re-frame]))

(def examples
  [{:group "Classic traced forms"
    :items [{:label "let"
             :dispatch [::events/let]
             :code "(fn-traced {:locals true :msg \"let bindings\"}
  [db _]
  (let [a 10
        b (+ a 20)
        logo \"logo\"
        original-logo \"old-logo\"
        new-logo? (not (or (empty? logo)
                           (= logo original-logo)))]
    (assoc db ::let {:sum (+ a b)
                     :new-logo? new-logo?})))"}
            {:label "cond"
             :dispatch [::events/cond]
             :code "(fn-traced [db _]
  (assoc db ::cond
         (cond
           (and true false) 5
           (and true true) (inc 5))))"}
            {:label "condp"
             :dispatch [::events/condp]
             :code "(fn-traced [db _]
  (assoc db ::condp
         (condp = 4
           (inc 2) 5
           4       (inc 5)
           10)))"}
            {:label "->"
             :dispatch [::events/->]
             :code "(fn-traced [db _]
  (-> db
      (assoc ::-> (inc 5))))"}
            {:label "cond->"
             :dispatch [::events/cond->]
             :code "(fn-traced [db _]
  (let [show-alt? (get db ::show-alt? false)]
    (cond-> db
      true      (assoc ::-> (inc 5))
      show-alt? (assoc ::-> (dec 5)))))"}
            {:label "cond->>"
             :dispatch [::events/cond->>]
             :code "(fn-traced [db _]
  (let [show-alt? (get db ::show-alt? false)]
    (cond->> db
      true      (#(assoc %1 ::->> (inc 5)))
      show-alt? (#(assoc %1 ::->> (dec 5))))))"}
            {:label "some->"
             :dispatch [::events/some->]
             :code "(fn-traced [db _]
  (assoc db ::some-> (some-> {:a 1} :a inc)))"}
            {:label "tricky"
             :dispatch [::events/tricky]
             :code "(fn-traced [db _]
  (let [res (-> [1 2 3 4 5]
                (->> (map (fn [val]
                            (condp = val
                              3 33
                              100 100
                              5 55
                              val))))
                vec)]
    (assoc db ::tricky res)))"}
            {:label "everything->"
             :dispatch [::events/everything->]
             :code "(fn-traced [db _]
  (-> db
      (assoc ::everything->
             (-> [10 11]
                 (cond->
                  true  (conj 12)
                  true  (as-> xs (map - xs [3 2 1]))
                  true  (->> (map inc))
                  true  (some->> (map inc))
                  false (reverse))))))"}
            {:label "dot"
             :dispatch [::events/dot]
             :code "(fn-traced [db _]
  (-> db
      (assoc ::dot \"abc\")
      (update ::dot #(.. % toUpperCase (concat \"ABC\")))))"}
            {:label "vec"
             :dispatch [::events/vec]
             :code "(fn-traced [db _]
  (-> db
      (assoc ::vec
             [:div {:style {:color (if true
                                      (str \"hello \" \"world\")
                                      :never)}}
              (when true
                [inc \"putting inc symbol inside a vector\"])])))"}]}

   {:group "v0.7 options and standalone probes"
    :items [{:label "locals/if/once"
             :dispatch [::events/opts 3]
             :code "(fn-traced {:locals true
             :if some?
             :once true
             :msg \"locals + if + once\"}
  [db [_ amount]]
  (let [next-count (+ amount (get db ::opts-count 0))]
    (assoc db ::opts-count next-count)))"}
            {:label "final"
             :dispatch [::events/final]
             :code "(fn-traced {:final true :msg \"final value only\"}
  [db _]
  (let [base 5
        scaled (* base 10)
        described {:base base
                   :scaled scaled
                   :label (str \"scaled-\" scaled)}]
    (assoc db ::final described)))"}
            {:label "verbose"
             :dispatch [::events/verbose]
             :code "(fn-traced {:verbose true :msg \"verbose literals\"}
  [db _]
  (assoc db ::verbose {:n 42
                       :flag true
                       :label \"leaf literals\"}))"}
            {:label "trace frames"
             :dispatch [::events/frame-markers]
             :code "(fn-traced {:msg \"function frame markers\"}
  [db _]
  (let [result (->> (range 4)
                    (map inc)
                    (reduce +))]
    (assoc db ::frame-markers result)))"}
            {:label "dbg/dbg-last/dbgn"
             :dispatch [::events/standalone-probes]
             :code "(fn [db _]
  (let [numbers (dbg [1 2 3 4] {:msg \"dbg value\" :tap? true})
        evens   (->> numbers
                     (filter even?)
                     (dbg-last {:msg \"dbg-last after filter\"})
                     (map inc)
                     vec)
        summary (dbgn (-> {:evens evens}
                          (assoc :total (reduce + evens))))]
    (assoc db ::standalone-probes summary)))"}
            {:label "tap output"
             :dispatch [::events/tap-output]
             :code "(fn-traced {:final true :msg \"set-tap-output!\"}
  [db _]
  (install-console-tap!)
  (tracing/set-tap-output! true)
  (assoc db ::tap-output :enabled))"}]}

   {:group "Effect and runtime tracing"
    :items [{:label "fx-traced"
             :dispatch [::events/map]
             :code "(re-frame/reg-event-fx
 ::map
 (fx-traced {:locals true :msg \"inline fx-traced\"}
   [{:keys [db]} _]
   {:db       (assoc db :a (inc 5)
                       :b (if true :t :f))
    :dispatch [::record \"fx-traced returned :dispatch\"]}))"}
            {:label "fx implied do"
             :dispatch [::events/implied-do]
             :code "(re-frame/reg-event-fx
 ::implied-do
 (fx-traced {:final true :msg \"fx-traced implied do\"}
   [{:keys [db]} _]
   (inc 1)
   {:db (assoc db :a (inc 5)
                 :b (if true :t :f))}))"}
            {:label "defn-fx-traced"
             :dispatch [::events/defn-fx "feature tour"]
             :code "(defn-fx-traced {:final true :msg \"defn-fx-traced handler\"}
  update-name-handler
  [{:keys [db]} [_ suffix]]
  {:db (assoc db :name (str \"re-frame/debux \" suffix))})"}
            {:label "wrap-handler!"
             :dispatch [::events/runtime-wrap]
             :code "(re-frame/reg-event-fx
 ::runtime-wrap
 (fn [{:keys [db]} _]
   (tracing-runtime/unwrap-handler! :event ::runtime-target)
   (let [wrap-result (wrap-handler!
                      :event
                      ::runtime-target
                      (fn [db [_ amount]]
                        (let [doubled (* 2 amount)]
                          (assoc db ::runtime-target
                                 {:source :runtime-wrap
                                  :amount amount
                                  :doubled doubled}))))]
     {:db       (assoc db ::runtime-wrap-result wrap-result)
      :dispatch [::runtime-target 21]})))"}]}])

(defn title []
  (let [name (re-frame/subscribe [::subs/name])]
    [re-com/title
     :label (str "Hello from " @name)
     :level :level1]))

(defn section-row [group]
  [:tr
   [:th {:style {:padding "24px 16px 10px 0"
                 :font-size "18px"
                 :font-weight 500
                 :text-align "left"}}
    group]
   [:th {:style {:padding "24px 0 10px"
                 :text-align "left"}}]])

(defn example-row [{:keys [label dispatch code]}]
  [:tr
   [:td {:style {:padding "0 18px 16px 0"
                 :vertical-align "top"
                 :width "78%"}}
    [:pre {:style {:background "#f7f7f7"
                   :border "1px solid #ddd"
                   :border-radius "4px"
                   :font-size "13px"
                   :line-height 1.38
                   :margin 0
                   :max-width "980px"
                   :overflow-x "auto"
                   :padding "12px"
                   :white-space "pre-wrap"}}
     code]]
   [:td {:style {:padding "0 0 16px"
                 :vertical-align "top"
                 :white-space "nowrap"}}
    [re-com/button
     :label label
     :on-click #(re-frame/dispatch dispatch)]]])

(defn example-rows []
  (mapcat
   (fn [{:keys [group items]}]
     (cons
      ^{:key (str group "-header")} [section-row group]
      (map (fn [{:keys [label] :as item}]
             ^{:key (str group "-" label)} [example-row item])
           items)))
   examples))

(defn main-panel []
  [re-com/v-box
   :height "100%"
   :gap "12px"
   :padding "20px"
   :children [[title]
              [:table {:style {:border-collapse "collapse"
                               :width "100%"}}
               [:tbody
                (doall (example-rows))]]]])
