(ns debux.cs.core
  #?(:cljs (:require-macros
             [debux.dbgn :as dbgn]
             [debux.cs.macro-types :as mt]))
  (:require [debux.common.util :as ut]))

#?(:cljs (enable-console-print!))

(def reset-indent-level! ut/reset-indent-level!)
(def set-print-seq-length! ut/set-print-seq-length!)


;;; debugging APIs
(defmacro dbgn [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(debux.dbgn/dbgn ~form ~opts')))

;;; macro registering APIs
(defmacro register-macros! [macro-type symbols]
  `(debux.cs.macro-types/register-macros! ~macro-type ~symbols))

(defmacro show-macros
  ([] `(debux.cs.macro-types/show-macros))
  ([macro-type] `(debux.cs.macro-types/show-macros ~macro-type)))

;; TODO: trace arglists
(defmacro defntrace
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
       (debux.dbgn/dbgn ~@form {})
       #_(trace-fn-call '~name f# args#))))

(defmacro fntrace
  [& definition]
  (let [args (first definition)
        form (rest definition)]
    `(fn ~args
       (debux.dbgn/dbgn ~@form {}))))
