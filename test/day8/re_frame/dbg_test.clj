(ns day8.re-frame.dbg-test
  "Tests for `day8.re-frame.tracing/dbg`. Single-form
   tracing macro — wraps a single expression and emits one trace
   record per evaluation. Inside a re-frame trace event the trace
   lands on :tags :code (same surface as fn-traced); outside, falls
   back to tap>.

   Scope of this file:
     - in-trace path: `*current-trace*` bound → send-trace! captures
       `:form` / `:result` plus optional `:name` / `:locals`
     - out-of-trace path: `*current-trace*` nil → tap> with
       `:debux/dbg true`
     - opts plumb-through: `:if` (predicate gate), `:tap?` (always
       tap, in addition to in-trace emit), `:name`, `:locals`
     - value transparency: dbg returns the form's value unchanged

   We don't need re-frame's full dispatch fixture (cf.
   integration_test.clj) — `dbg`'s sink is just `*current-trace*` +
   tap>, so we can drive it directly with a synthetic
   `(binding [trace/*current-trace* ...] ...)` per test."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.tracing :refer [dbg]]
            ;; Required for the dbg-stub-is-no-op test: macroexpand-1
            ;; only recognises a fully-qualified macro reference when
            ;; the var actually resolves, which means the ns must be
            ;; loaded.
            [day8.re-frame.tracing-stubs]
            [re-frame.trace :as rft]))

;; ---------------------------------------------------------------------------
;; Helpers — capture the in-trace and out-of-trace sinks
;; ---------------------------------------------------------------------------

(defn- with-fresh-current-trace
  "Run `body-fn` with re-frame.trace/*current-trace* bound to a
   minimal trace map. Returns the FINAL value of *current-trace*
   after the body — which is what the dbg macro's send-trace! mutates
   via merge-trace! — so callers can assert on the captured `:code`
   entries.

   `set!` on a dynamic var only works inside a `binding`, which is
   exactly what merge-trace!'s implementation needs to write to
   *current-trace*. The trace.cljc ns gates the whole machinery on
   `is-trace-enabled?` (a CLJ atom that defaults false), so we flip
   it on for the duration of the body too."
  [body-fn]
  (with-redefs [rft/trace-enabled? true]
    (let [captured (atom nil)]
      (binding [rft/*current-trace* {:tags {}}]
        (body-fn)
        (reset! captured rft/*current-trace*))
      @captured)))

(defn- code-entries
  "Pull the :code vec off a captured *current-trace* map."
  [captured-trace]
  (get-in captured-trace [:tags :code] []))

;; ---------------------------------------------------------------------------
;; In-trace path — send-trace! captures into *current-trace*'s :code
;; ---------------------------------------------------------------------------

(deftest dbg-in-trace-emits-code-entry
  (testing "single dbg call → one :code entry on the active trace"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (+ 1 2))))
          entries  (code-entries captured)]
      (is (= 1 (count entries)))
      (let [e (first entries)]
        (is (= '(+ 1 2) (:form e)))
        (is (= 3 (:result e)))
        (is (= 0 (:indent-level e)))
        (is (= 0 (:syntax-order e)))
        (is (= 0 (:num-seen e)))))))

(deftest dbg-returns-the-form-value
  (testing "dbg is value-transparent — its return is the wrapped expression's value"
    (with-fresh-current-trace
      (fn [] (is (= 42 (dbg (* 6 7))))))
    (with-fresh-current-trace
      (fn [] (is (= [:a :b] (dbg [:a :b])))))))

(deftest dbg-name-flows-into-payload
  (testing "the :name opt is whitelisted onto the :code entry payload"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (inc 9) {:name "next-id"})))
          [e]      (code-entries captured)]
      (is (= "next-id" (:name e)))
      (is (= 10 (:result e))))))

(deftest dbg-locals-flow-into-payload
  (testing ":locals is forwarded as the [[sym val] ...] vec the caller supplied"
    (let [n        7
          captured (with-fresh-current-trace
                     (fn [] (dbg (* n 2) {:locals [['n n]]})))
          [e]      (code-entries captured)]
      (is (= [['n 7]] (:locals e)))
      (is (= 14 (:result e))))))

(deftest dbg-if-predicate-gates-emission
  (testing ":if pred — trace fires only when (pred result) is truthy"
    (let [captured (with-fresh-current-trace
                     (fn []
                       (dbg 5  {:if odd?})    ; emits
                       (dbg 6  {:if odd?})    ; suppressed
                       (dbg 7  {:if odd?})))  ; emits
          entries  (code-entries captured)]
      (is (= [5 7] (mapv :result entries))))))

(deftest dbg-tap-also-fires-tap-when-in-trace
  (testing ":tap? true → both send-trace! AND tap> fire"
    (let [taps (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (let [captured (with-fresh-current-trace
                         (fn [] (dbg :hello {:name "lbl" :tap? true})))
              entries  (code-entries captured)]
          (is (= 1 (count entries))
              "the in-trace send-trace! fired")
          ;; tap> is async (handled on the tap-loop agent). Give it
          ;; a moment to drain.
          (Thread/sleep 50)
          (is (= 1 (count @taps))
              ":tap? true also fires tap>")
          (is (= true (:debux/dbg (first @taps)))
              "tap> payloads carry the :debux/dbg sentinel"))
        (finally
          (remove-tap tapper))))))

(deftest dbg-tap-not-set-no-tap-when-in-trace
  (testing "default :tap? unset → tap> stays silent in-trace"
    (let [taps (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (with-fresh-current-trace
          (fn [] (dbg :hi)))
        (Thread/sleep 50)
        (is (empty? @taps)
            "no :tap? → no tap> in the in-trace path")
        (finally
          (remove-tap tapper))))))

;; ---------------------------------------------------------------------------
;; Out-of-trace path — *current-trace* nil → tap> always
;; ---------------------------------------------------------------------------

(deftest dbg-out-of-trace-falls-back-to-tap
  (testing "outside any re-frame trace event, tap> fires regardless of :tap?"
    (let [taps   (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        ;; *current-trace* is nil at top-level — no `binding` here.
        (let [r (dbg (str "result-" 42))]
          (is (= "result-42" r)
              "still value-transparent out of trace"))
        (Thread/sleep 50)
        (is (= 1 (count @taps)))
        (let [t (first @taps)]
          (is (= "result-42" (:result t)))
          (is (= '(str "result-" 42) (:form t)))
          (is (= true (:debux/dbg t))))
        (finally
          (remove-tap tapper))))))

(deftest dbg-out-of-trace-respects-if
  (testing ":if pred suppresses tap> in the out-of-trace path too"
    (let [taps   (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (dbg 4 {:if odd?})  ; suppressed
        (dbg 5 {:if odd?})  ; emits
        (Thread/sleep 50)
        (is (= [5] (mapv :result @taps)))
        (finally
          (remove-tap tapper))))))

;; ---------------------------------------------------------------------------
;; tracing-stubs no-op — `dbg` from the stubs must be value-transparent
;; with no trace side effect.
;; ---------------------------------------------------------------------------

(deftest dbg-stub-is-no-op
  (testing "the production stub returns the form's value with no tap> / send-trace!"
    (let [taps   (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        ;; Reach for the stub explicitly so this test still exercises
        ;; it even when the test ns required the live `dbg` macro.
        (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg (+ 2 3)))]
          (is (= '(+ 2 3) r)
              "stub macroexpands to bare form — no wrap, no opts"))
        (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg (+ 2 3) {:name "x"}))]
          (is (= '(+ 2 3) r)
              "stub with opts also macroexpands to the bare form"))
        (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbgn (-> 1 inc inc)))]
          (is (= '(-> 1 inc inc) r)
              "dbgn stub also bare-forms"))
        (Thread/sleep 50)
        (is (empty? @taps)
            "no taps — stubs don't fire any sink")
        (finally
          (remove-tap tapper))))))
