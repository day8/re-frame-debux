(ns day8.re-frame.tracing
  #?(:cljs (:require-macros
             [day8.re-frame.debux.dbgn :as dbgn]
             [day8.re-frame.debux.cs.macro-types :as mt]
             [day8.re-frame.tracing])
     :clj (:require
            [day8.re-frame.debux.dbgn :as dbgn]
            [day8.re-frame.debux.cs.macro-types :as mt]))
  (:require [day8.re-frame.debux.common.util :as ut]
            [day8.re-frame.debux.common.macro-specs :as ms]
            [clojure.spec.alpha :as s]
            [clojure.zip :as z]))

#?(:cljs (enable-console-print!))

#?(:cljs (goog-define trace-enabled? false)
   :clj  (def ^boolean trace-enabled? false))

(defn ^boolean is-trace-enabled?
  "See https://groups.google.com/d/msg/clojurescript/jk43kmYiMhA/IHglVr_TPdgJ for more details"
  ;; We can remove this extra step of type hinting indirection once our minimum CLJS version includes
  ;; https://dev.clojure.org/jira/browse/CLJS-1439
  ;; r1.10.63 is the first version with this:
  ;; https://github.com/clojure/clojurescript/commit/9ec796d791b1b2bd613af2f62cdecfd25caa6482
  []
  trace-enabled?)

(def reset-indent-level! ut/reset-indent-level!)
(def set-print-seq-length! ut/set-print-seq-length!)
(def set-tap-output! ut/set-tap-output!)


;;; debugging APIs
(defmacro dbgn [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(if (is-trace-enabled?)
       (day8.re-frame.debux.dbgn/dbgn ~form ~opts')
       ~form)))

;; `dbg` is the single-form counterpart to `dbgn`. Wrap any
;; expression to emit one trace record per evaluation; inside a
;; re-frame event handler the trace lands on :tags :code (same surface
;; as fn-traced — 10x's Code panel and re-frame-pair's :debux/code
;; pick it up identically). Outside any trace context, falls back to
;; tap> so REPL callers still see output.
;;
;; Self-contained at the macro level — no zipper / cljs.analyzer
;; dependency, so it's safe to live in tracing.cljc alongside the
;; other CLJC wrappers (vs. the dbgn zipper machinery in dbgn.clj).

(defmacro dbg
  "DeBuG a single form. `(dbg form)` or `(dbg form opts-map)`.

   The expression evaluates exactly as if dbg weren't there — `dbg`
   is value-transparent. As a side effect, one trace record per
   evaluation is emitted with the same payload contract as fn-traced:
     {:form         <quoted source>
      :result       <evaluated value>
      :indent-level 0   ; single form — no nesting
      :syntax-order 0
      :num-seen     0
      :locals?      <opt> :name? <opt>}

   Sink: `re-frame.trace/*current-trace*` non-nil → merge into the
   active event's :tags :code (re-frame-pair surfaces it
   as :debux/code in the coerced epoch); nil → tap> with `:debux/dbg
   true` so REPL `add-tap` consumers can branch.

   Trailing opts map (all optional):
     :name   — string label carried in the payload
     :locals — vec of [[sym val] ...] pairs (caller-supplied — the
               macro can't introspect &env across CLJ/CLJS the way
               fn-traced does at function-arg time, so callers
               wanting locals capture do the gather themselves;
               pattern: `(dbg form {:locals [['db db] ['x x]]})`)
     :if     — runtime predicate; the trace fires only when (pred
               result) is truthy
     :once   — suppress consecutive identical emissions from this
               specific call site. The first invocation emits; the
               next runs that produce the same result are skipped.
               Cleared when the result changes, OR explicitly via
               `day8.re-frame.debux.common.util/-reset-once-state!`.
     :tap?   — also fire tap> alongside the in-trace send-trace!
               emit. Out-of-trace, tap> always fires regardless.

   Example:
     (re-frame.core/reg-event-db
       :user/login
       (fn-traced [db [_ creds]]
         (assoc db :user (dbg (lookup-user creds) {:name \"login\"}))))

   Compiles out to a plain `~form` evaluation under tracing-stubs in
   release builds (production-mode contract)."
  ([form] `(day8.re-frame.tracing/dbg ~form nil))
  ([form opts]
   (let [r        (gensym "dbg-result_")
         o        (gensym "dbg-opts_")
         p        (gensym "dbg-pred_")
         m        (gensym "dbg-msg_")
         ;; Macro-expansion-site identity for :once dedup. Stable
         ;; across runtime invocations of this compiled call site,
         ;; distinct between separate dbg call sites in the same file.
         trace-id (str (gensym "dbg_"))]
     `(if (is-trace-enabled?)
        (let [~r ~form
              ~o ~opts
              ~p (:if ~o)
              ;; :msg / :m alias resolution — same convention as
              ;; emit-trace-body in dbgn.clj. `:msg` wins if both are set.
              ~m (or (:msg ~o) (:m ~o))]
          (when (and (or (nil? ~p) (~p ~r))
                     (or (not (:once ~o))
                         (day8.re-frame.debux.common.util/-once-emit? ~trace-id 0 ~r)))
            (day8.re-frame.debux.common.util/send-trace-or-tap!
             (cond-> {:form         '~form
                      :result       ~r
                      :indent-level 0
                      :syntax-order 0
                      :num-seen     0}
               (:name ~o)   (assoc :name (:name ~o))
               (:locals ~o) (assoc :locals (:locals ~o))
               ~m           (assoc :msg ~m))
             (boolean (:tap? ~o))))
          ~r)
        ~form))))

;; `dbg-last` is the thread-last-friendly counterpart to `dbg`. The
;; usability gap it closes: `dbg` wraps an expression, but `->>`
;; pipelines flow values through positions rather than expressions —
;; dropping `(dbg ...)` mid-pipeline forces a restructure. `dbg-last`
;; sits in the natural ->> step slot:
;;
;;   (->> coll
;;        (filter pred?)
;;        dbg-last           ; bare; or (dbg-last opts-map)
;;        (map xf)
;;        (reduce +))
;;
;; The expansion swaps args and delegates to `dbg`: the threaded
;; value reaches dbg as its leading form-arg (so dbg's `'~form`
;; capture sees the upstream thread chain, just like a wrapped
;; expression), while opts (if supplied) trail. Same payload, opts
;; contract, and trace-id semantics as `dbg`.

(defmacro dbg-last
  "Thread-last-friendly variant of `dbg`. Suitable as a `->>` step:

     (->> coll
          (filter pred?)
          dbg-last                          ; bare
          (map xf))

     (->> coll
          (filter pred?)
          (dbg-last {:name \"after-filter\"})  ; with opts
          (map xf))

   Value-transparent — the threaded value flows through unchanged.
   Same payload schema and opts as `dbg` (:name, :locals, :if,
   :once, :tap?). See `dbg`'s docstring for details.

   Expands to `(dbg <value> <opts>)`, so the trace record captures
   the upstream thread chain (everything between the head of the
   `->>` and this call site) as the `:form` field."
  ([value] `(day8.re-frame.tracing/dbg ~value nil))
  ([opts value] `(day8.re-frame.tracing/dbg ~value ~opts)))

;;; macro registering APIs
(defmacro register-macros! [macro-type symbols]
  `(day8.re-frame.debux.cs.macro-types/register-macros! ~macro-type ~symbols))

(defmacro show-macros
  ([] `(day8.re-frame.debux.cs.macro-types/show-macros))
  ([macro-type] `(day8.re-frame.debux.cs.macro-types/show-macros ~macro-type)))

(defn find-symbols [args]
  "iterate through the function args and get a list of the symbols"
  (loop [loc (ut/sequential-zip args)
         seen []]
    (let [node (z/node loc)]
      (cond
        (z/end? loc) seen
        ;; Skip the '& arglist separator. It's a fn-arglist sentinel,
        ;; not a real local — emitting it into +debux-dbg-locals+
        ;; would expand to the unbound symbol `&` and raise
        ;; CompilerException at handler-load time.
        (and (symbol? node) (not= '& node)) (recur (z/next loc) (conj seen node))
        :else (recur (z/next loc) seen)
        ))))

(defn- split-opts
  "If the leading form of a fn-traced / defn-traced definition is a
   map literal, treat it as the opts map (:locals, :if) and return
   [opts (rest definition)]. Otherwise [nil definition].
   The map sniffer is unambiguous because clojure.core/fn forbids a
   map in this slot — `(fn {} [args] ...)` is always invalid."
  [definition]
  (if (map? (first definition))
    [(first definition) (rest definition)]
    [nil definition]))

(defn fn-body [args+body opts & send-form]
  (let [args            (or (-> args+body :args :args) [])
        body-or-prepost (-> args+body :body (nth 0))
        body            (nth (:body args+body) 1)
        args-symbols    (find-symbols args)
        ;; Per-call-site frame id, baked in at expansion. Both
        ;; -send-frame-enter! and -send-frame-exit! receive the same
        ;; id so consumers can pair the markers.
        frame-id        (str (gensym "frame_"))
        r               (gensym "fn-traced-result_")
        ;; :fx-trace is the fx-traced opt that asks for per-key
        ;; tracing of the returned effect map. Set by the fx-traced
        ;; macro — bare fn-traced ignores it.
        fx-trace?       (:fx-trace opts)]
    (if (= :body body-or-prepost)   ;; no pre and post conditions
      `(~args
        (day8.re-frame.debux.common.util/-send-frame-enter! ~frame-id)
        (let [~r (dbgn/dbgn-forms ~body ~send-form ~args-symbols ~opts)]
          ~@(when fx-trace?
              [`(day8.re-frame.debux.common.util/-emit-fx-traces! ~r)])
          (day8.re-frame.debux.common.util/-send-frame-exit! ~frame-id ~r)
          ~r))
    ;; prepost+body
      `(~args
        ~(:prepost body)
        (day8.re-frame.debux.common.util/-send-frame-enter! ~frame-id)
        (let [~r (dbgn/dbgn-forms ~(:body body) ~send-form ~args-symbols ~opts)]
          ~@(when fx-trace?
              [`(day8.re-frame.debux.common.util/-emit-fx-traces! ~r)])
          (day8.re-frame.debux.common.util/-send-frame-exit! ~frame-id ~r)
          ~r)))))

;; Components of a defn
;; name
;; docstring?
;; meta?
;; bs (1-n)
;; body
;; prepost?

(defmacro defn-traced*
  [opts & definition]
  ;; ::ms/defn-args also conforms :docstring and :meta plus an arity-n
  ;; trailing-attr — splice them back into the emitted defn so they
  ;; land on the resulting var. Order: name → docstring → meta →
  ;; bodies → trailing-attr (arity-n only) — standard defn signature.
  (let [conformed (s/conform ::ms/defn-args definition)
        name      (:name conformed)
        docstring (:docstring conformed)
        meta-map  (:meta conformed)
        bs        (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      `(defn ~name
         ~@(when docstring [docstring])
         ~@(when meta-map [meta-map])
         ~@(fn-body args+body opts &form))
      (let [trailing-attr (:attr args+body)]
        `(defn ~name
           ~@(when docstring [docstring])
           ~@(when meta-map [meta-map])
           ~@(map #(fn-body % opts &form) (:bodies args+body))
           ~@(when trailing-attr [trailing-attr]))))))

(defmacro defn-traced
  "Traced defn. Accepts an optional opts map immediately after the
   macro name:
     :locals    true — attach captured args as [[sym val] ...] to each
                       :code trace entry.
     :if        pred — runtime predicate called with the per-form result;
                       send-trace! fires only when pred returns truthy.
     :once      true — suppress consecutive emissions whose (form, result)
                       pair matches the previous one. Per call site;
                       dedup state is process-local and survives across
                       handler invocations until the result actually
                       changes. Useful for high-frequency dispatches
                       where you only want to see what's NEW.
     :verbose   true — also wrap leaf literals (numbers, strings, booleans,
       (or :show-all)  keywords, chars, nil) that the default mode skips
                       for noise reduction. Special-form skips (recur,
                       throw, var, quote, etc.) stay honoured because
                       instrumenting them corrupts evaluation semantics.
   Example: (defn-traced {:locals true :verbose true} my-handler [db event] ...)"
  {:arglists '([opts? name doc-string? attr-map? [params*] prepost-map? body]
                [opts? name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& definition]
  (let [[opts def'] (split-opts definition)]
    `(if (is-trace-enabled?)
       (defn-traced* ~opts ~@def')
       (defn ~@def'))))



;; Components of a fn
;; name?
;; bs (1-n)
;; body
;; prepost?

(defmacro fn-traced*
  "Traced form of fn. Prefer fn-traced to compile out under advanced optimizations."
  [opts & definition]
  (let [conformed (s/conform ::ms/fn-args definition)
        name      (:name conformed)
        bs        (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      ;; If name is nil, then the empty vector is removed by the unquote
      `(fn ~@(when name [name])
         ~@(fn-body args+body opts &form))
      ;; arity-n
      (let [bodies (:bodies args+body)]
        `(fn ~@(when name [name])
           ~@(map #(fn-body % opts &form) bodies))))))

(defmacro fn-traced
  "Traced fn. Accepts an optional opts map immediately after the
   macro name:
     :locals    true — attach captured args as [[sym val] ...] to each
                       :code trace entry.
     :if        pred — runtime predicate called with the per-form result;
                       send-trace! fires only when pred returns truthy.
     :once      true — suppress consecutive emissions whose (form, result)
                       pair matches the previous one. Per call site.
     :verbose   true — also wrap leaf literals that the default mode skips
       (or :show-all)  for noise reduction.
   Example: (fn-traced {:locals true :once true} [db event] ...)"
  {:arglists '[(fn-traced opts? name? [params*] exprs*)
               (fn-traced opts? name? ([params*] exprs*) +)]}
  [& definition]
  (let [[opts def'] (split-opts definition)]
    `(if (is-trace-enabled?)
       (fn-traced* ~opts ~@def')
       (fn ~@def'))))


;; ---------------------------------------------------------------------------
;; fx-traced — fn-traced for reg-event-fx handlers
;; ---------------------------------------------------------------------------
;;
;; reg-event-fx handlers return an effect-map like
;;   {:db <new-db> :http {...} :dispatch [:other-event ...]}
;;
;; fn-traced surfaces the inner sub-forms of the body (each let-binding,
;; each computation that fed into a value), but doesn't flag the
;; per-key entries of the RETURNED map. fx-traced does both — it
;; inherits all of fn-traced's per-form :code emission, frame markers,
;; :locals / :if / :once / :verbose opts, and adds one :fx-effects
;; entry per key in the returned map. Consumers (10x panels, custom
;; inspectors) can render the effect-map breakdown alongside the
;; form-level trace.
;;
;; The returned map flows through unchanged — fx-traced is value-
;; transparent. The trace emission is a side effect on the active
;; trace's :tags :fx-effects vector. No-op outside a re-frame trace.
;;
;; Internally fx-traced is a thin wrapper that sets `:fx-trace true`
;; on fn-traced's opts map; fn-body picks up the flag and emits the
;; -emit-fx-traces! call after the body evaluates.

(defmacro fx-traced
  "Like fn-traced, but for reg-event-fx handlers that return effect
   maps. After the body produces the return value, each key of the
   map is emitted as its own :fx-effects trace entry alongside the
   usual per-form :code entries.

   Same opts as fn-traced (:locals, :if, :once, :verbose).

   Example:
     (re-frame.core/reg-event-fx :checkout
       (fx-traced [_ [_ amount]]
         (let [taxed (* 1.1 amount)]
           {:db {:total taxed}
            :http {:method :post :body taxed}
            :dispatch [:notify :checkout-done]})))"
  {:arglists '[(fx-traced opts? name? [params*] exprs*)
               (fx-traced opts? name? ([params*] exprs*) +)]}
  [& definition]
  (let [[opts def'] (split-opts definition)
        opts'       (assoc (or opts {}) :fx-trace true)]
    `(day8.re-frame.tracing/fn-traced ~opts' ~@def')))

(defmacro defn-fx-traced
  "defn variant of fx-traced. Same surface as defn-traced plus the
   per-effect-key tracing on the returned map.

   Example:
     (defn-fx-traced checkout-handler [_ [_ amount]]
       {:db {:total amount} :http {:method :post}})"
  {:arglists '([opts? name doc-string? attr-map? [params*] prepost-map? body]
                [opts? name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& definition]
  (let [[opts def'] (split-opts definition)
        opts'       (assoc (or opts {}) :fx-trace true)]
    `(day8.re-frame.tracing/defn-traced ~opts' ~@def')))
