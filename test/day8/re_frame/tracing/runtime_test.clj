(ns day8.re-frame.tracing.runtime-test
  "Tests for day8.re-frame.tracing.runtime — wrap/unwrap macros + the
   side-table they maintain. See the namespace docstring there for the
   design rationale (rfd-8g9 item 7 of docs/improvement-plan.md §6)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime
             :refer [wrap-handler! unwrap-handler! wrap-fx! unwrap-fx!]]
            ;; The wrap-* macros expand to (re-frame.core/reg-event-db ...)
            ;; etc., so re-frame.core has to be on the test classpath for
            ;; macroexpansion to resolve.
            [re-frame.core]
            [re-frame.registrar :as registrar]))

(def captured-traces (atom []))

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
      (f)
      ;; Belt-and-suspenders cleanup: any wrap that escaped a test
      ;; gets undone here so the next test starts clean.
      (runtime/unwrap-all!))))

;; ---------------------------------------------------------------------------
;; Feature-detection hook — rf-yvu / ci-hpg
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
