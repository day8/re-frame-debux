(ns day8.re-frame.debux.traced-macros-test
  "CLJ-only — every test below runs `(macroexpand (eval ...))` on a
   quoted defn-traced/fn-traced source form to verify the expansion
   compiles and yields the expected var-or-fn shape. CLJS lacks
   runtime `macroexpand`/`eval` (cljs.analyzer.api/macroexpand-1 is
   compile-time only), so the test pattern can't be ported as-is. The
   CLJS-side macroexpansion path is exercised indirectly via
   if_option_test.cljc and final_option_test.cljc — those embed live
   `dbgn` / `tracing/dbgn` calls in deftests, which the CLJS compiler
   expands at build time."
  (:require [clojure.test :refer [deftest is]]
            [day8.re-frame.debux.core :refer [defn-traced fn-traced]]
            [day8.re-frame.tracing]))

(comment
  (with-redefs [ut/send-form!  #(zp/czprint %)
                ut/send-trace! #(zp/czprint %)]
    ((day8.re-frame.debux.core/defn-traced* f1 [x] (-> (str "x" x)
                                         (str "5"))) "b"))
  )

(defn m-expand-eval 
  [f]
  (-> f
      macroexpand
      eval))

(deftest defn-traced-test
  ;; These are testing that they all compile and don't throw.
  (-> '(day8.re-frame.debux.core/defn-traced f1 [] nil)
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced f1 [x] x)
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced f1 [[x]] x)
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced f1 [x] (prn x) x)
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced f1 ([x] x) ([x y] y))
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced add1
                   "add1 docstring"
                   {:added "1.0"}
                   [x y]
                   (+ x y))
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced add1
                  "add1 docstring"
                  [x y]
                  (+ x y))
      m-expand-eval
      var?
      is)
  (-> '(day8.re-frame.debux.core/defn-traced add2
                  "add2 docstring"
                  {:added "1.0"}
                  ([] 0)
                  ([x] x)
                  ([x y] (+ x y))
                  ([x y & zs] (apply + x y zs)))
      m-expand-eval
      var?
      is))

(deftest defn-prepost-test
  (is (thrown? AssertionError
               ((m-expand-eval '(day8.re-frame.debux.core/defn-traced 
                                 constrained-fn [f x]
                                  {:pre [(pos? x)]
                                   :post [(= % (* 2 x))]}
                                   (f x)))
                 inc -1))))

(deftest defn-traced-trailing-attr
    (is (= (-> '(day8.re-frame.debux.core/defn-traced
                  trailing-attr
                    ([] 0)
                    {:doc "test"})
                m-expand-eval
                meta
                :doc)
           "test")))

;; ---------------------------------------------------------------------------
;; defn-traced must propagate the leading docstring + attr-map onto the var
;; ---------------------------------------------------------------------------
;;
;; Spotted while fixing rfd-543. The ::ms/defn-args spec conforms
;; :docstring and :meta but defn-traced* in core.clj / tracing.cljc
;; only read :name and :bs, silently discarding both. The
;; defn-traced-test cases above include forms with leading docstrings
;; + attr-maps but only assert (var? expansion) — they pass even
;; though :doc / :added never landed on the var. These tests close
;; that gap by reading .meta on the resulting var.

(deftest defn-traced-leading-docstring-lands-on-var
  (let [v (m-expand-eval
            '(day8.re-frame.debux.core/defn-traced f-with-doc
               "this fn has a docstring"
               [x]
               x))]
    (is (var? v))
    (is (= "this fn has a docstring" (:doc (meta v)))
        "leading docstring lands as :doc on the var")))

(deftest defn-traced-leading-attr-map-lands-on-var
  (let [v (m-expand-eval
            '(day8.re-frame.debux.core/defn-traced f-with-attr
               {:added "1.0" :tag String}
               [x]
               x))]
    (is (var? v))
    (is (= "1.0" (:added (meta v)))
        ":added from the leading attr-map is on the var meta")
    (is (= String (:tag (meta v)))
        ":tag from the leading attr-map is on the var meta")))

(deftest defn-traced-docstring-and-attr-both-land-on-var
  (let [v (m-expand-eval
            '(day8.re-frame.debux.core/defn-traced f-with-both
               "doc and attr"
               {:added "1.0"}
               [x]
               x))]
    (is (var? v))
    (is (= "doc and attr" (:doc (meta v))))
    (is (= "1.0" (:added (meta v))))))

(deftest defn-traced-docstring-with-arity-n
  (let [v (m-expand-eval
            '(day8.re-frame.debux.core/defn-traced f-multi-arity
               "multi-arity with doc"
               {:added "1.0"}
               ([] 0)
               ([x] x)
               ([x y] (+ x y))))]
    (is (var? v))
    (is (= "multi-arity with doc" (:doc (meta v)))
        "docstring survives arity-n expansion")
    (is (= "1.0" (:added (meta v)))
        "leading attr-map survives arity-n expansion")))

;; The modernized `day8.re-frame.tracing/defn-traced` (in
;; tracing.cljc) has its own defn-traced* expansion — verify the same
;; fix applies there too.

(deftest defn-traced-tracing-ns-docstring-and-attr-land-on-var
  (let [v (m-expand-eval
            '(day8.re-frame.tracing/defn-traced f-tracing-ns
               "tracing.cljc docstring"
               {:added "0.7"}
               [x]
               x))]
    (is (var? v))
    (is (= "tracing.cljc docstring" (:doc (meta v))))
    (is (= "0.7" (:added (meta v))))))

(deftest defn-traced-tracing-ns-arity-n-docstring
  (let [v (m-expand-eval
            '(day8.re-frame.tracing/defn-traced f-tracing-arity-n
               "multi-arity tracing.cljc"
               {:added "0.7"}
               ([] 0)
               ([x] x)))]
    (is (var? v))
    (is (= "multi-arity tracing.cljc" (:doc (meta v))))
    (is (= "0.7" (:added (meta v))))))

(deftest fn-traced-test
  ;; These are testing that they all compile and don't throw.
  (-> '(day8.re-frame.debux.core/fn-traced [])
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced [] nil)
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced [x] x)
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced [[x]] x)
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced [x] (prn x) x)
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced ([x] x) ([x y] y))
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced f-name [] nil)
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced constrained-fn [f x]
        (f x))
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced constrained-fn [f x]
        {:pre [(pos? x)]
         :post [(= % (* 2 x))]}
        (f x))
      m-expand-eval
      fn?
      is)
  (-> '(day8.re-frame.debux.core/fn-traced const
        ([f x]
         {:pre [(pos? x)]
          :post [(= % (* 2 x))]}
         (f x))
         ([x] x))
      m-expand-eval
      fn?
      is))

(deftest fn-prepost-test
  (is (thrown? AssertionError ((-> '(day8.re-frame.debux.core/fn-traced const
                                     [f x]
                                     {:pre [(pos? x)]
                                      :post [(= % (* 2 x))]}
                                     (f x))
                                   m-expand-eval)
                               inc -1))))
