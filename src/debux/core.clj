(ns debux.core
  (:require [debux.dbgn :as dbgn]
            [debux.macro-types :as mt]
            [debux.common.util :as ut]
            [clojure.walk :as walk]))

(def reset-indent-level! ut/reset-indent-level!)
(def set-print-seq-length! ut/set-print-seq-length!)

(def ^boolean trace-enabled? false)

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

(defmacro fn-traced
  [& definition]
  (let [args (first definition)
        form (rest definition)]
    `(if (is-trace-enabled?)
       (fn ~args
         (debux.dbgn/dbgn ~@form {}))
       (fn ~@definition))))



;(fntrace [x] (inc x))

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
