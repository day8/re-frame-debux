(ns debux.dbgn-test
  (:require [clojure.test :refer :all]
            [debux.core :refer [dbgn]]
            [debux.dbgn :as dbgn]))


;; Works in Cursive, fails with lein test
;; See https://github.com/technomancy/leiningen/issues/912
(deftest skip-outer-skip-inner-test
  (is (= (macroexpand-1 '(dbgn/mini-dbgn
                           (-> '())))
         '(clojure.core/let []
            (debux.common.util/spy-first
              (quote ())
              (quote (quote ())))))))


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
