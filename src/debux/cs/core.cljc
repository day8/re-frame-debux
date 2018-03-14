(ns debux.cs.core
  #?(:cljs (:require-macros
             [debux.dbgn :as dbgn]
             [debux.cs.macro-types :as mt]))
  (:require [debux.common.util :as ut]))

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
    `(debux.dbgn/dbgn ~form ~opts')))

;;; macro registering APIs
(defmacro register-macros! [macro-type symbols]
  `(debux.cs.macro-types/register-macros! ~macro-type ~symbols))

(defmacro show-macros
  ([] `(debux.cs.macro-types/show-macros))
  ([macro-type] `(debux.cs.macro-types/show-macros ~macro-type)))

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
    `(if (is-trace-enabled?)
       (defn ~name ~doc-string ~arg-list
         (debux.dbgn/dbgn ~@form {})
         #_(trace-fn-call '~name f# args#))
       (defn ~name ~@definition))))

(defmacro fn-traced
  [& definition]
  (let [args (first definition)
        form (rest definition)]
    `(if (is-trace-enabled?)
       (fn ~args
         (debux.dbgn/dbgn ~@form {}))
       (fn ~@definition))))
