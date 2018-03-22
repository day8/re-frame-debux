(ns debux.traced-macros-test
  (:require [clojure.test :refer :all]
            [debux.core :refer [defn-traced fn-traced]]))

#_(deftest defn-traced-test
  (is (var? (defn-traced f1 [] nil)))
  (is (var? (defn-traced f1 [x] x)))
  (is (var? (defn-traced f1 [[x]] x)))
  (is (var? (defn-traced f1 [x] (prn x) x)))
  (is (var? (defn-traced f1 ([x] x) ([x y] y))))
  (is (var? (defn-traced add1
              "add1 docstring"
              {:added "1.0"}
              [x y]
              (+ x y))))
  (is (var? (defn-traced add1
              "add1 docstring"
              [x y]
              (+ x y))))
  (is (var? (defn-traced add2
              "add2 docstring"
              {:added "1.0"}
              ([] 0)
              ([x] x)
              ([x y] (+ x y))
              ([x y & zs] (apply + x y zs)))))
  ;; TODO: add pre/post



  )

(deftest fn-traced-test
  (fn-traced [])
  (fn? (fn-traced [] nil))
  (fn? (fn-traced [x] x))
  (fn? (fn-traced [[x]] x))
  (fn? (fn-traced [x] (prn x) x))
  (fn? (fn-traced ([x] x) ([x y] y)))
  (fn? (fn-traced f-name [] nil))
  (fn-traced constrained-fn [f x]
    (f x))
  (fn-traced constrained-fn [f x]
    {:pre [(pos? x)]
     :post [(= % (* 2 x))]}
    (f x))

  (fn-traced const
    ([f x]
     {:pre [(pos? x)]
      :post [(= % (* 2 x))]}
     (f x))
    ([x]
     x))

  (is (thrown? AssertionError ((fn-traced const
                                 [f x]
                                 {:pre [(pos? x)]
                                  :post [(= % (* 2 x))]}
                                 (f x)) inc -1))))
