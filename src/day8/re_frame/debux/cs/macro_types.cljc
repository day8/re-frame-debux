(ns day8.re-frame.debux.cs.macro-types
  (:require [clojure.set :as set]
            [day8.re-frame.debux.common.util :as ut]))

(def ^:private common-macro-types
  {:def-type '#{def defonce}
   :defn-type '#{defn defn-}
   :fn-type '#{fn fn*}

   :let-type
   '#{let binding dotimes if-let if-some loop when-first when-let
      when-some with-out-str with-redefs}
   :letfn-type '#{letfn}
   :loop-type '#{loop}

   :for-type '#{for doseq}
   :case-type '#{case}

   :thread-first-type '#{-> some-> doto}
   :thread-last-type '#{->> some->>}
   :cond-first-type '#{cond->}
   :cond-last-type '#{cond->>}

   :skip-arg-1-type '#{set!}
   :skip-arg-2-type '#{as->}
   :skip-arg-1-2-type '#{}
   :skip-arg-1-3-type '#{defmethod}
   :skip-arg-2-3-type '#{amap areduce}

   ;; Forms whose internals carry compile-time semantics that can't be
   ;; evaluated as plain expressions (protocol impls, reify method
   ;; bodies, var / quote, etc.). Emit one trace for the whole form; do
   ;; NOT instrument the args. Mirrors upstream debux's
   ;; :skip-all-args-type — strictly more informative than
   ;; :skip-form-itself-type (which produces no trace at all).
   :skip-all-args-type
   '#{comment condp declare defmacro defmulti
      extend-protocol extend-type import memfn new quote
      refer-clojure reify use var}

   :skip-form-itself-type
   '#{catch defprotocol defrecord deftype finally
      ;; recur is a special form whose arguments must be in tail
      ;; position of the enclosing loop / fn; instrumenting it as a
      ;; normal call corrupts that contract and triggers a macroexpansion
      ;; non-termination (issue #40).
      recur
      throw}

   :dot-type '#{.}
   :dot-dot-type '#{..}})

(def ^:private clj-only-macro-types
  {:let-type '#{with-in-str with-local-vars with-open}
   :skip-arg-1-type '#{with-precision}
   :skip-all-args-type '#{defstruct extend ns proxy proxy-super sync}
   :skip-form-itself-type
   '#{definline definterface gen-class gen-interface
      day8.re-frame.debux.core/dbg day8.re-frame.debux.core/dbgn}})

(def ^:private cljs-only-macro-types
  {:skip-all-args-type
   '#{goog-define import-macros require require-macros
      simple-benchmark specify specify! use-macros}
   :skip-form-itself-type
   '#{day8.re-frame.debux.cs.core/dbg debux.cs.core/dbgn
      day8.re-frame.debux.cs.core/clog debux.cs.core/clogn}})

(def ^:private special-form-symbols
  '#{def fn* set! new quote var catch finally recur throw .})

(defn- core-symbol
  [core-ns sym]
  (if (or (namespace sym) (special-form-symbols sym))
    sym
    (symbol core-ns (name sym))))

(defn- qualify-core-symbols
  [core-ns macro-types]
  (reduce-kv (fn [m macro-type symbols]
               (assoc m macro-type (set (map #(core-symbol core-ns %) symbols))))
             {}
             macro-types))

(defn- merge-macro-types [& maps]
  (apply merge-with set/union maps))

(def macro-types*
  (let [clj  (merge-macro-types
              (qualify-core-symbols "clojure.core" common-macro-types)
              (qualify-core-symbols "clojure.core" clj-only-macro-types))
        cljs (merge-macro-types
              (qualify-core-symbols "cljs.core" common-macro-types)
              (qualify-core-symbols "cljs.core" cljs-only-macro-types))]
    (atom (merge-macro-types clj cljs))))


(defn- merge-symbols [old-symbols new-symbols env]
  (->> new-symbols       
       (map #(ut/ns-symbol % env))
       set
       (set/union old-symbols)))

(defmacro register-macros! [macro-type new-symbols]
  (-> macro-types*
      (swap! update macro-type
             #(merge-symbols % new-symbols &env))
      ut/quote-vals))

(defmacro show-macros
  ([] (-> @macro-types*
          ut/quote-vals))
  ([macro-type] (-> @macro-types*
                    (select-keys [macro-type])
                    ut/quote-vals)))
