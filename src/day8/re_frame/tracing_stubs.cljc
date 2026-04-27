(ns day8.re-frame.tracing-stubs
  #?(:cljs (:require-macros [day8.re-frame.tracing-stubs])))

(defmacro defn-traced
  "Traced defn, this variant compiles down to the standard defn, without tracing."
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
                [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& definition]
  `(defn ~@definition))

(defmacro fn-traced
  "Traced fn, this variant compiles down to the standard fn, without tracing."
  {:arglists '[(fn name? [params*] exprs*) (fn name? ([params*] exprs*) +)]}
  [& definition]
  `(fn ~@definition))

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

