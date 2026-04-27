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

(defn- dbgn-forms-locals-binding
  [opts-form]
  (let [[_ bindings] (macroexpand-1
                       (list 'day8.re-frame.debux.dbgn/dbgn-forms
                             '((inc x))
                             '((day8.re-frame.tracing/fn-traced [x] (inc x)))
                             '[x]
                             opts-form))]
    (->> (partition 2 bindings)
         (some (fn [[binding value]]
                 (when (= "+debux-dbg-locals+" (name binding))
                   value))))))

(deftest dbgn-forms-skips-locals-vector-when-not-requested
  (is (nil? (dbgn-forms-locals-binding nil))
      "literal nil opts do not build the locals vector")
  (is (nil? (dbgn-forms-locals-binding '{:locals false}))
      "literal false :locals does not build the locals vector")
  (let [locals-form (dbgn-forms-locals-binding '{:locals true})]
    (is (= 1 (count locals-form))
        "truthy literal :locals keeps the per-arg locals vector")
    (is (= '(quote x) (-> locals-form first first)))
    (is (= 'x (-> locals-form first second))))
  (is (some? (dbgn-forms-locals-binding 'runtime-opts))
      "unknown opts fall back to the previous locals-building shape"))

;; Works in Cursive, fails with lein test
;; See https://github.com/technomancy/leiningen/issues/912
(deftest skip-outer-skip-inner-test
  ;; mini-dbgn binds +debux-dbg-opts+ / +debux-dbg-locals+ so trace*'s
  ;; emit references resolve, plus +debux-trace-id+ for the :once
  ;; dedup gate (mini-dbgn uses the fixed string "mini-dbgn" instead
  ;; of a gensym so this shape assertion stays byte-stable).
  ;;
  ;; The inner (quote ()) gets its own trace because `quote` is now
  ;; :skip-all-args-type — emit one trace for the whole form with no
  ;; descent into the args.
  (is (= (macroexpand-1 `(mini-dbgn
                           (-> '())))
         '(clojure.core/let [+debux-dbg-opts+ nil
                             +debux-dbg-locals+ []
                             +debux-trace-id+ "mini-dbgn"]
            (day8.re-frame.debux.dbgn/trace {:day8.re-frame.debux.dbgn/indent 0
                                             :day8.re-frame.debux.dbgn/num-seen 1
                                             :day8.re-frame.debux.dbgn/syntax-order 1}
                                            (clojure.core/-> (day8.re-frame.debux.dbgn/trace
                                                              {:day8.re-frame.debux.dbgn/indent 1
                                                               :day8.re-frame.debux.dbgn/num-seen 1
                                                               :day8.re-frame.debux.dbgn/syntax-order 2}
                                                              (quote ()))))))))


;; Commented out as we no longer print the traces, we need to get the traced data instead.
(deftest ->-test
  (let [f `(dbgn (-> '()))]
    (is (= (eval f) '()))
    ;; (quote ()) is :skip-all-args-type — gets one trace for the
    ;; whole form with no descent. So the trace stream is
    ;; [(quote ()) (-> ...)] rather than just [(-> ...)] as it was
    ;; when `quote` lived in :skip-form-itself-type and produced no
    ;; trace at all.
    (is (= [{:form '(quote ())
             :indent-level 1
             :num-seen 1
             :result ()
             :syntax-order 2}
            {:form '(-> (quote ()))
             :indent-level 0
             :num-seen 1
             :result ()
             :syntax-order 1}]
           @traces))
    (is (= '(-> (quote ()))
           @form))))

(deftest ->-test2
  (let [f `(dbgn (-> {}
                     (assoc :a 1)
                     (get :a (identity :missing))))]
    (is (= (eval f) 1))
    (is (= '[{:form {}, :indent-level 1, :num-seen 1, :result {}, :syntax-order 2}
             {:form (assoc :a 1)
              :indent-level 1
              :num-seen 1
              :result {:a 1}
              :syntax-order 4}
             {:form (identity :missing)
              :indent-level 2
              :num-seen 1
              :result :missing
              :syntax-order 11}
             {:form (get :a (identity :missing))
              :indent-level 1
              :num-seen 1
              :result 1
              :syntax-order 8}
             {:form (-> {} (assoc :a 1) (get :a (identity :missing)))
              :indent-level 0
              :num-seen 1
              :result 1
              :syntax-order 1}]
           @traces))
    (is (= '(-> {}
                (assoc :a 1)
                (get :a (identity :missing)))
           @form))))

(deftest cond->>-test
  (let [f  '(cond->> 1
                   true inc
                   false (+ 2)
                   (= 2 2) (* 45)
                   :always (+ 6))
        f1 `(dbgn ~f)]
    (is (= (eval f1)
           (eval f)))
    (is (= @form f))))

(deftest condp-test
  (let [f1 `(dbgn (condp some [1 2 3 4]
                                 #{0 6 7} :>> inc
                                 #{4 5 9} :>> dec
                                 #{1 2 3} :>> #(+ % 3)))
        f2 `(dbgn (condp = 3
                                 1 "one"
                                 2 "two"
                                 3 "three"
                                 (str "unexpected value, 3")))
        f3 `(dbgn (condp = 4
                                 1 "one"
                                 2 "two"
                                 3 "three"
                                 (str "unexpected value, 4")))
        f4 `(dbgn (condp = 3
                                 1 "one"
                                 2 "two"
                                 3 "three"))]

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
      ;; mini-dbgn binds +debux-dbg-opts+ / +debux-dbg-locals+ /
      ;; +debux-trace-id+ so trace*'s emit references and the :once
      ;; gate resolve.
      (= '(clojure.core/let
            [+debux-dbg-opts+ nil
             +debux-dbg-locals+ []
             +debux-trace-id+ "mini-dbgn"]
            (day8.re-frame.debux.dbgn/trace
             {:day8.re-frame.debux.dbgn/indent 0
              :day8.re-frame.debux.dbgn/num-seen 1
              :day8.re-frame.debux.dbgn/syntax-order 1}
             (->
              (day8.re-frame.debux.dbgn/trace
               {:day8.re-frame.debux.dbgn/indent 1
                :day8.re-frame.debux.dbgn/num-seen 1
                :day8.re-frame.debux.dbgn/syntax-order 2}
               {:a 1})
              (day8.re-frame.debux.dbgn/trace
               {:day8.re-frame.debux.dbgn/indent 1
                :day8.re-frame.debux.dbgn/num-seen 1
                :day8.re-frame.debux.dbgn/syntax-order 5}
               (assoc :a 3)))))
         ))
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
      ;; mini-dbgn binds +debux-dbg-opts+ / +debux-dbg-locals+ /
      ;; +debux-trace-id+ so trace*'s emit references and the :once
      ;; gate resolve.
      (= '(clojure.core/let
            [+debux-dbg-opts+ nil
             +debux-dbg-locals+ []
             +debux-trace-id+ "mini-dbgn"]
            (day8.re-frame.debux.dbgn/trace
             {:day8.re-frame.debux.dbgn/indent 0
              :day8.re-frame.debux.dbgn/num-seen 1
              :day8.re-frame.debux.dbgn/syntax-order 1}
             (->
              (day8.re-frame.debux.dbgn/trace
               {:day8.re-frame.debux.dbgn/indent 1
                :day8.re-frame.debux.dbgn/num-seen 1
                :day8.re-frame.debux.dbgn/syntax-order 2}
               {:a 1})
              (day8.re-frame.debux.dbgn/trace
               {:day8.re-frame.debux.dbgn/indent 1
                :day8.re-frame.debux.dbgn/num-seen 1
                :day8.re-frame.debux.dbgn/syntax-order 5}
               (assoc :a 3))
              (day8.re-frame.debux.dbgn/trace
               {:day8.re-frame.debux.dbgn/indent 1
                :day8.re-frame.debux.dbgn/num-seen 1
                :day8.re-frame.debux.dbgn/syntax-order 9}
               frequencies))))
          (macroexpand-1 '(day8.re-frame.debux.dbgn/mini-dbgn
                           (-> {:a 1}
                               (assoc :a 3)
                               frequencies)))))
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
