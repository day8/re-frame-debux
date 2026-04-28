(ns day8.re-frame.debux.core
  (:require [day8.re-frame.debux.dbgn :as dbgn]
            [day8.re-frame.debux.common.util :as ut]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [day8.re-frame.debux.common.macro-specs :as ms]))

(def reset-indent-level! ut/reset-indent-level!)
(def set-date-time-fn! ut/set-date-time-fn!)
(def set-print-length! ut/set-print-length!)
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

;; defn-traced and fn-traced macros

(defn legacy-fn-body
  "Build the body for the legacy day8.re-frame.debux.core
   fn-traced/defn-traced macros. This predates the richer
   day8.re-frame.tracing/fn-body helper, so it only wraps each body
   form in dbgn and does not handle opts, frame markers, locals, or
   fx-effect tracing."
  [args+body]
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
  ;; ::ms/defn-args also conforms :docstring and :meta — splice them
  ;; back into the emitted defn so they land on the resulting var.
  ;; Without this, `(defn-traced f "doc" {:added "1.0"} [x] x)` produced
  ;; a var with no :doc / :added meta. Order: name → docstring → meta
  ;; → bodies → trailing-attr (arity-n only) — standard defn signature.
  (let [conformed (s/conform ::ms/defn-args definition)
        name      (:name conformed)
        docstring (:docstring conformed)
        meta-map  (:meta conformed)
        bs        (:bs conformed)
        arity-1?  (= (nth bs 0) :arity-1)
        args+body (nth bs 1)]
    (if arity-1?
      `(defn ~name
         ~@(when docstring [docstring])
         ~@(when meta-map [meta-map])
         ~@(legacy-fn-body args+body))
      (let [trailing-attr (:attr args+body)]
        `(defn ~name
           ~@(when docstring [docstring])
           ~@(when meta-map [meta-map])
           ~@(map legacy-fn-body (:bodies args+body))
           ~@(when trailing-attr [trailing-attr]))))))

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
         ~@(legacy-fn-body args+body))
      ;; arity-n
      (let [bodies (:bodies args+body)]
        `(fn ~@(when name [name])
           ~@(map legacy-fn-body bodies))))))

(defmacro fn-traced
  "Defines a traced fn"
  {:arglists '[(fn name? [params*] exprs*) (fn name? ([params*] exprs*) +)]}
  [& definition]
  `(if (is-trace-enabled?)
     (fn-traced* ~@definition)
     (fn ~@definition)))
