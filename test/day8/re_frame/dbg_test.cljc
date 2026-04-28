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
       tap, in addition to in-trace emit), `:name`, `:locals`,
       `:once` (consecutive-emit dedup)
     - value transparency: dbg returns the form's value unchanged

   We don't need re-frame's full dispatch fixture (cf.
   integration_test.clj) — `dbg`'s sink is just `*current-trace*` +
   tap>, so we can drive it directly with a synthetic
   `(binding [trace/*current-trace* ...] ...)` per test."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.tracing :as tracing
             #?@(:clj  [:refer [dbg dbgn dbg-last]]
                 :cljs [:refer-macros [dbg dbgn dbg-last]])]
            [day8.re-frame.debux.common.util :as util]
            ;; Required for the dbg-stub-is-no-op test: macroexpand-1
            ;; only recognises a fully-qualified macro reference when
            ;; the var actually resolves, which means the ns must be
            ;; loaded.
            [day8.re-frame.tracing-stubs]
            [re-frame.trace :as rft]))

(defn- wait-for-tap
  "tap> is async on CLJ (agent-pool-backed). Sleep on CLJ to give the
   tap loop a chance to drain; CLJS tap assertions use with-sync-taps
   to avoid racing tap>'s default setTimeout dispatch."
  []
  #?(:clj  (Thread/sleep 50)
     :cljs nil))

(defn- with-sync-taps
  "Run `body-fn` with CLJS tap> delivery made immediate for synchronous assertions."
  [body-fn]
  #?(:cljs (with-redefs [cljs.core/*exec-tap-fn* (fn [f]
                                                   (f)
                                                   true)]
             (body-fn))
     :clj  (body-fn)))

(defn- with-tracing-enabled
  "Run `body-fn` with the day8 tracing macro gate enabled."
  [body-fn]
  (with-redefs [tracing/trace-enabled? true]
    (body-fn)))

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
   *current-trace*. The tracing macro gate and re-frame's trace sink
   gate both need to be on for the in-trace path."
  [body-fn]
  (with-redefs [tracing/trace-enabled? true
                rft/trace-enabled? true]
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

(deftest dbg-disabled-evaluates-form-only
  (testing "trace-enabled? false leaves dbg as the bare form and skips opts/sinks"
    (let [form-evals (atom 0)
          opts-evals (atom 0)
          taps       (atom [])
          tapper     #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (with-redefs [tracing/trace-enabled? false
                      rft/trace-enabled? true]
          (is (= 1 (dbg (swap! form-evals inc)
                        (do
                          (swap! opts-evals inc)
                          {:if   (fn [_]
                                   (swap! opts-evals inc)
                                   true)
                           :tap? true})))))
        (wait-for-tap)
        (is (= 1 @form-evals)
            "the wrapped form still evaluates exactly once")
        (is (zero? @opts-evals)
            "the opts expression and predicate are never evaluated")
        (is (empty? @taps)
            "disabled dbg does not fall back to tap>")
        (finally
          (remove-tap tapper))))))

(deftest dbgn-disabled-evaluates-form-only
  (testing "trace-enabled? false leaves dbgn as the bare form"
    (let [form-evals (atom 0)
          pred-calls (atom 0)
          taps       (atom [])
          tapper     #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (with-redefs [tracing/trace-enabled? false
                      rft/trace-enabled? true]
          (is (= 1 (dbgn (swap! form-evals inc)
                         :if (fn [_]
                               (swap! pred-calls inc)
                               true)))))
        (wait-for-tap)
        (is (= 1 @form-evals)
            "the wrapped form still evaluates exactly once")
        (is (zero? @pred-calls)
            "dbgn opts are not consulted when tracing is disabled")
        (is (empty? @taps)
            "disabled dbgn emits no tap output")
        (finally
          (remove-tap tapper))))))

(deftest dbg-name-flows-into-payload
  (testing "the :name opt is whitelisted onto the :code entry payload"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (inc 9) {:name "next-id"})))
          [e]      (code-entries captured)]
      (is (= "next-id" (:name e)))
      (is (= 10 (:result e))))))

(deftest dbg-msg-flows-into-payload
  (testing "the :msg opt surfaces as the :msg field on the :code entry"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (inc 9) {:msg "labelled"})))
          [e]      (code-entries captured)]
      (is (= "labelled" (:msg e)))
      (is (= 10 (:result e))))))

(deftest dbg-m-alias-equivalent-to-msg
  (testing ":m is an accepted shorthand for :msg"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (inc 9) {:m "short"})))
          [e]      (code-entries captured)]
      (is (= "short" (:msg e))
          ":m flowed through to the :msg field"))))

(deftest dbg-msg-wins-over-m-when-both-set
  (testing ":msg takes precedence over :m when both are present"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (inc 9) {:msg "primary" :m "secondary"})))
          [e]      (code-entries captured)]
      (is (= "primary" (:msg e))))))

(deftest dbg-msg-omitted-when-not-requested
  (testing "no :msg key when the opts don't ask for it"
    (let [captured (with-fresh-current-trace
                     (fn [] (dbg (+ 1 2))))
          [e]      (code-entries captured)]
      (is (not (contains? e :msg))
          "default emission has no :msg key"))))

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
    (with-sync-taps
      (fn []
        (let [taps (atom [])
              tapper #(swap! taps conj %)]
          (add-tap tapper)
          (try
            (let [captured (with-fresh-current-trace
                             (fn [] (dbg :hello {:name "lbl" :tap? true})))
                  entries  (code-entries captured)]
              (is (= 1 (count entries))
                  "the in-trace send-trace! fired")
              (wait-for-tap)
              (is (= 1 (count @taps))
                  ":tap? true also fires tap>")
              (is (= true (:debux/dbg (first @taps)))
                  "tap> payloads carry the :debux/dbg sentinel"))
            (finally
              (remove-tap tapper))))))))

(deftest dbg-tap-not-set-no-tap-when-in-trace
  (testing "default :tap? unset → tap> stays silent in-trace"
    (let [taps (atom [])
          tapper #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (with-fresh-current-trace
          (fn [] (dbg :hi)))
        (wait-for-tap)
        (is (empty? @taps)
            "no :tap? → no tap> in the in-trace path")
        (finally
          (remove-tap tapper))))))

;; ---------------------------------------------------------------------------
;; Out-of-trace path — *current-trace* nil → tap> always
;; ---------------------------------------------------------------------------

(deftest dbg-out-of-trace-falls-back-to-tap
  (testing "outside any re-frame trace event, tap> fires regardless of :tap?"
    (with-sync-taps
      (fn []
        (let [taps   (atom [])
              tapper #(swap! taps conj %)]
          (add-tap tapper)
          (try
            ;; *current-trace* is nil at top-level — no `binding` here.
            (with-tracing-enabled
              (fn []
                (let [r (dbg (str "result-" 42))]
                  (is (= "result-42" r)
                      "still value-transparent out of trace"))
                (wait-for-tap)
                (is (= 1 (count @taps)))
                (let [t (first @taps)]
                  (is (= "result-42" (:result t)))
                  (is (= '(str "result-" 42) (:form t)))
                  (is (= true (:debux/dbg t))))))
            (finally
              (remove-tap tapper))))))))

(deftest dbg-out-of-trace-respects-if
  (testing ":if pred suppresses tap> in the out-of-trace path too"
    (with-sync-taps
      (fn []
        (let [taps   (atom [])
              tapper #(swap! taps conj %)]
          (add-tap tapper)
          (try
            (with-tracing-enabled
              (fn []
                (dbg 4 {:if odd?})  ; suppressed
                (dbg 5 {:if odd?})  ; emits
                (wait-for-tap)
                (is (= [5] (mapv :result @taps)))))
            (finally
              (remove-tap tapper))))))))

;; ---------------------------------------------------------------------------
;; tracing-stubs no-op — `dbg` from the stubs must be value-transparent
;; with no trace side effect.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; :once — consecutive-emit dedup
;; ---------------------------------------------------------------------------

(deftest dbg-once-suppresses-second-identical-emit
  (testing ":once true emits the first invocation and skips the second when result is unchanged"
    ;; ONE dbg call site (inside the fn body), executed twice — the
    ;; macro's trace-id is baked into the expansion at compile time,
    ;; so both invocations share the same trace-id and the second
    ;; sees the same (form, result) and dedupes.
    (util/-reset-once-state!)
    (let [f        (fn [] (dbg (+ 1 2) {:once true}))
          captured (with-fresh-current-trace (fn [] (f) (f)))
          entries  (code-entries captured)]
      (is (= 1 (count entries))
          "only the first invocation emits — the second's identical result is suppressed"))))

(deftest dbg-once-emits-when-result-changes
  (testing ":once still emits when the same call site produces a different result"
    (util/-reset-once-state!)
    (let [x        (atom 0)
          ;; ONE dbg call site, executed twice. Each run produces a
          ;; different result (1, then 2), so neither emission is
          ;; deduped.
          f        (fn [] (dbg (swap! x inc) {:once true}))
          captured (with-fresh-current-trace (fn [] (f) (f)))
          entries  (code-entries captured)]
      (is (= 2 (count entries))
          "swap! produces 1 then 2 — different results, neither deduped"))))

(deftest dbg-once-distinct-call-sites-do-not-dedup-each-other
  (testing "two SEPARATE (dbg ... {:once true}) call sites each emit on their own first run"
    ;; Sanity: :once is per-call-site, not per-form-text. Two literal
    ;; copies of `(dbg (+ 1 2) {:once true})` are separate macro
    ;; expansions with distinct gensym'd trace-ids, so they don't
    ;; suppress each other.
    (util/-reset-once-state!)
    (let [captured (with-fresh-current-trace
                     (fn []
                       (dbg (+ 1 2) {:once true})
                       (dbg (+ 1 2) {:once true})))
          entries  (code-entries captured)]
      (is (= 2 (count entries))
          "each call site has its own trace-id, so each emits its own first time"))))

(deftest dbg-without-once-emits-twice
  (testing "without :once, two identical calls emit two trace entries (sanity)"
    (let [captured (with-fresh-current-trace
                     (fn []
                       (dbg (+ 1 2))
                       (dbg (+ 1 2))))]
      (is (= 2 (count (code-entries captured)))))))

;; The stub macroexpansion-shape test runs CLJ-only — `macroexpand-1`
;; is a JVM runtime fn (cljs.analyzer.api/macroexpand-1 only fires at
;; compile time). The behavioural side (the stub must compile to the
;; bare form, no tap>) is exercised on the CLJS side simply by
;; *requiring* day8.re-frame.tracing-stubs above: if the stub macros
;; ever started doing something at expansion time, the test ns
;; wouldn't compile.
#?(:clj
   (deftest dbg-stub-is-no-op
     (testing "the production stub returns the form's value with no tap> / send-trace!"
       (let [taps   (atom [])
             tapper #(swap! taps conj %)]
         (add-tap tapper)
         (try
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
             (remove-tap tapper)))))))

;; ---------------------------------------------------------------------------
;; dbg-last — thread-last-friendly variant
;; ---------------------------------------------------------------------------
;;
;; dbg-last expands to (dbg value opts) — args swapped — so dbg's
;; existing `'~form` capture sees the upstream thread chain. These
;; tests pin:
;;   - 1-arity bare form in ->> (no opts)
;;   - 2-arity with opts in ->>
;;   - value transparency through the pipeline
;;   - opts plumb-through (:name, :if, :tap?)
;;   - production stub is a value pass-through

(deftest dbg-last-bare-in-thread-last-emits-code-entry
  (testing "(->> ... dbg-last ...) emits one :code entry; the upstream chain is the captured :form"
    (let [captured (with-fresh-current-trace
                     (fn []
                       (->> [1 2 3]
                            (filter even?)
                            dbg-last
                            (map inc)
                            doall)))
          [e]      (code-entries captured)]
      (is (= '(filter even? [1 2 3]) (:form e))
          "dbg captures the upstream thread expression as :form")
      (is (= [2] (:result e))
          "the traced value is the seq produced by the upstream chain"))))

(deftest dbg-last-pipeline-result-unchanged
  (testing "dbg-last is value-transparent; the pipeline's final result matches a non-traced run"
    (with-fresh-current-trace
      (fn []
        (let [traced  (->> [1 2 3]
                           (filter even?)
                           dbg-last
                           (map inc))
              control (->> [1 2 3]
                           (filter even?)
                           (map inc))]
          (is (= [3] traced))
          (is (= control traced)))))))

(deftest dbg-last-disabled-evaluates-value-only
  (testing "trace-enabled? false leaves dbg-last as the bare threaded value"
    (let [opts-evals (atom 0)
          taps       (atom [])
          tapper     #(swap! taps conj %)]
      (add-tap tapper)
      (try
        (with-redefs [tracing/trace-enabled? false
                      rft/trace-enabled? true]
          (is (= [2 3] (->> [1 2]
                            (map inc)
                            (dbg-last (do
                                        (swap! opts-evals inc)
                                        {:tap? true}))
                            doall))))
        (wait-for-tap)
        (is (zero? @opts-evals)
            "dbg-last opts are not evaluated when tracing is disabled")
        (is (empty? @taps)
            "disabled dbg-last emits no tap output")
        (finally
          (remove-tap tapper))))))

(deftest dbg-last-with-opts-flows-name-into-payload
  (testing "(dbg-last opts) in ->> position — opts lead, threaded value trails — opts reach the payload"
    (let [captured (with-fresh-current-trace
                     (fn []
                       (->> [10 20]
                            (map inc)
                            (dbg-last {:name "after-inc"})
                            doall)))
          [e]      (code-entries captured)]
      (is (= "after-inc" (:name e)))
      (is (= [11 21] (:result e))))))

(deftest dbg-last-msg-flows-into-payload
  (testing "the :msg opt survives dbg-last's thread-last arg swap"
    (let [captured (with-fresh-current-trace
                     (fn []
                       (->> [1 2 3]
                            (filter odd?)
                            (dbg-last {:msg "after-filter"})
                            doall)))
          [e]      (code-entries captured)]
      (is (= "after-filter" (:msg e)))
      (is (= [1 3] (:result e))))))

(deftest dbg-last-locals-flow-into-payload
  (testing ":locals is forwarded through dbg-last to the emitted payload"
    (let [n        2
          captured (with-fresh-current-trace
                     (fn []
                       (->> [1 2 3]
                            (map #(* n %))
                            (dbg-last {:locals [['n n]]})
                            doall)))
          [e]      (code-entries captured)]
      (is (= [['n 2]] (:locals e)))
      (is (= [2 4 6] (:result e))))))

(deftest dbg-last-if-predicate-gates-emission
  (testing ":if pred — trace fires only when (pred result) is truthy"
    (let [captured (with-fresh-current-trace
                     (fn []
                       ;; non-empty seq → (seq res) truthy → emits
                       (->> [1 3 5]
                            (filter odd?)
                            (dbg-last {:if seq})
                            doall)
                       ;; empty seq → (seq res) nil → suppressed
                       (->> [1 3 5]
                            (filter even?)
                            (dbg-last {:if seq})
                            doall)))
          entries  (code-entries captured)]
      (is (= 1 (count entries))
          "non-empty seq emits, empty seq suppresses")
      (is (= [1 3 5] (:result (first entries)))))))

(deftest dbg-last-once-suppresses-second-identical-emit
  (testing ":once true dedups repeated identical results from the same dbg-last call site"
    (util/-reset-once-state!)
    (let [f        (fn []
                     (->> [1 2 3]
                          (filter odd?)
                          (dbg-last {:once true})
                          doall))
          captured (with-fresh-current-trace (fn [] (f) (f)))
          entries  (code-entries captured)]
      (is (= 1 (count entries))
          "only the first identical dbg-last invocation emits")
      (is (= [1 3] (:result (first entries)))))))

(deftest dbg-last-out-of-trace-falls-back-to-tap
  (testing "outside any re-frame trace event, dbg-last taps the threaded value with :debux/dbg true"
    (with-sync-taps
      (fn []
        (let [taps   (atom [])
              tapper #(swap! taps conj %)]
          (add-tap tapper)
          (try
            (with-tracing-enabled
              (fn []
                (let [r (->> [1 2 3]
                             (map inc)
                             dbg-last
                             (reduce +))]
                  (is (= 9 r)
                      "value-transparent out of trace; pipeline yields (+ 2 3 4) = 9"))
                (wait-for-tap)
                (is (= 1 (count @taps)))
                (let [t (first @taps)]
                  (is (= [2 3 4] (:result t)))
                  (is (= '(map inc [1 2 3]) (:form t)))
                  (is (= true (:debux/dbg t))))))
            (finally
              (remove-tap tapper))))))))

;; CLJ-only for the same reason as dbg-stub-is-no-op above.
#?(:clj
   (deftest dbg-last-stub-is-no-op
     (testing "the production stub returns the threaded value with no trace side effect"
       (let [taps   (atom [])
             tapper #(swap! taps conj %)]
         (add-tap tapper)
         (try
           (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg-last (+ 2 3)))]
             (is (= '(+ 2 3) r)
                 "1-arity stub macroexpands to bare value"))
           (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg-last {:name "x"} (+ 2 3)))]
             (is (= '(+ 2 3) r)
                 "2-arity stub also macroexpands to the bare value, opts dropped"))
           (Thread/sleep 50)
           (is (empty? @taps)
               "no taps — stubs don't fire any sink")
           (finally
             (remove-tap tapper)))))))
