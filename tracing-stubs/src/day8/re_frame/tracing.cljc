(ns day8.re-frame.tracing
  #?(:cljs (:require-macros [day8.re-frame.tracing])))

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

