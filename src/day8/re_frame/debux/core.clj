(ns day8.re-frame.debux.core
  (:require [day8.re-frame.debux.dbgn :as dbgn]
            [day8.re-frame.debux.macro-types :as mt]
            [day8.re-frame.debux.common.util :as ut]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [day8.re-frame.debux.common.macro-specs :as ms]))

(def reset-indent-level! ut/reset-indent-level!)
(def set-print-seq-length! ut/set-print-seq-length!)

(def ^boolean trace-enabled? true)

(defn ^boolean is-trace-enabled?
  "See https://groups.google.com/d/msg/clojurescript/jk43kmYiMhA/IHglVr_TPdgJ for more details"
  []
  trace-enabled?)


;;; debugging APIs

(defmacro dbgn [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(dbgn/dbgn ~form ~opts')))


;;; macro registering APIs
(defmacro register-macros! [macro-type symbols]
  `(mt/register-macros! ~macro-type ~symbols))

(defmacro show-macros
  ([] `(mt/show-macros))
  ([macro-type] `(mt/show-macros ~macro-type)))

;; defn-traced and fn-traced macros

(defn fn-body [args+body]
  (if (= :body (nth (:body args+body) 0))
    `(~(or (:args (:args args+body)) [])
       ~@(map (fn [body] `(dbgn ~body)) (nth (:body args+body) 1)))
    ;; prepost+body
    `(~(or (:args (:args args+body)) [])
       ~(:prepost (nth (:body args+body) 1))
       ~@(map (fn [body] `(dbgn ~body)) (:body (nth (:body args+body) 1))))))

;; Components of a defn
;; name
;; docstring?
;; meta?
;; bs (1-n)
;; body
;; prepost?

(defmacro defn-traced*
  [& definition]
  (let [conformed (s/conform ::ms/defn-args definition)
        name (:name conformed)
        bs (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      `(defn ~name ~@(fn-body args+body))
      `(defn ~name ~@(map fn-body (:bodies args+body))))))

(defmacro defn-traced
  "Traced defn"
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
                [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& definition]
  `(if (is-trace-enabled?)
     (defn-traced* ~@definition)
     (defn ~@definition)))



;; Components of a fn
;; name?
;; bs (1-n)
;; body
;; prepost?

(defmacro fn-traced*
  "Traced form of fn. Prefer fn-traced to compile out under advanced optimizations."
  [& definition]
  (let [conformed (s/conform ::ms/fn-args definition)
        name      (:name conformed)
        bs        (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      ;; If name is nil, then the empty vector is removed by the unquote
      `(fn ~@(when name [name])
         ~@(fn-body args+body))
      ;; arity-n
      (let [bodies (:bodies args+body)]
        `(fn ~@(when name [name])
           ~@(map fn-body bodies))))))

(defmacro fn-traced
  "Defines a traced fn"
  {:arglists '[(fn name? [params*] exprs*) (fn name? ([params*] exprs*) +)]}
  [& definition]
  `(if (is-trace-enabled?)
     (fn-traced* ~@definition)
     (fn ~@definition)))

;
;(dbg (-> {:a 1}
;         (assoc :a 3)
;         (frequencies)))
;
;(declare f)
;
;(dbg (-> {:a 1}
;         (ut/spy-first (skip '{:a 1}))
;         (assoc :a (f 3))
;         (ut/spy-first (skip '(assoc :a (f 3))))
;         frequencies
;         (ut/spy-first (skip 'frequencies))))
;
;;; Need to skip the quoted forms too
;
;(ut/spy-first
;  (frequencies
;    (ut/spy-first
;      (assoc (ut/spy-first {:a 1} (skip '{:a 1}))
;        :a
;        (f 3))
;      (skip '(assoc :a (f 3)))))
;  (skip 'frequencies))
;
;;; spy-first: don't add d to first parameter (assoc :47), but recurse into it and trace parameters of assoc.
;
;
;
;(debux.dbgn/d
;  (debux.dbgn/d
;    (assoc (debux.dbgn/d {:a 1}
;                         '{:a 1})
;      :a 3)
;    '(assoc :a 3))
;  '(frequencies))




;(dbgn (-> {:a 1}
;          (assoc :a 3)
;          (frequencies)))
;
;
;(debux.dbgn/d
;   (-> (debux.dbgn/d {:a 1})
;      (debux.dbgn/d (assoc :a 3))
;      (debux.dbgn/d (frequencies))))
;
