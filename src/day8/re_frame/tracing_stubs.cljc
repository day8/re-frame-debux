(ns day8.re-frame.tracing-stubs
  #?(:cljs (:require-macros [day8.re-frame.tracing-stubs])))

(defmacro defn-traced
  "Traced defn, this variant compiles down to the standard defn, without tracing."
  {:arglists '([opts? name doc-string? attr-map? [params*] prepost-map? body]
                [opts? name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& definition]
  (let [def' (if (map? (first definition)) (rest definition) definition)]
    `(defn ~@def')))

(defmacro fn-traced
  "Traced fn, this variant compiles down to the standard fn, without tracing."
  {:arglists '[(fn-traced opts? name? [params*] exprs*)
               (fn-traced opts? name? ([params*] exprs*) +)]}
  [& definition]
  (let [def' (if (map? (first definition)) (rest definition) definition)]
    `(fn ~@def')))

;; fx-traced / defn-fx-traced production stubs. Strip the leading opts
;; map (fn / defn don't accept it in that slot), then compile down to
;; the bare fn / defn — zero runtime cost in release builds.

(defmacro fx-traced
  "fx-traced; production stub. Strips opts and returns bare fn."
  [& definition]
  (let [def' (if (map? (first definition)) (rest definition) definition)]
    `(fn ~@def')))

(defmacro defn-fx-traced
  "defn-fx-traced; production stub. Strips opts and returns bare defn."
  [& definition]
  (let [def' (if (map? (first definition)) (rest definition) definition)]
    `(defn ~@def')))

;; Production stubs for dbg / dbgn. Compile out to the
;; bare expression so release builds incur zero runtime cost.
(defmacro dbg
  "Single-form tracer; production stub. Returns the form's value
   without emitting a trace."
  ([form] form)
  ([form _opts] form))

(defmacro dbgn
  "Nested-form tracer; production stub. Returns the form's value
   without emitting traces."
  [form & _opts] form)

(defmacro dbg-last
  "Thread-last-friendly single-form tracer; production stub. Returns
   the threaded value with no trace side effect."
  ([value] value)
  ([_opts value] value))

;; Configuration knobs — re-exported in dev as `(def x ut/x)` from
;; debux.common.util. In a release build the underlying state atoms
;; (tap-output?, trace-frames-output?, indent-level*,
;; print-seq-length*) aren't reached from any traced path, so the
;; setters become inert. They're stubbed as no-op defns (not defs) so
;; callers using `(set-tap-output! true)` at app boot don't 404 against
;; an unbound var.

(defn set-tap-output!
  "Production stub. No-op — the dev-side setter toggles whether trace
   emitters fan out to tap>; in a release build the emitters don't
   fire, so toggling has no observable effect."
  [_enabled?]
  nil)

(defn set-trace-frames-output!
  "Production stub. No-op — the dev-side setter toggles whether traced
   functions emit :trace-frames markers; in a release build traced
   functions compile down to plain functions, so toggling has no
   observable effect."
  [_enabled?]
  nil)

(defn set-date-time-fn!
  "Production stub. No-op — the dev-side setter controls tap payload
   :date-time values; in a release build no payloads are produced."
  [_f]
  nil)

(defn set-print-length!
  "Production stub. No-op — the dev-side setter bounds collection
   pretty-printing inside trace payloads; in a release build no
   payloads are produced so the bound is moot."
  [_num]
  nil)

(defn set-print-seq-length!
  "Production stub. No-op — deprecated alias for set-print-length!."
  [_num]
  nil)

(defn reset-indent-level!
  "Production stub. No-op — indent-level state isn't tracked in a
   release build (no per-form trace machinery to indent)."
  []
  nil)

(defn reset-once-state!
  "Production stub. No-op — the dev-side fn drops `:once` dedup state;
   in a release build the dedup atom is never populated (no trace
   emitters fire), so there is nothing to reset."
  []
  nil)
