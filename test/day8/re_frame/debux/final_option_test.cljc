(ns day8.re-frame.debux.final-option-test
  "Tests for the `:final` / `:f` option on `dbgn` / `fn-traced` /
   `defn-traced` / `fx-traced` / `defn-fx-traced`.

   Semantics: when `:final true` is in the opts, emit-trace-body
   suppresses every per-form :code emission EXCEPT the outermost
   (indent-level 0). Each top-level wrapping form's final value
   makes it through; intermediate sub-forms do not.

   Two test surfaces:
     - dbgn-direct: drive the macro at the unit level via
       `with-unit-capture`, which redefs send-trace! to a local atom.
     - fn-traced via re-frame.dispatch: end-to-end through the
       re-frame trace pipeline via `with-trace-capture` — same shape
       as integration_test.clj.

   Composition with :if and :once is also covered: :final filters
   first, then the survivors run through :if and :once.

   Macro signatures: the inner `day8.re-frame.debux.dbgn/dbgn` takes
   `[form & [opts-map]]` — opts is a single map. The outer
   `day8.re-frame.tracing/dbgn` takes `[form & opts]` and runs
   parse-opts over the rest, so callers can write keyword-style
   `(tracing/dbgn form :final)` or `(tracing/dbgn form :f)`. Both
   paths are covered.

   Note on fixtures: `use-fixtures :each` is intentionally NOT used
   here. The unit-level capture rebinds `send-trace!` away from the
   re-frame trace stream, which would silence the integration tests.
   Each test sets up its own capture explicitly via the helpers
   below."
  ;; `day8.re-frame.debux.dbgn` is `.clj`-only — see if_option_test.cljc.
  #?(:clj  (:require [day8.re-frame.debux.dbgn :refer [dbgn]])
     :cljs (:require-macros [day8.re-frame.debux.dbgn :refer [dbgn]]))
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.debux.common.util :as util]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime]
            [re-frame.core]
            [re-frame.trace :as rft]))

;; ---------------------------------------------------------------------------
;; Unit-level helper — redirect send-trace! into a local atom so a
;; macroexpansion's runtime emissions are inspectable without going
;; through the re-frame trace machinery.
;; ---------------------------------------------------------------------------

(defn- with-unit-capture
  "Run `body-fn` with `send-trace!` redirected to a local atom; return
   the captured vec of trace entries (post `:form` tidy)."
  [body-fn]
  (let [traces (atom [])]
    (with-redefs [tracing/trace-enabled? true
                  util/send-trace! (fn [code-trace]
                                     (swap! traces conj
                                            (update code-trace :form
                                                    util/tidy-macroexpanded-form {})))
                  util/send-form!  (fn [_])]
      (body-fn))
    @traces))

;; ---------------------------------------------------------------------------
;; dbgn (inner) — :final via opts map produces a single outer trace,
;; suppresses intermediates.
;; ---------------------------------------------------------------------------

(deftest dbgn-final-suppresses-intermediates
  (testing ":final true emits only the outermost form's final value (single trace at indent 0)"
    (let [r       (atom nil)
          traces  (with-unit-capture
                    (fn []
                      (reset! r (dbgn (-> 1 inc inc) {:final true}))))]
      (is (= 3 @r) "value transparency — :final does not change the result")
      (is (= 1 (count traces))
          "exactly one :code entry — every intermediate trace was suppressed")
      (let [e (first traces)]
        (is (zero? (:indent-level e))
            "the surviving entry is the outermost form (indent 0)")
        (is (= 3 (:result e))
            "the surviving entry's :result is the final value (3)")))))

(deftest dbgn-without-final-emits-intermediates
  (testing "without :final, the same form emits multiple :code entries (sanity baseline)"
    (let [traces (with-unit-capture
                   (fn []
                     (dbgn (-> 1 inc inc))))]
      (is (< 1 (count traces))
          "the default mode emits more than one entry — :final is what suppresses them")
      (is (some zero? (map :indent-level traces))
          "the outermost form is among the emitted entries")
      (is (some pos? (map :indent-level traces))
          "at least one intermediate entry (indent>0) is among the emitted entries"))))

(deftest dbgn-final-on-thread-last-pipeline
  (testing ":final on a ->> pipeline emits only the outer ->> result"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (dbgn (->> [1 2 3 4 5]
                                          (filter odd?)
                                          (map inc)
                                          (reduce +))
                                     {:final true}))))]
      ;; (filter odd? [1 2 3 4 5]) → (1 3 5)
      ;; (map inc (1 3 5))         → (2 4 6)
      ;; (reduce + (2 4 6))        → 12
      (is (= 12 @r)
          "value transparency — sum of (2 4 6) is 12")
      (is (= 1 (count traces))
          "every per-step trace is suppressed; only the outer ->> result is emitted")
      (is (zero? (:indent-level (first traces)))
          "the surviving entry is at indent 0")
      (is (= 12 (:result (first traces)))
          "the surviving entry holds the final reduce result"))))

(deftest dbgn-final-composes-with-if
  (testing ":final still respects :if — outermost form is gated by the predicate"
    ;; (inc (inc 0)) → 2. With :final, only the outer (inc (inc 0))
    ;; entry is a candidate. With :if even?, that entry's result (2)
    ;; is even, so it emits. With :if odd?, it'd be filtered.
    (let [traces-even (with-unit-capture
                        (fn [] (dbgn (inc (inc 0)) {:final true :if even?})))
          traces-odd  (with-unit-capture
                        (fn [] (dbgn (inc (inc 0)) {:final true :if odd?})))]
      (is (= 1 (count traces-even))
          ":final + :if even? — the outermost result (2) is even, so it emits")
      (is (zero? (count traces-odd))
          ":final + :if odd? — :final lets the outermost through, :if odd? then filters it"))))

;; ---------------------------------------------------------------------------
;; tracing/dbgn (outer, parse-opts path) — keyword-style :final and :f
;; ---------------------------------------------------------------------------

(deftest tracing-dbgn-keyword-final-via-parse-opts
  (testing "keyword-style `(tracing/dbgn form :final)` parses through parse-opts to {:final true}"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn [] (reset! r (tracing/dbgn (-> 1 inc inc) :final))))]
      (is (= 3 @r))
      (is (= 1 (count traces))
          "parse-opts mapped :final → :final true; emit-trace-body gated intermediates"))))

(deftest tracing-dbgn-f-alias-via-parse-opts
  (testing ":f is the shorthand alias for :final through parse-opts"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn [] (reset! r (tracing/dbgn (-> 1 inc inc) :f))))]
      (is (= 3 @r))
      (is (= 1 (count traces))
          ":f gates emission identically to :final"))))

;; ---------------------------------------------------------------------------
;; fn-traced — :final on a re-frame handler suppresses intermediate :code
;; entries through the full dispatch pipeline.
;; ---------------------------------------------------------------------------

(defn- with-trace-capture
  [f]
  (with-redefs [rft/trace-enabled?     true
                tracing/trace-enabled? true
                rft/schedule-debounce  (fn [] nil)]
    (reset! rft/traces [])
    (reset! rft/next-delivery 0)
    (reset! runtime/wrapped-originals {})
    (util/-reset-once-state!)
    (try
      (f)
      (finally
        (runtime/unwrap-all!)))))

(defn- captured-traces [] @rft/traces)

(defn- code-entries [captured]
  (->> captured (mapcat (comp :code :tags)) vec))

(deftest fn-traced-final-suppresses-intermediates
  (with-trace-capture
    (fn []
      (testing ":final true on fn-traced suppresses every :code entry except the outermost"
        (re-frame.core/reg-event-db ::final-handler (fn [db _] db))
        (re-frame.core/reg-event-db ::final-handler
                                    (tracing/fn-traced
                                      {:final true}
                                      [db _]
                                      (->> (range 5)
                                           (filter odd?)
                                           (map inc)
                                           (reduce +))))
        (re-frame.core/dispatch-sync [::final-handler])
        (let [code (code-entries (captured-traces))]
          (is (seq code)
              "at least one :code entry made it through")
          (is (every? zero? (map :indent-level code))
              "every surviving :code entry is at indent 0 — intermediates suppressed")
          ;; (range 5) → (0 1 2 3 4); (filter odd?) → (1 3); (map inc) → (2 4); (reduce +) → 6
          (is (some #(= 6 (:result %)) code)
              "the outer ->> reduce result (6) is among the surviving entries"))))))

(deftest fn-traced-without-final-emits-intermediates
  (with-trace-capture
    (fn []
      (testing "without :final, the same body emits intermediate :code entries (sanity baseline)"
        (re-frame.core/reg-event-db ::no-final-handler (fn [db _] db))
        (re-frame.core/reg-event-db ::no-final-handler
                                    (tracing/fn-traced
                                      [db _]
                                      (->> (range 5)
                                           (filter odd?)
                                           (map inc)
                                           (reduce +))))
        (re-frame.core/dispatch-sync [::no-final-handler])
        (let [code (code-entries (captured-traces))]
          (is (some pos? (map :indent-level code))
              "at least one intermediate (indent>0) entry — :final is what gates them"))))))

;; ---------------------------------------------------------------------------
;; fx-traced — :final on a reg-event-fx handler suppresses intermediate
;; :code entries through the full dispatch pipeline. fx-traced shares
;; fn-body with fn-traced and only flips :fx-trace, so every fn-traced
;; opt should reach the fx-traced :code path; this deftest pins the
;; :final case alongside its fn-traced sibling above.
;; ---------------------------------------------------------------------------

(deftest fx-traced-final-suppresses-intermediates
  (with-trace-capture
    (fn []
      (testing ":final true on fx-traced suppresses every :code entry except the outermost"
        (re-frame.core/reg-event-fx ::fx-final-handler (fn [_ _] {}))
        (re-frame.core/reg-event-fx ::fx-final-handler
                                    (tracing/fx-traced
                                      {:final true}
                                      [_ _]
                                      (let [v (->> (range 5)
                                                   (filter odd?)
                                                   (map inc)
                                                   (reduce +))]
                                        {:db {:v v}})))
        (re-frame.core/dispatch-sync [::fx-final-handler])
        (let [code (code-entries (captured-traces))]
          (is (seq code)
              "at least one :code entry made it through fx-traced + :final")
          (is (every? zero? (map :indent-level code))
              "every surviving :code entry is at indent 0 — intermediates suppressed")
          (is (some #(= {:db {:v 6}} (:result %)) code)
              "the outer body result (effect map with v=6) is among the surviving entries"))))))
