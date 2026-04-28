(ns day8.re-frame.debux.common.skip
  (:require [clojure.zip :as z]
    #?(:clj
            [clojure.spec.alpha :as s]
       :cljs [cljs.spec.alpha :as s])
            [day8.re-frame.debux.cs.macro-types :as mt]
            [day8.re-frame.debux.common.macro-specs :as ms]
            [day8.re-frame.debux.common.util :as ut]))

(defn- macro-types [env]
  @mt/macro-types*)

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


;;; :skip-all-args-type
;; Wraps the form so insert-trace emits ONE trace for the whole form
;; and does not descend into its args. The (a-skip ...) wrapper is
;; stripped in remove-skip, leaving the form un-modified at runtime.
(defn insert-skip-all-args
  [form]
  `(ms/a-skip ~form))


;;; :skip-form-itself-type
(defn insert-skip-form-itself
  [form]
  `(ms/skip ~form))


;;; :dot-type
(defn insert-skip-in-dot
  [[name arg1 arg2]]
  (let [arg1' (if (symbol? arg1) `(ms/skip ~arg1) arg1)]
    `(~name ~arg1' (ms/skip ~arg2))))

;;; :dot-type
(defn insert-skip-in-dot-dot
  [[name arg1 arg2 arg3]]
  (let [arg1' (if (symbol? arg1) `(ms/skip ~arg1) arg1)]
    `(~name ~arg1' (ms/skip ~arg2) (ms/skip ~arg3))))

;;; :thread-first-type
(defn insert-skip-thread-first
  [form]
  (let [name (first form)
        value (second form)
        new-args (map (fn[f] (if (seq? f)
                                    (with-meta 
                                      `(~(first f) ::ms/skip-place ~@(next f)) 
                                      (meta f))
                                    f)) (drop 2 form))]
    `(~name ~value ~@new-args)))

;;; :thread-last-type
(defn insert-skip-thread-last
  [form]
  (let [name (first form)
        value (second form)
        new-args (map (fn[f] (if (seq? f)
                                    (with-meta 
                                      `(~(first f) ~@(next f) ::ms/skip-place) 
                                      (meta f))
                                    f)) (drop 2 form))]
    `(~name ~value ~@new-args)))

;;; :cond-first-type
(defn insert-skip-cond-first
  [form]
  (let [name      (first form)
        value     (second form)
        clauses   (drop 2 form)
        tests     (take-nth 2 clauses)
        forms     (take-nth 2 (rest clauses))
        new-forms (map (fn[f] (if (seq? f)
                                    (with-meta 
                                      `(~(first f) ::ms/skip-place ~@(next f)) 
                                      (meta f))
                                    f)) 
                   forms)]
    `(~name ~value ~@(interleave tests new-forms))))

;;; :cond-last-type
(defn insert-skip-cond-last
  [form]
  (let [name      (first form)
        value     (second form)
        clauses   (drop 2 form)
        tests     (take-nth 2 clauses)
        forms     (take-nth 2 (rest clauses))
        new-forms (map (fn[f] (if (seq? f)
                                    (with-meta 
                                      `(~(first f) ~@(next f) ::ms/skip-place) 
                                      (meta f))
                                    f)) 
                   forms)]
    `(~name ~value ~@(interleave tests new-forms))))

(defn insert-spy-first
  [[name & body]]
  (list* name (mapcat (fn [subform] [subform `ms/skip-outer `(ut/spy-first '~subform ms/indent) `ms/skip-outer])
                      body)
         #_(interpose '(ut/spy-first '(:quoted-form) {}) body)))

(defn insert-spy-last
  [[name & body]]
  (list* name (mapcat (fn [subform] [subform `ms/skip-outer `(ut/spy-last '~subform ms/indent) `ms/skip-outer])
                      body)))


;;; insert outermost skip
(defn insert-o-skip
  [form]
  `(ms/o-skip ~form))

(defn insert-o-skip-for-recur
  "Protect `recur` forms from dbgn's normal trace insertion.

   `recur` must remain in tail position relative to its enclosing
   `loop` / fn target. The normal walker can otherwise insert trace
   calls around ancestors of a `recur`, moving the `recur` out of tail
   position and producing invalid code. This pass runs before trace
   insertion and wraps the path from a found `recur` back up to the
   enclosing loop target in `o-skip`, telling the later walker to keep
   those structural forms opaque while still allowing surrounding code
   to be traced.

   The `upwards` flag is the walker's state machine:
   - false: scan forward through the zipper looking for a `recur`.
   - true: ascend from that `recur`, wrapping each ancestor until the
     enclosing loop/fn target is reached.

   The one-up / two-up zipper checks detect whether the ancestor slot
   about to be wrapped is already an `o-skip` form. That prevents nested
   or repeated passes from wrapping the same form again."
  [form & [env]]
  (loop [loc     (ut/sequential-zip form)
         upwards false]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; Found a recur while scanning downward. Wrap its parent form,
        ;; then move upward so the ancestors back to the loop target are
        ;; protected too. The two-up/one-down path asks whether the
        ;; parent slot is already `(o-skip ...)`.
        ;; `some->` guards the z/up chain — for a `recur` at the very
        ;; top of a form (no enclosing loop/wrapper), the upward walk
        ;; runs past the root and z/down on nil would throw NPE. When
        ;; the chain short-circuits to nil, treat the slot as "nothing
        ;; to skip" and fall through to the :else branch.
        (and (symbol? node)
             (= 'recur (ut/ns-symbol node env))
             (not upwards)
             (when-let [g (some-> loc z/up z/up z/down z/node)]
               (not (ut/o-skip? g))))
        (recur (-> (z/replace (z/up loc)
                              (insert-o-skip (-> loc z/up z/node)))
                   z/up)
               true)

        ;; Still ascending from a recur. Keep wrapping ancestors until
        ;; `final-target?` says the enclosing loop/fn target has been
        ;; reached. The one-up/one-down path checks whether this node's
        ;; own slot is already wrapped.
        ;; Same nil-guard rationale as upwards start.
        (and upwards
             (symbol? (first node))
             (not (ut/final-target? (ut/ns-symbol (first node) env)
                                    (:loop-type (macro-types env))
                                    env))
             (when-let [g (some-> loc z/up z/down z/node)]
               (not (ut/o-skip? g))))
        (recur (-> (z/replace loc (insert-o-skip (-> loc z/node)))
                   z/up)
               true)

        ;; Reached the loop/fn target that owns the recur. Stop
        ;; ascending; that target is allowed to be seen by the later
        ;; walker because it defines the tail-position boundary.
        (and upwards
             (symbol? (first node))
             (ut/final-target? (ut/ns-symbol (first node) env)
                               (:loop-type (macro-types env))
                               env))
        (recur (z/next loc) false)

        :else (recur (z/next loc) false)))))
