(ns day8.re-frame.integration-test
  "End-to-end integration tests that exercise the FULL re-frame
   dispatch → fn-traced → :code tag → epoch-buffer pipeline. Per
   docs/improvement-plan.md §2 / §6 item 9, this is the load-bearing
   test gap: the existing dbgn_test.clj exercises macroexpansion and
   the trace-emit calls in isolation, but no test today verifies that
   a real re-frame dispatch through a `fn-traced`-wrapped handler
   produces a `:code` payload that downstream consumers (re-frame-10x's
   panel, re-frame-pair's `:debux/code` surfacing) actually see.

   Three macro-walker bugs (#22, #23, #29) shipped to master without
   warning because of this gap; #40 is the latest. Closing the gap
   pays dividends on every future commit.

   STATUS: scaffold only.
   The fixture + the wrap/dispatch happy path are sketched below as
   commented deftests; making them run requires:

     1. A test runner that boots a re-frame.trace + 10x context.
        cloud test setup likely reuses the existing `:karma-test`
        shadow-cljs build (project.clj:78). karma renders a Reagent
        view, so re-frame.core can fire dispatches; ensure
        re-frame.trace.trace-enabled? = true via :closure-defines.

     2. A way to consume the trace stream from the test side.
        Either: register-trace-cb to capture emitted traces into
        an atom (clean), or read from a 10x-style epoch buffer
        if 10x is loaded as a test dep (heavier).

     3. A live re-frame.registrar with a known dispatcher; rebuild
        clean state per test (re-frame.core/make-restore-fn).

   See PARTIAL_IMPLEMENTATION_NOTES at the bottom for what each
   deftest needs once the runner is up. Tracking under follow-up
   bead — see docs/v0.6-roadmap.md §End-to-end integration test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime]
            [re-frame.registrar :as registrar]))

;; ---------------------------------------------------------------------------
;; Test infrastructure (placeholder — fill in once a karma / browser-
;; test runner can host re-frame.trace + a registered trace-cb).
;; ---------------------------------------------------------------------------

(def ^:dynamic *captured-traces*
  "Per-test capture atom for whatever the trace-cb emits. Bound by
   the each-fixture once the runner is in place."
  nil)

(defn- with-trace-capture
  "Stub: in the real implementation this would
     (re-frame.trace/register-trace-cb ::test-cb (fn [traces] ...))
   and tear down via remove-trace-cb on exit. For now it just resets
   the runtime's wrap state so tests don't bleed into each other."
  [f]
  (binding [*captured-traces* (atom [])]
    (reset! runtime/wrapped-originals {})
    (try
      (f)
      (finally
        (runtime/unwrap-all!)))))

(use-fixtures :each with-trace-capture)

;; ---------------------------------------------------------------------------
;; The deftests below are intentionally `^:integration` and `^:pending`
;; so they don't fire in the default `lein test` run while the runner
;; isn't ready. Flip the meta to nothing once the karma fixture below
;; can register a trace-cb and consume emitted traces.
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:pending wrap-handler-emits-code-tag-on-dispatch
  ;; Acceptance: register a handler, wrap-handler! it, dispatch,
  ;; assert the captured trace stream contains a :code tag whose
  ;; entries match the wrapped body's per-form payload.
  (testing "fn-traced handler produces :code payload reachable from
            the trace stream"
    ;; (re-frame.core/reg-event-db :test/inc (fn [db _] (update db :n inc)))
    ;; (runtime/wrap-handler! :event :test/inc (fn [db _] (update db :n inc)))
    ;; (re-frame.core/dispatch-sync [:test/inc])
    ;; (let [code-entries (->> @*captured-traces*
    ;;                         (mapcat (comp :code :tags))
    ;;                         vec)]
    ;;   (is (seq code-entries))
    ;;   (is (some #(re-find #"update" (str (:form %))) code-entries)))
    (is true "scaffold only; flip ^:pending off once the runner is wired up")))

(deftest ^:integration ^:pending unwrap-stops-code-emission
  (testing "after unwrap-handler!, dispatching the same event produces
            no :code tag"
    ;; Same setup as above, but call unwrap-handler! after the first
    ;; dispatch and reset *captured-traces*; second dispatch should
    ;; carry no :code entries.
    (is true "scaffold only")))

(deftest ^:integration ^:pending fn-traced-survives-loop-recur
  ;; Regression for #40 at the integration level — even after the
  ;; cs/macro_types.cljc fix from rfd-t0t db9b7de, we want a real
  ;; dispatch through a loop+recur-using handler to confirm
  ;; macroexpansion → eval → trace pipeline doesn't re-introduce
  ;; the hang when the runner ships.
  (testing "loop+recur in a fn-traced body executes and emits traces"
    (is true "scaffold only")))

(deftest ^:integration ^:pending wrap-fx-traces-effect-payload
  ;; Acceptance for the dbgn.clj:341 'trace inside maps' TODO: a
  ;; reg-fx wrapped via wrap-fx! traces the value-side of an effect
  ;; map without modifying the zipper walker.
  (testing "wrap-fx! emits :code entries when the fx fires"
    ;; (re-frame.core/reg-fx :test/log (fn [v] (println v)))
    ;; (runtime/wrap-fx! :test/log (fn [v] (let [s (str "got:" v)]
    ;;                                       (js/console.log s))))
    ;; (re-frame.core/reg-event-fx :test/trigger
    ;;                              (fn [_ [_ v]] {:test/log v}))
    ;; (re-frame.core/dispatch-sync [:test/trigger 42])
    (is true "scaffold only")))

;; ---------------------------------------------------------------------------
;; PARTIAL_IMPLEMENTATION_NOTES
;; ---------------------------------------------------------------------------
;;
;; To make the deftests above run, the next session needs:
;;
;; 1. A trace-cb capture wrapper that replaces the placeholder above:
;;
;;      (defn- with-trace-capture [f]
;;        (binding [*captured-traces* (atom [])]
;;          (re-frame.trace/register-trace-cb
;;            ::integration-test
;;            (fn [traces] (swap! *captured-traces* into traces)))
;;          (reset! runtime/wrapped-originals {})
;;          (try
;;            (f)
;;            (finally
;;              (runtime/unwrap-all!)
;;              (re-frame.trace/remove-trace-cb ::integration-test)))))
;;
;;    re-frame.trace's API is sufficient — no need to bring in 10x
;;    just for the test runner. The trace-cb fires after a debounce
;;    so tests should give it a beat (use a deferred / promise
;;    pattern, or call re-frame.trace/flush-trace! explicitly if
;;    that exists).
;;
;; 2. shadow-cljs runtime-test or karma-test build needs:
;;      :compiler-options {:closure-defines
;;                         {re-frame.trace.trace-enabled? true
;;                          day8.re-frame.tracing.trace-enabled? true
;;                          goog.DEBUG true}}
;;    Already set for the existing :runtime-test build (project.clj:78).
;;
;; 3. cljs.test convention: the deftests' ^:pending meta should
;;    correspond to a custom :test-selector in project.clj if we want
;;    to run them with `lein test :integration`. Add:
;;      :test-selectors {:integration :integration ...}
;;
;; 4. The fn-traced expansions need re-frame.registrar/register-handler
;;    AND re-frame.core/reg-event-db / reg-sub / reg-fx to be reachable
;;    from a CLJS test runtime, which they already are if re-frame is
;;    on the test classpath.
;;
;; Once those four are in place, populate the (commented out) test
;; bodies in each ^:pending deftest, flip the meta off, and verify
;; via `lein test :integration` (or `lein with-profile dev karma test`).
