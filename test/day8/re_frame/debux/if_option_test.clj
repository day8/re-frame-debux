(ns day8.re-frame.debux.if-option-test
  "Tests for the `:if` option on `dbgn` / `tracing/dbgn`.

   Semantics: when `:if pred` is set, emit-trace-body fires a per-form
   :code emission only when `(pred result)` is truthy. The runtime
   gate is `(or (not (:if opts)) ((:if opts) r))` — nil predicate
   means 'no filter, always emit'.

   Two surfaces to cover:
     - inner map-style: `(dbgn form {:if pred})` — opts map flows
       directly into `+debux-dbg-opts+`; emit-trace-body reads `:if`
       and the predicate fires.
     - outer keyword-style: `(tracing/dbgn form :if pred)` — opts
       sequence flows through `parse-opts` first, which must produce
       `{:if pred}` for the runtime gate to see the predicate.

   Regression: an earlier parse-opts implementation renamed `:if` to
   `:condition`, so the kw-style surface silently dropped the
   predicate (every form emitted regardless). The kw-style tests
   here pin the parse-opts → emit-trace-body wiring."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.debux.common.util :as util]
            [day8.re-frame.debux.dbgn :as dbgn :refer [dbgn]]
            [day8.re-frame.tracing :as tracing]))

(defn- with-unit-capture
  "Run `body-fn` with `send-trace!` redirected to a local atom; return
   the captured vec of trace entries (post `:form` tidy)."
  [body-fn]
  (let [traces (atom [])]
    (with-redefs [util/send-trace! (fn [code-trace]
                                     (swap! traces conj
                                            (update code-trace :form
                                                    util/tidy-macroexpanded-form {})))
                  util/send-form!  (fn [_])]
      (body-fn))
    @traces))

;; ---------------------------------------------------------------------------
;; dbgn (inner) — :if via opts map gates emission
;; ---------------------------------------------------------------------------

(deftest dbgn-if-predicate-passes
  (testing ":if even? lets the outer (inc 1) -> 2 result through"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(dbgn (inc 1) {:if even?})))))]
      (is (= 2 @r))
      (is (some #(= 2 (:result %)) traces)
          "the outer form's even? result is among the emissions"))))

(deftest dbgn-if-predicate-fails
  (testing ":if even? on (inc 0) -> 1 suppresses every emission (1 is odd)"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(dbgn (inc 0) {:if even?})))))]
      (is (= 1 @r) "value transparency — :if doesn't change the result")
      (is (zero? (count traces))
          ":if even? on a result of 1 suppresses every emission"))))

;; ---------------------------------------------------------------------------
;; tracing/dbgn (outer, parse-opts path) — keyword-style :if
;; ---------------------------------------------------------------------------

(deftest tracing-dbgn-keyword-if-passes-via-parse-opts
  (testing "kw-style `(tracing/dbgn form :if even?)` with a passing predicate emits"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(tracing/dbgn (inc 1) :if even?)))))]
      (is (= 2 @r))
      (is (some #(= 2 (:result %)) traces)
          "parse-opts mapped :if → :if pred; emit-trace-body fired the gate"))))

(deftest tracing-dbgn-keyword-if-suppresses-via-parse-opts
  (testing "kw-style `(tracing/dbgn form :if even?)` with a failing predicate emits nothing"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(tracing/dbgn (inc 0) :if even?)))))]
      (is (= 1 @r))
      (is (zero? (count traces))
          "parse-opts must produce {:if even?} (not {:condition even?}) for the gate to see it"))))
