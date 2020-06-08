(ns day8.re-frame.debux.common.skip
  (:require [clojure.zip :as z]
    #?(:clj
            [clojure.spec.alpha :as s]
       :cljs [cljs.spec.alpha :as s])
            [day8.re-frame.debux.macro-types :as mt]
            [day8.re-frame.debux.cs.macro-types :as cs.mt]
            [day8.re-frame.debux.common.macro-specs :as ms]
            [day8.re-frame.debux.common.util :as ut]))

(defn- macro-types [env]
  (if (ut/cljs-env? env)
    @cs.mt/macro-types*
    @mt/macro-types*))

;;; :def-type
(defn insert-skip-in-def [form]
  (->> (s/conform ::ms/def-args (next form))
       (s/unform ::ms/def-args)
       (cons (first form))))


;;; :defn-type
(defn- insert-indent-info
  "Inserts dbg-count in front of form."
  [form]
  `((ms/skip let) (ms/skip [~'+debux-dbg-opts+ ~'+debux-dbg-opts+])
     ((ms/skip try)
       (ms/skip (swap! ut/indent-level* inc))
       (ms/skip (ut/insert-blank-line))
       ~@form
       (ms/skip (catch Exception ~'e (throw ~'e)))
       (ms/skip (finally (swap! ut/indent-level* dec))))))

(defn- insert-indent-info-in-defn-body [arity]
  (let [body  (get-in arity [:body 1])
        body' (insert-indent-info body)]
    (assoc-in arity [:body 1] [body'])))

(defn insert-skip-in-defn [form]
  (let [name    (first form)
        conf    (s/conform ::ms/defn-args (next form))
        arity-1 (get-in conf [:bs 1])
        arity-n (get-in conf [:bs 1 :bodies])]
    (->> (cond
           arity-n (assoc-in conf [:bs 1 :bodies] (mapv insert-indent-info-in-defn-body
                                                        arity-n))
           arity-1 (assoc-in conf [:bs 1] (insert-indent-info-in-defn-body arity-1)))
         (s/unform ::ms/defn-args)
         (cons name))))


;;; :fn-type
(defn insert-skip-in-fn [form]
  (->> (s/conform ::ms/fn-args (next form))
       (s/unform ::ms/fn-args)
       (cons (first form))))


;;; :let-type
(defn- process-let-binding [[binding form]]
  [`(ms/skip ~binding) form])

(defn insert-skip-in-let
  [[name bs & body]]
  (let [bs' (->> (partition 2 bs)
                 (mapcat process-let-binding)
                 vec)]
    (list* name `(ms/o-skip ~bs') body)))


;;; :letfn-type
(defn- process-letfn-binding [[fn-name binding & body]]
  `((ms/skip ~fn-name) (ms/skip ~binding) ~@body))

(defn insert-skip-in-letfn
  [[name bindings & body]]
  (let [bindings' (-> (map process-letfn-binding bindings)
                      vec)]
    (list* name `(ms/o-skip ~bindings')
           body)))


;;; :for-type
(defn- process-for-binding [[binding form]]
  (if (keyword? binding)
    (case binding
      :let `[~binding (ms/o-skip [(ms/skip ~(first form)) ~(second form)])]
      [binding form])
    `[(ms/skip ~binding) ~form]))

(defn insert-skip-in-for
  [[name bindings & body]]
  (let [bindings' (->> (partition 2 bindings)
                       (mapcat process-for-binding)
                       vec)]
    `(~name (ms/o-skip ~bindings') ~@body)))


;;; :case-type
(defn- process-case-body [[arg1 arg2]]
  (if arg2
    `[(ms/skip ~arg1) ~arg2]
    [arg1]))

(defn insert-skip-in-case
  [[name expr & body]]
  (let [body' (->> (partition-all 2 body)
                   (mapcat process-case-body))]
    (list* name expr body')))


;;; skip-arg-*-type
(defn insert-skip-arg-1
  [[name arg1 & body]]
  (list* name `(ms/skip ~arg1) body))

(defn insert-skip-arg-2
  [[name arg1 arg2 & body]]
  (list* name arg1 `(ms/skip ~arg2) body))

(defn insert-skip-arg-1-2
  [[name arg1 arg2 & body]]
  (list* name `(ms/skip ~arg1) `(ms/skip ~arg2) body))

(defn insert-skip-arg-1-3
  [[name arg1 arg2 arg3 & body]]
  (list* name `(ms/skip ~arg1) arg2 `(ms/skip ~arg3) body))

(defn insert-skip-arg-2-3
  [[name arg1 arg2 arg3 & body]]
  (list* name arg1 `(ms/skip ~arg2) `(ms/skip ~arg3) body))


;;; :skip-form-itself-type
(defn insert-skip-form-itself
  [form]
  `(ms/skip ~form))


;;; :dot-type
(defn insert-skip-in-dot
  [[name arg1 arg2]]
  (let [arg1' (if (symbol? arg1) `(ms/skip ~arg1) arg1)]
    `(~name ~arg1' (ms/skip ~arg2))))



(defn insert-spy-first
  [[name & body]]
  (list* name (mapcat (fn [subform] [subform `ms/skip-outer `(ut/spy-first '~subform ms/indent) `ms/skip-outer])
                      body)
         #_(interpose '(ut/spy-first '(:quoted-form) {}) body)))

(defn insert-spy-last
  [[name & body]]
  (list* name (mapcat (fn [subform] [subform `ms/skip-outer `(ut/spy-last '~subform ms/indent) `ms/skip-outer])
                      body)))

(defmacro traced-some->
  "When expr is not nil, threads it into the first form (via ->),
  and when that result is not nil, through the next etc"
  {:added "1.5"}
  [expr & forms]
  (let [g     (gensym)
        steps (map (fn [step] (let [fg (macroexpand-1 `(-> (ms/skip ~g) ~step (ms/skip-outer) (ut/spy-first '~step ms/indent) (ms/skip-outer)))]
                                `(ms/skip-outer (if (ms/skip (nil? ~g)) nil ~fg))))
                   forms)]
    `(ms/skip-outer
       (let [~g (ms/skip-outer (ut/spy-first (ms/skip ~expr) '~expr ms/indent))
             ~@(interleave (repeat g) (butlast steps))]
         (ms/skip-outer
           ~(if (empty? steps)
              g
              (last steps)))))))

(defn skip-some-> [[name & body]]
  `(traced-some-> ~@body))

(defmacro traced-some->>
  "When expr is not nil, threads it into the first form (via ->>),
  and when that result is not nil, through the next etc"
  {:added "1.5"}
  [expr & forms]
  (let [g     (gensym)
        steps (map (fn [step] (let [fg (macroexpand-1 `(->> (ms/skip ~g) ~step (ms/skip-outer) (ut/spy-last '~step ms/indent) (ms/skip-outer)))]
                                `(ms/skip-outer (if (ms/skip (nil? ~g)) nil ~fg))))
                   forms)]
    `(ms/skip-outer
       (let [~g (ms/skip-outer (ut/spy-last '~expr ms/indent (ms/skip ~expr)))
             ~@(interleave (repeat g) (butlast steps))]
         (ms/skip-outer
           ~(if (empty? steps)
              g
              (last steps)))))))

(defn skip-some->> [[name & body]]
  `(traced-some->> ~@body))

(defmacro traced-cond->
  "Takes an expression and a set of test/form pairs. Threads expr (via ->)
  through each form for which the corresponding test
  expression is true. Note that, unlike cond branching, cond-> threading does
  not short circuit after the first true test expression."
  {:added "1.5"}
  [expr & clauses]
  (assert (even? (count clauses)))
  (let [g     (gensym)
        steps (map (fn [[test step]]
                     (let [fg (macroexpand-1 `(-> (ms/skip ~g) ~step (ms/skip-outer) (ut/spy-first '~step ms/indent) (ms/skip-outer)))]
                       `(ms/skip-outer
                          (if (ms/skip (ut/spy-first ~test '~test ms/indent))
                            ~fg
                            (ms/skip ~g)))))
                   (partition 2 clauses))]
    `(ms/skip-outer
       (let [~g (ms/skip-outer (ut/spy-first (ms/skip ~expr) '~expr ms/indent))
             ~@(interleave (repeat g) (butlast steps))]
         ~(if (empty? steps)
            g                                               ;; TODO: add a skip around this g too.
            (last steps))))))

(defn skip-cond->
  [[name & body]]
  `(traced-cond-> ~@body))

(defmacro traced-cond->>
  "Takes an expression and a set of test/form pairs. Threads expr (via ->>)
  through each form for which the corresponding test expression
  is true.  Note that, unlike cond branching, cond->> threading does not short circuit
  after the first true test expression."
  {:added "1.5"}
  [expr & clauses]
  (assert (even? (count clauses)))
  (let [g     (gensym)
        steps (map (fn [[test step]]
                     (let [fg (macroexpand-1 `(->> (ms/skip ~g) ~step (ms/skip-outer) (ut/spy-last '~step ms/indent) (ms/skip-outer)))]
                       `(ms/skip-outer
                          (if (ms/skip (ut/spy-last '~test ms/indent ~test))
                            ~fg
                            (ms/skip ~g)))))
                   (partition 2 clauses))]
    `(ms/skip-outer
       (let [~g (ms/skip-outer (ut/spy-last '~expr ms/indent (ms/skip ~expr)))
             ~@(interleave (repeat g) (butlast steps))]
         ~(if (empty? steps)
            g
            (last steps))))))

(defn skip-cond->>
  [[name & body]]
  `(traced-cond->> ~@body))

(defmacro core-condp
  "Takes a binary predicate, an expression, and a set of clauses.
  Each clause can take the form of either:

  test-expr result-expr

  test-expr :>> result-fn

  Note :>> is an ordinary keyword.

  For each clause, (pred test-expr expr) is evaluated. If it returns
  logical true, the clause is a match. If a binary clause matches, the
  result-expr is returned, if a ternary clause matches, its result-fn,
  which must be a unary function, is called with the result of the
  predicate as its argument, the result of that call being the return
  value of condp. A single default expression can follow the clauses,
  and its value will be returned if no clause matches. If no default
  expression is provided and no clause matches, an
  IllegalArgumentException is thrown."
  {:added "1.0"}

  [pred expr & clauses]
  (let [gpred (gensym "pred__")
        gexpr (gensym "expr__")
        emit  (fn emit [pred expr args]
                (let [[[a b c :as clause] more]
                      (split-at (if (= :>> (second args)) 3 2) args)
                      n (count clause)]
                  (cond
                    (= 0 n) `(throw (IllegalArgumentException. (str "No matching clause: " ~expr)))
                    (= 1 n) a
                    (= 2 n) `(if (~pred ~a ~expr)
                               ~b
                               ~(emit pred expr more))
                    :else `(if-let [p# (~pred ~a ~expr)]
                             (~c p#)
                             ~(emit pred expr more)))))]
     `(let [~gpred ~pred
            ~gexpr ~expr]
      ~(emit gpred gexpr clauses))))

(defmacro traced-condp
  "Copied from Clojure and modified"
  ;; N.B. This traced-condp could be cleaned up a little bit further, but it's so
  ;; infrequently used (I think), that this is probably good enough, although Patches Welcome!
  {:added "1.0"}
  [pred expr & clauses]
  (let [gpred (gensym "pred__")
        gexpr (gensym "expr__")
        emit  (fn emit [pred expr args]
                (let [[[a b c :as clause] more]
                      (split-at (if (= :>> (second args)) 3 2) args)
                      n (count clause)]
                  (cond
                    (= 0 n) `(throw (IllegalArgumentException. (str "No matching clause: " ~expr)))
                    (= 1 n) a
                    (= 2 n) `(if (ms/skip (ut/spy-first (~pred ~a ~expr) '~a ms/indent))
                               ~b
                               ~(emit pred expr more))
                    :else `(ms/skip-outer
                             (if-let [p# (ms/skip (ut/spy-first (~pred ~a ~expr) '~a ms/indent))]
                               (ms/skip (ut/spy-first (~c p#) '~c ms/indent))
                               ~(emit pred expr more))))))]
    `(let [~gpred ~pred
           ~gexpr ~expr]
      ~(emit gpred gexpr clauses))))

(defn skip-condp
  [[name & body]]
  `(traced-condp ~@body))

;;; insert outermost skip
(defn insert-o-skip
  [form]
  `(ms/o-skip ~form))

(defn insert-o-skip-for-recur
  ;; TODO: add why this is needed?
  [form & [env]]
  (loop [loc     (ut/sequential-zip form)
         upwards false]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; upwards start
        (and (symbol? node)
             (= 'recur (ut/ns-symbol node env))
             (not upwards)
             (not (ut/o-skip? (-> loc z/up z/up z/down z/node))))
        (recur (-> (z/replace (z/up loc)
                              (insert-o-skip (-> loc z/up z/node)))
                   z/up)
               true)

        ;; upwards ongoing
        (and upwards
             (symbol? (first node))
             (not (ut/final-target? (ut/ns-symbol (first node) env)
                                    (:loop-type (macro-types env))
                                    env))
             (not (ut/o-skip? (-> loc z/up z/down z/node))))
        (recur (-> (z/replace loc (insert-o-skip (-> loc z/node)))
                   z/up)
               true)

        ;; upwards finish
        (and upwards
             (symbol? (first node))
             (ut/final-target? (ut/ns-symbol (first node) env)
                               (:loop-type (macro-types env))
                               env))
        (recur (z/next loc) false)

        :else (recur (z/next loc) false)))))
