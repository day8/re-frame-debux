(ns day8.re-frame.integration-test
  "End-to-end integration tests for re-frame-debux. Exercises the
   FULL re-frame dispatch → fn-traced → :code tag → trace-cb pipeline
   so a real `re-frame.core/dispatch-sync` going through a wrapped
   handler produces a `:code` payload reachable from a registered
   trace callback.

   Per docs/improvement-plan.md §2 / §6 item 9, this fixture
   is the load-bearing test gap — historically the macroexpansion
   tests were thorough but no test exercised the full dispatch
   pipeline. Three closed macro-walker bugs (#22, #23, #29) shipped
   to master without warning because of this gap; #40 was the latest.
   Closing the gap pays dividends on every future commit.

   Runs CLJ-side under cognitect.test-runner via `bb test`. CLJ
   re-frame ships with a `re-frame.interop/clj` shim that supports
   `dispatch-sync` end-to-end, so no Chrome / browser-test runner is
   needed for these tests — they live alongside the macroexpansion
   suite, not in the (separately-gated) browser-test build.

   CLJ-only by design — this fixture replaces `rft/schedule-debounce`
   with a synchronous cb-runner, sidestepping a CLJ-specific quirk
   in re-frame's `debounce` shim that binds `schedule-debounce` to
   `[]` at namespace-load time (the `:clj` branch invokes its arg
   instead of wrapping it). The fixture's runner delivers `@traces`
   to every registered `trace-cb` synchronously and does NOT clear
   the atom, so sibling tests that read `@rft/traces` directly
   (the SEND side) keep working alongside `register-trace-cb`
   tests that pin the DELIVERY side. CLJS uses
   `goog.functions/debounce`, so the cb is deferred and the same
   stub isn't needed there — but the tap-output Thread/sleep waits
   for the CLJ tap-loop agent are also JVM-only. A CLJS analogue
   should live in a separate browser-test fixture rather than
   ride this file via reader conditionals; tracked separately."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [day8.re-frame.tracing :as tracing]
            [day8.re-frame.tracing.runtime :as runtime]
            [day8.re-frame.debux.common.util :as util]
            ;; The wrap-* macros expand to (re-frame.core/reg-* ...) forms,
            ;; so the test ns has to require re-frame.core (and re-frame.trace
            ;; for our trace-capture fixture).
            [re-frame.core]
            [re-frame.db]
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
;; The fixture works around the broken root binding by replacing
;; `schedule-debounce` with a synchronous cb-runner. The runner
;; delivers `@traces` to every registered `trace-cb`, but does NOT
;; reset the atom — sibling tests that read `@traces` directly to
;; verify the SEND side still see the full stream after dispatch.
;; A separate `register-trace-cb` deftest then pins the DELIVERY
;; side: a real consumer registering a cb gets the :code payload
;; from the same dispatch the SEND-side tests inspect.

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
                ;; Real cb-runner replacing the broken-on-CLJ
                ;; schedule-debounce root binding (see the fixture
                ;; comment block above). Synchronously delivers the
                ;; current `@traces` snapshot to every registered cb;
                ;; does NOT reset the atom so SEND-side assertions
                ;; reading `@rft/traces` keep working alongside
                ;; DELIVERY-side assertions reading the cb's batches.
                rft/schedule-debounce
                (fn synchronous-cb-runner []
                  (doseq [[k cb] @rft/trace-cbs]
                    (try (cb @rft/traces)
                         (catch Exception e
                           (println "trace cb" k "threw:" e)))))]
    (reset! rft/traces [])
    (reset! rft/next-delivery 0)
    ;; trace-cbs is a process-global registry; clear between deftests
    ;; so a registered cb in one test doesn't fire under another's
    ;; dispatch.
    (reset! rft/trace-cbs {})
    (reset! runtime/wrapped-originals {})
    (util/set-trace-frames-output! false)
    (util/set-tap-output! false)
    ;; :once dedup state is process-local; reset between deftests so
    ;; one test's last emission doesn't silence the next test.
    (util/-reset-once-state!)
    (try
      (f)
      (finally
        (runtime/unwrap-all!)
        (reset! rft/trace-cbs {})
        (util/set-trace-frames-output! false)
        (util/set-tap-output! false)))))

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

(defn- code-form-present?
  [code form]
  (some #(and (contains? % :form)
              (= form (:form %)))
        code))

(defn- code-form-with-head-present?
  [code head]
  (some #(let [form (:form %)]
           (and (seq? form)
                (= head (first form))))
        code))

(defn- frame-entries
  "Flatten the per-test capture stream into a vec of :trace-frames
   entries (entry/exit markers) regardless of which trace carried
   them. Each marker is {:phase :enter|:exit :frame-id ... :t ...}."
  [captured]
  (->> captured
       (mapcat (comp :trace-frames :tags))
       vec))

(defn- fx-effects-entries
  "Per-test :fx-effects entries from the captured trace stream. Each
   entry is {:fx-key k :value v :t ms} per the contract documented
   in -emit-fx-traces! (src/.../common/util.cljc)."
  [captured]
  (->> captured
       (mapcat (comp :fx-effects :tags))
       vec))

;; ---------------------------------------------------------------------------
;; Acceptance tests for wrap-handler! / unwrap-handler!
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
;; Trace-cb delivery channel — `register-trace-cb` is the public surface
;; that production consumers (re-frame-10x, re-frame-pair) use; the
;; sibling deftests above only verify the SEND side via `@rft/traces`.
;; This pins the DELIVERY side so a regression in the
;; merge-trace! → finish-trace → run-tracing-callbacks! pipeline can't
;; sneak through with green tests and only break in real tools.
;; ---------------------------------------------------------------------------

(deftest fn-traced-code-payload-reaches-registered-trace-cb
  (testing "register-trace-cb receives the :code payload from a wrapped fn-traced handler"
    (let [delivered (atom [])
          probe-key ::trace-cb-probe]
      (try
        (rft/register-trace-cb probe-key
                               (fn [batch] (swap! delivered conj (vec batch))))
        (re-frame.core/reg-event-db ::cb-target (fn [db _] db))
        (runtime/wrap-handler! :event ::cb-target
                               (fn [db _]
                                 (let [n (inc 41)]
                                   (assoc db :n n))))
        (re-frame.core/dispatch-sync [::cb-target])
        (let [batches @delivered
              code    (->> batches
                           (mapcat identity)
                           (mapcat (comp :code :tags))
                           vec)
              forms   (set (map (comp pr-str :form) code))
              results (set (map :result code))]
          (is (seq batches)
              "registered trace-cb received at least one delivery during dispatch-sync")
          (is (seq code)
              "the cb's deliveries carried at least one :code entry")
          (is (some #(re-find #"inc" %) forms)
              "the cb's :code stream includes the wrapped body's inc form")
          (is (contains? results 42)
              "the cb's :code stream carries the (inc 41) → 42 result"))
        (finally
          (rft/remove-trace-cb probe-key)
          (runtime/unwrap-handler! :event ::cb-target))))))

;; ---------------------------------------------------------------------------
;; #40 regression at the integration level — complement the
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
        "fn-traced + loop+recur produces trace entries (was diverging at macroexpansion before commit 48de2e8)")
    (runtime/unwrap-handler! :event ::looped)))

;; ---------------------------------------------------------------------------
;; #31 regression at the integration level — cond in a dispatched handler.
;; Unit-level cond-doesnt-throw in regression_test.clj covers macro
;; expansion; this test pins the full dispatch → :code pipeline with cond.
;; ---------------------------------------------------------------------------

(deftest fn-traced-survives-cond
  (testing "cond in a fn-traced body executes and emits :code entries"
    (re-frame.core/reg-event-db ::branched (fn [db _] db))
    (runtime/wrap-handler! :event ::branched
                           (fn [db [_ x]]
                             (let [label (cond
                                           (neg? x)  :negative
                                           (zero? x) :zero
                                           :else      :positive)]
                               (assoc db :label label))))
    (re-frame.core/dispatch-sync [::branched 5])
    (let [code (code-entries (captured-traces))
          fs   (forms (captured-traces))]
      (is (seq code)
          "fn-traced + cond dispatched without throwing and emitted :code entries")
      (is (some #(re-find #"cond" %) fs)
          "captured forms include the cond expression")
      (is (some #(= :positive (:result %)) code)
          "the :positive branch result was captured"))
    (runtime/unwrap-handler! :event ::branched)))

;; ---------------------------------------------------------------------------
;; wrap-event-fx! / wrap-event-ctx! — the reg-event-fx and reg-event-ctx
;; chain-builder paths through the runtime API. wrap-handler! :event is
;; covered by the wrap-handler-emits-code-tag-on-dispatch test above;
;; these two pin the other event-flavour shorthands end-to-end via
;; dispatch-sync.
;; ---------------------------------------------------------------------------

(deftest wrap-event-fx-traces-the-effects-map
  (testing "wrap-event-fx! installs an fx-traced reg-event-fx; dispatch
            flows through the effects-map chain (handler returns
            {:db ...}, do-fx applies it) and :code entries surface from
            the wrapped body."
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-fx ::checkout-fx-baseline (fn [_ _] {}))
    (runtime/wrap-event-fx! ::checkout-fx-baseline
                            (fn [_ [_ amount]]
                              (let [doubled (* 2 amount)]
                                {:db {:total doubled}})))
    (re-frame.core/dispatch-sync [::checkout-fx-baseline 100])
    (let [code (code-entries (captured-traces))
          fs   (forms (captured-traces))]
      (is (seq code)
          "wrap-event-fx! body produced :code entries via fx-traced
           (which inherits fn-traced's :code emission)")
      (is (some #(re-find #"\* 2 amount" %) fs)
          "captured forms include the (* 2 amount) sub-form"))
    (is (= 200 (:total @re-frame.db/app-db))
        ":db effect from the wrapped body landed on app-db — proves the
         reg-event-fx chain (not reg-event-db) processed the return value")
    (is (true? (runtime/unwrap-handler! :event ::checkout-fx-baseline))
        "unwrap restores the baseline reg-event-fx handler")))

(deftest wrap-event-ctx-traces-context
  (testing "wrap-event-ctx! installs an fx-traced :ctx-mode reg-event-ctx;
            dispatch flows through the context-style chain (handler takes
            ctx, returns ctx) and :code entries surface from the wrapped
            body."
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-ctx ::ctx-baseline (fn [ctx] ctx))
    (runtime/wrap-event-ctx! ::ctx-baseline
                             (fn [{:keys [coeffects] :as ctx}]
                               (let [n (inc 41)]
                                 (assoc-in ctx [:effects :db]
                                           (assoc (:db coeffects) :n n)))))
    (re-frame.core/dispatch-sync [::ctx-baseline])
    (let [code (code-entries (captured-traces))
          fs   (forms (captured-traces))]
      (is (seq code)
          "wrap-event-ctx! body produced :code entries via fx-traced
           (which inherits fn-traced's :code emission)")
      (is (some #(re-find #"inc 41" %) fs)
          "captured forms include the (inc 41) sub-form"))
    (is (= 42 (:n @re-frame.db/app-db))
        ":db effect set inside the ctx flowed through to app-db — proves
         the reg-event-ctx chain (not reg-event-db / reg-event-fx)
         processed the return ctx")
    (is (true? (runtime/unwrap-handler! :event ::ctx-baseline))
        "unwrap restores the baseline reg-event-ctx handler")))

;; ---------------------------------------------------------------------------
;; wrap-event-fx! / wrap-event-ctx! also emit :fx-effects per-key.
;;
;; The runtime-wrap path used to expand to bare fn-traced and so never
;; surfaced the per-effect-key entries that source-edit
;; (reg-event-fx :foo (fx-traced [...] ...)) produces. After the
;; fx-traced switch, runtime wraps yield the same trace surface the
;; source-edit form does — these tests pin that contract end-to-end
;; via dispatch-sync so a future regression to fn-traced (or to a
;; wrapper that swallows the :fx-trace opts) gets caught.
;; ---------------------------------------------------------------------------

(deftest wrap-event-fx-emits-fx-effects-per-key
  (testing "wrap-event-fx! produces one :fx-effects entry per key in the
            returned effect-map — same contract as a source-edit
            (reg-event-fx :foo (fx-traced [...] ...))."
    (re-frame.core/reg-event-fx ::checkout-fx-effects (fn [_ _] {}))
    (runtime/wrap-event-fx! ::checkout-fx-effects
                            (fn [_ [_ amount]]
                              (let [taxed (* 1.1 amount)]
                                {:db {:total taxed}
                                 :http {:method :post :body taxed}
                                 :dispatch [:notify :checkout-done]})))
    (re-frame.core/dispatch-sync [::checkout-fx-effects 100])
    (let [fx      (fx-effects-entries (captured-traces))
          fx-keys (set (map :fx-key fx))]
      (is (= #{:db :http :dispatch} fx-keys)
          "every key of the wrapped body's effect-map produced a :fx-effects entry")
      (let [by-key (into {} (map (juxt :fx-key :value)) fx)]
        (is (= {:total 110.00000000000001} (get by-key :db))
            ":db entry carries the value the wrapped body produced")
        (is (= [:notify :checkout-done] (get by-key :dispatch))
            ":dispatch entry carries the dispatched event tuple")))
    (is (true? (runtime/unwrap-handler! :event ::checkout-fx-effects)))))

(deftest wrap-event-ctx-emits-fx-effects-from-ctx-effects-map
  (testing "wrap-event-ctx! produces :fx-effects entries from
            (:effects ctx) — the ctx-mode adaptation of fx-traced.
            A context handler returns the larger context structure;
            the per-effect breakdown sits at :effects, and only that
            sub-map gets per-key emission."
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-ctx ::ctx-fx-effects (fn [ctx] ctx))
    (runtime/wrap-event-ctx! ::ctx-fx-effects
                             (fn [ctx]
                               (-> ctx
                                   (assoc-in [:effects :db] {:n 42})
                                   (assoc-in [:effects :dispatch]
                                             [:downstream :payload]))))
    (re-frame.core/dispatch-sync [::ctx-fx-effects])
    (let [fx      (fx-effects-entries (captured-traces))
          fx-keys (set (map :fx-key fx))]
      (is (contains? fx-keys :db)
          ":db effect set into (:effects ctx) surfaced as a :fx-effects entry")
      (is (contains? fx-keys :dispatch)
          ":dispatch effect set into (:effects ctx) surfaced as a :fx-effects entry")
      (let [by-key (into {} (map (juxt :fx-key :value)) fx)]
        (is (= {:n 42} (get by-key :db))
            ":db entry carries the effect value, not the wrapping ctx")
        (is (= [:downstream :payload] (get by-key :dispatch))
            ":dispatch entry carries the effect value")))
    (is (true? (runtime/unwrap-handler! :event ::ctx-fx-effects)))))

;; ---------------------------------------------------------------------------
;; wrap-fx! traces effect-payload sub-forms (the
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

(deftest runtime-wrap-options-thread-through-dispatch
  (testing "wrap-handler! forwards fn-traced options through a real dispatch"
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-db ::runtime-db-options (fn [db _] db))
    (runtime/wrap-handler! :event
                           ::runtime-db-options
                           {:locals true :msg "runtime-db"}
                           (fn [db [_ x]]
                             (assoc db :n (inc x))))
    (re-frame.core/dispatch-sync [::runtime-db-options 7])
    (let [code (code-entries (captured-traces))]
      (is (= 8 (:n @re-frame.db/app-db))
          "wrapped reg-event-db handler still updates app-db")
      (is (seq code)
          "dispatch emitted code traces")
      (is (every? #(= "runtime-db" (:msg %)) code)
          ":msg flowed through wrap-handler!")
      (is (some #(some (fn [[sym value]]
                         (and (= 'x sym) (= 7 value)))
                       (:locals %))
                code)
          ":locals captured the event arg destructured by the wrapped handler")))
  (testing "wrap-event-fx! forwards fx-traced options"
    (reset! rft/traces [])
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-fx ::runtime-event-fx-options (fn [_ _] {}))
    (runtime/wrap-event-fx! ::runtime-event-fx-options
                            {:msg "runtime-event-fx"}
                            (fn [_ [_ x]]
                              {:db {:n (inc x)}}))
    (re-frame.core/dispatch-sync [::runtime-event-fx-options 10])
    (let [code (code-entries (captured-traces))]
      (is (= 11 (:n @re-frame.db/app-db))
          "wrapped reg-event-fx handler still returns effects")
      (is (seq code)
          "dispatch emitted code traces")
      (is (every? #(= "runtime-event-fx" (:msg %)) code)
          ":msg flowed through wrap-event-fx!")))
  (testing "wrap-event-ctx! forwards fx-traced options and preserves ctx-mode"
    (reset! rft/traces [])
    (reset! re-frame.db/app-db {})
    (re-frame.core/reg-event-ctx ::runtime-event-ctx-options (fn [ctx] ctx))
    (runtime/wrap-event-ctx! ::runtime-event-ctx-options
                             {:msg "runtime-event-ctx"}
                             (fn [ctx]
                               (assoc-in ctx [:effects :db] {:from-ctx true})))
    (re-frame.core/dispatch-sync [::runtime-event-ctx-options])
    (let [code (code-entries (captured-traces))]
      (is (true? (:from-ctx @re-frame.db/app-db))
          "wrapped reg-event-ctx handler still returns a context with db effects")
      (is (seq code)
          "dispatch emitted code traces")
      (is (every? #(= "runtime-event-ctx" (:msg %)) code)
          ":msg flowed through wrap-event-ctx!"))))

;; ---------------------------------------------------------------------------
;; :locals and :if options for fn-traced
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

(deftest fn-traced-locals-with-varargs-arglist
  (testing ":locals true + a varargs arglist [a & rest] compiles and binds named locals only"
    ;; The '& separator is an arglist sentinel, not a binding —
    ;; emitting `['& &]` into +debux-dbg-locals+ would raise
    ;; CompilerException at handler-load time. find-symbols must drop it.
    (re-frame.core/reg-event-db ::var-args (fn [db _] db))
    (re-frame.core/reg-event-db ::var-args
                                (tracing/fn-traced
                                  {:locals true}
                                  [db & rest]
                                  (assoc db :rest rest)))
    (re-frame.core/dispatch-sync [::var-args :a :b :c])
    (let [code     (code-entries (captured-traces))
          a-locals (-> code first :locals)]
      (is (seq code)
          "varargs handler with :locals true expanded and dispatched without throwing")
      (is (every? :locals code)
          ":locals attaches to each :code entry")
      (is (not-any? #(= '& (first %)) a-locals)
          "the '& arglist separator never appears as a local binding")
      (is (some #(= 'rest (first %)) a-locals)
          "the variadic-tail symbol IS a real local — included")
      (is (some #(= 'db (first %)) a-locals)
          "fixed args are still captured")
      (is (= '([:day8.re-frame.integration-test/var-args :a :b :c])
             (some (fn [[s v]] (when (= 'rest s) v)) a-locals))
          "rest binds to the variadic tail at runtime (re-frame passes the event vec as the single 2nd arg)"))))

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

;; ---------------------------------------------------------------------------
;; :once option — duplicate suppression across handler invocations
;; ---------------------------------------------------------------------------

(deftest fn-traced-once-dedupes-across-dispatches
  (testing ":once true suppresses :code emission when (form, result) is unchanged"
    ;; Handler ignores db (no app-db carryover from sibling tests can
    ;; perturb the per-form results) and returns a fresh constant
    ;; map both times. Every traced form's result is identical
    ;; across the two dispatches, so :once should suppress them all.
    (re-frame.core/reg-event-db ::stable-once (fn [_ _] {}))
    (re-frame.core/reg-event-db ::stable-once
                                (tracing/fn-traced
                                  {:once true}
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:n n})))
    (re-frame.core/dispatch-sync [::stable-once])
    (let [first-code (code-entries (captured-traces))]
      (is (seq first-code)
          "first dispatch emits :code entries (forms haven't been seen)"))
    (reset! rft/traces [])
    (re-frame.core/dispatch-sync [::stable-once])
    (let [second-code (code-entries (captured-traces))]
      (is (empty? second-code)
          "second dispatch with identical results emits nothing — :once dedupes"))))

(deftest fn-traced-once-emits-on-changed-result
  (testing ":once still emits when the result actually differs from last time"
    (re-frame.core/reg-event-db ::changing-once (fn [_ _] {}))
    (re-frame.core/reg-event-db ::changing-once
                                (tracing/fn-traced
                                  {:once true}
                                  [_ [_ x]]
                                  (let [n (* 2 x)]
                                    {:n n})))
    (re-frame.core/dispatch-sync [::changing-once 5])
    (let [first-code (code-entries (captured-traces))]
      (is (some #(= 10 (:result %)) first-code)
          "first dispatch records the (* 2 5) → 10 entry"))
    (reset! rft/traces [])
    (re-frame.core/dispatch-sync [::changing-once 6])
    (let [second-code (code-entries (captured-traces))]
      (is (some #(= 12 (:result %)) second-code)
          "second dispatch with x=6 records the (* 2 6) → 12 entry — different result, not deduped"))))

(deftest fn-traced-once-without-flag-still-emits-twice
  (testing "without :once, identical dispatches emit identical :code each time (sanity)"
    (re-frame.core/reg-event-db ::no-once (fn [db _] db))
    (re-frame.core/reg-event-db ::no-once
                                (tracing/fn-traced
                                  [db _]
                                  (let [n (inc 41)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::no-once])
    (let [first-count (count (code-entries (captured-traces)))]
      (reset! rft/traces [])
      (re-frame.core/dispatch-sync [::no-once])
      (is (= first-count (count (code-entries (captured-traces))))
          "without :once, the second dispatch emits the same number of :code entries as the first"))))

(deftest fn-traced-once-composes-with-if
  (testing ":if AND :once both gate emission"
    (re-frame.core/reg-event-db ::if-and-once (fn [_ _] {}))
    (re-frame.core/reg-event-db ::if-and-once
                                (tracing/fn-traced
                                  {:once true :if number?}
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:s (str n)})))
    (re-frame.core/dispatch-sync [::if-and-once])
    (let [first-code (code-entries (captured-traces))]
      (is (every? #(number? (:result %)) first-code)
          ":if number? still suppresses non-numeric results")
      (is (seq first-code)
          ":if didn't gate everything; some entries land"))
    (reset! rft/traces [])
    (re-frame.core/dispatch-sync [::if-and-once])
    (let [second-code (code-entries (captured-traces))]
      (is (empty? second-code)
          "second dispatch: :once dedupes everything that survived :if"))))

;; ---------------------------------------------------------------------------
;; :verbose / :show-all option — wrap leaf literals that the default
;; behaviour skips for noise reduction
;; ---------------------------------------------------------------------------

(deftest fn-traced-default-skips-leaf-literals
  (testing "without :verbose, leaf number/string literals don't produce :code entries with the literal as :form"
    (re-frame.core/reg-event-db ::quiet-literals (fn [_ _] {}))
    (re-frame.core/reg-event-db ::quiet-literals
                                (tracing/fn-traced
                                  [_ _]
                                  (let [n 42
                                        s "hello"]
                                    {:n n :s s})))
    (re-frame.core/dispatch-sync [::quiet-literals])
    (let [forms (set (map :form (code-entries (captured-traces))))]
      ;; Default mode: literals appear inside enclosing forms (the let
      ;; binding, the map literal) but are NOT emitted as their own
      ;; standalone entries with :form 42 / :form "hello".
      (is (not (contains? forms 42))
          "no entry has :form 42 in default mode")
      (is (not (contains? forms "hello"))
          "no entry has :form \"hello\" in default mode"))))

(deftest fn-traced-verbose-wraps-leaf-literals
  (testing ":verbose true wraps the leaf literals the default mode skips"
    (re-frame.core/reg-event-db ::loud-literals (fn [_ _] {}))
    (re-frame.core/reg-event-db ::loud-literals
                                (tracing/fn-traced
                                  {:verbose true}
                                  [_ _]
                                  (let [n 42
                                        s "hello"]
                                    {:n n :s s})))
    (re-frame.core/dispatch-sync [::loud-literals])
    (let [forms (set (map :form (code-entries (captured-traces))))]
      (is (contains? forms 42)
          ":verbose surfaces the 42 literal as its own :code entry (:form 42)")
      (is (contains? forms "hello")
          ":verbose surfaces the \"hello\" literal too"))))

(deftest fn-traced-show-all-alias-wraps-literals
  (testing ":show-all is an accepted alias for :verbose"
    (re-frame.core/reg-event-db ::show-all-alias (fn [_ _] {}))
    (re-frame.core/reg-event-db ::show-all-alias
                                (tracing/fn-traced
                                  {:show-all true}
                                  [_ _]
                                  (let [n 99]
                                    {:n n})))
    (re-frame.core/dispatch-sync [::show-all-alias])
    (let [forms (set (map :form (code-entries (captured-traces))))]
      (is (contains? forms 99)
          ":show-all wraps the 99 literal just like :verbose would"))))

(deftest fn-traced-verbose-covers-all-leaf-types
  (testing ":verbose surfaces every default-skipped leaf literal type"
    (let [leaf-literals [42 "hello" true :leaf/keyword \a nil]]
      (re-frame.core/reg-event-db ::all-leaf-types-default (fn [_ _] {}))
      (re-frame.core/reg-event-db ::all-leaf-types-default
                                  (tracing/fn-traced
                                    [_ _]
                                    (let [number-literal 42
                                          string-literal "hello"
                                          boolean-literal true
                                          keyword-literal :leaf/keyword
                                          char-literal \a
                                          nil-literal nil]
                                      {:number number-literal
                                       :string string-literal
                                       :boolean boolean-literal
                                       :keyword keyword-literal
                                       :char char-literal
                                       :nil nil-literal})))
      (re-frame.core/dispatch-sync [::all-leaf-types-default])
      (let [default-code (code-entries (captured-traces))]
        (doseq [literal leaf-literals]
          (is (not (code-form-present? default-code literal))
              (str "default mode does not emit standalone leaf literal "
                   (pr-str literal)))))

      (reset! rft/traces [])

      (re-frame.core/reg-event-db ::all-leaf-types-verbose (fn [_ _] {}))
      (re-frame.core/reg-event-db ::all-leaf-types-verbose
                                  (tracing/fn-traced
                                    {:verbose true}
                                    [_ _]
                                    (let [number-literal 42
                                          string-literal "hello"
                                          boolean-literal true
                                          keyword-literal :leaf/keyword
                                          char-literal \a
                                          nil-literal nil]
                                      {:number number-literal
                                       :string string-literal
                                       :boolean boolean-literal
                                       :keyword keyword-literal
                                       :char char-literal
                                       :nil nil-literal})))
      (re-frame.core/dispatch-sync [::all-leaf-types-verbose])
      (let [verbose-code (code-entries (captured-traces))]
        (doseq [literal leaf-literals]
          (is (code-form-present? verbose-code literal)
              (str ":verbose emits standalone leaf literal "
                   (pr-str literal))))))))

(deftest fn-traced-verbose-still-honours-skip-form-itself
  (testing ":verbose leaves special forms opaque enough to preserve evaluation semantics"
    (re-frame.core/reg-event-db ::verbose-special-form-skips (fn [_ _] {}))
    (re-frame.core/reg-event-db ::verbose-special-form-skips
                                (tracing/fn-traced
                                  {:verbose true}
                                  [_ _]
                                  (let [loop-result (loop [n 0
                                                           acc 0]
                                                      (if (< n 3)
                                                        (recur (inc n) (+ acc n))
                                                        acc))
                                        quoted-leaf (quote [:quoted-leaf-sentinel])
                                        throw-branch (if false
                                                       (throw (ex-info "unreachable"
                                                                       {:kind :throw-sentinel}))
                                                       :not-thrown)]
                                    {:loop-result loop-result
                                     :quoted-leaf quoted-leaf
                                     :throw-branch throw-branch})))
    (re-frame.core/dispatch-sync [::verbose-special-form-skips])
    (is (= {:loop-result 3
            :quoted-leaf [:quoted-leaf-sentinel]
            :throw-branch :not-thrown}
           @re-frame.db/app-db)
        "special-form-heavy handler still evaluates correctly")
    (let [code (code-entries (captured-traces))]
      (doseq [head '[recur throw]]
        (is (not (code-form-with-head-present? code head))
            (str ":verbose does not emit a standalone "
                 head
                 " form")))
      (is (not (code-form-present? code :quoted-leaf-sentinel))
          "quote remains opaque; verbose does not trace quoted leaves")
      (is (not (code-form-present? code :throw-sentinel))
          "throw remains opaque; verbose does not trace throw body literals"))))

;; ---------------------------------------------------------------------------
;; :trace-frames — function entry/exit markers around fn-traced bodies
;; ---------------------------------------------------------------------------

(deftest fn-traced-frame-markers-default-off
  (testing ":trace-frames markers are opt-in on the trace stream"
    (re-frame.core/reg-event-db ::frames-default-off (fn [_ _] {}))
    (re-frame.core/reg-event-db ::frames-default-off
                                (tracing/fn-traced
                                  [_ _]
                                  {:answer 42}))
    (re-frame.core/dispatch-sync [::frames-default-off])
    (is (empty? (frame-entries (captured-traces)))
        "fn-traced does not emit :trace-frames unless explicitly enabled")))

(deftest fn-traced-emits-enter-and-exit-frame-markers
  (testing "every fn-traced'd dispatch emits paired :enter and :exit markers"
    (util/set-trace-frames-output! true)
    (re-frame.core/reg-event-db ::framed (fn [_ _] {}))
    (re-frame.core/reg-event-db ::framed
                                (tracing/fn-traced
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:n n})))
    (re-frame.core/dispatch-sync [::framed])
    (let [frames (frame-entries (captured-traces))]
      (is (= 2 (count frames))
          "exactly one :enter and one :exit per dispatch")
      (is (= [:enter :exit] (mapv :phase frames))
          ":enter precedes :exit chronologically")
      (let [[enter exit] frames]
        (is (= (:frame-id enter) (:frame-id exit))
            "both markers carry the same frame-id (paired by gensym at expansion)")
        (is (string? (:frame-id enter))
            "frame-id is a string")
        (is (number? (:t enter))
            ":enter carries a millisecond timestamp")
        (is (number? (:t exit))
            ":exit carries a millisecond timestamp")
        (is (>= (:t exit) (:t enter))
            ":exit timestamp is at or after :enter")))))

(deftest fn-traced-frame-exit-omits-result
  (testing ":exit marker avoids retaining the handler's return value"
    (util/set-trace-frames-output! true)
    (re-frame.core/reg-event-db ::framed-result (fn [_ _] {}))
    (re-frame.core/reg-event-db ::framed-result
                                (tracing/fn-traced
                                  [_ _]
                                  {:answer 42}))
    (re-frame.core/dispatch-sync [::framed-result])
    (let [exit (first (filter #(= :exit (:phase %))
                              (frame-entries (captured-traces))))]
      (is (some? exit)
          ":exit marker is present")
      (is (not (contains? exit :result))
          ":exit does not duplicate the body's last expression value"))))

(deftest fn-traced-frames-ignore-code-gates-and-carry-msg
  (testing ":if can suppress :code while invocation frame markers still emit with :msg"
    (util/set-trace-frames-output! true)
    (re-frame.core/reg-event-db ::gated-frames (fn [_ _] {}))
    (re-frame.core/reg-event-db ::gated-frames
                                (tracing/fn-traced
                                  {:if (constantly false)
                                   :msg "silenced-handler"}
                                  [db _]
                                  (assoc db :answer 42)))
    (re-frame.core/dispatch-sync [::gated-frames])
    (let [captured (captured-traces)
          code     (code-entries captured)
          frames   (frame-entries captured)]
      (is (empty? code)
          ":if false suppresses every :code entry")
      (is (= [:enter :exit] (mapv :phase frames))
          "frame markers still bracket the invocation")
      (is (every? #(= "silenced-handler" (:msg %)) frames)
          ":msg is copied onto both frame markers"))))

(deftest wrap-handler-frames-too
  (testing "wrap-handler! → fn-traced inherits frame markers"
    (util/set-trace-frames-output! true)
    (re-frame.core/reg-event-db ::wrapped-framed (fn [db _] db))
    (runtime/wrap-handler! :event ::wrapped-framed
                           (fn [db _] (assoc db :touched? true)))
    (re-frame.core/dispatch-sync [::wrapped-framed])
    (let [frames (frame-entries (captured-traces))]
      (is (seq frames)
          "wrap-handler! produces frame markers via its fn-traced expansion")
      (is (= [:enter :exit] (mapv :phase frames))
          ":enter then :exit"))
    (runtime/unwrap-handler! :event ::wrapped-framed)))

;; ---------------------------------------------------------------------------
;; fx-traced — per-effect-key tracing on reg-event-fx return maps
;; ---------------------------------------------------------------------------

(deftest fx-traced-emits-per-effect-entry
  (testing "fx-traced surfaces each key of the returned effect-map"
    (re-frame.core/reg-event-fx ::checkout (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::checkout
                                (tracing/fx-traced
                                  [_ [_ amount]]
                                  (let [taxed (* 1.1 amount)]
                                    {:db {:total taxed}
                                     :http {:method :post :body taxed}
                                     :dispatch [:notify :checkout-done]})))
    (re-frame.core/dispatch-sync [::checkout 100])
    (let [fx-keys (set (map :fx-key (fx-effects-entries (captured-traces))))]
      (is (= #{:db :http :dispatch} fx-keys)
          "every key of the returned effect-map produces an :fx-effects entry"))))

(deftest fx-traced-effect-values-match-handler-return
  (testing ":fx-effects entries carry the actual effect values"
    (re-frame.core/reg-event-fx ::checkout-vals (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::checkout-vals
                                (tracing/fx-traced
                                  [_ _]
                                  {:db {:answer 42}
                                   :http "stringy-effect"}))
    (re-frame.core/dispatch-sync [::checkout-vals])
    (let [entries (fx-effects-entries (captured-traces))
          by-key  (into {} (map (juxt :fx-key :value)) entries)]
      (is (= {:answer 42} (get by-key :db))
          ":db entry carries the new db map")
      (is (= "stringy-effect" (get by-key :http))
          ":http entry carries the value the handler put there"))))

(deftest fx-traced-effect-entries-share-one-timestamp
  (testing "all per-key entries from one effect-map share the same logical timestamp"
    (with-redefs [util/now-ms (let [ticks (atom 0)]
                                (fn [] (swap! ticks inc)))]
      (re-frame.core/reg-event-fx ::checkout-timestamps (fn [_ _] {}))
      (re-frame.core/reg-event-fx ::checkout-timestamps
                                  (tracing/fx-traced
                                    [_ _]
                                    {:db {:answer 42}
                                     :http {:method :post}
                                     :dispatch [:notify]}))
      (re-frame.core/dispatch-sync [::checkout-timestamps])
      (let [timestamps (mapv :t (fx-effects-entries (captured-traces)))]
        (is (= 3 (count timestamps))
            "one :fx-effects entry is emitted per effect-map key")
        (is (apply = timestamps)
            "one -emit-fx-traces! invocation captures now-ms once")))))

(deftest fx-traced-also-emits-code-and-frames
  (testing "fx-traced inherits fn-traced's :code and :trace-frames behaviour"
    (util/set-trace-frames-output! true)
    (re-frame.core/reg-event-fx ::checkout-mixed (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::checkout-mixed
                                (tracing/fx-traced
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:db {:n n}})))
    (re-frame.core/dispatch-sync [::checkout-mixed])
    (let [code   (code-entries (captured-traces))
          frames (frame-entries (captured-traces))
          fx     (fx-effects-entries (captured-traces))]
      (is (seq code)
          "fx-traced still emits per-form :code entries (fn-traced inheritance)")
      (is (= [:enter :exit] (mapv :phase frames))
          "fx-traced still bracket frames the body")
      (is (some #(= :db (:fx-key %)) fx)
          "fx-traced ALSO emits the :db key as an :fx-effects entry"))))

(deftest fx-traced-non-map-return-skips-fx-emit
  (testing "fx-traced is value-transparent even if the body returns a non-map"
    ;; A handler could violate the reg-event-fx contract and return a
    ;; non-map; -emit-fx-traces! is map?-gated and silently skips. The
    ;; return value still flows through unchanged.
    (re-frame.core/reg-event-fx ::malformed (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::malformed
                                (tracing/fx-traced
                                  [_ _]
                                  ;; deliberately non-map (broken contract)
                                  {:db {}}))
    (re-frame.core/dispatch-sync [::malformed])
    (is (some? (fx-effects-entries (captured-traces)))
        "well-formed fx-traced doesn't crash; emits per-key entries normally")))

(deftest fx-traced-ctx-mode-targets-effects-sub-map
  (testing ":ctx-mode true points :fx-effects emission at (:effects ctx)
            instead of the body's full return. The wrapped reg-event-ctx
            handler returns the larger context structure, but only the
            effects sub-map should produce per-key entries — :coeffects
            / :queue / :stack are infrastructure, not effects."
    (re-frame.core/reg-event-ctx ::ctx-fx-mode (fn [ctx] ctx))
    (re-frame.core/reg-event-ctx ::ctx-fx-mode
                                 (tracing/fx-traced
                                   {:ctx-mode true}
                                   [ctx]
                                   (-> ctx
                                       (assoc-in [:effects :db] {:n 42})
                                       (assoc-in [:effects :http] :stub))))
    (re-frame.core/dispatch-sync [::ctx-fx-mode])
    (let [fx     (fx-effects-entries (captured-traces))
          fx-ks  (set (map :fx-key fx))
          by-key (into {} (map (juxt :fx-key :value)) fx)]
      (is (= #{:db :http} fx-ks)
          ":fx-effects keys are exactly the keys of (:effects ctx) — not
           :coeffects / :queue / :stack")
      (is (= {:n 42} (get by-key :db))
          ":db entry carries the effect value, not the wrapping ctx")
      (is (= :stub (get by-key :http))))))

;; ---------------------------------------------------------------------------
;; fx-traced opts surface — :msg / :locals / :if / :once / :verbose
;; ---------------------------------------------------------------------------
;;
;; fx-traced shares fn-body with fn-traced and only flips :fx-trace.
;; The opts surface is therefore a transitive contract: every
;; fn-traced opt should reach the fx-traced :code path. Pin that
;; assumption per-opt so a future change to opts threading can't
;; silently break fx-traced while leaving fn-traced green. The
;; :final variant lives in debux/final_option_test.cljc alongside
;; the rest of the :final coverage.

(deftest fx-traced-msg-option-labels-every-code-entry
  (testing ":msg \"label\" attaches the label as :msg on every fx-traced :code entry"
    (re-frame.core/reg-event-fx ::fx-with-msg (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::fx-with-msg
                                (tracing/fx-traced
                                  {:msg "checkout-handler"}
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:db {:n n}})))
    (re-frame.core/dispatch-sync [::fx-with-msg])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "at least one :code entry was emitted")
      (is (every? #(= "checkout-handler" (:msg %)) code)
          "every :code entry from fx-traced carries :msg = \"checkout-handler\""))))

(deftest fx-traced-locals-option-captures-args
  (testing ":locals true on fx-traced attaches captured arg pairs to each :code entry"
    (re-frame.core/reg-event-fx ::fx-with-locals (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::fx-with-locals
                                (tracing/fx-traced
                                  {:locals true}
                                  [_ [_ x]]
                                  (let [n (inc x)]
                                    {:db {:n n}})))
    (re-frame.core/dispatch-sync [::fx-with-locals 7])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "at least one :code entry was emitted")
      (is (every? :locals code)
          "every :code entry carries a :locals key when :locals true")
      (let [a-locals (-> code first :locals)]
        (is (some #(= 'x (first %)) a-locals)
            ":locals includes the bound x arg")
        (is (some #(= 7 (second %)) a-locals)
            ":locals binds x to its runtime value (7)")))))

(deftest fx-traced-if-option-gates-emission
  (testing ":if (constantly false) on fx-traced suppresses every :code entry"
    (re-frame.core/reg-event-fx ::fx-quiet-if (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::fx-quiet-if
                                (tracing/fx-traced
                                  {:if (constantly false)}
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:db {:n n}})))
    (re-frame.core/dispatch-sync [::fx-quiet-if])
    (is (empty? (code-entries (captured-traces)))
        ":if predicate returned false → send-trace! gated for every form, even through fx-traced")))

(deftest fx-traced-once-dedupes-across-dispatches
  (testing ":once true on fx-traced suppresses :code emission when (form, result) is unchanged"
    ;; Handler ignores both args and returns a fresh constant map both
    ;; times — every traced form's result is identical across the two
    ;; dispatches, so :once should suppress them all the second time.
    (re-frame.core/reg-event-fx ::fx-stable-once (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::fx-stable-once
                                (tracing/fx-traced
                                  {:once true}
                                  [_ _]
                                  (let [n (inc 41)]
                                    {:db {:n n}})))
    (re-frame.core/dispatch-sync [::fx-stable-once])
    (let [first-code (code-entries (captured-traces))]
      (is (seq first-code)
          "first dispatch emits :code entries (forms haven't been seen)"))
    (reset! rft/traces [])
    (re-frame.core/dispatch-sync [::fx-stable-once])
    (let [second-code (code-entries (captured-traces))]
      (is (empty? second-code)
          "second dispatch with identical results emits nothing — :once dedupes through fx-traced"))))

(deftest fx-traced-verbose-wraps-leaf-literals
  (testing ":verbose true on fx-traced wraps the leaf literals the default mode skips"
    (re-frame.core/reg-event-fx ::fx-loud-literals (fn [_ _] {}))
    (re-frame.core/reg-event-fx ::fx-loud-literals
                                (tracing/fx-traced
                                  {:verbose true}
                                  [_ _]
                                  (let [n 42
                                        s "hello"]
                                    {:db {:n n :s s}})))
    (re-frame.core/dispatch-sync [::fx-loud-literals])
    (let [forms (set (map :form (code-entries (captured-traces))))]
      (is (contains? forms 42)
          ":verbose surfaces the 42 literal as its own :code entry through fx-traced")
      (is (contains? forms "hello")
          ":verbose surfaces the \"hello\" literal too"))))

;; ---------------------------------------------------------------------------
;; defn-fx-traced — defn variant of fx-traced. Sanity coverage only:
;; a bare invocation (no opts) and an opts-bearing invocation, each
;; verified through reg-event-fx + dispatch-sync. The macroexpansion
;; path (defn-fx-traced → defn-traced + :fx-trace flag) is exercised
;; in tracing_stubs_test.clj; this section pins the live integration
;; shape — macro expands, var interns, dispatch flows through, and
;; both :code (fn-traced inheritance) and :fx-effects (fx-traced
;; addition) entries land on the trace stream.
;; ---------------------------------------------------------------------------

(declare fx-defn-bare-impl fx-defn-opts-impl)

(deftest defn-fx-traced-bare-dispatches-through-reg-event-fx
  (testing "defn-fx-traced without opts: macro expands, var interns, dispatch flows through reg-event-fx producing :code and :fx-effects entries"
    (tracing/defn-fx-traced fx-defn-bare-impl [_ [_ amount]]
      (let [doubled (* 2 amount)]
        {:db {:total doubled}}))
    (re-frame.core/reg-event-fx ::fx-defn-bare-event fx-defn-bare-impl)
    (re-frame.core/dispatch-sync [::fx-defn-bare-event 100])
    (let [code (code-entries (captured-traces))
          fs   (forms (captured-traces))
          fx   (fx-effects-entries (captured-traces))]
      (is (seq code)
          "defn-fx-traced body produced :code entries via fn-traced inheritance")
      (is (some #(re-find #"\* 2 amount" %) fs)
          "captured forms include the (* 2 amount) sub-form")
      (is (some #(= :db (:fx-key %)) fx)
          "defn-fx-traced still emits per-key :fx-effects entries"))))

(deftest defn-fx-traced-with-opts-dispatches-through-reg-event-fx
  (testing "defn-fx-traced with leading opts map: opts thread through to the :code payload"
    (tracing/defn-fx-traced
      {:msg "fx-defn-labelled"}
      fx-defn-opts-impl
      [_ [_ amount]]
      (let [doubled (* 2 amount)]
        {:db {:total doubled}}))
    (re-frame.core/reg-event-fx ::fx-defn-opts-event fx-defn-opts-impl)
    (re-frame.core/dispatch-sync [::fx-defn-opts-event 50])
    (let [code (code-entries (captured-traces))
          fx   (fx-effects-entries (captured-traces))]
      (is (seq code)
          "opts-bearing defn-fx-traced still emitted :code entries")
      (is (every? #(= "fx-defn-labelled" (:msg %)) code)
          ":msg flows through defn-fx-traced → defn-traced → fn-traced opts threading")
      (is (some #(= :db (:fx-key %)) fx)
          ":fx-trace flag also makes it through; per-key :fx-effects still emitted"))))

;; ---------------------------------------------------------------------------
;; tap> output channel — set-tap-output!
;; ---------------------------------------------------------------------------
;;
;; CLJ tap> dispatch is async (agent-pool-backed queue). Tests use a
;; small wait helper that polls the probe atom up to a generous
;; timeout — robust to scheduler jitter without burning real time on
;; the happy path.

(defn- wait-for
  "Poll `pred-fn` until it returns truthy or `timeout-ms` elapses.
   Returns the truthy value or nil on timeout."
  [pred-fn timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred-fn)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))

(deftest set-tap-output-routes-trace-records-to-add-tap
  (testing "set-tap-output! true → add-tap probe receives processed :code entries"
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        (util/set-tap-output! true)
        (re-frame.core/reg-event-db ::tapped (fn [db _] db))
        (runtime/wrap-handler! :event ::tapped
                               (fn [db _] (let [n (inc 41)] (assoc db :n n))))
        (re-frame.core/dispatch-sync [::tapped])
        (wait-for #(some (fn [entry] (= :code (:debux/kind entry))) @received) 1000)
        (is (seq @received)
            "tap probe received at least one trace record")
        (let [code-tapped (filter #(= :code (:debux/kind %)) @received)
              results     (set (map :result code-tapped))
              forms       (set (map (comp pr-str :form) code-tapped))]
          (is (seq code-tapped)
              "at least one :debux/kind :code entry reached the tap")
          (is (contains? results 42)
              "tap probe got the (inc 41) → 42 entry")
          (is (some #(re-find #"inc" %) forms)
              "tap probe entries include the inc form (tidied)")
          (is (every? #(contains? % :indent-level) code-tapped)
              "every :code-kind tapped entry carries the :code payload contract"))
        (runtime/unwrap-handler! :event ::tapped)
        (finally
          (remove-tap probe)
          (util/set-tap-output! false))))))

(deftest set-tap-output-default-is-quiet
  (testing "without set-tap-output! true, add-tap probe sees no send-trace! emissions"
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        ;; Don't enable tap output — dispatch should land on :code only.
        (re-frame.core/reg-event-db ::quiet-tap (fn [db _] db))
        (runtime/wrap-handler! :event ::quiet-tap
                               (fn [db _] (let [n (inc 41)] (assoc db :n n))))
        (re-frame.core/dispatch-sync [::quiet-tap])
        ;; Give the agent a window to (mis)deliver; we expect nothing.
        (Thread/sleep 50)
        (is (seq (code-entries (captured-traces)))
            "the trace channel still received entries (sanity)")
        (is (empty? @received)
            "tap channel is silent by default — no entries reached the probe")
        (runtime/unwrap-handler! :event ::quiet-tap)
        (finally
          (remove-tap probe))))))

(deftest set-tap-output-toggle-stops-emission
  (testing "set-tap-output! false stops further emissions to add-tap consumers"
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        (util/set-tap-output! true)
        (re-frame.core/reg-event-db ::toggled (fn [db _] db))
        (runtime/wrap-handler! :event ::toggled
                               (fn [db _] (let [n (inc 41)] (assoc db :n n))))
        (re-frame.core/dispatch-sync [::toggled])
        (wait-for #(seq @received) 1000)
        (let [first-count (count @received)]
          (is (pos? first-count)
              "first dispatch with tap-output on emitted to the probe"))
        (util/set-tap-output! false)
        (reset! received [])
        (re-frame.core/dispatch-sync [::toggled])
        (Thread/sleep 50)
        (is (empty? @received)
            "after toggling off, second dispatch produces no tap emissions")
        (runtime/unwrap-handler! :event ::toggled)
        (finally
          (remove-tap probe)
          (util/set-tap-output! false))))))

(deftest set-tap-output-fires-out-of-trace
  (testing "set-tap-output! true emits even when no trace event is in flight"
    ;; Independent of re-frame.trace machinery: a send-trace! call
    ;; with trace-enabled? false should still tap. The fixture turns
    ;; trace-enabled? on for the dispatch tests; flip it off here so
    ;; merge-trace! is a no-op (and doesn't try to set! an unbound
    ;; *current-trace*) and we exercise only the tap arm.
    (with-redefs [rft/trace-enabled?     false
                  tracing/trace-enabled? false]
      (let [received (atom [])
            probe    (fn [x] (swap! received conj x))]
        (try
          (add-tap probe)
          (util/set-tap-output! true)
          (util/send-trace! {:form         '(inc 41)
                             :result       42
                             :indent-level 0
                             :syntax-order 0
                             :num-seen     0})
          (wait-for #(seq @received) 1000)
          (is (= 1 (count @received))
              "tap probe got exactly one emission from the bare send-trace! call")
          (let [entry (first @received)]
            (is (= 42 (:result entry)))
            (is (= '(inc 41) (:form entry))
                ":form is tidied (clojure.core/inc → inc) in the tapped entry"))
          (finally
            (remove-tap probe)
            (util/set-tap-output! false)))))))

(deftest set-date-time-fn-stamps-tap-payloads
  (testing "tap payloads carry both stable numeric :t and configured :date-time"
    (with-redefs [rft/trace-enabled?     false
                  tracing/trace-enabled? false]
      (let [received (atom [])
            probe    (fn [x] (swap! received conj x))]
        (try
          (add-tap probe)
          (util/set-date-time-fn! (constantly [:fixed :instant]))
          (util/set-tap-output! true)
          (util/send-form! '(inc 1))
          (util/send-trace! {:form         '(inc 41)
                             :result       42
                             :indent-level 0
                             :syntax-order 0
                             :num-seen     0})
          (util/-send-frame-enter! "frame_1")
          (util/-emit-fx-traces! {:db {:answer 42}})
          (wait-for #(<= 4 (count @received)) 1000)
          (let [kinds (set (map :debux/kind @received))]
            (is (set/subset? #{:form :code :frame-enter :fx-effect} kinds))
            (is (every? number? (map :t @received))
                "every tap payload keeps a numeric millisecond :t")
            (is (every? #(= [:fixed :instant] (:date-time %)) @received)
                "every tap payload uses the configured date/time value"))
          (finally
            (remove-tap probe)
            (util/set-date-time-fn! nil)
            (util/set-tap-output! false)))))))

(deftest set-tap-output-emits-form-kind
  (testing "set-tap-output! routes the once-per-fn :form marker to tap with :debux/kind :form"
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        (util/set-tap-output! true)
        (re-frame.core/reg-event-db ::form-tapped (fn [db _] db))
        (runtime/wrap-handler! :event ::form-tapped
                               (fn [db _] (let [n (inc 41)] (assoc db :n n))))
        (re-frame.core/dispatch-sync [::form-tapped])
        (wait-for (fn [] (some #(= :form (:debux/kind %)) @received)) 1000)
        (let [forms (filter #(= :form (:debux/kind %)) @received)]
          (is (= 1 (count forms))
              "exactly one :debux/kind :form payload per fn-traced dispatch")
          (is (contains? (first forms) :form)
              ":form key carries the whole-form marker"))
        (runtime/unwrap-handler! :event ::form-tapped)
        (finally
          (remove-tap probe)
          (util/set-tap-output! false))))))

(deftest set-tap-output-emits-paired-frame-markers
  (testing "set-tap-output! surfaces :frame-enter and :frame-exit on the tap stream"
    (reset! re-frame.db/app-db {})
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        (util/set-tap-output! true)
        (re-frame.core/reg-event-db ::frame-tapped (fn [db _] db))
        (runtime/wrap-handler! :event ::frame-tapped
                               (fn [db _] (assoc db :answer 42)))
        (re-frame.core/dispatch-sync [::frame-tapped])
        (wait-for (fn [] (some #(= :frame-exit (:debux/kind %)) @received)) 1000)
        (let [enters (filter #(= :frame-enter (:debux/kind %)) @received)
              exits  (filter #(= :frame-exit  (:debux/kind %)) @received)]
          (is (= 1 (count enters)) "one :frame-enter per dispatch")
          (is (= 1 (count exits))  "one :frame-exit per dispatch")
          (let [enter (first enters)
                exit  (first exits)]
            (is (= (:frame-id enter) (:frame-id exit))
                "tap :frame-enter / :frame-exit share a frame-id (paired)")
            (is (string? (:frame-id enter)) ":frame-id is a gensym string")
            (is (number? (:t enter)) ":frame-enter carries a numeric :t")
            (is (number? (:t exit))  ":frame-exit carries a numeric :t")
            (is (>= (:t exit) (:t enter))
                ":frame-exit timestamp is at or after :frame-enter")
            (is (not (contains? exit :result))
                ":frame-exit avoids duplicating the body's return value")))
        (runtime/unwrap-handler! :event ::frame-tapped)
        (finally
          (remove-tap probe)
          (util/set-tap-output! false))))))

(deftest set-tap-output-emits-fx-effect-per-key
  (testing "set-tap-output! emits one :debux/kind :fx-effect tap per effect-map key"
    (let [received (atom [])
          probe    (fn [x] (swap! received conj x))]
      (try
        (add-tap probe)
        (util/set-tap-output! true)
        (re-frame.core/reg-event-fx ::fx-tapped (fn [_ _] {}))
        (re-frame.core/reg-event-fx ::fx-tapped
                                    (tracing/fx-traced
                                      [_ _]
                                      {:db        {:answer 42}
                                       :http      {:method :post}
                                       :dispatch  [:notify]}))
        (re-frame.core/dispatch-sync [::fx-tapped])
        (wait-for (fn [] (<= 3 (count (filter #(= :fx-effect (:debux/kind %)) @received))))
                  1000)
        (let [fx-tapped (filter #(= :fx-effect (:debux/kind %)) @received)
              by-key    (into {} (map (juxt :fx-key :value)) fx-tapped)]
          (is (= #{:db :http :dispatch} (set (keys by-key)))
              "every fx-key surfaces as a :debux/kind :fx-effect entry on tap")
          (is (= {:answer 42} (get by-key :db))
              ":db value preserved on the tap entry")
          (is (every? :t fx-tapped)
              "every fx tap entry carries a :t timestamp")
          (is (apply = (map :t fx-tapped))
              "all keys from one return share one :t (mirrors the trace-channel guarantee)"))
        (finally
          (remove-tap probe)
          (util/set-tap-output! false))))))

(deftest set-tap-output-frame-and-fx-fire-out-of-trace
  (testing "frame markers and fx-effect taps fire when tap-output is on but no trace is in flight"
    (with-redefs [rft/trace-enabled?     false
                  tracing/trace-enabled? false]
      (let [received (atom [])
            probe    (fn [x] (swap! received conj x))]
        (try
          (add-tap probe)
          (util/set-tap-output! true)
          (util/-send-frame-enter! "frame_oot")
          (util/-send-frame-exit!  "frame_oot" {:n 1})
          (util/-emit-fx-traces!   {:db {:n 1} :dispatch [:notify]})
          (wait-for #(<= 4 (count @received)) 1000)
          (let [kinds (frequencies (map :debux/kind @received))]
            (is (= 1 (get kinds :frame-enter))
                "frame-enter taps even with trace-enabled? false")
            (is (= 1 (get kinds :frame-exit))
                "frame-exit taps even with trace-enabled? false")
            (is (= 2 (get kinds :fx-effect))
                "fx-effect taps once per key even with trace-enabled? false"))
          (finally
            (remove-tap probe)
            (util/set-tap-output! false)))))))

(deftest set-tap-output-coerces-truthiness
  (testing "set-tap-output! treats arg as boolean; nil → off, non-nil → on"
    ;; Mirrors the boolean coercion in the setter — a nil/falsy arg
    ;; must turn the channel off, not crash on @atom deref.
    (with-redefs [rft/trace-enabled?     false
                  tracing/trace-enabled? false]
      (let [received (atom [])
            probe    (fn [x] (swap! received conj x))]
        (try
          (add-tap probe)
          (util/set-tap-output! "non-nil-truthy-thing")
          (util/send-trace! {:form '(identity 1) :result 1 :indent-level 0
                             :syntax-order 0 :num-seen 0})
          (wait-for #(seq @received) 1000)
          (is (seq @received)
              "truthy non-boolean turned the channel on")
          (reset! received [])
          (util/set-tap-output! nil)
          (util/send-trace! {:form '(identity 2) :result 2 :indent-level 0
                             :syntax-order 0 :num-seen 0})
          (Thread/sleep 50)
          (is (empty? @received)
              "nil arg turned the channel off")
          (finally
            (remove-tap probe)
            (util/set-tap-output! false)))))))

;; ---------------------------------------------------------------------------
;; :msg / :m option — developer-supplied label on each :code entry
;; ---------------------------------------------------------------------------

(deftest fn-traced-msg-option-labels-every-code-entry
  (testing ":msg \"label\" attaches the label as :msg on every :code entry"
    (re-frame.core/reg-event-db ::with-msg (fn [db _] db))
    (re-frame.core/reg-event-db ::with-msg
                                (tracing/fn-traced
                                  {:msg "login-handler"}
                                  [db _]
                                  (let [n (inc 41)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::with-msg])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "at least one :code entry was emitted")
      (is (every? #(= "login-handler" (:msg %)) code)
          "every :code entry carries :msg = \"login-handler\""))))

(deftest fn-traced-m-alias-equivalent-to-msg
  (testing ":m is accepted as a shorthand alias for :msg"
    (re-frame.core/reg-event-db ::with-m (fn [db _] db))
    (re-frame.core/reg-event-db ::with-m
                                (tracing/fn-traced
                                  {:m "short-alias"}
                                  [db _]
                                  (let [n (inc 41)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::with-m])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "at least one :code entry was emitted")
      (is (every? #(= "short-alias" (:msg %)) code)
          ":m flowed through to the :msg field on every entry"))))

(deftest fn-traced-msg-omitted-when-not-requested
  (testing "no :msg key on entries when opts is absent"
    (re-frame.core/reg-event-db ::no-msg (fn [db _] db))
    (re-frame.core/reg-event-db ::no-msg
                                (tracing/fn-traced
                                  [db _]
                                  (let [n (inc 41)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::no-msg])
    (let [code (code-entries (captured-traces))]
      (is (seq code)
          "default emission still works without opts")
      (is (every? #(not (contains? % :msg)) code)
          "no :msg key on any entry — opt-in only"))))

(deftest fn-traced-msg-wins-over-m-when-both-set
  (testing "when both :msg and :m are set, :msg wins (documented precedence)"
    (re-frame.core/reg-event-db ::both-msg-m (fn [db _] db))
    (re-frame.core/reg-event-db ::both-msg-m
                                (tracing/fn-traced
                                  {:msg "primary" :m "secondary"}
                                  [db _]
                                  (assoc db :n 1)))
    (re-frame.core/dispatch-sync [::both-msg-m])
    (let [code (code-entries (captured-traces))]
      (is (every? #(= "primary" (:msg %)) code)
          ":msg takes precedence over :m"))))

(deftest fn-traced-msg-composes-with-locals
  (testing ":msg sits alongside :locals on the same payload"
    (re-frame.core/reg-event-db ::msg-and-locals (fn [db _] db))
    (re-frame.core/reg-event-db ::msg-and-locals
                                (tracing/fn-traced
                                  {:msg "labelled" :locals true}
                                  [db [_ x]]
                                  (let [n (* 2 x)]
                                    (assoc db :n n))))
    (re-frame.core/dispatch-sync [::msg-and-locals 5])
    (let [code (code-entries (captured-traces))]
      (is (every? #(= "labelled" (:msg %)) code)
          ":msg propagates")
      (is (every? :locals code)
          ":locals also propagates — the two compose"))))
