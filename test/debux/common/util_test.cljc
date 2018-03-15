(ns debux.common.util-test
  (:require [clojure.test :refer :all]
            [debux.common.util :as ut]))

(deftest with-gensyms-names-test
  (testing "auto gensym patterns"
    (is (= (vals (ut/with-gensyms-names `(let [a# 1]
                                           a#)
                                        {}))
           ["a#"]))
    (is (= (vals (ut/with-gensyms-names `(let [a# 1
                                               b# 2]
                                           b#)
                                        {}))
           ["a#" "b#"])))
  (testing "anon gensym patterns"
    (is (= (vals (ut/with-gensyms-names (gensym) {}))
           ["gensym#"])))
  (testing "named gensym patterns"
    (is (= (vals (ut/with-gensyms-names (gensym "abc") {}))
           ["abc#"])))
  (testing "anon param pattern"
    (is (= (vals (ut/with-gensyms-names '#(identity %1) {}))
           ["param1#"]))))

(deftest tidy-macroexpanded-form-test
  (is (= (ut/tidy-macroexpanded-form `(let [a# 1]
                                        a#)
                                     {})
         '(let [a# 1]
            a#)))
  (is (= (ut/tidy-macroexpanded-form '#(let [a (gensym)
                                             b %2]
                                         (gensym "def"))
                                     {})
         '(fn* [param1# param2#]
            (let [a (gensym)
                  b param2#]
              (gensym "def")))))

  (is (= (ut/tidy-macroexpanded-form '#(let [a (gensym)
                                             b %2]
                                         (gensym "def"))
                                     {})
         '(fn* [param1# param2#]
            (let [a (gensym)
                  b param2#]
              (gensym "def"))))))
