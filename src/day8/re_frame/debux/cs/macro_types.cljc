(ns day8.re-frame.debux.cs.macro-types
  (:require [clojure.set :as set]
            [day8.re-frame.debux.common.util :as ut]))

(def macro-types*
  (atom {:def-type '#{def cljs.core/defonce}
         :defn-type '#{cljs.core/defn cljs.core/defn-}
         :fn-type '#{cljs.core/fn fn*}

         :let-type
         '#{cljs.core/let cljs.core/binding cljs.core/dotimes cljs.core/if-let
            cljs.core/if-some cljs.core/loop cljs.core/when-first cljs.core/when-let
            cljs.core/when-some cljs.core/with-out-str cljs.core/with-redefs}
         :letfn-type '#{cljs.core/letfn}
         :loop-type '#{cljs.core/loop}

         :for-type '#{cljs.core/for cljs.core/doseq}
         :case-type '#{cljs.core/case}

         :thread-first-type `#{cljs.core/-> cljs.core/some->}
         :thread-last-type `#{cljs.core/->> cljs.core/some->>}
         :cond-first-type `#{cljs.core/cond->}
         :cond-last-type `#{cljs.core/cond->>}

         :skip-arg-1-type '#{set!}
         :skip-arg-2-type '#{cljs.core/as->}
         :skip-arg-1-2-type '#{}
         :skip-arg-2-3-type '#{cljs.core/amap cljs.core/areduce}
         :skip-arg-1-3-type '#{cljs.core/defmethod}
         :skip-form-itself-type
         '#{catch cljs.core/comment cljs.core/declare cljs.core/defmacro
            cljs.core/defmulti cljs.core/defprotocol cljs.core/defrecord
            cljs.core/deftype cljs.core/extend-protocol cljs.core/extend-type
            finally cljs.core/import cljs.core/memfn new quote
            cljs.core/refer-clojure cljs.core/reify var throw
            day8.re-frame.debux.cs.core/dbg debux.cs.core/dbgn
            day8.re-frame.debux.cs.core/clog debux.cs.core/clogn}

         :expand-type
         '#{cljs.core/.. #_cljs.core/-> #_cljs.core/->> cljs.core/doto
            #_cljs.core/cond-> #_cljs.core/cond->> #_cljs.core/condp cljs.core/import
            #_cljs.core/some-> #_cljs.core/some->>}
         :dot-type '#{.}}))


(defn- merge-symbols [old-symbols new-symbols env]
  (->> (map #(ut/ns-symbol % env)
            new-symbols)
       set
       (set/union old-symbols)))

(defmacro register-macros! [macro-type new-symbols]
  (-> (swap! macro-types* update macro-type
             #(merge-symbols % new-symbols &env))
      ut/quote-vals))

(defmacro show-macros
  ([] (-> @macro-types*
          ut/quote-vals))
  ([macro-type] (-> (select-keys @macro-types* [macro-type])
                    ut/quote-vals)))

