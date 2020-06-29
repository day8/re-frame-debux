(ns day8.re-frame.debux.indenting-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.zip :as z]
            [day8.re-frame.debux.dbgn :refer [dbgn real-depth]]
            [day8.re-frame.debux.common.util :as ut]))

(def traces (atom []))
(def form (atom nil))

(defn next-not-end?
  "Returns the next element in the zipper until
  you reach the end when it returns nil."
  [loc]
  (when-not (z/end? loc)
    (z/next loc)))

(defn depth-of-all-forms [form]
  (->> (ut/sequential-zip form)
       (iterate next-not-end?)
       (take-while some?)
       (keep real-depth)))

(deftest real-depth-test
  (is (= (real-depth nil) -1)))

(deftest form-depth-test
  (is (= (depth-of-all-forms '(+ 1 (+ 2)))
         '(0 1 1 1 2 2 0)))
  (is (= (depth-of-all-forms '(+ 1 (+ 2 (+ 3))))
         '(0 1 1 1 2 2 2 3 3 0)))
  (is (= (depth-of-all-forms '(day8.re-frame.debux.common.macro-specs/skip-outer
                                (day8.re-frame.debux.common.util/spy-first
                                  (day8.re-frame.debux.common.macro-specs/skip-outer
                                    (+ (day8.re-frame.debux.common.macro-specs/skip-outer
                                         (day8.re-frame.debux.common.util/spy-first
                                           (day8.re-frame.debux.common.macro-specs/skip-outer 1)
                                           (day8.re-frame.debux.common.macro-specs/skip (quote 1))))
                                       2
                                       (+ 3)))
                                  (day8.re-frame.debux.common.macro-specs/skip
                                    (quote (+ 2 (+ 3)))))))
         '(0 0 0 0 1 1 1 1 1 1 1 2 2 1 1 2 2 0 0 1 1 2 2 2 3 3))))

(use-fixtures :each (fn [f]
                      (with-redefs [ut/send-trace! (fn [code-trace] (swap! traces conj (update code-trace :form ut/tidy-macroexpanded-form {})))
                                    ut/send-form!  (fn [traced-form] (reset! form (ut/tidy-macroexpanded-form traced-form {})))]
                        (f)
                        (reset! traces [])
                        (reset! form nil))))

(deftest indent-test
  (let [f `(dbgn (+ 1 (* 2 3)))]
    (is (= (eval f) 7))
    (is (= '[{:form (* 2 3)
              :indent-level 1
              :result 6}
             {:form (+ 1 (* 2 3))
              :indent-level 0
              :result 7}] 
          @traces))))

(deftest indent-test2
  (let [f `(dbgn (+ 1 (* 2 3) (+ 4 5)))]
    (eval f)
    (is (= '[{:form (* 2 3)
              :indent-level 1
              :result 6}
             {:form (+ 4 5)
              :indent-level 1
              :result 9}
             {:form (+ 1 (* 2 3) (+ 4 5))
              :indent-level 0
              :result 16}] 
          @traces))))

(deftest indent-test3
  (let [f `(dbgn (-> 1
                    (+ 2 (+ 3))
                    (+ 5)))]
    (eval f)
    (is (= [ {:form '(+ 3) :indent-level 2 :result 3}
             {:form '(+ 2 (+ 3)) :indent-level 1 :result 6}
             {:form '(+ 5) :indent-level 1 :result 11}
             {:form '(-> 1 (+ 2 (+ 3)) (+ 5)), :indent-level 0, :result 11}] 
          @traces))))

(deftest indent-test3-macroexpanded
  (let [f `(dbgn (+ (+ 1 2 (+ 3)) 5))]
    (eval f)
    (is (= @traces
           '[{:form (+ 3)
              :indent-level 2
              :result 3}
             {:form (+ 1 2 (+ 3))
              :indent-level 1
             :result 6}
             {:form (+ (+ 1 2 (+ 3)) 5)
              :indent-level 0
              :result 11}]))))

(deftest indent-test4
  (let [f `(dbgn (+ 1 (* 2 (* 7 1)) (+ 4 5)))]
    (eval f)
    (is (= '[{:form (* 7 1)
              :indent-level 2
              :result 7}
             {:form (* 2 (* 7 1))
              :indent-level 1
              :result 14}
             {:form (+ 4 5)
              :indent-level 1
              :result 9}
             {:form (+ 1 (* 2 (* 7 1)) (+ 4 5))
              :indent-level 0
              :result 24}] 
          @traces))))

(deftest indent-test5
  (let [f `(dbgn (-> 1 (+ 2 (+ 3))))]
    (eval f)
    (is (= [{:form '(+ 3) :indent-level 2 :result 3}
            {:form '(+ 2 (+ 3)) :indent-level 1 :result 6}
            {:form '(-> 1 (+ 2 (+ 3))), :indent-level 0, :result 6}]
            @traces
           ))))
