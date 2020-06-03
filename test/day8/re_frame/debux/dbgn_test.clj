(ns day8.re-frame.debux.dbgn-test
  (:require [clojure.test :refer [use-fixtures deftest is]]
            [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.debux.dbgn :as dbgn :refer [dbgn mini-dbgn]]))

(def traces (atom []))
(def form (atom nil))

(use-fixtures :each (fn [f]
                      (with-redefs [ut/send-trace! (fn [code-trace] (swap! traces conj (update code-trace :form ut/tidy-macroexpanded-form {})))
                                    ut/send-form!  (fn [traced-form] (reset! form (ut/tidy-macroexpanded-form traced-form {})))]
                        (f)
                        (reset! traces [])
                        (reset! form nil))))

;; Works in Cursive, fails with lein test
;; See https://github.com/technomancy/leiningen/issues/912
(deftest skip-outer-skip-inner-test
  (is (= (macroexpand-1 `(mini-dbgn
                           (-> '())))
         '(do
            (day8.re-frame.debux.common.util/spy-first
              (quote ())
              (quote (quote ()))
              0)))))


;; Commented out as we no longer print the traces, we need to get the traced data instead.
(deftest ->-test
  (let [f (macroexpand `(dbgn (-> '())))]
    (is (= (eval f) '()))
    (is (= [{:form '(quote ()), :indent-level 0, :result ()}]
           @traces))
    (is (= '(-> (quote ()))
           @form))))

(deftest ->-test2
  (let [f (macroexpand `(dbgn (-> {} 
                                (assoc :a 1) 
                                (get :a (identity :missing)))))]
    (is (= (eval f) 1))
    (is (= [{:form {}, :indent-level 2, :result {}}
            {:form '(assoc :a 1), :indent-level 1, :result {:a 1}}
            {:form '(identity :missing), :indent-level 1, :result :missing}
            {:form '(get :a (identity :missing)), :indent-level 0, :result 1}]
           @traces))
    (is (= '(-> {} 
                (assoc :a 1) 
                (get :a (identity :missing)))
           @form))))

;; Failing test raises an error
(deftest ^:failing cond->>-test
  (let [f (macroexpand `(dbgn (cond->> 1 
                                       true inc 
                                       false (+ 2) 
                                       (= 2 2) (* 45) 
                                       :always (+ 6))))]
    (is (= (with-out-str (eval f))
           "\ndbgn: (cond->> 1 true inc false (+ 2) (= 2 2) (* 45) :always (+ 6)) =>\n| 1 =>\n|   1\n| true =>\n|   true\n| inc =>\n|   2\n| false =>\n|   false\n| (= 2 2) =>\n|   true\n| (* 45) =>\n|   90\n| :always =>\n|   :always\n| (+ 6) =>\n|   96\n"))))

;; TODO: fix this. Failing test raises an error
(deftest ^:failing condp-test
  (let [f1 (macroexpand `(dbgn (condp some [1 2 3 4]
                                 #{0 6 7} :>> inc
                                 #{4 5 9} :>> dec
                                 #{1 2 3} :>> #(+ % 3))))
        f2 (macroexpand `(dbgn (condp = 3
                                 1 "one"
                                 2 "two"
                                 3 "three"
                                 (str "unexpected value, 3"))))
        f3 (macroexpand `(dbgn (condp = 4
                                 1 "one"
                                 2 "two"
                                 3 "three"
                                 (str "unexpected value, 4"))))
        f4 (macroexpand `(dbgn (condp = 3
                                 1 "one"
                                 2 "two"
                                 3 "three")))]
                             
    (is (= (eval f1)
           3))
    (is (= (eval f2)
           "three"))
    (is (= (eval f3)
           "unexpected value, 4"))
    (is (= (eval f4))
           "three")))

(deftest thread-first-test
    (is
      (= (macroexpand-1 '(day8.re-frame.debux.dbgn/mini-dbgn
                           (-> {:a 1}
                               (assoc :a 3))))
         '(do
           (day8.re-frame.debux.common.util/spy-first
            (assoc
             (day8.re-frame.debux.common.util/spy-first {:a 1} (quote {:a 1}) 1)
             :a
             3)
            (quote (assoc :a 3))
            0))))
          ; Old result
          ; #_'(clojure.core/let
          ;   []
          ;   (debux.dbgn/d
          ;     (debux.common.util/spy-first
          ;       (debux.dbgn/d
          ;         (assoc
          ;           (debux.dbgn/d
          ;             (debux.common.util/spy-first
          ;               (debux.dbgn/d
          ;                 {:a 1})
          ;               (quote
          ;                 {:a 1})
          ;               {}))
          ;           :a
          ;           3))
          ;       (quote
          ;         (assoc
          ;           :a
          ;           3))
          ;       {})))))



    (is
      (= (macroexpand-1 '(day8.re-frame.debux.dbgn/mini-dbgn
                           (-> {:a 1}
                               (assoc :a 3)
                               frequencies)))
         '(do
           (day8.re-frame.debux.common.util/spy-first
            (frequencies
             (day8.re-frame.debux.common.util/spy-first
              (assoc (day8.re-frame.debux.common.util/spy-first {:a 1} (quote {:a 1}) 2) :a 3)
              (quote (assoc :a 3))
              1))
            (quote frequencies)
            0))))
          ;  Old result
        ; '(clojure.core/let
        ;     []
        ;     (debux.dbgn/d
        ;       (debux.common.util/spy-first
        ;         (debux.dbgn/d
        ;           (frequencies
        ;             (debux.dbgn/d
        ;               (debux.common.util/spy-first
        ;                 (debux.dbgn/d
        ;                   (assoc
        ;                     (debux.dbgn/d
        ;                       (debux.common.util/spy-first
        ;                         (debux.dbgn/d
        ;                           {:a 1})
        ;                         (quote
        ;                           {:a 1})
        ;                         {}))
        ;                     :a
        ;                     3))
        ;                 (quote
        ;                   (assoc
        ;                     :a
        ;                     3))
        ;                 {}))))
        ;         (quote
        ;           frequencies)
        ;         {})))))
    )
