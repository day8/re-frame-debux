(ns example.events
  (:require
   [day8.re-frame.tracing :as tracing
    :refer-macros [dbg dbg-last dbgn defn-fx-traced fn-traced fx-traced]]
   [day8.re-frame.tracing.runtime :as tracing-runtime
    :refer-macros [wrap-handler!]]
   [example.db :as db]
   [re-frame.core :as re-frame]))

(defonce console-tap-installed? (atom false))

(defn install-console-tap! []
  (when-not @console-tap-installed?
    (add-tap #(.log js/console "debux tap" (clj->js %)))
    (reset! console-tap-installed? true)))

(re-frame/reg-event-db
 ::initialize-db
 (fn-traced [_ _]
   db/default-db))

(re-frame/reg-event-db
 ::let
 (fn-traced {:locals true :msg "let bindings"}
            [db _]
   (let [a 10
         b (+ a 20)
         logo "logo"
         original-logo "old-logo"
         new-logo? (not
                    (or (empty? logo)
                        (= logo original-logo)))]
     (assoc db ::let {:sum (+ a b)
                      :new-logo? new-logo?}))))

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
   (let [show-alt? (get db ::show-alt? false)]
     (cond-> db
       true      (assoc ::-> (inc 5))
       show-alt? (assoc ::-> (dec 5))))))

(re-frame/reg-event-db
 ::cond->>
 (fn-traced [db _]
   (let [show-alt? (get db ::show-alt? false)]
     (cond->> db
       true      (#(assoc %1 ::->> (inc 5)))
       show-alt? (#(assoc %1 ::->> (dec 5)))))))

(re-frame/reg-event-db
 ::tricky
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

(re-frame/reg-event-db
 ::everything->
 (fn-traced [db _]
   (-> db
       (assoc ::everything-> (-> [10 11]
                                 (cond->
                                  true  (conj 12)
                                  true  (as-> xs (map - xs [3 2 1]))
                                  true  (->> (map inc))
                                  true  (some->> (map inc))
                                  false (reverse)))))))

(re-frame/reg-event-db
 ::dot
 (fn-traced [db _]
   (-> db
       (assoc ::dot "abc")
       (update ::dot #(.. % toUpperCase (concat "ABC"))))))

(re-frame/reg-event-db
 ::vec
 (fn-traced [db _]
   (-> db
       (assoc ::vec [:div {:style {:color (if true (str "hello " "world") :never)}}
                     (when true [inc "putting inc symbol inside a vector"])]))))

(re-frame/reg-event-fx
 ::map
 (fx-traced {:locals true :msg "inline fx-traced"}
            [{:keys [db]} _]
   {:db       (assoc db :a (inc 5)
                        :b (if true :t :f))
    :dispatch [::record "fx-traced returned :dispatch"]}))

(re-frame/reg-event-fx
 ::implied-do
 (fx-traced {:final true :msg "fx-traced implied do"}
            [{:keys [db]} _]
   (inc 1)
   {:db (assoc db :a (inc 5)
                  :b (if true :t :f))}))

(re-frame/reg-event-db
 ::record
 (fn-traced {:msg "follow-up event"}
            [db [_ entry]]
   (update db ::log (fnil conj []) entry)))

(re-frame/reg-event-db
 ::opts
 (fn-traced {:locals true
             :if some?
             :once true
             :msg "locals + if + once"}
            [db [_ amount]]
   (let [next-count (+ amount (get db ::opts-count 0))]
     (assoc db ::opts-count next-count))))

(re-frame/reg-event-db
 ::final
 (fn-traced {:final true :msg "final value only"}
            [db _]
   (let [base 5
         scaled (* base 10)
         described {:base base
                    :scaled scaled
                    :label (str "scaled-" scaled)}]
     (assoc db ::final described))))

(re-frame/reg-event-db
 ::verbose
 (fn-traced {:verbose true :msg "verbose literals"}
            [db _]
   (assoc db ::verbose {:n 42
                        :flag true
                        :label "leaf literals"})))

(re-frame/reg-event-db
 ::frame-markers
 (fn-traced {:msg "function frame markers"}
            [db _]
   (let [result (->> (range 4)
                     (map inc)
                     (reduce +))]
     (assoc db ::frame-markers result))))

(re-frame/reg-event-db
 ::standalone-probes
 (fn [db _]
   (let [numbers (dbg [1 2 3 4] {:msg "dbg value" :tap? true})
         evens   (->> numbers
                      (filter even?)
                      (dbg-last {:msg "dbg-last after filter"})
                      (map inc)
                      vec)
         summary (dbgn (-> {:evens evens}
                           (assoc :total (reduce + evens))))]
     (assoc db ::standalone-probes summary))))

(re-frame/reg-event-db
 ::tap-output
 (fn-traced {:final true :msg "set-tap-output!"}
            [db _]
   (install-console-tap!)
   (tracing/set-tap-output! true)
   (assoc db ::tap-output :enabled)))

(defn-fx-traced {:final true :msg "defn-fx-traced handler"}
  update-name-handler
  [{:keys [db]} [_ suffix]]
  {:db (assoc db :name (str "re-frame/debux " suffix))})

(re-frame/reg-event-fx
 ::defn-fx
 update-name-handler)

(re-frame/reg-event-db
 ::runtime-target
 (fn-traced {:msg "runtime target original"}
            [db [_ amount]]
   (assoc db ::runtime-target {:source :original
                               :amount amount})))

(re-frame/reg-event-fx
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
      :dispatch [::runtime-target 21]})))
