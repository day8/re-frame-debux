(ns day8.re-frame.integration-test
  "End-to-end integration tests for re-frame-debux. Exercises the
   FULL re-frame dispatch → fn-traced → :code tag → trace-cb pipeline
   so a real `re-frame.core/dispatch-sync` going through a wrapped
   handler produces a `:code` payload reachable from a registered
   trace callback.

   Per docs/improvement-plan.md §2 / §6 item 9 (rfd-8g9), this fixture
   is the load-bearing test gap — pre-rfd-880 the macroexpansion
   tests were thorough but no test exercised the full dispatch
   pipeline. Three closed macro-walker bugs (#22, #23, #29) shipped
   to master without warning because of this gap; #40 was the latest.
   Closing the gap pays dividends on every future commit.

   Runs CLJ-side under cognitect.test-runner via `bb test`. CLJ
   re-frame ships with a `re-frame.interop/clj` shim that supports
   `dispatch-sync` end-to-end, so no Chrome / browser-test runner is
   needed for these tests — they live alongside the macroexpansion
   suite, not in the (separately-gated) browser-test build."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime]
            ;; The wrap-* macros expand to (re-frame.core/reg-* ...) forms,
            ;; so the test ns has to require re-frame.core (and re-frame.trace
            ;; for our trace-capture fixture).
            [re-frame.core]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as rft]))

;; ---------------------------------------------------------------------------
;; Trace-capture fixture
;; ---------------------------------------------------------------------------
;;
;; re-frame.trace gates trace recording on its (atom-backed)
;; `trace-enabled?` flag. In CLJ that defaults to false (CLJS uses
;; goog-define). We force it on for each deftest.
;;
;; The cb-delivery path has a quirk on CLJ: re-frame.trace/debounce's
;; :clj branch is `(f)` (immediate invocation) rather than a wrapping
;; fn, so the `(def schedule-debounce (debounce ...))` form ends up
;; binding `schedule-debounce` to the *return value* of the inner
;; cb-runner — a vector returned by `(reset! traces [])` — not a fn.
;; `run-tracing-callbacks!` then calls `(schedule-debounce)` and
;; throws ArityException at every dispatch.
;;
;; We sidestep the broken delivery path by reading from the public
;; `re-frame.trace/traces` atom directly. The `finish-trace` macro
;; swap!s each trace into that atom synchronously inside `with-trace`,
;; so by the time `dispatch-sync` returns, every trace produced by
;; the wrapped handler is in `@traces`. We also stub
;; `schedule-debounce` to a callable no-op so the cb path doesn't
;; throw partway through dispatch.

(defn- captured-traces
  "Snapshot of the re-frame.trace stream so far in the current test.
   The fixture resets `re-frame.trace/traces` to `[]` before each
   deftest, so this is per-test."
  []
  @rft/traces)

(defn- with-trace-capture
  [f]
  (with-redefs [rft/trace-enabled?     true
                tracing/trace-enabled? true
                ;; Stub the broken-on-CLJ cb scheduler so
                ;; run-tracing-callbacks! doesn't throw.
                rft/schedule-debounce  (fn [] nil)]
    (reset! rft/traces [])
    (reset! rft/next-delivery 0)
    (reset! runtime/wrapped-originals {})
    (try
      (f)
      (finally
        (runtime/unwrap-all!)))))

(use-fixtures :each with-trace-capture)

;; ---------------------------------------------------------------------------
;; Helpers — the pipeline produces nested trace-and-tags shapes; pull
;; out the `:code` entries irrespective of which trace they rode on.
;; ---------------------------------------------------------------------------

(defn- code-entries
  "Flatten the per-test capture stream into a vec of :code entries
   regardless of which trace carried them. Each :code entry is a
   {:form :result :indent-level :syntax-order :num-seen} map per the
   contract documented in src/.../common/util.cljc above send-trace!."
  [captured]
  (->> captured
       (mapcat (comp :code :tags))
       vec))

(defn- forms
  "Vec of (pr-str-ed) :form values across all captured :code entries.
   Useful for `(some #(re-find #\"...\" %) (forms ...))`."
  [captured]
  (mapv #(pr-str (:form %)) (code-entries captured)))

;; ---------------------------------------------------------------------------
;; Acceptance tests for rfd-8g9 item 7 — wrap-handler! / unwrap-handler!
;; (the wrap-and-emit-:code-on-dispatch contract).
;; ---------------------------------------------------------------------------

(deftest wrap-handler-emits-code-tag-on-dispatch
  (testing "fn-traced handler produces :code payload reachable from the trace stream"
    (re-frame.core/reg-event-db ::root (fn [db _] db))
    (runtime/wrap-handler! :event ::root
                           (fn [db _] (let [n (inc (or (:n db) 0))]
                                        (assoc db :n n))))
    (re-frame.core/dispatch-sync [::root])
    (let [code (code-entries (captured-traces))
          fs   (forms (captured-traces))]
      (is (seq code)
          "wrapped handler should have emitted at least one :code entry")
      (is (some #(re-find #"inc" %) fs)
          "captured forms should include the body's inc call")
      (is (some #(re-find #"assoc" %) fs)
          "captured forms should include the assoc")
      (runtime/unwrap-handler! :event ::root))))

(deftest unwrap-stops-code-emission
  (testing "after unwrap-handler!, dispatching the same event produces no :code tag"
    (re-frame.core/reg-event-db ::quiet (fn [db _] (assoc db :touched? true)))
    (runtime/wrap-handler! :event ::quiet
                           (fn [db _] (let [r (assoc db :touched? true)] r)))
    (re-frame.core/dispatch-sync [::quiet])
    (is (seq (code-entries (captured-traces)))
        "wrapped dispatch produced :code entries (sanity)")
    (reset! rft/traces [])
    (runtime/unwrap-handler! :event ::quiet)
    (re-frame.core/dispatch-sync [::quiet])
    (is (empty? (code-entries (captured-traces)))
        "after unwrap, no :code entries land — fn-traced is gone")))

;; ---------------------------------------------------------------------------
;; rfd-t0t #40 regression at the integration level — complement the
;; unit-level loop+recur deftests in regression_test.clj.
;; ---------------------------------------------------------------------------

(deftest fn-traced-survives-loop-recur
  (testing "loop+recur in a fn-traced body executes and emits traces"
    (re-frame.core/reg-event-db ::looped (fn [db _] db))
    (runtime/wrap-handler! :event ::looped
                           (fn [db _]
                             (loop [n 0 acc 0]
                               (if (< n 3)
                                 (recur (inc n) (+ acc n))
                                 (assoc db :sum acc)))))
    (re-frame.core/dispatch-sync [::looped])
    (is (seq (code-entries (captured-traces)))
        "fn-traced + loop+recur produces trace entries (was diverging at macroexpansion before rfd-d00 48de2e8)")
    (runtime/unwrap-handler! :event ::looped)))

;; ---------------------------------------------------------------------------
;; rfd-8g9 item 7 — wrap-fx! traces effect-payload sub-forms (the
;; dbgn.clj:341 'trace inside maps, especially for fx' TODO).
;; ---------------------------------------------------------------------------

(deftest wrap-fx-traces-effect-payload
  (testing "wrap-fx! emits :code entries when the fx fires"
    (let [side-effect (atom nil)]
      (re-frame.core/reg-fx ::log (fn [v] (reset! side-effect v)))
      (re-frame.core/reg-event-fx ::trigger
                                  (fn [_ [_ v]] {::log v}))
      (runtime/wrap-fx! ::log (fn [v] (let [s (str "got:" v)] (reset! side-effect s))))
      (re-frame.core/dispatch-sync [::trigger 42])
      (is (= "got:42" @side-effect)
          "wrapped fx ran and produced its side effect")
      (is (seq (code-entries (captured-traces)))
          "wrapped fx fired send-trace! at least once")
      (is (some #(re-find #"got:" %) (forms (captured-traces)))
          "captured trace forms include the wrapped body's str call")
      (runtime/unwrap-fx! ::log))))

;; ---------------------------------------------------------------------------
;; rfd-880 item 6 — :locals and :if options for fn-traced
;; ---------------------------------------------------------------------------

(deftest fn-traced-locals-option-captures-args
  (testing ":locals true attaches captured arg pairs to each :code entry"
    (re-frame.core/reg-event-db ::with-locals (fn [db _] db))
    (re-frame.core/reg-event-db ::with-locals
                                (tracing/fn-traced
                                  {:locals true}
                                  [db [_ x]]
                                  (let [n (inc x)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::with-locals 7])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "at least one :code entry was emitted")
      (is (every? :locals code)
          "every :code entry carries a :locals key when :locals true")
      (let [a-locals (-> code first :locals)]
        (is (vector? a-locals)
            ":locals is a vec of pairs")
        ;; The destructured handler args [db [_ x]] surface as
        ;; symbols db, _, x — find-symbols flattens through the
        ;; destructure form.
        (is (some #(= 'x (first %)) a-locals)
            ":locals includes the bound x arg")
        (is (some #(= 7 (second %)) a-locals)
            ":locals binds x to its runtime value (7)")))))

(deftest fn-traced-locals-omitted-when-not-requested
  (testing "no :locals key on entries when opts is absent / false"
    (re-frame.core/reg-event-db ::no-locals (fn [db _] db))
    (re-frame.core/reg-event-db ::no-locals
                                (tracing/fn-traced
                                  [db _]
                                  (let [n (inc (:n db 0))]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::no-locals])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "default :code emission still works without opts")
      (is (every? #(not (contains? % :locals)) code)
          "no :locals key on any entry — opt-in only"))))

(deftest fn-traced-if-option-gates-emission
  (testing ":if (constantly false) suppresses every :code entry"
    (re-frame.core/reg-event-db ::quiet-if (fn [db _] db))
    (re-frame.core/reg-event-db ::quiet-if
                                (tracing/fn-traced
                                  {:if (constantly false)}
                                  [db _]
                                  (let [n (inc (:n db 0))]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::quiet-if])
    (is (empty? (code-entries (captured-traces)))
        ":if predicate returned false → send-trace! gated for every form")))

(deftest fn-traced-if-option-passes-result-to-pred
  (testing ":if pred receives the per-form result; truthy lets the trace through"
    (re-frame.core/reg-event-db ::number-if (fn [db _] db))
    ;; Predicate fires on numeric results only; let-bound string
    ;; result and the final assoc map both should be filtered out.
    (re-frame.core/reg-event-db ::number-if
                                (tracing/fn-traced
                                  {:if number?}
                                  [db _]
                                  (let [n (inc 41)]
                                    (assoc db :s (str n)))))
    (re-frame.core/dispatch-sync [::number-if])
    (let [code (code-entries (captured-traces))]
      (is (every? #(number? (:result %)) code)
          "every kept :code entry has a number result; non-numeric results were filtered")
      (is (some #(= 42 (:result %)) code)
          "the (inc 41) → 42 entry made it through the :if filter"))))
