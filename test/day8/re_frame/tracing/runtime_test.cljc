(ns day8.re-frame.tracing.runtime-test
  "Tests for day8.re-frame.tracing.runtime — wrap/unwrap macros + the
   side-table they maintain. See the namespace docstring there for the
   design rationale (docs/improvement-plan.md §6 item 7)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime
             :refer [wrap-handler! unwrap-handler! wrap-fx! unwrap-fx! wrap-sub!]]
            [re-frame.core]
            [re-frame.db]
            [re-frame.registrar :as registrar]))

(def captured-traces (atom []))

(defn- code-entries
  []
  (filterv #(contains? % :form) @captured-traces))

(use-fixtures :each
  (fn [f]
    ;; fn-traced gates on (is-trace-enabled?) at runtime; force it
    ;; on so the trace-emit path is reachable in tests. send-trace!
    ;; is redirected into our capture atom so we can assert what
    ;; the wrapped body emitted without going through 10x's trace
    ;; debounce.
    (with-redefs [tracing/is-trace-enabled? (constantly true)
                  ut/send-trace! (fn [t]
                                   (swap! captured-traces conj
                                          (update t :form ut/tidy-macroexpanded-form {})))]
      (reset! captured-traces [])
      (reset! runtime/wrapped-originals {})
      (ut/-reset-once-state!)
      (f)
      ;; Belt-and-suspenders cleanup: any wrap that escaped a test
      ;; gets undone here so the next test starts clean.
      (runtime/unwrap-all!))))

;; ---------------------------------------------------------------------------
;; Feature-detection hook
;; ---------------------------------------------------------------------------

(deftest runtime-api?-returns-true
  (testing "runtime-api? is the feature-detection contract for the
            wrap-handler! / unwrap-handler! / etc. surface — its
            presence advertises availability; consumers probe the
            var's JS-munged path via goog.global rather than
            requiring this ns at compile time."
    (is (true? (runtime/runtime-api?)))))

;; ---------------------------------------------------------------------------
;; Side-table mechanics
;; ---------------------------------------------------------------------------

(deftest wrapped-originals-starts-empty
  (testing "Fixture initialises wrapped-originals to empty"
    (is (empty? @runtime/wrapped-originals))))

(deftest wrap-captures-original-by-reference
  (testing "wrap-fx! stores the original handler verbatim in the side-table"
    (let [orig-fn (fn [_v] :original-result)]
      (registrar/register-handler :fx :test/probe orig-fn)
      (wrap-fx! :test/probe (fn [v] (* v 2)))
      (is (identical? orig-fn (get @runtime/wrapped-originals [:fx :test/probe]))
          "side-table value is the same fn object that was registered"))))

(deftest wrapped?-and-wrapped-list
  (testing "wrapped? true after wrap, false after unwrap"
    (registrar/register-handler :fx :test/a (fn [_] :a))
    (is (false? (runtime/wrapped? :fx :test/a)))
    (wrap-fx! :test/a (fn [v] v))
    (is (true? (runtime/wrapped? :fx :test/a)))
    (unwrap-fx! :test/a)
    (is (false? (runtime/wrapped? :fx :test/a))))
  (testing "wrapped-list returns the kind/id tuples of every active wrap"
    (registrar/register-handler :fx :test/b (fn [_] :b))
    (registrar/register-handler :fx :test/c (fn [_] :c))
    (wrap-fx! :test/b (fn [v] v))
    (wrap-fx! :test/c (fn [v] v))
    (is (= #{[:fx :test/b] [:fx :test/c]}
           (set (runtime/wrapped-list))))))

(deftest unwrap-restores-original-by-reference
  (testing "unwrap puts back the SAME fn object that was captured"
    (let [orig-fn (fn [_v] :original-result)]
      (registrar/register-handler :fx :test/probe orig-fn)
      (wrap-fx! :test/probe (fn [v] (* v 2)))
      (is (true? (unwrap-fx! :test/probe)))
      (is (identical? orig-fn (registrar/get-handler :fx :test/probe))
          "after unwrap, the registrar has the same fn back, not a new wrapper"))))

(deftest unwrap-clears-side-table-entry
  (let [orig-fn (fn [_] :a)]
    (registrar/register-handler :fx :test/clear orig-fn)
    (wrap-fx! :test/clear (fn [v] v))
    (is (runtime/wrapped? :fx :test/clear))
    (unwrap-fx! :test/clear)
    (is (not (runtime/wrapped? :fx :test/clear))
        "side-table no longer carries a [kind id] entry post-unwrap")))

(deftest unwrap-on-non-wrapped-is-no-op
  (testing "unwrap-handler! on a non-wrapped [kind id] returns false"
    (is (false? (unwrap-handler! :fx :test/never-wrapped))))
  (testing "side-table is unchanged"
    (is (empty? @runtime/wrapped-originals))))

;; ---------------------------------------------------------------------------
;; Trace emission — proves the wrapped fn really is fn-traced
;; ---------------------------------------------------------------------------

(deftest wrap-fx-emits-code-traces-when-called
  (testing "calling a wrapped fx fires send-trace! for forms in its body"
    (registrar/register-handler :fx :test/effect (fn [_v] :original))
    (wrap-fx! :test/effect (fn [v] (let [doubled (* 2 v)] doubled)))
    (let [wrapped-fx (registrar/get-handler :fx :test/effect)]
      (is (= 10 (wrapped-fx 5))
          "wrapped fn produces the expected return value")
      (is (seq @captured-traces)
          "fn-traced fired send-trace! at least once")
      ;; pr-str (not str) — `(str some-list)` returns a JVM-style
      ;; "clojure.lang.PersistentList@<hash>" under CLJ; only CLJS's
      ;; default str-on-coll prints the form. pr-str is uniform.
      (is (some #(re-find #"\* 2 v" (pr-str (:form %))) @captured-traces)
          "captured trace forms include sub-forms of the wrapped body")
      (is (some #(re-find #"let" (pr-str (:form %))) @captured-traces)
          "captured trace forms include the let binding"))))

(deftest wrap-fx-threads-fn-traced-options
  (testing "wrap-fx! accepts a fn-traced opts map and forwards every option"
    (registrar/register-handler :fx :test/options (fn [_v] :original))
    (wrap-fx! :test/options
              {:locals true
               :if      number?
               :once    true
               :verbose true
               :msg     "runtime-fx"}
              (fn [v]
                (let [n (inc v)]
                  (+ n 10))))
    (let [wrapped-fx (registrar/get-handler :fx :test/options)]
      (is (= 15 (wrapped-fx 4)))
      (let [first-code (code-entries)]
        (is (seq first-code)
            "first call emits code traces")
        (is (every? #(= "runtime-fx" (:msg %)) first-code)
            ":msg labels every emitted code entry")
        (is (every? #(number? (:result %)) first-code)
            ":if number? filters out non-numeric entries")
        (is (some #(some (fn [[sym value]]
                           (and (= 'v sym) (= 4 value)))
                         (:locals %))
                  first-code)
            ":locals captures the wrapped fx argument")
        (is (some #(= 10 (:form %)) first-code)
            ":verbose traces leaf literals that default mode skips"))
      (reset! captured-traces [])
      (is (= 15 (wrapped-fx 4)))
      (is (empty? (code-entries))
          ":once suppresses the second call with identical form/results"))))

(deftest unwrap-stops-trace-emission
  (testing "after unwrap, calling the original handler fires NO traces"
    (let [calls (atom 0)
          orig-fn (fn [v] (swap! calls inc) (str "orig:" v))]
      (registrar/register-handler :fx :test/silent orig-fn)
      (wrap-fx! :test/silent (fn [v] (let [r (str "wrapped:" v)] r)))
      ;; Call the wrapped version — should emit
      ((registrar/get-handler :fx :test/silent) 1)
      (is (seq @captured-traces) "wrapped call emitted traces")
      ;; Reset capture, unwrap, call again — should NOT emit
      (reset! captured-traces [])
      (unwrap-fx! :test/silent)
      ((registrar/get-handler :fx :test/silent) 2)
      (is (= 1 @calls) "original fn was called exactly once")
      (is (empty? @captured-traces)
          "no traces after unwrap — fn-traced is no longer in the path"))))

;; ---------------------------------------------------------------------------
;; unwrap-all! — bulk cleanup
;; ---------------------------------------------------------------------------

(deftest unwrap-all-restores-every-wrap
  (registrar/register-handler :fx :test/a (fn [_] :a))
  (registrar/register-handler :fx :test/b (fn [_] :b))
  (wrap-fx! :test/a (fn [v] v))
  (wrap-fx! :test/b (fn [v] v))
  (testing "unwrap-all returns the kind/id pairs it processed"
    (let [restored (runtime/unwrap-all!)]
      (is (= 2 (count restored)))
      (is (= #{[:fx :test/a] [:fx :test/b]} (set restored)))))
  (testing "side-table is empty after unwrap-all"
    (is (empty? @runtime/wrapped-originals)))
  (testing "the registrar entries are back to their originals"
    (is (= :a ((registrar/get-handler :fx :test/a) :ignored)))
    (is (= :b ((registrar/get-handler :fx :test/b) :ignored)))))

(deftest unwrap-all-on-empty-is-no-op
  (testing "unwrap-all on a clean side-table returns []"
    (is (= [] (runtime/unwrap-all!)))))

;; ---------------------------------------------------------------------------
;; Refusal semantics — double-wrap and missing-handler edge cases
;; ---------------------------------------------------------------------------

(deftest double-wrap-is-refused
  (testing "second wrap-fx! on the same id returns a failure map and does NOT
            clobber the side-table's record of the true original"
    (let [orig-fn (fn [_] :original)]
      (registrar/register-handler :fx :test/double orig-fn)
      ;; first wrap succeeds
      (is (= [:fx :test/double]
             (wrap-fx! :test/double (fn [v] (let [a (* 2 v)] a)))))
      (is (identical? orig-fn (get @runtime/wrapped-originals [:fx :test/double]))
          "first wrap captured the user's original")
      ;; second wrap is refused
      (let [result (wrap-fx! :test/double (fn [v] (let [b (* 3 v)] b)))]
        (is (= {:ok? false :reason :already-wrapped :kind :fx :id :test/double}
               result)
            "second wrap returns the documented refusal map"))
      (is (identical? orig-fn (get @runtime/wrapped-originals [:fx :test/double]))
          "side-table still holds the user's true original — second wrap did
           not capture the already-wrapped fn"))))

(deftest unwrap-after-refused-double-wrap-restores-true-original
  (testing "after a refused double-wrap, unwrap restores the user's original
            (the bug this bead fixes: pre-fix unwrap restored the first wrapper)"
    (let [orig-fn (fn [_v] :truly-original)]
      (registrar/register-handler :fx :test/round-trip orig-fn)
      (wrap-fx! :test/round-trip (fn [v] v))
      (wrap-fx! :test/round-trip (fn [v] v))   ; refused, no-op
      (is (true? (unwrap-fx! :test/round-trip)))
      (is (identical? orig-fn (registrar/get-handler :fx :test/round-trip))
          "registrar holds the user's original, not the first wrapper"))))

(deftest wrap-without-registered-handler-is-refused
  (testing "wrap-fx! against an unregistered id returns :no-handler and leaves
            the side-table empty (the bug fix: pre-fix it stored nil, and
            unwrap would later corrupt the registrar by re-registering nil)"
    (let [result (wrap-fx! :test/never-registered (fn [v] v))]
      (is (= {:ok? false :reason :no-handler :kind :fx :id :test/never-registered}
             result)))
    (is (empty? @runtime/wrapped-originals)
        "no nil entry was recorded in the side-table")
    (is (false? (runtime/wrapped? :fx :test/never-registered))
        "wrapped? agrees the id is not wrapped")))

(deftest wrap-handler-event-kind-refusal-shapes
  (testing "wrap-handler! :event refusal maps carry :kind :event"
    ;; double-wrap path
    (re-frame.core/reg-event-db :test/evt-double (fn [db _] db))
    (wrap-handler! :event :test/evt-double (fn [db _] db))
    (is (= {:ok? false :reason :already-wrapped :kind :event :id :test/evt-double}
           (wrap-handler! :event :test/evt-double (fn [db _] db))))
    ;; no-handler path
    (is (= {:ok? false :reason :no-handler :kind :event :id :test/evt-missing}
           (wrap-handler! :event :test/evt-missing (fn [db _] db))))))

(deftest wrap-event-fx-refusals
  (testing "wrap-event-fx! refuses double-wrap and missing-handler the same way"
    ;; double-wrap
    (re-frame.core/reg-event-fx :test/efx-double (fn [_ _] {}))
    (is (= [:event :test/efx-double]
           (runtime/wrap-event-fx! :test/efx-double
                                   (fn [_ _] (let [a 1] {})))))
    (is (= {:ok? false :reason :already-wrapped :kind :event :id :test/efx-double}
           (runtime/wrap-event-fx! :test/efx-double (fn [_ _] {}))))
    ;; no-handler
    (is (= {:ok? false :reason :no-handler :kind :event :id :test/efx-missing}
           (runtime/wrap-event-fx! :test/efx-missing (fn [_ _] {}))))))

(deftest wrap-event-ctx-refusals
  (testing "wrap-event-ctx! refuses double-wrap and missing-handler the same way"
    ;; double-wrap
    (re-frame.core/reg-event-ctx :test/ectx-double (fn [ctx] ctx))
    (is (= [:event :test/ectx-double]
           (runtime/wrap-event-ctx! :test/ectx-double
                                    (fn [ctx] (let [a 1] ctx)))))
    (is (= {:ok? false :reason :already-wrapped :kind :event :id :test/ectx-double}
           (runtime/wrap-event-ctx! :test/ectx-double (fn [ctx] ctx))))
    ;; no-handler
    (is (= {:ok? false :reason :no-handler :kind :event :id :test/ectx-missing}
           (runtime/wrap-event-ctx! :test/ectx-missing (fn [ctx] ctx))))))

(deftest re-wrap-after-unwrap-succeeds
  (testing "after unwrap, the same id can be wrapped again with a new replacement"
    (let [orig (fn [_v] :orig)]
      (registrar/register-handler :fx :test/cycle orig)
      (is (= [:fx :test/cycle] (wrap-fx! :test/cycle (fn [v] v))))
      (is (true? (unwrap-fx! :test/cycle)))
      (is (= [:fx :test/cycle] (wrap-fx! :test/cycle (fn [v] (* 2 v))))
          "post-unwrap, the side-table is empty so a fresh wrap is allowed")
      (is (identical? orig (get @runtime/wrapped-originals [:fx :test/cycle]))
          "the second wrap re-captured the user's original"))))

;; ---------------------------------------------------------------------------
;; Chain-builder choice — :event flavour routes through reg-event-db
;; ---------------------------------------------------------------------------

(deftest wrap-handler-event-flavour-uses-reg-event-db
  (testing "wrap-handler! :event chooses the reg-event-db chain-builder
            (the db-handler-flavoured one), not reg-event-fx or
            reg-event-ctx. Without this contract, a wrapped :event
            handler that returns plain new-db would be misinterpreted
            as an effects map (under reg-event-fx) or as a context
            (under reg-event-ctx) on every dispatch."
    (re-frame.core/reg-event-db :test/evt-chain (fn [db _] db))
    (let [calls (atom [])]
      (with-redefs [re-frame.core/reg-event-db
                    (fn [id handler]
                      (swap! calls conj :reg-event-db)
                      (re-frame.registrar/register-handler :event id handler))
                    re-frame.core/reg-event-fx
                    (fn [_id _handler]
                      (swap! calls conj :reg-event-fx))
                    re-frame.core/reg-event-ctx
                    (fn [_id _handler]
                      (swap! calls conj :reg-event-ctx))]
        (wrap-handler! :event :test/evt-chain (fn [db _] db)))
      (is (= [:reg-event-db] @calls)
          "reg-event-db was the only re-frame.core/reg-event-* fn invoked"))))

;; ---------------------------------------------------------------------------
;; wrap-sub! — fn-traced through the :sub kind, deref of the registered
;; reaction triggers send-trace! emission
;; ---------------------------------------------------------------------------

(deftest wrap-sub-emits-code-on-deref
  (testing "wrap-sub! routes through reg-sub; deref'ing the reaction it
            returns invokes the user's fn-traced'd body, which fires
            send-trace! for sub-forms.

            Why direct-invoke instead of re-frame.core/subscribe + deref?
            Either path eventually derefs the reaction; calling the
            registered handler ourselves keeps the test independent of
            the subscription cache and reagent-id machinery — same
            pattern as wrap-fx-emits-code-traces-when-called above."
    (re-frame.core/reg-sub :test/calc (fn [db _] (:n db)))
    (wrap-sub! :test/calc {:msg "runtime-sub"} (fn [_db _v] (let [n (inc 41)] {:n n})))
    (let [subs-handler-fn (registrar/get-handler :sub :test/calc)
          reaction        (subs-handler-fn re-frame.db/app-db [:test/calc])]
      @reaction
      (is (seq @captured-traces)
          "deref of the wrapped sub's reaction fired send-trace! at least once")
      (is (every? #(= "runtime-sub" (:msg %)) (code-entries))
          ":msg option flowed through wrap-sub!")
      (is (some #(re-find #"inc" (pr-str (:form %))) @captured-traces)
          "captured trace forms include the (inc 41) sub-form"))))

(deftest wrap-sub-preserves-explicit-signal-fn
  (testing "wrap-sub! accepts the layer-3 reg-sub shape:
            id, signal-fn, computation-fn. The signal-fn wiring is
            preserved and only the computation fn is fn-traced."
    (reset! re-frame.db/app-db {:items [1 2 3]})
    (re-frame.core/reg-sub :test/layer3-total
                           (fn [_query _dyn]
                             re-frame.db/app-db)
                           (fn [db _query]
                             (count (:items db))))
    (wrap-sub! :test/layer3-total
               (fn [_query _dyn]
                 re-frame.db/app-db)
               (fn [db _query]
                 (let [total (count (:items db))]
                   total)))
    (let [subs-handler-fn (registrar/get-handler :sub :test/layer3-total)
          reaction        (subs-handler-fn re-frame.db/app-db [:test/layer3-total])]
      (is (= 3 @reaction)
          "the wrapped layer-3 sub still computes from the signal-fn input")
      (is (some #(re-find #"count" (pr-str (:form %))) @captured-traces)
          "the traced payload comes from the computation fn body"))))

(deftest wrap-sub-preserves-arrow-input-sugar
  (testing "wrap-sub! accepts reg-sub's :<- sugar and traces the final
            computation fn without losing the input subscription."
    (reset! re-frame.db/app-db {:items [1 2 3 4]})
    (re-frame.core/reg-sub :test/items-for-arrow
                           (fn [db _query]
                             (:items db)))
    (re-frame.core/reg-sub :test/arrow-total
                           :<- [:test/items-for-arrow]
                           (fn [items _query]
                             (count items)))
    (wrap-sub! :test/arrow-total
               :<- [:test/items-for-arrow]
               (fn [items _query]
                 (let [total (count items)]
                   total)))
    (let [subs-handler-fn (registrar/get-handler :sub :test/arrow-total)
          reaction        (subs-handler-fn re-frame.db/app-db [:test/arrow-total])]
      (is (= 4 @reaction)
          "the wrapped :<- sub still receives its input subscription value")
      (is (some #(re-find #"count" (pr-str (:form %))) @captured-traces)
          "the traced payload comes from the computation fn body"))))

(deftest wrap-sub-preserves-three-arity-computation-fn
  (testing "wrap-sub! keeps the reg-sub computation fn's dynamic-vector
            arity when the registered handler is invoked with dyn-vec."
    (reset! re-frame.db/app-db {:n 7})
    (re-frame.core/reg-sub :test/dyn-total
                           (fn [db _query dyn-vec]
                             (+ (:n db) (first dyn-vec))))
    (wrap-sub! :test/dyn-total
               (fn [db _query dyn-vec]
                 (let [total (+ (:n db) (first dyn-vec))]
                   total)))
    (let [subs-handler-fn (registrar/get-handler :sub :test/dyn-total)
          reaction        (subs-handler-fn re-frame.db/app-db [:test/dyn-total] [35])]
      (is (= 42 @reaction)
          "the wrapped sub computes with the dyn-vec arity")
      (is (some #(re-find #"\+ \(:n db\)" (pr-str (:form %))) @captured-traces)
          "the traced payload includes the 3-arity computation body"))))
