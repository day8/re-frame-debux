(ns day8.re-frame.cljs-integration-test
  #?(:cljs
     (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
               [day8.re-frame.debux.common.util :as util]
               [day8.re-frame.tracing :as tracing]
               [day8.re-frame.tracing.runtime :as runtime]
               [re-frame.core :as re-frame]
               [re-frame.db]
               [re-frame.subs :as subs]
               [re-frame.trace :as rft]
               [reagent.core :as reagent])))

#?(:cljs
   (do
     ;; CLJS companion to integration_test.clj. This fixture keeps the
     ;; browser-test path focused on CLJS-shaped behaviour: no JVM tap
     ;; sleeps, no CLJ schedule-debounce workaround, and app-db updates
     ;; flowing through Reagent's ratom-backed re-frame.db/app-db.

     (defn- reset-capture! []
       (reset! rft/traces [])
       (reset! rft/next-delivery 0))

     (defn- captured-traces []
       @rft/traces)

     (defn- code-entries
       [captured]
       (->> captured
            (mapcat (comp :code :tags))
            vec))

     (defn- forms
       [captured]
       (set (map :form (code-entries captured))))

     (defn- frame-entries
       [captured]
       (->> captured
            (mapcat (comp :trace-frames :tags))
            vec))

     (defn- fx-effects-entries
       [captured]
       (->> captured
            (mapcat (comp :fx-effects :tags))
            vec))

     (defn- dispatch-sync!
       [event]
       (re-frame/dispatch-sync event)
       (reagent/flush)
       (captured-traces))

     (defn- with-trace-capture
       [f]
       (with-redefs [rft/trace-enabled?     true
                     tracing/trace-enabled? true]
         (reset-capture!)
         (reset! re-frame.db/app-db {})
         (reset! runtime/wrapped-originals {})
         (subs/clear-subscription-cache!)
         (util/-reset-once-state!)
         (util/set-tap-output! false)
         (try
           (f)
           (finally
             (runtime/unwrap-all!)
             (subs/clear-subscription-cache!)
             (util/set-tap-output! false)))))

     (use-fixtures :each with-trace-capture)

     (deftest wrap-handler-event-emits-code-on-dispatch
       (testing "wrap-handler! :event installs a traced reg-event-db handler"
         (re-frame/reg-event-db ::wrapped-event (fn [db _] db))
         (runtime/wrap-handler! :event ::wrapped-event
                                (fn [db [_ amount]]
                                  (let [n (inc amount)]
                                    (assoc db :n n))))
         (dispatch-sync! [::wrapped-event 41])
         (let [code (code-entries (captured-traces))
               fs   (forms (captured-traces))]
           (is (= 42 (:n @re-frame.db/app-db))
               "wrapped handler updated the ratom-backed app-db")
           (is (seq code)
               "wrapped dispatch emitted :code entries")
           (is (some #(= 42 (:result %)) code)
               "the inc result flowed into :code")
           (is (some #(re-find #"assoc" (pr-str %)) fs)
               "captured forms include the wrapped body"))))

     (deftest wrap-event-flavours-emit-code-on-dispatch
       (testing "wrap-event-fx! preserves the reg-event-fx effects contract"
         (re-frame/reg-event-fx ::wrapped-event-fx (fn [_ _] {}))
         (runtime/wrap-event-fx! ::wrapped-event-fx
                                 (fn [_ [_ amount]]
                                   (let [total (* 2 amount)]
                                     {:db {:total total}})))
         (dispatch-sync! [::wrapped-event-fx 21])
         (let [code (code-entries (captured-traces))]
           (is (= 42 (:total @re-frame.db/app-db)))
           (is (some #(= 42 (:result %)) code)
               "the effect-map computation emitted :code")))

       (testing "wrap-event-ctx! preserves the reg-event-ctx context contract"
         (reset-capture!)
         (reset! re-frame.db/app-db {})
         (re-frame/reg-event-ctx ::wrapped-event-ctx (fn [ctx] ctx))
         (runtime/wrap-event-ctx! ::wrapped-event-ctx
                                  (fn [{:keys [coeffects] :as ctx}]
                                    (let [n (inc 41)]
                                      (assoc-in ctx [:effects :db]
                                                (assoc (:db coeffects) :n n)))))
         (dispatch-sync! [::wrapped-event-ctx])
         (let [code (code-entries (captured-traces))]
           (is (= 42 (:n @re-frame.db/app-db)))
           (is (some #(= 42 (:result %)) code)
               "the context handler computation emitted :code"))))

     (deftest wrap-fx-and-wrap-sub-emit-code
       (testing "wrap-fx! traces the custom effect handler fired by dispatch-sync"
         (let [side-effect (atom nil)]
           (re-frame/reg-fx ::log (fn [v] (reset! side-effect v)))
           (re-frame/reg-event-fx ::trigger-fx
                                  (fn [_ [_ v]]
                                    {::log v}))
           (runtime/wrap-fx! ::log
                             (fn [v]
                               (let [s (str "got:" v)]
                                 (reset! side-effect s))))
           (dispatch-sync! [::trigger-fx 42])
           (let [fs (forms (captured-traces))]
             (is (= "got:42" @side-effect))
             (is (some #(re-find #"got:" (pr-str %)) fs)
                 "the wrapped fx body emitted :code"))))

       (testing "wrap-sub! traces the subscription computation reached from app-db"
         (reset-capture!)
         (reset! re-frame.db/app-db {})
         (re-frame/reg-event-db ::set-n
                                (fn [db [_ n]]
                                  (assoc db :n n)))
         (re-frame/reg-sub ::computed-n
                           (fn [db _]
                             (:n db)))
         (runtime/wrap-sub! ::computed-n
                            (fn [db _]
                              (let [n (inc (:n db))]
                                n)))
         (dispatch-sync! [::set-n 41])
         (reset-capture!)
         (let [sub (re-frame/subscribe [::computed-n])]
           (is (= 42 @sub)
               "the wrapped subscription computes from app-db")
           (let [code (code-entries (captured-traces))]
             (is (some #(= 42 (:result %)) code)
                 "the subscription run emitted :code entries")))))

     (deftest fn-traced-options-flow-through-app-db
       (testing ":locals, :if, :once, :msg, :verbose and frame tags survive dispatch"
         (re-frame/reg-event-db ::optioned (fn [_ _] {}))
         (re-frame/reg-event-db ::optioned
                                (tracing/fn-traced
                                  {:locals true
                                   :if      number?
                                   :once    true
                                   :msg     "optioned-handler"
                                   :verbose true}
                                  [_ [_ x]]
                                  (let [n       (inc x)
                                        literal 42]
                                    {:n n :literal literal})))
         (dispatch-sync! [::optioned 41])
         (let [captured (captured-traces)
               code     (code-entries captured)
               frames   (frame-entries captured)
               fs       (forms captured)]
           (is (= {:n 42 :literal 42} @re-frame.db/app-db))
           (is (seq code))
           (is (every? #(number? (:result %)) code)
               ":if number? filtered non-numeric results")
           (is (every? :locals code)
               ":locals metadata is attached to every surviving entry")
           (is (some #(some (fn [[sym v]]
                              (and (= 'x sym) (= 41 v)))
                            (:locals %))
                     code)
               "captured locals include the event argument")
           (is (every? #(= "optioned-handler" (:msg %)) code)
               ":msg labels every surviving entry")
           (is (contains? fs 42)
               ":verbose emits leaf literals as standalone forms")
           (is (= [:enter :exit] (mapv :phase frames))
               "frame markers wrap the traced handler")
           (is (= {:n 42 :literal 42} (:result (second frames)))
               "the exit frame carries the app-db value returned by the handler"))
         (reset-capture!)
         (dispatch-sync! [::optioned 41])
         (is (empty? (code-entries (captured-traces)))
             ":once suppresses a second dispatch with identical form results")))

     (deftest fn-traced-show-all-alias-wraps-literals
       (testing ":show-all is the CLJS alias for verbose literal tracing"
         (re-frame/reg-event-db ::show-all (fn [_ _] {}))
         (re-frame/reg-event-db ::show-all
                                (tracing/fn-traced
                                  {:show-all true}
                                  [_ _]
                                  {:answer 99}))
         (dispatch-sync! [::show-all])
         (is (contains? (forms (captured-traces)) 99)
             ":show-all emits the literal as its own :code form")))

     (deftest fx-traced-emits-per-effect-key
       (testing "fx-traced emits one :fx-effects entry per returned effect key"
         (let [side-effect (atom nil)]
           (re-frame/reg-fx ::audit (fn [v] (reset! side-effect v)))
           (re-frame/reg-event-fx ::fx-traced-checkout
                                  (tracing/fx-traced
                                    [_ [_ amount]]
                                    (let [total (* 2 amount)]
                                      {:db      {:total total}
                                       ::audit  {:total total}
                                       :dispatch [:unused]})))
           (dispatch-sync! [::fx-traced-checkout 21])
           (let [entries (fx-effects-entries (captured-traces))
                 by-key  (into {} (map (juxt :fx-key :value)) entries)]
             (is (= 42 (:total @re-frame.db/app-db)))
             (is (= {:total 42} @side-effect))
             (is (= #{:db ::audit :dispatch} (set (map :fx-key entries))))
             (is (= {:total 42} (:db by-key)))
             (is (= {:total 42} (::audit by-key)))))))

     (deftest set-tap-output-routes-cljs-traces-to-tap
       (testing "set-tap-output! true sends CLJS trace entries through tap>"
         (let [received (atom [])]
           (with-redefs [cljs.core/tap> (fn [x]
                                          (swap! received conj x)
                                          true)]
             (try
               (util/set-tap-output! true)
               (re-frame/reg-event-db ::tapped (fn [db _] db))
               (runtime/wrap-handler! :event ::tapped
                                      (fn [db _]
                                        (let [n (inc 41)]
                                          (assoc db :n n))))
               (dispatch-sync! [::tapped])
               (let [results (set (map :result @received))
                     fs      (set (map :form @received))]
                 (is (contains? results 42)
                     "tap> received the traced inc result")
                 (is (some #(re-find #"inc" (pr-str %)) fs)
                     "tap> entries include the traced form")
                 (is (every? #(contains? % :indent-level) @received)
                     "tap payloads carry the :code entry contract"))
               (finally
                 (util/set-tap-output! false)))))))))
