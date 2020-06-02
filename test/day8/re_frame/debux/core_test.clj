(ns day8.re-frame.debux.core-test
  (:require [clojure.test :refer :all]
            [day8.re-frame.debux.core :refer :all]
            [day8.re-frame.debux.dbgn :as dbgn]
            [re-frame.trace]
            [day8.re-frame.debux.common.util :as ut]
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
  (is (= (dbgn (inc 1)) 2))
  (is (= @traces
         [{:form '(inc 1) :indent-level 0 :result 2}]))
  (is (= @form
         '(inc 1))))

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

#_(deftest tricky-dbgn-test2
  (is (= (dbgn (-> [1 2 3 4 5]
                   (->> identity)))
         [1 2 3 4 5]))
  (is (= (debux-left-behind (map :form @traces))
         #{}))
  (is (= (into #{}
               (comp
                 (mapcat ut/form-tree-seq)
                 (filter symbol?)
                 (filter debux-form?))
               @form)
         #{})))

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

#_(deftest remove-skip-test
    (is (= (debux-left-behind
             [(dbgn/remove-skip
                '(debux.common.util/spy-first (debux.common.macro-specs/skip (debux.common.macro-specs/skip-outer (debux.common.util/spy-first (debux.common.macro-specs/skip-outer 5) (quote 5)))) (debux.common.macro-specs/skip (quote (debux.common.macro-specs/skip-outer (debux.common.util/spy-first (debux.common.macro-specs/skip-outer 5) (quote 5)))))))])
           #{})))

;; TODO: not working yet
#_(deftest cond->test
    (is (= (debux.dbgn/dbgn
             (-> 5
                 (cond-> false
                         true)))
           5)))
