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
  (contains? #{'day8.re-frame.debux.common.macro-specs/skip-outer
               'day8.re-frame.debux.common.macro-specs/skip
               'day8.re-frame.debux.common.macro-specs/o-skip
               :day8.re-frame.debux.common.macro-specs/skip-place}
             sym))

(defn debux-left-behind [forms]
  (into #{}
        (comp
          (mapcat ut/form-tree-seq)
          (filter symbol?)
          (filter debux-form?))
        forms))

(deftest tricky-dbgn-test
  (let [f '(let [res (-> [1 2 3 4 5]
                       (->> (map (fn [val] (condp = val
                                            3 33
                                            100 100
                                            5 55
                                            val))))
                       vec)]
                 (assoc res 1 -1))]
    (is (= [1 -1 33 4 55]
           (eval `(dbgn ~f))))
    (is (= '[(fn [val] (condp = val 3 33 100 100 5 55 val))
           [1 2 3 4 5]
           (map (fn [val] (condp = val 3 33 100 100 5 55 val)))
           (->> (map (fn [val] (condp = val 3 33 100 100 5 55 val))))
           =
           val
           val
           (condp = val 3 33 100 100 5 55 val)
           =
           val
           val
           (condp = val 3 33 100 100 5 55 val)
           =
           val
           (condp = val 3 33 100 100 5 55 val)
           =
           val
           val
           (condp = val 3 33 100 100 5 55 val)
           =
           val
           (condp = val 3 33 100 100 5 55 val)
           vec
           (->
            [1 2 3 4 5]
            (->> (map (fn [val] (condp = val 3 33 100 100 5 55 val))))
            vec)
           res
           (assoc res 1 -1)
           (let
            [res
             (->
              [1 2 3 4 5]
              (->> (map (fn [val] (condp = val 3 33 100 100 5 55 val))))
              vec)]
            (assoc res 1 -1))]
            (map :form @traces)))
    (is (= (debux-left-behind (map :form @traces))
           #{}))
    (is (= (into #{}
                (comp
                   (mapcat ut/form-tree-seq)
                   (filter symbol?)
                   (filter  debux-form?))
                @form)
           #{}))
    (is (= f @form))))

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
          [(ut/remove-d '(day8.re-frame.debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5])) 'dbgn/d)])
         #{}))

  (is (= #{}
         (debux-left-behind
          [(ut/remove-d '(day8.re-frame.debux.common.macro-specs/skip-outer (quote [1 2 3 4 5])) 'dbgn/d)])
         ))

  (is (= (debux-left-behind
          [(ut/remove-d '(map (fn [val]
                                 (let [pred__# =
                                      expr__# val]
                                  (if (pred__# 3 expr__#)
                                     33
                                     (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))))
                              (day8.re-frame.debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5]))) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
          [(ut/remove-d '(day8.re-frame.debux.common.macro-specs/skip-outer
                            (day8.re-frame.debux.common.util/spy-first (debux.common.macro-specs/skip-outer [1 2 3 4 5])
                                                         (quote [1 2 3 4 5]))) 'dbgn/d)])
         #{})))

(deftest ^:failing remove-skip-test
    (println (dbgn/remove-skip
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
                          (quote 5))))))))
    (is (= #{}
           (debux-left-behind
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
                          (quote 5)))))))]))))

(defmulti trace*
  (fn [& args]
      ; (println "TRACE" args)
      (cond
        (= 2 
           (count args))  :trace
        (= java.lang.Long 
           (-> args
               second
               type))     :trace->
        :else :trace->>)))

(defmethod trace* :trace
  [_ f]
  f)

(defmethod trace* :trace->
  [f _ form]
  `(-> ~f ~form))

(defmethod trace* :trace->>
  [_ form f]
  `(->> ~f ~form))

(defmacro trace 
  [& args]
  (apply trace* args))

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

(deftest doc-thread-first-test
    (let [f  '(-> 5 inc)
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(-> 5 inc)))
        (is (= f2 '(day8.re-frame.debux.core-test/trace 0
                      (-> 5 (day8.re-frame.debux.core-test/trace 2 inc)))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace 0
                      (-> 5 (day8.re-frame.debux.core-test/trace 2 inc)))))
        (println f3)
        (println (macroexpand f3))
        (is (= (eval f3)
               (eval f)))))

(deftest doc-thread-last-test
    (let [f  '(->> 5 inc)
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(->> 5 inc)))
        (is (= f2 '(day8.re-frame.debux.core-test/trace 0
                    (->> 5 (day8.re-frame.debux.core-test/trace 2 inc)))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace 0
                    (->> 5 (day8.re-frame.debux.core-test/trace 2 inc)))))
        (is (= (eval f3)
               (eval f)))))

           
(deftest doc-cond->test
    (let [f  '(cond-> 5 
                      true inc
                      false dec)
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 f))
        (is (= f2 '(day8.re-frame.debux.core-test/trace
                       0
                       (cond->
                        5
                        true
                        (day8.re-frame.debux.core-test/trace 2 inc)
                        false
                        (day8.re-frame.debux.core-test/trace 2 dec)))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace
                       0
                       (cond->
                        5
                        true
                        (day8.re-frame.debux.core-test/trace 2 inc)
                        false
                        (day8.re-frame.debux.core-test/trace 2 dec)))))
        (is (= (eval f3)
               6))))
             
(deftest doc-cond->>test
    (let [f  '(cond->> 5 
                       true (+ 1 ,)
                       false (- 1 ,))
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)]
        (is (= f1 '(cond->>
                     5
                     true (+ 1 :day8.re-frame.debux.common.macro-specs/skip-place)
                     false (- 1 :day8.re-frame.debux.common.macro-specs/skip-place))))
        (is (= f2 '(day8.re-frame.debux.core-test/trace
                       0
                       (cond->>
                        5
                        true
                        (day8.re-frame.debux.core-test/trace 2 (+ 1 :day8.re-frame.debux.common.macro-specs/skip-place))
                        false
                        (day8.re-frame.debux.core-test/trace 2 (- 1 :day8.re-frame.debux.common.macro-specs/skip-place))))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace
                       0
                       (cond->>
                        5
                        true
                        (day8.re-frame.debux.core-test/trace 2 (+ 1))
                        false
                        (day8.re-frame.debux.core-test/trace 2 (- 1))))))
        (is (= (eval f3)
               6))))
           
(deftest some->test
    (let [f  '(every? some?
                  [(some->> {:y 3 :x 5} (:y) (- 2))
                   (some->> {:y 3 :x 5} (:z) (- 2))])
          f1 `(dbgn ~f)]
        (is (= (eval f1)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))

(deftest some->>test
    (let [f  '(every? some?
                  [(some-> {:a 1} :a inc)
                   (some-> {:a 1} :b inc)])
          f1 `(dbgn ~f)]
        (is (= (eval f1)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))

(deftest as->test
    (let [f  '(as-> 0 n
                        (inc n)
                        (inc n))
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (is (= f1 
               '(as-> 0 (day8.re-frame.debux.common.macro-specs/skip n)
                  (inc n)
                  (inc n))))
        (is (= f2 
               '(day8.re-frame.debux.core-test/trace 0
                 (as-> 0 (day8.re-frame.debux.common.macro-specs/skip n)
                  (day8.re-frame.debux.core-test/trace 2 
                    (inc (day8.re-frame.debux.core-test/trace 4 n)))
                  (day8.re-frame.debux.core-test/trace 2 
                    (inc (day8.re-frame.debux.core-test/trace 4 n)))))))
        (is (= f3 
               '(day8.re-frame.debux.core-test/trace 0
                 (as-> 0 n
                  (day8.re-frame.debux.core-test/trace 2
                   (inc (day8.re-frame.debux.core-test/trace 4 n)))
                  (day8.re-frame.debux.core-test/trace 2
                   (inc (day8.re-frame.debux.core-test/trace 4 n)))))))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))

(deftest ->as->test
    (let [f  '(-> [10 11]
                       (conj 12)
                       (as-> xs (map - xs [3 2 1]))
                       (reverse))
          f1 (dbgn/insert-skip f {})
          f1' '(-> [10 11]
                   (conj :day8.re-frame.debux.common.macro-specs/skip-place 12)
                   (as-> :day8.re-frame.debux.common.macro-specs/skip-place (day8.re-frame.debux.common.macro-specs/skip xs)
                    (map - xs [3 2 1]))
                   (reverse :day8.re-frame.debux.common.macro-specs/skip-place))
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (is (= f1 f1'))
        (is (= f2 '(day8.re-frame.debux.core-test/trace 0
                     (->
                      (day8.re-frame.debux.core-test/trace 2 [10 11])
                      (day8.re-frame.debux.core-test/trace 2 
                       (conj :day8.re-frame.debux.common.macro-specs/skip-place 12))
                      (day8.re-frame.debux.core-test/trace 2
                       (as->
                        :day8.re-frame.debux.common.macro-specs/skip-place
                        (day8.re-frame.debux.common.macro-specs/skip xs)
                        (day8.re-frame.debux.core-test/trace 4
                         (map
                          (day8.re-frame.debux.core-test/trace 6 -)
                          (day8.re-frame.debux.core-test/trace 6 xs)
                          (day8.re-frame.debux.core-test/trace 6 [3 2 1])))))
                      (day8.re-frame.debux.core-test/trace 2 
                      (reverse :day8.re-frame.debux.common.macro-specs/skip-place))))))
        (is (= f3 '(day8.re-frame.debux.core-test/trace 0
                     (->
                      (day8.re-frame.debux.core-test/trace 2 [10 11])
                      (day8.re-frame.debux.core-test/trace 2 (conj 12))
                      (day8.re-frame.debux.core-test/trace 2
                       (as->
                        xs
                        (day8.re-frame.debux.core-test/trace 4
                         (map
                          (day8.re-frame.debux.core-test/trace 6 -)
                          (day8.re-frame.debux.core-test/trace 6 xs)
                          (day8.re-frame.debux.core-test/trace 6 [3 2 1])))))
                      (day8.re-frame.debux.core-test/trace 2 (reverse))))))
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))
               
(deftest cond->as->test
    (let [f   '(cond-> [10 11]
                true       (conj 12)
                true       (as-> xs (map - xs [3 2 1]))
                false      (reverse))
          f1  (dbgn/insert-skip f {})
          f1' '(cond-> [10 11]
                 true   (conj :day8.re-frame.debux.common.macro-specs/skip-place 12)
                 true   (as-> :day8.re-frame.debux.common.macro-specs/skip-place (day8.re-frame.debux.common.macro-specs/skip xs)
                          (map - xs [3 2 1]))
                 false  (reverse :day8.re-frame.debux.common.macro-specs/skip-place))
          f2 (dbgn/insert-trace f1' `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (is (= f1 f1'))
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))
               
(deftest everything-test
    (let [f   '(-> [10 11]
                   (cond->
                    true       (conj 12)
                    true       (as-> xs (map - xs [3 2 1]))
                    true       (->> (map inc))
                    true       (some->> (map inc))
                    false      (reverse)))
          f1  (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (println "F1" f1)
        (println "F2" f2)
        (println "F3" f3)
        (println "F4" f4)
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))
             
(deftest doto-test
    (let [f   '(doto (java.util.HashMap.)
                      (.put "a" 1)
                      (.put "b" 2)
                      (as-> x (println x)))
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (println "F1" f1)
        (println "F2" f2)
        (println "F3" f3)
        (println "F4" f4)
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))

(deftest dot-test
    (let [f   '(do (. "abc" toUpperCase)
                   (.. "abc" toUpperCase (concat "ABC")))
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (println "F1" f1)
        (println "F2" f2)
        (println "F3" f3)
        (println "F4" f4)
        (is (= (eval f3)
               (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))

(deftest  vector-test
    (let [f   '[:div {:style {:background (if true (inc 5) "blue")}} (str "Hello " "World")]
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (println "F1" f1)
        (println "F2" f2)
        (println "F3" f3)
        (println "F4" f4)
        (is (= (eval f3)
              (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))
        ))


(deftest ^:current map-test
    (let [f   '{:db (assoc {} :a (inc 5) 
                              :b (if true :t :f))}
          f1 (dbgn/insert-skip f {})
          f2 (dbgn/insert-trace f1 `trace {})
          f3 (dbgn/remove-skip f2)
          f4 `(dbgn ~f)]
        (println "F1" f1)
        (println "F2" f2)
        (println "F3" f3)
        (println "F4" f4)
        (is (= (eval f3)
              (eval f)))
        (is (= (eval f4)
               (eval f)))
        (is (= @form f))
        (is (->> @traces
                 (map (fn [f'] (->> f'
                                :form
                                pr-str)))
                 (every? #(clojure.string/includes? (pr-str f) %))))))
             