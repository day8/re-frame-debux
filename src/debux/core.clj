(ns debux.core
  (:require [debux.dbgn :as dbgn]
            [debux.macro-types :as mt]
            [debux.common.util :as ut]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [debux.common.macro-specs :as ms]))

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

;; TODO: trace arglists
;; Components of a defn
;; name
;; docstring?
;; meta?
;; bs (1-n)
;; body
;; prepost

(defmacro defn-traced
  "Use in place of defn; traces each call/return of this fn, including
   arguments. Nested calls to deftrace'd functions will print a
   tree-like structure.
   The first argument of the form definition can be a doc string"
  [name & definition]
  (let [doc-string (if (string? (first definition)) (first definition) "")
        fn-form    (if (string? (first definition)) (rest definition) definition)
        form       (rest fn-form)
        arg-list   (first fn-form)]
    `(defn ~name ~doc-string ~arg-list
       (dbgn/dbgn ~@form {})
       #_(trace-fn-call '~name f# args#))))

;; Components of a fn
;; name?
;; bs (1-n)
;; body
;; prepost?

(defn fn-body [args+body]
  (if (= :body (nth (:body args+body) 0))
    `(~(or (:args (:args args+body)) [])
       ~@(map (fn [body] `(dbgn ~body)) (nth (:body args+body) 1)))
    ;; prepost+body
    `(~(or (:args (:args args+body)) [])
       ~(:prepost (nth (:body args+body) 1))
       ~@(map (fn [body] `(dbgn ~body)) (:body (nth (:body args+body) 1))))))

(defmacro fn-traced*
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
  [& definition]
  `(if (is-trace-enabled?)
     (fn-traced* ~@definition)
     (fn ~@definition)))


#_(defmacro deftrace2
    [form]
    `(dbgn/dbgn ~form {}))

#_(deftrace simple-fn "" [inte missing]
            (let [a inte]
              (->> (inc a)
                   inc
                   dec)))



#_(defn simple-fn1 ""
    []
    (dbgn/dbgn
      (->> (inc 2)
           inc
           dec)))

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
