(ns day8.re-frame.debux.core-test
  (:require [clojure.test :refer [use-fixtures deftest is]]
            [day8.re-frame.debux.core :refer [dbgn]]
            [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.debux.dbgn :as dbgn]
            [re-frame.trace]
            [zprint.core]))

(def traces (atom []))
(def form (atom nil))

(use-fixtures :each (fn [f]
                      (with-redefs [ut/send-trace! (fn [code-trace] (swap! traces conj (update code-trace :form ut/tidy-macroexpanded-form {})))
                                    ut/send-form!  (fn [traced-form] (reset! form (ut/tidy-macroexpanded-form traced-form {})))]
                        (f)
                        (reset! traces [])
                        (reset! form nil))))

(deftest simple-dbgn-test
  (let [f `(dbgn (inc 1))]
      (is (= (eval f) 2))
      (is (= @traces
             [{:form '(inc 1) :indent-level 0 :result 2}]))
      (is (= @form
             '(inc 1)))))

(defn debux-form? [sym]
  (contains? #{'debux.common.macro-specs/skip-outer
              'debux.common.macro-specs/skip
              'debux.common.macro-specs/o-skip}
             sym))

(defn debux-left-behind [forms]
  (into #{}
        (comp
          (mapcat ut/form-tree-seq)
          (filter symbol?)
          (filter debux-form?))
        forms))

#_(deftest tricky-dbgn-test
  (is (= (dbgn (let [res (-> [1 2 3 4 5]
                             (->> (map (fn [val] (condp = val
                                                  3 33
                                                  100 100
                                                  5 55
                                                  val))))
                             vec)]
                 (assoc res 1 -1)))
         [1 -1 33 4 55]))
  (is (= (map :form @traces)
         '((fn [val]
             (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                 33
                 (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))))
            [1 2 3 4 5]
            [1 2 3 4 5]
            [1 2 3 4 5]
            (map (fn [val] (condp = val 3 33 100 100 5 55 val)))
            (map (fn [val]
                  (let [pred__# =
                         expr__# val]
                     (if (pred__# 3 expr__#)
                      33
                      (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))))
                 [1 2 3 4 5])
            (->> (map (fn [val] (condp = val 3 33 100 100 5 55 val))))
            =
            val
            3
            100
            5
            val
            (if (pred__# 5 expr__#) 55 val)
            (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))
            (if (pred__# 3 expr__#)
              33
              (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))
            (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                33
                (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))))
            =
            val
            3
            100
            5
            val
            (if (pred__# 5 expr__#) 55 val)
            (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))
            (if (pred__# 3 expr__#)
              33
              (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))
            (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                33
                (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))))
            =
            val
            3
            (if (pred__# 3 expr__#)
              33
              (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))
            (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                33
                (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))))
            =
            val
            3
            100
            5
            val
            (if (pred__# 5 expr__#) 55 val)
            (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))
            (if (pred__# 3 expr__#)
              33
              (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))
            (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                33
                (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))))
            =
            val
            3
            100
            5
            (if (pred__# 5 expr__#) 55 val)
            (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))
            (if (pred__# 3 expr__#)
              33
              (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))
            (let [pred__# =
                  expr__# val]
              (if (pred__# 3 expr__#)
                33
                (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val))))
            vec
            res
            (assoc res 1 -1)
            (let [res (vec (map
                             (fn [val]
                              (let [pred__# =
                                     expr__# val]
                                 (if (pred__# 3 expr__#)
                                  33
                                  (if (pred__# 100 expr__#)
                                     100
                                     (if (pred__# 5 expr__#) 55 val)))))
                             [1 2 3 4 5]))]
              (assoc res 1 -1)))))
  (is (= (debux-left-behind (map :form @traces))
         #{}))
  (is (= (into #{}
              (comp
                 (mapcat ut/form-tree-seq)
                 (filter symbol?)
                 (filter  debux-form?))
              @form)
         #{})))

(deftest tricky-dbgn-test2
  (let [f `(dbgn (-> [1 2 3 4 5]
                                (->> identity)))]
      (is (= (eval f)
             [1 2 3 4 5]))
      (is (= (debux-left-behind (map :form @traces))
             #{}))
      (is (= (into #{}
                  (comp
                     (mapcat ut/form-tree-seq)
                     (filter symbol?)
                     (filter debux-form?))
                  @form)
             #{}))))

(deftest remove-d-test
  (is (= (debux-left-behind
          [(ut/remove-d '(debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5])) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
          [(ut/remove-d '(debux.common.macro-specs/skip-outer (quote [1 2 3 4 5])) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
          [(ut/remove-d '(map (fn [val]
                                 (let [pred__# =
                                      expr__# val]
                                  (if (pred__# 3 expr__#)
                                     33
                                     (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))))
                              (debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5]))) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
          [(ut/remove-d '(debux.common.macro-specs/skip-outer
                            (debux.common.util/spy-first (debux.common.macro-specs/skip-outer [1 2 3 4 5])
                                                         (quote [1 2 3 4 5]))) 'dbgn/d)])
         #{})))

(deftest remove-skip-test
    (is (= (debux-left-behind
             [(dbgn/remove-skip
                '(day8.re-frame.debux.common.util/spy-first
                  (day8.re-frame.debux.common.macro-specs/skip
                     (day8.re-frame.debux.common.macro-specs/skip-outer
                      (day8.re-frame.debux.common.util/spy-first
                         (day8.re-frame.debux.common.macro-specs/skip-outer 5)
                         (quote 5))))
                     (day8.re-frame.debux.common.macro-specs/skip
                      (quote (day8.re-frame.debux.common.macro-specs/skip-outer
                         (day8.re-frame.debux.common.util/spy-first
                          (day8.re-frame.debux.common.macro-specs/skip-outer 5)
                          (quote 5)))))))])
          #{})))

(defn trace
  [_ f & [_]]
  (eval f))

(deftest doc-example-test
    (let [f1 (dbgn/insert-skip
                             '(let [a 10
                                    b (+ a 20)]
                                   (+ a b))
                             {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(let
                     (day8.re-frame.debux.common.macro-specs/o-skip
                     [(day8.re-frame.debux.common.macro-specs/skip a)
                      10
                      (day8.re-frame.debux.common.macro-specs/skip b)
                      (+ a 20)])
                     (+ a b))))
        (is (= f2 '(day8.re-frame.debux.core-test/trace
                    0
                    (let
                     (day8.re-frame.debux.common.macro-specs/o-skip
                     [(day8.re-frame.debux.common.macro-specs/skip a)
                      10
                      (day8.re-frame.debux.common.macro-specs/skip b)
                      (day8.re-frame.debux.core-test/trace 3
                          (+ (day8.re-frame.debux.core-test/trace 5 a) 20))])
                     (day8.re-frame.debux.core-test/trace 2 (+ (day8.re-frame.debux.core-test/trace 4 a) (day8.re-frame.debux.core-test/trace 4 b)))))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace
                    0
                   (let
                    [a 10
                     b (day8.re-frame.debux.core-test/trace 3 (+ (day8.re-frame.debux.core-test/trace 5 a) 20))]
                    (day8.re-frame.debux.core-test/trace 2 (+ (day8.re-frame.debux.core-test/trace 4 a) (day8.re-frame.debux.core-test/trace 4 b)))))))
        (is (= (eval f3)
               40))))

(deftest doc-cond-test
    (let [f1 (dbgn/insert-skip
                             '(cond
                                (and true false) 5
                                (and true true) (inc 5))
                             {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(cond (and true false) 5 (and true true) (inc 5))))
        (is (= f2 '(day8.re-frame.debux.core-test/trace
                     0
                     (cond
                      (day8.re-frame.debux.core-test/trace 2 (and true false))
                      5
                      (day8.re-frame.debux.core-test/trace 2 (and true true))
                      (day8.re-frame.debux.core-test/trace 2 (inc 5))))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace
                     0
                     (cond
                      (day8.re-frame.debux.core-test/trace 2 (and true false))
                      5
                      (day8.re-frame.debux.core-test/trace 2 (and true true))
                      (day8.re-frame.debux.core-test/trace 2 (inc 5))))))
        (is (= (eval f3)
               6))))

(deftest doc-condp-test
    (let [f  '(condp = 4
                (inc 2) 5
                4       (inc 5)
                10)
          f1 (with-redefs [gensym symbol]
                          (dbgn/insert-skip
                             f
                             {}))
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (is (= f1 f))
        (is (= f2 '(day8.re-frame.debux.core-test/trace
                       0
                       (condp
                        (day8.re-frame.debux.core-test/trace 2 =)
                        4
                        (day8.re-frame.debux.core-test/trace 2 (inc 2))
                        5
                        4
                        (day8.re-frame.debux.core-test/trace 2 (inc 5))
                        10))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace
                       0
                       (condp
                        (day8.re-frame.debux.core-test/trace 2 =)
                        4
                        (day8.re-frame.debux.core-test/trace 2 (inc 2))
                        5
                        4
                        (day8.re-frame.debux.core-test/trace 2 (inc 5))
                        10))))
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @traces [{:form '=, :indent-level 1, :result =}
                        {:form '(inc 2), :indent-level 1, :result 3}
                        {:form '(inc 5), :indent-level 1, :result 6}
                        {:form '(condp = 4 (inc 2) 5 4 (inc 5) 10),
                         :indent-level 0, :result 6}]))
        (is (= @form f))
             ))

(deftest ^:current doc-thread-first-test
    (let [f  '(-> 5 inc)
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(day8.re-frame.debux.common.macro-specs/skip-outer
                     (day8.re-frame.debux.common.util/spy-first
                       (day8.re-frame.debux.common.macro-specs/skip-outer
                         (inc
                           (day8.re-frame.debux.common.macro-specs/skip-outer
                             (day8.re-frame.debux.common.util/spy-first
                               (day8.re-frame.debux.common.macro-specs/skip-outer 5)
                               (day8.re-frame.debux.common.macro-specs/skip (quote 5))
                              day8.re-frame.debux.common.macro-specs/indent))))
                       (day8.re-frame.debux.common.macro-specs/skip (quote inc))
                        day8.re-frame.debux.common.macro-specs/indent))))
        (is (= f2 '(day8.re-frame.debux.common.macro-specs/skip-outer
                     (day8.re-frame.debux.common.util/spy-first
                      (day8.re-frame.debux.common.macro-specs/skip-outer
                       (inc
                        (day8.re-frame.debux.common.macro-specs/skip-outer
                         (day8.re-frame.debux.common.util/spy-first
                          (day8.re-frame.debux.common.macro-specs/skip-outer 5)
                          (day8.re-frame.debux.common.macro-specs/skip (quote 5))
                          1))))
                      (day8.re-frame.debux.common.macro-specs/skip (quote inc))
                      0))))
        (is (= f3 '(day8.re-frame.debux.common.util/spy-first
                     (inc (day8.re-frame.debux.common.util/spy-first 5 (quote 5) 1))
                     (quote inc)
                     0)))
        (is (= (eval f3)
               6))))