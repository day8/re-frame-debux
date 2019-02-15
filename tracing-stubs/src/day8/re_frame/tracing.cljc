(ns day8.re-frame.tracing
  #?(:cljs (:require-macros [day8.re-frame.tracing])))

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

