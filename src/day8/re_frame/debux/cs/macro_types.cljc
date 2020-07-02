(ns day8.re-frame.debux.cs.macro-types
  (:require [clojure.set :as set]
            [day8.re-frame.debux.common.util :as ut]))

(def macro-types*
  (atom #?(:cljs {:def-type '#{def cljs.core/defonce}
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

                  :thread-first-type `#{cljs.core/-> cljs.core/some-> cljs.core/doto}
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

                  :dot-type '#{.}
                  :dot-dot-type `#{cljs.core/..}}
           :clj {:def-type `#{def defonce}
                 :defn-type `#{defn defn-}
                 :fn-type `#{fn fn*}

                 :let-type
                 `#{let binding dotimes if-let if-some loop when-first when-let
                    when-some with-in-str with-local-vars with-open with-out-str
                    with-redefs}
                 :letfn-type `#{letfn}
                 :loop-type `#{loop}

                 :for-type `#{for doseq}
                 :case-type `#{case}

                 :thread-first-type `#{-> some-> doto}
                 :thread-last-type `#{->> some->>}
                 :cond-first-type `#{cond->}
                 :cond-last-type `#{cond->>}

                 :skip-arg-1-type `#{set! with-precision}
                 :skip-arg-2-type `#{as->}
                 :skip-arg-1-2-type `#{}
                 :skip-arg-1-3-type `#{defmethod}
                 :skip-arg-2-3-type `#{amap areduce}
                 :skip-form-itself-type
                 `#{catch comment declare definline definterface defmacro defmulti
                    defprotocol defrecord defstruct deftype extend-protocol
                    extend-type finally gen-class gen-interface import memfn
                    new ns proxy proxy-super quote refer-clojure reify sync
                    var throw day8.re-frame.debux.core/dbg day8.re-frame.debux.core/dbgn}

                 :dot-type `#{.}
                 :dot-dot-type `#{clojure.core/..}})))


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

