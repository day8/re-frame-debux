(ns debux.dbgn-test
  (:require [clojure.test :refer :all]
            [debux.dbgn :as dbgn :refer [dbgn mini-dbgn]]))


;; Works in Cursive, fails with lein test
;; See https://github.com/technomancy/leiningen/issues/912
#_(deftest skip-outer-skip-inner-test
  (is (= (macroexpand-1 '(mini-dbgn
                           (-> '())))
         '(do
            (debux.common.util/spy-first
              (quote ())
              (quote (quote ())))))))

(def ->-test-output-2
"
dbgn: (-> {} (assoc :a 1) (get :a (identity :missing))) =>
| {} =>
|   {}
| (assoc :a 1) =>
|   {:a 1}
| (identity :missing) =>
|   :missing
| (get :a (identity :missing)) =>
|   1
")

(deftest ->-test
  (is (= "\ndbgn: (-> (quote ())) =>\n| (quote ()) =>\n|   ()\n"
         (with-out-str (dbgn (-> '())))))
  (is (= ->-test-output-2
         (with-out-str (dbgn (-> {} (assoc :a 1) (get :a (identity :missing))))))))

(deftest cond->>-test
  (is (= (with-out-str (dbgn (cond->> 1 true inc false (+ 2) (= 2 2) (* 45) :always (+ 6))))
         "\ndbgn: (cond->> 1 true inc false (+ 2) (= 2 2) (* 45) :always (+ 6)) =>\n| 1 =>\n|   1\n| true =>\n|   true\n| inc =>\n|   2\n| false =>\n|   false\n| (= 2 2) =>\n|   true\n| (* 45) =>\n|   90\n| :always =>\n|   :always\n| (+ 6) =>\n|   96\n")))

(deftest condp-test
  (is (= (dbgn (condp some [1 2 3 4]
                 #{0 6 7} :>> inc
                 #{4 5 9} :>> dec
                 #{1 2 3} :>> #(+ % 3)))
         3))
  (is (= (dbgn (condp = 3
                 1 "one"
                 2 "two"
                 3 "three"
                 (str "unexpected value, \"" 3 \")))
         "three"))
  (is (= (dbgn (condp = 4
                 1 "one"
                 2 "two"
                 3 "three"
                 (str "unexpected value, \"" 4 \")))
         "unexpected value, \"4\""))
  (is (= (dbgn (condp = 3
                 1 "one"
                 2 "two"
                 3 "three"))
         "three")))

#_(deftest thread-first-test
    (is
      (= (macroexpand-1 '(dbgn/mini-dbgn
                           (-> {:a 1}
                               (assoc :a 3))))
         '(clojure.core/let
            []
            (debux.dbgn/d
              (debux.common.util/spy-first
                (debux.dbgn/d
                  (assoc
                    (debux.dbgn/d
                      (debux.common.util/spy-first
                        (debux.dbgn/d
                          {:a 1})
                        (quote
                          {:a 1})
                        {}))
                    :a
                    3))
                (quote
                  (assoc
                    :a
                    3))
                {})))))



    (is
      (= (macroexpand-1 '(dbgn/mini-dbgn
                           (-> {:a 1}
                               (assoc :a 3)
                               frequencies)))
         '(clojure.core/let
            []
            (debux.dbgn/d
              (debux.common.util/spy-first
                (debux.dbgn/d
                  (frequencies
                    (debux.dbgn/d
                      (debux.common.util/spy-first
                        (debux.dbgn/d
                          (assoc
                            (debux.dbgn/d
                              (debux.common.util/spy-first
                                (debux.dbgn/d
                                  {:a 1})
                                (quote
                                  {:a 1})
                                {}))
                            :a
                            3))
                        (quote
                          (assoc
                            :a
                            3))
                        {}))))
                (quote
                  frequencies)
                {})))))
    )


(deftest cond->-test
  )
