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

(def ^:private skip-handlers
  (array-map
   :def-type              [sk/insert-skip-in-def z/next]
   :defn-type             [sk/insert-skip-in-defn z/next]
   :fn-type               [sk/insert-skip-in-fn z/next]
   :let-type              [sk/insert-skip-in-let z/next]
   :loop-type             [sk/insert-skip-in-let z/next]
   :letfn-type            [sk/insert-skip-in-letfn z/next]
   :for-type              [sk/insert-skip-in-for z/next]
   :case-type             [sk/insert-skip-in-case z/next]
   :thread-first-type     [sk/insert-skip-thread-first z/next]
   :thread-last-type      [sk/insert-skip-thread-last z/next]
   :cond-first-type       [sk/insert-skip-cond-first z/next]
   :cond-last-type        [sk/insert-skip-cond-last z/next]
   :skip-arg-1-type       [sk/insert-skip-arg-1 z/next]
   :skip-arg-2-type       [sk/insert-skip-arg-2 z/next]
   :skip-arg-1-2-type     [sk/insert-skip-arg-1-2 z/next]
   :skip-arg-2-3-type     [sk/insert-skip-arg-2-3 z/next]
   :skip-arg-1-3-type     [sk/insert-skip-arg-1-3 z/next]
   :skip-all-args-type    [sk/insert-skip-all-args ut/right-or-next]
   :skip-form-itself-type [sk/insert-skip-form-itself ut/right-or-next]
   :dot-type              [sk/insert-skip-in-dot #(-> % z/down z/right)]
   :dot-dot-type          [sk/insert-skip-in-dot-dot #(-> % z/down z/right)]))

(defn- skip-handler [sym]
  (some (fn [[macro-type handler]]
          (when ((macro-types macro-type) sym)
            handler))
        skip-handlers))

;;; insert skip
(defn insert-skip
  "Marks the form to skip."
  [form env]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        (and (seq? node) (symbol? (first node)))
        (let [sym (ut/ns-symbol (first node) env)]
          (if-let [[skip-fn nav] (skip-handler sym)]
            (recur (nav (z/replace loc (skip-fn node))))
            (recur (z/next loc))))

        :else (recur (z/next loc))))))

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

(def ^:private trace-opts-omitted (Object.))

(defn- trace-meta
  [indent syntax-order num-seen trace-opts]
  (cond-> {::indent       indent
           ::syntax-order syntax-order
           ::num-seen     num-seen}
    (not (identical? trace-opts-omitted trace-opts))
    (assoc ::opts trace-opts)))

(defn- traced-form
  [d-sym loc syntax-order num-seen trace-opts node]
  `(~d-sym ~(trace-meta (real-depth loc)
                        syntax-order
                        num-seen
                        trace-opts)
           ~node))

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
   (insert-trace form d-sym env seen verbose? trace-opts-omitted))
  ([form d-sym env seen verbose? trace-opts]
  (loop [loc          (ut/sequential-zip form)
          indent       0
          syntax-order 0
          seen         seen]
     (let [node (z/node loc)
           seen (conj seen node)
           syntax-order (inc syntax-order)
           num-seen     (-> #{node}
                            (keep seen)
                            count)]
       (cond
         (z/end? loc) (z/root loc)
         
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
         (recur (-> (z/replace loc (traced-form d-sym loc syntax-order num-seen trace-opts node))
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
             ))


        ;; in case that the first symbol is defn/defn-
         (and (seq? node)
              (symbol? (first node))
              (`#{defn defn-} (ut/ns-symbol (first node) env)))
         (recur (-> loc z/down z/next) indent syntax-order seen)

        ;; in case of the first symbol except defn/defn-/def
         
        ;; DC: why not def? where is that handled?
         (and (seq? node) (ifn? (first node)))
         (recur (-> (z/replace loc (traced-form d-sym loc syntax-order num-seen trace-opts node))
                    skip-past-trace 
                    ut/right-or-next)
                (inc indent) syntax-order seen)

        ;; |[1 2 (+ 3 4)]
        ;; |(d [1 2 (+ 3 4)])
         

         (vector? node)
         (recur (-> loc
                    (z/replace (traced-form d-sym loc syntax-order num-seen trace-opts node))
                    skip-past-trace)
                indent syntax-order seen)

         (map? node)
         (recur (-> loc 
                    (z/replace (traced-form d-sym loc syntax-order num-seen trace-opts node))
                    skip-past-trace)
                indent syntax-order seen)

         (= node `day8.re-frame.debux.common.macro-specs/indent)
        ;; TODO: does this real-depth need an inc/dec to bring it into line with the d?
         (recur (z/replace loc (real-depth loc)) indent  syntax-order seen)

        ;; Map nodes are traced as whole values, not descended into here.
        ;; Per-key fx-map tracing lives at the runtime emit layer via
        ;; fx-traced/-emit-fx-traces!. Symbols and sets follow the same
        ;; "wrap value, do not zip into children" path.
         (or (symbol? node) (map? node) (set? node))
         (recur (-> (z/replace loc (traced-form d-sym loc syntax-order num-seen trace-opts node))
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
         (recur (-> (z/replace loc (traced-form d-sym loc syntax-order num-seen trace-opts node))
                    ut/right-or-next)
                indent syntax-order seen)

         :else
         (recur (z/next loc) indent syntax-order seen))))))

(defmulti trace*
  (fn [& args]
    (cond
      (= 2 (count args))  :trace
      (and (-> args
               second
               map?)
           (-> args
               second
               (contains? ::indent)))     :trace->
      :else :trace->>)))

;; Each trace* arity can receive expansion-time opts through the trace
;; metadata emitted by insert-trace. When the opts shape is known, only
;; the requested runtime gates are emitted. If an older direct trace
;; call lacks opts metadata, preserve the historical "check all gates"
;; shape.

(defn- trace-option-flags
  [trace-meta]
  (if (contains? trace-meta ::opts)
    (let [opts (::opts trace-meta)
          literal? (or (nil? opts) (map? opts))
          has-opt? (fn [k]
                     (and (map? opts) (contains? opts k)))
          msg? (or (has-opt? :msg) (has-opt? :m))]
      (if literal?
        {:final?  (has-opt? :final)
         :if?     (has-opt? :if)
         :once?   (has-opt? :once)
         :locals? (has-opt? :locals)
         :msg?    msg?
         :msg-expr (cond
                     (and (has-opt? :msg) (has-opt? :m))
                     `(or (:msg ~'+debux-dbg-opts+)
                          (:m ~'+debux-dbg-opts+))

                     (has-opt? :msg)
                     `(:msg ~'+debux-dbg-opts+)

                     (has-opt? :m)
                     `(:m ~'+debux-dbg-opts+))}
        {:final?  true
         :if?     true
         :once?   true
         :locals? true
         :msg?    true
         :msg-expr `(or (:msg ~'+debux-dbg-opts+)
                        (:m ~'+debux-dbg-opts+))}))
    {:final?  true
     :if?     true
     :once?   true
     :locals? true
     :msg?    true
     :msg-expr `(or (:msg ~'+debux-dbg-opts+)
                    (:m ~'+debux-dbg-opts+))}))

(defn- emit-trace-body
  "Emits the `(let [r ...] (when ... (send-trace! ...)) r)` shape
   shared by all three trace* arities. `bind-form` is the per-arity
   computation (`form` for :trace, `(-> f form)` for :trace->,
   `(->> f form)` for :trace->>). `org-form` is the cleaned-up
   user form for the trace payload."
  [bind-form org-form indent syntax-order num-seen trace-meta]
  (let [r            (gensym "trace-result_")
        m            (gensym "trace-msg_")
        trace-form   (ut/tidy-macroexpanded-form org-form {})
        {:keys [final? if? once? locals? msg? msg-expr]}
        (trace-option-flags trace-meta)
        final?       (and final? (not (zero? indent)))
        bind-pairs   (cond-> [r bind-form]
                       msg? (conj m msg-expr))
        gate-forms   (cond-> []
                       final?
                       (conj `(not (:final ~'+debux-dbg-opts+)))

                       if?
                       (conj `(or (not (:if ~'+debux-dbg-opts+))
                                  ((:if ~'+debux-dbg-opts+) ~r)))

                       once?
                       (conj `(or (not (:once ~'+debux-dbg-opts+))
                                  (ut/-once-emit? ~'+debux-trace-id+
                                                  ~syntax-order
                                                  ~r))))
        base-payload `{:form         '~trace-form
                       :result       ~r
                       :indent-level ~indent
                       :syntax-order ~syntax-order
                       :num-seen     ~num-seen}
        payload      (if (or locals? msg?)
                       `(cond-> ~base-payload
                          ~@(when locals?
                              [`(:locals ~'+debux-dbg-opts+)
                               `(assoc :locals ~'+debux-dbg-locals+)])
                          ~@(when msg?
                              [m `(assoc :msg ~m)]))
                       base-payload)
        send-trace   `(ut/send-trace!
                        (with-meta
                          ~payload
                          {::ut/form-tidied? true}))]
    `(let [~@bind-pairs]
       ~(if (seq gate-forms)
          `(when (and ~@gate-forms)
             ~send-trace)
          send-trace)
       ~r)))

(defmethod trace* :trace
  [{::keys [indent syntax-order num-seen] :as trace-meta} form]
  (emit-trace-body form
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen trace-meta))


(defmethod trace* :trace->
  [f {::keys [indent syntax-order num-seen] :as trace-meta} form]
  (emit-trace-body `(-> ~f ~form)
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen trace-meta))

(defmethod trace* :trace->>
  [{::keys [indent syntax-order num-seen] :as trace-meta} form f]
  (emit-trace-body `(->> ~f ~form)
                   (remove-d form 'day8.re-frame.debux.dbgn/trace)
                   indent syntax-order num-seen trace-meta))

(defmacro trace [& args]
  (apply trace* args))


;;; remove skip
(defn remove-skip [form]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
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

(defn- verbose-trace?
  [trace-opts]
  (and (not (identical? trace-opts-omitted trace-opts))
       (boolean (or (:verbose trace-opts) (:show-all trace-opts)))))

(defn- walk-traced-form
  "Macroexpansion-time walker shared by dbgn, dbgn-forms, and mini-dbgn.
   It owns the recur-protection, skip insertion, trace insertion, and
   skip removal pipeline so option changes flow through one place."
  ([form env]
   (walk-traced-form form env [] trace-opts-omitted))
  ([form env seen trace-opts]
   (let [form     (if (ut/include-recur? form)
                    (sk/insert-o-skip-for-recur form env)
                    form)
         inserted (insert-skip form env)
         traced   (if (identical? trace-opts-omitted trace-opts)
                    (insert-trace inserted 'day8.re-frame.debux.dbgn/trace env seen)
                    (insert-trace inserted 'day8.re-frame.debux.dbgn/trace env seen
                                  (verbose-trace? trace-opts)
                                  trace-opts))]
     (remove-skip traced))))


;;; dbgn
(defmacro dbgn
  "DeBuG every nested form in `form`.

   `opts` is the normalized opts map produced by
   `day8.re-frame.debux.common.util/parse-opts`, or a map supplied by
   callers that delegate directly to this macro.

   Supported opts:
     :if      runtime predicate; emit a trace only when (pred result)
              is truthy for the per-form result
     :once    suppress consecutive duplicate emissions from this call
              site; :o is parsed as an alias by parse-opts
     :final   emit only the outermost form result; :f is parsed as an
              alias by parse-opts
     :msg     label copied onto each code trace; :m is parsed as an
              alias by parse-opts
     :verbose also trace leaf literals normally skipped for noise
              reduction; :show-all is parsed as an alias by parse-opts"
  [form & [opts]]
  `(let [~'+debux-dbg-opts+   ~(if (ut/cljs-env? &env)
                                 (dissoc opts :style :js)
                                 opts)
         ~'+debux-dbg-locals+ []
         ~'+debux-trace-id+   ~(str (gensym "dbgn_"))]
     ;; Send whole form to trace point
     (ut/send-form! '~(-> form (ut/tidy-macroexpanded-form {})))
     ~(walk-traced-form form &env [] opts)))

;;; dbgn
(defmacro dbgn-forms
  "Similar to dbgn but can deal with multiple forms and inject a specified form to send-form!
   args:
    forms - the sequence of forms i.e. an implied do in a fn
    send-form - the form sent to be traced
    args - the symbols in the args (that need to be added to num-seen)
    opts - optional normalized opts map. Supported keys:
           :locals - capture args as [[sym val] ...] in the trace payload
           :if - runtime predicate gating each per-form trace on result
           :once - suppress consecutive duplicate emissions from this
                   macro expansion site
           :final - emit only the outermost form result
           :msg - label copied onto each code trace; :m is also honored
           :verbose - also trace leaf literals normally skipped for
                      noise reduction; :show-all is also honored"
  [forms send-form args & [opts]]
  (let [send-form (-> send-form  ;;for some reason the form is wrapped in a list
                      first)
        func      (first send-form)
        send-form (conj (rest send-form) (symbol (name func)))
        literal-opts?   (or (nil? opts) (map? opts))
        capture-locals? (if literal-opts?
                          (boolean (:locals opts))
                          true)
        ;; :locals capture: emit [[sym val] ...] pairs only when the
        ;; expansion might need them. Symbols come from the function
        ;; arity's args (already plumbed through `args`); the values
        ;; resolve at runtime to whatever the function received.
        ;; Inner let bindings introduced inside the body are NOT
        ;; tracked here — &env at fn-traced expansion time only sees
        ;; the function args, per the design note in
        ;; docs/improvement-plan.md §4 ("&env only, accept that CLJS
        ;; captures less").
        locals-form   (when capture-locals?
                        (mapv (fn [s] `[(quote ~s) ~s]) args))]
    `(let [~'+debux-dbg-opts+   ~(if (ut/cljs-env? &env)
                                   (dissoc opts :style :js)
                                   opts)
           ~'+debux-dbg-locals+ ~locals-form
           ~'+debux-trace-id+   ~(str (gensym "dbgn-forms_"))]
       ;; Send whole form to trace point
       (ut/send-form! '~(-> send-form (ut/tidy-macroexpanded-form {})))
       ~@(map (fn [form] (walk-traced-form form &env args opts))
              forms))))

(defmacro mini-dbgn
  "Test-only nested-form tracer with a fixed trace-id for stable macroexpansion assertions."
  [form]
  ;; mini-dbgn is test-only — used to assert dbgn macroexpansion
  ;; shape. The trace-id is a fixed string (not a gensym) so the
  ;; expansion is byte-for-byte stable and the shape assertions in
  ;; dbgn_test.clj don't have to wildcard-match a moving value.
  `(let [~'+debux-dbg-opts+   nil
         ~'+debux-dbg-locals+ []
         ~'+debux-trace-id+   "mini-dbgn"]
     ~(walk-traced-form form &env)))


;; Two phase approach
;; add skips (+ macroexpansion)
;; add d's

;; macros create lots of code which we don't want to see
;; we want to output forms and form values at particular points, but not the rest of the stuff injected by the macros
;; Difficulty in two phase adding is that we do macroexpansion in first phase, so we have to annotate all macro code with skips.

;; We handle use of macros within macros then we macro-expand them before returning them out.
;; pre-emptive macroexpansion
