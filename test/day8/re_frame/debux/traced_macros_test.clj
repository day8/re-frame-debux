(ns day8.re-frame.debux.traced-macros-test
  (:require [clojure.test :refer [deftest is]]
            [day8.re-frame.debux.core :refer [defn-traced fn-traced]]))

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

;; TODO: we don't currently support trailing attr maps
;; I think the spec needs to be tweaked to conform it correctly?
(deftest ^:failing defn-traced-trailing-attr
    (is (= (-> '(day8.re-frame.debux.core/defn-traced 
                  trailing-attr
                    ([] 0)
                    {:doc "test"})
                m-expand-eval
                meta
                :doc)
           "test")))

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
