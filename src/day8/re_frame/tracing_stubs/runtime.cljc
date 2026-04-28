(ns day8.re-frame.tracing-stubs.runtime
  "Production stub for the runtime API. The dev-side namespace at
   src/day8/re_frame/tracing/runtime.cljc captures the original
   handler in a side-table and re-registers an `fn-traced`-wrapped
   replacement; in a release build (shadow-cljs :ns-aliases setup)
   this stub takes over and compiles every wrap-handler! /
   wrap-event-fx! / wrap-event-ctx! / wrap-sub! / wrap-fx! call
   down to a bare `re-frame.core/reg-event-db` (or sibling) — no
   `fn-traced` wrap, no zipper walking, zero per-handler trace cost.

   `wrapped-originals` exists as a regular atom so any external
   reference compiles, but the stub macros never write to it and the
   unwrap-* / wrapped? / wrapped-list / unwrap-all! runtime fns are
   no-ops. The shape mirrors the dev-side public surface so existing
   call sites compile unchanged under :ns-aliases or two-jar
   production wiring."
  #?(:cljs (:require-macros [day8.re-frame.tracing-stubs.runtime]))
  (:require [re-frame.core]))

(defonce wrapped-originals (atom {}))

#?(:clj
   (do
     (defn- fn-form?
       [form]
       (and (seq? form)
            (contains? '#{fn clojure.core/fn cljs.core/fn}
                       (first form))))

     (defn- bare-fn-form
       [context form]
       (when-not (fn-form? form)
         (throw (IllegalArgumentException.
                 (str context " requires a literal (fn ...) form, got: "
                      (pr-str form)))))
       `(fn ~@(rest form)))

     (defn- single-handler-registration
       [context registration-args register-form]
       (if (= 1 (count registration-args))
         (register-form (bare-fn-form context (first registration-args)))
         `(throw (ex-info ~(str context " expects exactly one literal (fn ...) replacement")
                          {}))))

     (defn- split-wrap-opts
       [registration-args]
       (if (map? (first registration-args))
         [(first registration-args) (rest registration-args)]
         [nil registration-args]))

     (defmacro wrap-handler!
     "Production stub. Routes to bare `reg-event-db` / `reg-sub` /
      `reg-fx` by kind — no `fn-traced` wrap, no side-table
      mutation. Accepts, but ignores, a leading opts map. Returns [kind id]."
     [kind id & registration-args]
     (let [[_opts registration-args] (split-wrap-opts registration-args)
           k-sym     (gensym "k__")
           id-sym    (gensym "id__")
           event-form (single-handler-registration
                        "wrap-handler! :event"
                        registration-args
                        (fn [bare-fn]
                          `(re-frame.core/reg-event-db ~id-sym ~bare-fn)))
           fx-form    (single-handler-registration
                        "wrap-handler! :fx"
                        registration-args
                        (fn [bare-fn]
                          `(re-frame.core/reg-fx ~id-sym ~bare-fn)))]
       `(let [~k-sym  ~kind
              ~id-sym ~id]
          (case ~k-sym
            :event ~event-form
            :sub   (re-frame.core/reg-sub ~id-sym ~@registration-args)
            :fx    ~fx-form)
          [~k-sym ~id-sym])))))

#?(:clj
   (defmacro wrap-event-fx!
     "Production stub. Routes to bare `reg-event-fx` — no `fn-traced`
      wrap. Accepts, but ignores, a leading opts map. Returns [:event id]."
     [id & registration-args]
     (let [[_opts registration-args] (split-wrap-opts registration-args)
           id-sym    (gensym "id__")
           event-form (single-handler-registration
                        "wrap-event-fx!"
                        registration-args
                        (fn [bare-fn]
                          `(re-frame.core/reg-event-fx ~id-sym ~bare-fn)))]
       `(let [~id-sym ~id]
          ~event-form
          [:event ~id-sym]))))

#?(:clj
   (defmacro wrap-event-ctx!
     "Production stub. Routes to bare `reg-event-ctx` — no `fn-traced`
      wrap. Accepts, but ignores, a leading opts map. Returns [:event id]."
     [id & registration-args]
     (let [[_opts registration-args] (split-wrap-opts registration-args)
           id-sym    (gensym "id__")
           event-form (single-handler-registration
                        "wrap-event-ctx!"
                        registration-args
                        (fn [bare-fn]
                          `(re-frame.core/reg-event-ctx ~id-sym ~bare-fn)))]
       `(let [~id-sym ~id]
          ~event-form
          [:event ~id-sym]))))

#?(:clj
   (defmacro wrap-sub!
     "Production stub. Convenience: (wrap-handler! :sub id & reg-sub-args)."
     [id & registration-args]
     `(wrap-handler! :sub ~id ~@registration-args)))

#?(:clj
   (defmacro wrap-fx!
     "Production stub. Convenience: (wrap-handler! :fx id replacement)."
     [id & registration-args]
     `(wrap-handler! :fx ~id ~@registration-args)))

(defn unwrap-handler!
  "Production stub. No-op — always returns false (the stub side-table
   is never populated)."
  [_kind _id]
  false)

(defn unwrap-sub!
  "Production stub. Convenience alias — no-op, returns false."
  [id]
  (unwrap-handler! :sub id))

(defn unwrap-fx!
  "Production stub. Convenience alias — no-op, returns false."
  [id]
  (unwrap-handler! :fx id))

(defn ^boolean runtime-api?
  "Production stub. Returns true — the API surface IS reachable at
   runtime, even though wrap-* don't trace and unwrap-* are no-ops.
   Tools feature-detecting via this var get the same 'available'
   signal as in dev."
  []
  true)

(defn wrapped?
  "Production stub. No-op — always returns false."
  [_kind _id]
  false)

(defn wrapped-list
  "Production stub. No-op — returns an empty vec."
  []
  [])

(defn unwrap-all!
  "Production stub. No-op — returns an empty vec."
  []
  [])
