(ns debux.traced-macros-test
  (:require [clojure.test :refer :all]
            [debux.core :refer [defn-traced fn-traced]]))

(deftest defn-traced-test
  ;; These are testing that they all compile and don't throw.
  (defn-traced f1 [] nil)
  (defn-traced f1 [x] x)
  (defn-traced f1 [[x]] x)
  (defn-traced f1 [x] (prn x) x)
  (defn-traced f1 ([x] x) ([x y] y))
  (defn-traced add1
               "add1 docstring"
               {:added "1.0"}
               [x y]
               (+ x y))
  (defn-traced add1
               "add1 docstring"
               [x y]
               (+ x y))
  (defn-traced add2
               "add2 docstring"
               {:added "1.0"}
               ([] 0)
               ([x] x)
               ([x y] (+ x y))
               ([x y & zs] (apply + x y zs))))

(deftest defn-prepost-test
  (is (thrown? AssertionError
               ((defn-traced constrained-fn [f x]
                             {:pre [(pos? x)]
                              :post [(= % (* 2 x))]}
                             (f x))
                 inc -1))))

;; TODO: we don't currently support trailing attr maps
;; I think the spec needs to be tweaked to conform it correctly?
#_(deftest defn-traced-trailing-attr
    (is (= (:doc (meta (defn-traced trailing-attr
                                    ([] 0)
                                    {:doc "test"})))
           "test")))

(deftest fn-traced-test
  ;; These are testing that they all compile and don't throw.
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
    ([x] x)))

(deftest fn-prepost-test
  (is (thrown? AssertionError ((fn-traced const
                                 [f x]
                                 {:pre [(pos? x)]
                                  :post [(= % (* 2 x))]}
                                 (f x)) inc -1))))
