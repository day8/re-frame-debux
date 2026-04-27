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


;;; debugging APIs
(defmacro dbgn [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(day8.re-frame.debux.dbgn/dbgn ~form ~opts')))

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
   (let [r (gensym "dbg-result_")
         o (gensym "dbg-opts_")
         p (gensym "dbg-pred_")]
     `(let [~r ~form
            ~o ~opts
            ~p (:if ~o)]
        (when (or (nil? ~p) (~p ~r))
          (day8.re-frame.debux.common.util/send-trace-or-tap!
           (cond-> {:form         '~form
                    :result       ~r
                    :indent-level 0
                    :syntax-order 0
                    :num-seen     0}
             (:name ~o)   (assoc :name (:name ~o))
             (:locals ~o) (assoc :locals (:locals ~o)))
           (boolean (:tap? ~o))))
        ~r))))

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
        (symbol? node) (recur (z/next loc) (conj seen node))
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
        args-symbols    (find-symbols args)]
    (if (= :body body-or-prepost)   ;; no pre and post conditions
      `(~args
        (dbgn/dbgn-forms ~body ~send-form ~args-symbols ~opts))
    ;; prepost+body
      `(~args
        ~(:prepost body)
        (dbgn/dbgn-forms ~(:body body) ~send-form ~args-symbols ~opts)))))

;; Components of a defn
;; name
;; docstring?
;; meta?
;; bs (1-n)
;; body
;; prepost?

(defmacro defn-traced*
  [opts & definition]
  (let [conformed (s/conform ::ms/defn-args definition)
        name      (:name conformed)
        bs        (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      `(defn ~name ~@(fn-body args+body opts &form))
      `(defn ~name ~@(map #(fn-body % opts &form) (:bodies args+body))))))

(defmacro defn-traced
  "Traced defn. Accepts an optional opts map immediately after the
   macro name:
     :locals true   — attach captured args as [[sym val] ...] to each
                      :code trace entry.
     :if      pred  — runtime predicate called with the per-form result;
                      send-trace! fires only when pred returns truthy.
   Example: (defn-traced {:locals true} my-handler [db event] ...)"
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
     :locals true   — attach captured args as [[sym val] ...] to each
                      :code trace entry.
     :if      pred  — runtime predicate called with the per-form result;
                      send-trace! fires only when pred returns truthy.
   Example: (fn-traced {:locals true} [db event] ...)"
  {:arglists '[(fn-traced opts? name? [params*] exprs*)
               (fn-traced opts? name? ([params*] exprs*) +)]}
  [& definition]
  (let [[opts def'] (split-opts definition)]
    `(if (is-trace-enabled?)
       (fn-traced* ~opts ~@def')
       (fn ~@def'))))

