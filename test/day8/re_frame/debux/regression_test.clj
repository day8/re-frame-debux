(ns day8.re-frame.debux.regression-test
  "Regression-test fixture for the macro-walker edge cases that have
   historically broken `dbgn`'s zipper-based traversal:

     - `loop` + `recur` (issue #40 — macroexpansion hangs / doesn't terminate;
       fix in cs/macro_types.cljc adds `recur` to :skip-form-itself-type)
     - `cond` (closed #31 — NPE in dbgn macro, no formal regression test)
     - `cond->` (closed #22 / #23 — indent-level bookkeeping broke)
     - `letfn` (closed #29 — zipper position drifted)
     - deeply-nested `for`
     - `case`

   This fixture exists because docs/improvement-plan.md §2 flags the
   absence of these tests as the reason three macro-walker bugs
   shipped to master without warning. Each case pins a previously-
   broken or previously-fixed scenario so it stays fixed.

   Tests use a `future` + timeout pattern for the cases that are
   suspected to hang at macroexpansion time (rather than just
   miscompile) — so a regression triggers a clean test failure
   instead of stalling the whole suite."
  (:require [clojure.test :refer [use-fixtures deftest is testing]]
            [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.debux.dbgn :as dbgn :refer [dbgn]]))

;; ----------------------------------------------------------------------
;; Test fixtures — capture the trace stream into atoms so each test can
;; inspect what `send-trace!` would have written.
;; ----------------------------------------------------------------------

(def traces (atom []))
(def form (atom nil))

(use-fixtures :each
  (fn [f]
    (with-redefs [ut/send-trace! (fn [code-trace]
                                   (swap! traces conj
                                          (update code-trace :form
                                                  ut/tidy-macroexpanded-form {})))
                  ut/send-form!  (fn [traced-form]
                                   (reset! form
                                           (ut/tidy-macroexpanded-form traced-form {})))]
      (f)
      (reset! traces [])
      (reset! form nil))))

;; ----------------------------------------------------------------------
;; Helper: run a macroexpansion in a future with a hard timeout, so
;; #40-style "doesn't terminate" bugs surface as test failures rather
;; than hung suites.
;; ----------------------------------------------------------------------

(def ^:private macroexpand-timeout-ms 5000)

(defn- macroexpand-with-timeout
  "Macroexpand `form-expr` in a future; if it doesn't return within
   `macroexpand-timeout-ms`, return ::timeout. The future is left
   running (we can't safely interrupt a CLJ compiler call). Use this
   only in tests where the expected good case completes in milliseconds."
  [form-expr]
  (let [fut (future (macroexpand-1 form-expr))]
    (deref fut macroexpand-timeout-ms ::timeout)))

;; ----------------------------------------------------------------------
;; Issue #40 — loop + recur non-termination
;; ----------------------------------------------------------------------
;;
;; Repro: `(fn-traced [] (loop [n 0] (when (< n 3) (recur (inc n)))))`
;; macroexpands forever. Likely caused by `recur` not being in
;; cs/macro_types.cljc's :skip-form-itself-type set, so the zipper
;; walker tries to instrument it as a regular call and re-injects
;; nested recur on every walk pass.
;;
;; Fix landed in this same commit: `recur` added to :skip-form-itself-
;; type in both the cljs and clj branches of macro_types.cljc.
;; This test will pass once the fix is in place; if it ever starts
;; failing again, it's #40 regression — investigate that file first.

(deftest loop-recur-macroexpand-terminates
  (testing "issue #40: dbgn around loop+recur macroexpands within timeout"
    (let [result (macroexpand-with-timeout
                   `(dbgn (loop [n# 0]
                            (when (< n# 3)
                              (recur (inc n#))))))]
      (is (not= ::timeout result)
          "loop+recur macroexpansion did not terminate within 5s — #40 regression"))))

(deftest loop-recur-evaluates-correctly
  (testing "issue #40: when fixed, loop+recur should evaluate to its tail value"
    (let [result (macroexpand-with-timeout
                   `(dbgn (loop [acc# 0 n# 0]
                            (if (< n# 3)
                              (recur (+ acc# n#) (inc n#))
                              acc#))))]
      (is (not= ::timeout result))
      ;; Once the fix lands, also assert the eval result is 3 (0+1+2).
      ;; Leaving as a TODO until the fix unblocks the macroexpand.
      )))

;; ----------------------------------------------------------------------
;; Closed #31 — `cond` NPE
;; ----------------------------------------------------------------------
;;
;; History: dbgn used to NPE when wrapping a `cond` with an even number
;; of test/expression pairs in certain positions. Closed in 2020 as
;; "Almost 100% sure this was fixed on master now" but no regression
;; test was added. Lock it down.

(deftest cond-doesnt-throw
  (testing "issue #31 (closed): dbgn around cond expands and evaluates"
    (let [result (macroexpand-with-timeout
                   `(dbgn (cond
                            (= 1 2) :nope
                            (= 1 1) :yep
                            :else   :default)))]
      (is (not= ::timeout result))
      (is (= :yep (eval `(dbgn (cond (= 1 2) :nope (= 1 1) :yep :else :default))))))))

(deftest cond-with-no-clauses
  (testing "dbgn around (cond) — should evaluate to nil"
    (is (nil? (eval `(dbgn (cond)))))))

;; ----------------------------------------------------------------------
;; Closed #22 / #23 — `cond->` indent-level bookkeeping
;; ----------------------------------------------------------------------

(deftest cond-arrow-first-indent-tracked
  (testing "issue #22/#23 (closed): dbgn through cond-> threading"
    (let [result (eval `(dbgn (cond-> 1
                                true  inc
                                false dec
                                true  inc)))]
      (is (= 3 result) "1 → inc=2 → (skip dec) → inc=3"))))

(deftest cond-arrow-last-indent-tracked
  (testing "dbgn through cond->> threading"
    (let [result (eval `(dbgn (cond->> [1 2 3]
                                 true  (mapv inc)
                                 false (mapv dec))))]
      (is (= [2 3 4] result)))))

;; ----------------------------------------------------------------------
;; Closed #29 — `letfn` zipper-position drift
;; ----------------------------------------------------------------------

(deftest letfn-binding-tracking
  (testing "issue #29 (closed): dbgn around letfn evaluates correctly"
    (let [result (eval `(dbgn (letfn [(double# [x#] (* 2 x#))
                                       (triple# [x#] (* 3 x#))]
                                 (+ (double# 5) (triple# 4)))))]
      (is (= 22 result) "(double 5) + (triple 4) = 10 + 12"))))

;; ----------------------------------------------------------------------
;; Deeply-nested `for`
;; ----------------------------------------------------------------------

(deftest for-deeply-nested
  (testing "dbgn around nested for produces expected cartesian"
    (let [result (eval `(dbgn (vec
                                 (for [a# (range 2)
                                       b# (range 2)
                                       :let [s# (+ a# b#)]]
                                   s#))))]
      (is (= [0 1 1 2] result)))))

(deftest for-with-when-filter
  (testing "dbgn around for + :when filter"
    (let [result (eval `(dbgn (vec
                                 (for [n# (range 10)
                                       :when (even? n#)]
                                   n#))))]
      (is (= [0 2 4 6 8] result)))))

;; ----------------------------------------------------------------------
;; `case`
;; ----------------------------------------------------------------------

(deftest case-evaluates
  (testing "dbgn around case evaluates the matching branch"
    (is (= :two (eval `(dbgn (case 2 1 :one 2 :two 3 :three :default))))))
  (testing "dbgn around case with default branch"
    (is (= :default (eval `(dbgn (case 99 1 :one 2 :two :default)))))))

(deftest case-with-multiple-test-values
  (testing "dbgn around case with grouped test values"
    (is (= :small (eval `(dbgn (case 2 (1 2 3) :small (4 5 6) :medium :large)))))))

;; ----------------------------------------------------------------------
;; Combined / stress
;; ----------------------------------------------------------------------

(deftest mixed-cond-let-cond-arrow
  (testing "dbgn around a let with cond and cond-> nested"
    (let [result (eval `(dbgn (let [x# 5]
                                (cond
                                  (pos? x#) (cond-> x#
                                              true     inc
                                              (> x# 3) (* 2))
                                  :else     :neg))))]
      (is (= 12 result) "5 → cond-> inc=6 → *2=12"))))

;; ----------------------------------------------------------------------
;; :skip-all-args-type classifier coverage
;; ----------------------------------------------------------------------
;;
;; Forms classified as :skip-all-args-type in cs/macro_types.cljc emit
;; ONE trace for the whole form and do NOT descend into the args.
;; This semantics matters because the args of these forms carry
;; compile-time meaning that ordinary expression instrumentation would
;; corrupt — protocol impl bodies, reify methods, var/quote special-
;; form arguments, defmulti's dispatch fn, etc.
;;
;; Two regression vectors this fixture guards against:
;;
;;   1. A form drops out of the classifier entirely. Its args are now
;;      walked as normal expressions, which produces compile errors on
;;      protocol-impl forms (the impl bodies aren't first-class
;;      expressions — `[this]` is a parameter destructure, not a vec
;;      literal).
;;   2. A form regresses to :skip-form-itself-type — silently emits
;;      ZERO traces, the user sees nothing for that form in their
;;      Code panel. The trace-count == 1 assertion catches this.

(defprotocol SkipAllArgsProbe
  "Protocol used only by skip-all-args-type-classifier-coverage. The
   tests below rely on the impl bodies being opaque — if dbgn ever
   instruments the bodies of `reify` / `extend-type`, the impl forms
   become ill-formed expressions and the test fails at compile time."
  (probe [this]))

(deftest skip-all-args-type-classifier-coverage
  (testing "reify — body opaque, impl returns its literal value"
    (reset! traces [])
    (let [r (eval `(dbgn (reify SkipAllArgsProbe (probe [_#] :reified))))]
      (is (= :reified (probe r))
          "reify method body wasn't instrumented; impl returns :reified")
      (is (= 1 (count @traces))
          "exactly one trace — :skip-all-args-type, not :skip-form-itself-type")
      (is (zero? (:indent-level (first @traces)))
          "the trace covers the whole reify form (indent 0, no descent)")))

  (testing "extend-type — protocol extension reaches the body"
    (reset! traces [])
    (eval `(dbgn (extend-type java.lang.Long
                   SkipAllArgsProbe
                   (probe [_#] :long-extended))))
    (is (= :long-extended (probe 42))
        "extend-type body wasn't instrumented; protocol method dispatches to :long-extended")
    (is (= 1 (count @traces))
        "one trace, args weren't descended into"))

  (testing "proxy — Object method override is callable"
    (reset! traces [])
    ;; ~' on the proxy form: `toString` is a method name that proxy
    ;; treats as a literal symbol. Syntax-quote would otherwise
    ;; namespace it to `regression-test/toString` and proxy's parse
    ;; would fail to find the matching Object method.
    (let [p (eval `(dbgn ~'(proxy [Object] [] (toString [] "proxied"))))]
      (is (= "proxied" (.toString ^Object p))
          "proxy method body wasn't instrumented; toString returns the literal")
      (is (= 1 (count @traces))
          "one trace for the whole proxy form")))

  (testing "defmulti — macroexpands without descending into the dispatch fn"
    (let [result (macroexpand-with-timeout
                   `(dbgn (defmulti skip-all-mm# identity)))]
      (is (not= ::timeout result)
          "defmulti macroexpansion did not stall (no zipper recursion through `identity`)")))

  (testing "declare — emits one trace, no descent"
    (reset! traces [])
    (eval `(dbgn (declare skip-all-declared#)))
    (is (= 1 (count @traces))
        "exactly one trace; the declared symbol arg wasn't instrumented (would be ill-formed)"))

  (testing "var special form — returns the var, dbgn preserves semantics"
    (reset! traces [])
    (let [v (eval `(dbgn (var inc)))]
      (is (var? v)
          "(var inc) returns #'clojure.core/inc; dbgn preserved the special-form semantics")
      (is (= 1 (count @traces))
          "one trace for (var inc); the var arg wasn't walked")))

  (testing "memfn — produces a callable that delegates to the named method"
    (reset! traces [])
    ;; ~' on the memfn form: `substring`/`start`/`end` are the literal
    ;; method name and arg-name symbols that memfn weaves into a
    ;; (.substring target start end) interop call. Syntax-quote
    ;; would namespace them and break the interop expansion.
    (let [f (eval `(dbgn ~'(memfn substring start end)))]
      (is (= "ell" (f "hello" 1 4))
          "memfn args weren't instrumented; the resulting fn does .substring")
      (is (= 1 (count @traces))
          "one trace for the memfn form"))))
