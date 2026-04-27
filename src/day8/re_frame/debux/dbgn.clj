(ns day8.re-frame.debux.dbgn
  (:require [clojure.zip :as z]
            [cljs.analyzer :as analyzer]
            [day8.re-frame.debux.common.macro-specs :as ms]
            [day8.re-frame.debux.common.skip :as sk]
            [day8.re-frame.debux.common.util :as ut :refer [remove-d]]
            [day8.re-frame.debux.cs.macro-types :as mt]
            [re-frame.trace :as trace]))

;;; Basic strategy for dbgn

;; 1. original form
;;
;; (let [a 10
;;       b (+ a 20)]
;;   (+ a b))

;; 2. after insert-skip
;;
;; (let (o-skip [(skip a) 10
;;               (skip b) (+ a 20)])
;;   (+ a b))

;; 3. after insert-trace
;;
;; (d (let (o-skip [(skip a) 10
;;                  (skip b) (d (+ (d a) 20))])
;;      (d (+ (d a) (d b)))))

;; 4. after remove-skip
;;
;; (d (let [a 10
;;          b (d (+ (d a) 20))]
;;      (d (+ (d a) (d b))))


(defn- macro-types [t]
  (t @mt/macro-types*))

;;; insert skip
(defn insert-skip
  "Marks the form to skip."
  [form env]
  ; (println "INSERT-SKIP" form env)
  ; (println "SEQ ZIP" (z/node (ut/sequential-zip form)))
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ; (ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        (and (seq? node) (symbol? (first node)))
        (let [sym (ut/ns-symbol (first node) env)]
          ;; (println "NODE" node "SYM" sym)
          (cond
            ((macro-types :def-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-def node))
                z/next
                recur)

            ((macro-types :defn-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-defn node))
                z/next
                recur)

            ((macro-types :fn-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-fn node))
                z/next
                recur)


            (or ((macro-types :let-type) sym)
                ((macro-types :loop-type) sym))
            (-> (z/replace loc (sk/insert-skip-in-let node))
                z/next
                recur)

            ((macro-types :letfn-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-letfn node))
                z/next
                recur)


            ((macro-types :for-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-for node))
                z/next
                recur)

            ((macro-types :case-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-case node))
                z/next
                recur)

            ((macro-types :thread-first-type) sym)
            (-> (z/replace loc (sk/insert-skip-thread-first node))
                z/next
                recur)

            ((macro-types :thread-last-type) sym)
            (-> (z/replace loc (sk/insert-skip-thread-last node))
                z/next
                recur)

            ((macro-types :cond-first-type) sym)
            (-> (z/replace loc (sk/insert-skip-cond-first node))
                z/next
                recur)

            ((macro-types :cond-last-type) sym)
            (-> (z/replace loc (sk/insert-skip-cond-last node))
                z/next
                recur)

            ((macro-types :skip-arg-1-type) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1 node))
                z/next
                recur)

            ((macro-types :skip-arg-2-type) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2 node))
                z/next
                recur)

            ((macro-types :skip-arg-1-2-type) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-2 node))
                z/next
                recur)

            ((macro-types :skip-arg-2-3-type) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2-3 node))
                z/next
                recur)

            ((macro-types :skip-arg-1-3-type) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-3 node))
                z/next
                recur)

            ((macro-types :skip-all-args-type) sym)
            (-> (z/replace loc (sk/insert-skip-all-args node))
                ut/right-or-next
                recur)

            ((macro-types :skip-form-itself-type) sym)
            (-> (z/replace loc (sk/insert-skip-form-itself node))
                ut/right-or-next
                recur)

            ((macro-types :dot-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot node))
                z/down z/right
                recur)

            ((macro-types :dot-dot-type) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot-dot node))
                z/down z/right
                recur)

            :else
            (recur (z/next loc))))

        :else (recur (z/next loc))))))

(defn depth
  "Calculate how far we are inside the zipper, by ascending straight up
  until we can't get any higher."
  ;;  There is probably a smarter way to do
  ;; this than checking for nil, but I'm not sure what it is.
  [loc]
  (loop [loc loc
         depth -1]
    (if (nil? loc)
      depth
      (recur (z/up loc)
             (inc depth)))))

(defn debux-symbol? [sym]
  (contains? #{'day8.re-frame.debux.dbgn/trace
               'day8.re-frame.debux.common.util/spy-first
               'day8.re-frame.debux.common.util/spy-last
               'day8.re-frame.debux.common.util/spy-comp
               'day8.re-frame.debux.common.macro-specs/skip-outer
               'day8.re-frame.debux.common.macro-specs/skip
               'day8.re-frame.debux.common.macro-specs/o-skip}
             sym))

(defn real-depth
  "Calculate how far we are inside the zipper, ignoring synthetic debux forms,
   by ascending straight up until we can't get any higher."
  ;;  There is probably a smarter way to do
  ;; this than checking for nil, but I'm not sure what it is.
  [loc]
  (try
    (if (and (sequential? (z/node loc))
             (debux-symbol? (first (z/node loc))))
      nil
      (loop [loc   loc
             depth -1]
        (if (nil? loc)
          depth
          (let [node (z/node loc)]
            (recur (z/up loc)
                   (if (and (sequential? node)
                            (debux-symbol? (first node)))
                     depth
                     (inc depth)))))))
    (catch java.lang.NullPointerException e -1)))  ;; not a zipper

(defn skip-past-trace
  "skips past added trace"
  [loc]
  (-> loc
      z/down
      z/right
      z/right
      z/down))

;;; insert/remove d
;;
;; `verbose?` (5-arity) is the :verbose / :show-all switch. Default
;; behaviour skips literal nodes (numbers, strings, booleans, keywords,
;; chars, nil) at the :else branch — they're "obvious" evaluations
;; whose traces would clutter the Code panel without adding signal.
;; When verbose? is true, those literals get wrapped in the trace
;; marker too. Special-form skips (recur, throw, var, quote, etc. —
;; the :skip-form-itself-type set in cs/macro_types.cljc) STAY
;; honoured even in verbose mode because instrumenting them
;; corrupts evaluation semantics (e.g. recur out of tail position).
(defn insert-trace
  ([form d-sym env] (insert-trace form d-sym env [] false))
  ([form d-sym env seen] (insert-trace form d-sym env seen false))
  ([form d-sym env seen verbose?]
  ; (println "INSERT-TRACE" (prn-str form))
  (loop [loc          (ut/sequential-zip form)
          indent       0
          syntax-order 0
          seen         seen]
     (let [node (z/node loc)
           seen (conj seen node)
           syntax-order (inc syntax-order)
           num-seen     (-> #{node}
                            (keep seen)
                            count)
           #_ #_ indent (real-depth loc)]
    ;;  (println "node" node syntax-order num-seen)
       (cond
         (z/end? loc) (z/root loc)

        ;;; in case of (spy-first ...) (and more to come)
        ;(and (seq? node) (= `ms/skip (first node)))
        ;(recur (-> (z/down node)
        ;           z/right
        ;           z/down))
         
        ;; TODO: is it more efficient to remove the skips here
        ;; rather than taking another pass through the form?
         
        ;; in case of (.. skip ...)
         (= ::ms/skip node)
         (recur (ut/right-or-next loc) indent syntax-order seen)

        ;; in case of (skip ...)
         (and (seq? node) (= `ms/skip (first node)) )
         (recur (ut/right-or-next loc) indent syntax-order (concat seen  (-> node
                                                                             next
                                                                             flatten)))

        ;; in case of (a-skip ...) — emit ONE trace for the whole form
        ;; with no descent into the args. Used by :skip-all-args-type
        ;; for forms whose internals carry compile-time semantics
        ;; (reify method bodies, extend-type protocol impls, condp
        ;; clause pairs, etc.). The (a-skip ~form) wrapper is stripped
        ;; in remove-skip, leaving (~d-sym {meta} ~form) at runtime.
         (and (seq? node) (= `ms/a-skip (first node)))
         (recur (-> (z/replace loc `(~d-sym {::indent ~(real-depth loc)
                                             ::syntax-order ~syntax-order
                                             ::num-seen ~num-seen} ~node))
                    ut/right-or-next)
                indent syntax-order seen)

        ;; in case of (o-skip ...)
         (and (seq? node)
              (= `ms/o-skip (first node)))
         (cond
          ;; <ex> (o-skip [(skip a) ...])
           (vector? (second node))
           (recur (-> loc z/down z/next z/down) indent syntax-order seen)

          ;; <ex> (o-skip (skip (recur ...)))
          ;; recur is wrapped in (skip ...) by
          ;; insert-skip-form-itself. The whole (o-skip (skip ...))
          ;; nest is opaque to insert-trace — wrapping recur in trace
          ;; would put it out of tail position, breaking compilation
          ;; (integration test fn-traced-survives-loop-recur).
           (and (seq? (second node))
                (= `ms/skip (first (second node))))
           (recur (ut/right-or-next loc) indent syntax-order seen)

          ;; <ex> (o-skip (if ...)) — outer o-skip wrapping the
          ;; ancestor of a recur. Navigate inside so we still trace
          ;; the surrounding form's sub-expressions.
           :else
           (recur (-> loc z/down z/next z/down ut/right-or-next) indent syntax-order seen))

        ;; TODO: handle lists that are just lists, not function calls
         

        ;; in case of (skip-outer ...)
         (and (seq? node)
              (= `ms/skip-outer (first node)))
         (let [inner-loc  (-> loc z/down z/right)
               inner-node (z/node inner-loc)]
           (cond
             (and (seq? inner-node)
                  (= `ms/skip (first inner-node)))
            ;; Recur once and let skip handle case
             (recur inner-loc indent syntax-order seen)

             (seq? inner-node)
             (recur (-> inner-loc z/down ut/right-or-next) indent syntax-order seen)

             (vector? inner-node)
             (recur (-> inner-loc z/down) indent syntax-order seen)

             :else
             (recur (-> inner-loc ut/right-or-next) indent syntax-order seen)


            ;true (throw (ex-info "Pause" {}))
            ;; vector
            ;; map
            ;; form
             
             ))


        ;; in case that the first symbol is defn/defn-
         (and (seq? node)
              (symbol? (first node))
              (`#{defn defn-} (ut/ns-symbol (first node) env)))
         (recur (-> loc z/down z/next) indent syntax-order seen)

        ;; in case of the first symbol except defn/defn-/def
         
        ;; DC: why not def? where is that handled?
         (and (seq? node) (ifn? (first node)))
         (recur (-> (z/replace loc  `(~d-sym {::indent ~(real-depth loc)
                                              ::syntax-order ~syntax-order
                                              ::num-seen ~num-seen} ~node))
                    skip-past-trace 
                    ut/right-or-next)
                (inc indent) syntax-order seen)

        ;; |[1 2 (+ 3 4)]
        ;; |(d [1 2 (+ 3 4)])
         

         (vector? node)
         (recur (-> loc
                    (z/replace `(~d-sym {::indent ~(real-depth loc)
                                         ::syntax-order ~syntax-order
                                         ::num-seen ~num-seen} ~node))
                    skip-past-trace)
                indent syntax-order seen)

         (map? node)
         (recur (-> loc 
                    (z/replace `(~d-sym {::indent ~(real-depth loc)
                                         ::syntax-order ~syntax-order
                                         ::num-seen ~num-seen} ~node))
                    skip-past-trace)
                indent syntax-order seen)

         (= node `day8.re-frame.debux.common.macro-specs/indent)
        ;; TODO: does this real-depth need an inc/dec to bring it into line with the d?
         (recur (z/replace loc (real-depth loc)) indent  syntax-order seen)

        ;; DC: We might also want to trace inside maps, especially for fx
        ;; in case of symbol, or set
         (or (symbol? node) (map? node) (set? node))
         (recur (-> (z/replace loc `(~d-sym {::indent ~(real-depth loc)
                                             ::syntax-order ~syntax-order
                                             ::num-seen ~num-seen} ~node))
                   ;; We're not zipping down inside the node further, so we don't need to add a
                   ;; second z/right like we do in the case of a vector or ifn? node above.
                    ut/right-or-next)
                indent syntax-order seen)

        ;; verbose / :show-all mode — wrap leaf literals (number,
        ;; string, boolean, keyword, char, nil) that the default
        ;; behaviour skips. Anything that's NOT a structural node
        ;; (seq / vector / map / set — handled above) is in scope.
         (and verbose?
              (or (number? node) (string? node) (boolean? node)
                  (keyword? node) (char? node) (nil? node)))
         (recur (-> (z/replace loc `(~d-sym {::indent ~(real-depth loc)
                                             ::syntax-order ~syntax-order
                                             ::num-seen ~num-seen} ~node))
                    ut/right-or-next)
                indent syntax-order seen)

         :else
         (recur (z/next loc) indent syntax-order seen))))))

(defmulti trace*
  (fn [& args]
    ;; (println "TRACE*" args)
    (cond
      (= 2 (count args))  :trace
      (and (-> args
               second
               map?)
           (-> args
               second
               (contains? ::indent)))     :trace->
      :else :trace->>)))

;; Every trace* arity reads `+debux-dbg-opts+`, `+debux-dbg-locals+`,
;; and `+debux-trace-id+` at runtime. All three are bound by the
;; surrounding (dbgn / dbgn-forms / mini-dbgn) expansion, so they're
;; always in scope by the time the emitted code runs. `:locals` adds
;; the captured arg pairs to the payload; `:if` is a runtime predicate
;; called with the per-form result, gating send-trace! emission so
;; high-frequency traces can be filtered without recompilation; `:once`
;; suppresses consecutive emissions of the same form+result pair so
;; loops or repeated dispatches don't spam the Code panel — see
;; `ut/-once-emit?` for the semantics. The `+debux-trace-id+` string
;; is the macro-expansion-site identity that lets `:once` distinguish
;; one fn-traced handler's forms from another's.

(defn- emit-trace-body
  "Emits the `(let [r ...] (when ... (send-trace! ...)) r)` shape
   shared by all three trace* arities. `bind-form` is the per-arity
   computation (`form` for :trace, `(-> f form)` for :trace->,
   `(->> f form)` for :trace->>). `org-form` is the cleaned-up
   user form for the trace payload."
  [bind-form org-form indent syntax-order num-seen]
  (let [r          (gensym "trace-result_")
        m          (gensym "trace-msg_")
        ;; `:final` suppresses every per-form emission except the
        ;; outermost (indent 0) — useful for long thread-* pipelines
        ;; where intermediate steps are noise. The depth check is
        ;; baked at expansion time: for indent=0 the literal is
        ;; `true` (always pass), for indent>0 it's `false` so the
        ;; runtime gate collapses to `(not (:final opts))` —
        ;; suppress when set, emit otherwise.
        outermost? (zero? indent)]
    `(let [~r ~bind-form
           ;; :msg is the developer-supplied label; :m is the alias.
           ;; Resolved here once so the cond-> branch reads a fixed
           ;; local instead of evaluating `or` against the opts map
           ;; twice (the value lookup AND the truthy test).
           ~m (or (:msg ~'+debux-dbg-opts+) (:m ~'+debux-dbg-opts+))]
       (when (and (or (not (:final ~'+debux-dbg-opts+))
                      ~outermost?)
                  (or (not (:if ~'+debux-dbg-opts+))
                      ((:if ~'+debux-dbg-opts+) ~r))
                  (or (not (:once ~'+debux-dbg-opts+))
                      (ut/-once-emit? ~'+debux-trace-id+ ~syntax-order ~r)))
         (ut/send-trace!
           (cond-> {:form         '~org-form
                    :result       ~r
                    :indent-level ~indent
                    :syntax-order ~syntax-order
                    :num-seen     ~num-seen}
             (:locals ~'+debux-dbg-opts+)
             (assoc :locals ~'+debux-dbg-locals+)
             ~m
             (assoc :msg ~m))))
       ~r)))

(defmethod trace* :trace
  [{::keys [indent syntax-order num-seen]} form]
  (emit-trace-body form
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen))


(defmethod trace* :trace->
  [f {::keys [indent syntax-order num-seen]} form]
  (emit-trace-body `(-> ~f ~form)
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen))

(defmethod trace* :trace->>
  [{::keys [indent syntax-order num-seen]} form f]
  (emit-trace-body `(->> ~f ~form)
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen))

(defmacro trace [& args]
  ;; (println "TRACE" args)
  (apply trace* args))


(defn spy [x]
  ;(zprint.core/czprint x)
  x)

;;; remove skip
(defn remove-skip [form]
;   (println "REMOVE-SKIP")
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (.. skip ...)
        (= ::ms/skip-place node)
        (recur (-> (z/remove loc)
                  ut/right-or-next))

        ;; in case of (skip ...) — replace and re-evaluate the same loc,
        ;; in case the inner form is itself a wrapped form
        ;; ((skip-outer ...), (o-skip ...), or another (skip ...)). The
        ;; previous advance with right-or-next walked past the new loc
        ;; without giving it a chance to match again, leaving the inner
        ;; wrapper in the output (mirrors the o-skip fix below).
        (and (seq? node)
             (= `ms/skip (first node)))
        (recur (z/replace loc (second node)))

        ;; in case of (a-skip ...) — strip the wrapper. insert-trace
        ;; has already wrapped the surrounding (a-skip ~form) in a
        ;; (~d-sym {meta} (a-skip ~form)) trace; here we unwrap to
        ;; (~d-sym {meta} ~form) so the form runs un-modified at
        ;; runtime while still being traced once at the top level.
        (and (seq? node)
             (= `ms/a-skip (first node)))
        (recur (z/replace loc (second node)))

        ;; in case of (o-skip ...)
        ;; Replace and re-evaluate the same loc — if the inner form
        ;; is itself a (skip ...) (the recur-protection nest emitted
        ;; by insert-skip-form-itself + insert-o-skip-for-recur), the
        ;; next iteration must match the (skip ...) handler against
        ;; the new node. Advancing with z/next here would descend
        ;; into the skip's children and leave the (skip ...) wrapper
        ;; intact in the output.
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (recur (z/replace loc (second node)))

        ;; in case of (skip-outer ...)
        (and (seq? node)
             (= `ms/skip-outer (first node)))
        (recur (-> (z/replace loc (second node))))

        :else
        (recur (z/next loc))))))


;;; dbgn
(defmacro dbgn
  "DeBuG every Nested forms of a form.s"
  [form & [opts]]
  ; (println "FULLFORM" &form)
  `(let [~'+debux-dbg-opts+   ~(if (ut/cljs-env? &env)
                                 (dissoc opts :style :js)
                                 opts)
         ~'+debux-dbg-locals+ []
         ~'+debux-trace-id+   ~(str (gensym "dbgn_"))]
     (try
       ;; Send whole form to trace point
       (ut/send-form! '~(-> form (ut/tidy-macroexpanded-form {})))
       ~(-> (if (ut/include-recur? form)
              (sk/insert-o-skip-for-recur form &env)
              form)
            (insert-skip &env)
            (insert-trace 'day8.re-frame.debux.dbgn/trace &env []
                          (boolean (or (:verbose opts) (:show-all opts))))
            remove-skip)
       ;; TODO: can we remove try/catch too?
       (catch ~(if (ut/cljs-env? &env)
                 :default
                 Exception)
              ~'e (throw ~'e)))))

;;; dbgn
(defmacro dbgn-forms
  "Similar to dbgn but can deal with multiple forms and inject a specified form to send-form!
   args:
    forms - the sequence of forms i.e. an implied do in a fn
    send-form - the form sent to be traced
    args - the symbols in the args (that need to be added to num-seen)
    opts - optional opts map; supports :locals (capture args as
           [[sym val] ...] in the trace payload) and :if (a runtime
           predicate gating send-trace! on the per-form result)."
  [forms send-form args & [opts]]
  (let [send-form (-> send-form  ;;for some reason the form is wrapped in a list
                      first)
        func      (first send-form)
        send-form (conj (rest send-form) (symbol (name func)))
        ;; :locals capture: emit [[sym val] ...]
        ;; pairs at expansion time. Symbols come from the function
        ;; arity's args (already plumbed through `args`); the values
        ;; resolve at runtime to whatever the function received.
        ;; Inner let bindings introduced inside the body are NOT
        ;; tracked here — &env at fn-traced expansion time only sees
        ;; the function args, per the design note in
        ;; docs/improvement-plan.md §4 ("&env only, accept that CLJS
        ;; captures less").
        locals-pairs (mapv (fn [s] `[(quote ~s) ~s]) args)]
    `(let [~'+debux-dbg-opts+   ~(if (ut/cljs-env? &env)
                                   (dissoc opts :style :js)
                                   opts)
           ~'+debux-dbg-locals+ ~locals-pairs
           ~'+debux-trace-id+   ~(str (gensym "dbgn-forms_"))]
       (try
       ;; Send whole form to trace point
         (ut/send-form! '~(-> send-form (ut/tidy-macroexpanded-form {})))
         ~@(map (fn [form] (-> (if (ut/include-recur? form)
                                 (sk/insert-o-skip-for-recur form &env)
                                 form)
                               (insert-skip &env)
                               (insert-trace 'day8.re-frame.debux.dbgn/trace &env args
                                             (boolean (or (:verbose opts) (:show-all opts))))
                               remove-skip))
                forms)
       ;; TODO: can we remove try/catch too?
         (catch ~(if (ut/cljs-env? &env)
                   :default
                   Exception)
                ~'e (throw ~'e))))))

(defmacro mini-dbgn
  "DeBuG every Nested forms of a form.s"
  [form]
  ;; mini-dbgn is test-only — used to assert dbgn macroexpansion
  ;; shape. The trace-id is a fixed string (not a gensym) so the
  ;; expansion is byte-for-byte stable and the shape assertions in
  ;; dbgn_test.clj don't have to wildcard-match a moving value.
  `(let [~'+debux-dbg-opts+   nil
         ~'+debux-dbg-locals+ []
         ~'+debux-trace-id+   "mini-dbgn"]
     ~(-> (if (ut/include-recur? form)
            (sk/insert-o-skip-for-recur form &env)
            form)
          (insert-skip &env)
          (insert-trace 'day8.re-frame.debux.dbgn/trace &env)
          remove-skip)))


;; Two phase approach
;; add skips (+ macroexpansion)
;; add d's

;; macros create lots of code which we don't want to see
;; we want to output forms and form values at particular points, but not the rest of the stuff injected by the macros
;; Difficulty in two phase adding is that we do macroexpansion in first phase, so we have to annotate all macro code with skips.


;(conj :d)
;(conj [:a :b] :d)

;; We handle use of macros within macros then we macro-expand them before returning them out.
;; pre-emptive macroexpansion




;(dbgn (-> {:a 1}
;          (assoc :a 3)
;          frequencies))
;
;(dbgn (-> :a (cons '(1 2 3))))

;(defn c-kw []
;  :c)
;
;(dbgn (some-> [:a :b (c-kw)]
;              (conj :d)
;              (distinct)))
;
;(dbgn (->> [:a :b (c-kw)]
;             (cons :d)
;             (distinct)))

#_(defn my-fun [a b c]
    (dbgn (+ a b c
             (->> (range a b)
                  (map (fn [x] (* x x)))
                  (filter even?)
                  (take a)
                  (reduce +)))))

;(reduce + (take a (filter even? (map (fn [x] (* x x)) (range a b)))))

