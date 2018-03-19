(ns debux.core-test
  (:require [clojure.test :refer :all]
            [debux.core :refer :all]
            [debux.dbgn :as dbgn]
            [re-frame.trace]
            [debux.common.util :as ut]
            [zprint.core]))

(alter-var-root #'re-frame.trace/trace-enabled? (constantly true))

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
         [{:form '(inc 1) :indent-level 1 :result 2}]))
  (is (= @form
         '(inc 1))))

(defn debux-left-behind [forms]
  (into #{}
        (comp
          (mapcat ut/form-tree-seq)
          (filter symbol?)
          (filter dbgn/debux-form?))
        forms))

(deftest tricky-dbgn-test
  (is (= (dbgn (let [res (-> [1 2 3 4 5]
                             (->> (map (fn [val] (condp = val
                                                   3 33
                                                   100 100
                                                   5 55
                                                   val))))
                             vec)]
                 (assoc res 1 -1)))
         [1 -1 33 4 55]))
  (println "PPRINT")
  (zprint.core/czprint (map :form @traces))
  (println "PPRINT END")
  (is (= (debux-left-behind (map :form @traces))
         #{}))
  (is (= (into #{}
               (comp
                 (mapcat ut/form-tree-seq)
                 (filter symbol?)
                 (filter dbgn/debux-form?))
               @form)
         #{}))
  )

(deftest remove-d-test
  (is (= (debux-left-behind
           [(dbgn/remove-d '(debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5])) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
           [(dbgn/remove-d '(debux.common.macro-specs/skip-outer (quote [1 2 3 4 5])) 'dbgn/d)])
         #{}))

  (is (= (debux-left-behind
           [(dbgn/remove-d '(map (fn [val]
                                   (let [pred__# =
                                         expr__# val]
                                     (if (pred__# 3 expr__#)
                                       33
                                       (if (pred__# 100 expr__#) 100 (if (pred__# 5 expr__#) 55 val)))))
                                 (debux.common.util/spy-first [1 2 3 4 5] (quote [1 2 3 4 5]))) 'dbgn/d)])
         #{})))
