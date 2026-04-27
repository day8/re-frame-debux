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
   (defmacro wrap-handler!
     "Production stub. Routes to bare `reg-event-db` / `reg-sub` /
      `reg-fx` by kind — no `fn-traced` wrap, no side-table
      mutation. Returns [kind id]."
     [kind id replacement]
     (let [args+body (rest replacement)]
       `(let [k#  ~kind
              id# ~id]
          (case k#
            :event (re-frame.core/reg-event-db id# (fn ~@args+body))
            :sub   (re-frame.core/reg-sub      id# (fn ~@args+body))
            :fx    (re-frame.core/reg-fx       id# (fn ~@args+body)))
          [k# id#]))))

#?(:clj
   (defmacro wrap-event-fx!
     "Production stub. Routes to bare `reg-event-fx` — no `fn-traced`
      wrap. Returns [:event id]."
     [id replacement]
     (let [args+body (rest replacement)]
       `(let [id# ~id]
          (re-frame.core/reg-event-fx id# (fn ~@args+body))
          [:event id#]))))

#?(:clj
   (defmacro wrap-event-ctx!
     "Production stub. Routes to bare `reg-event-ctx` — no `fn-traced`
      wrap. Returns [:event id]."
     [id replacement]
     (let [args+body (rest replacement)]
       `(let [id# ~id]
          (re-frame.core/reg-event-ctx id# (fn ~@args+body))
          [:event id#]))))

#?(:clj
   (defmacro wrap-sub!
     "Production stub. Convenience: (wrap-handler! :sub id replacement)."
     [id replacement]
     `(wrap-handler! :sub ~id ~replacement)))

#?(:clj
   (defmacro wrap-fx!
     "Production stub. Convenience: (wrap-handler! :fx id replacement)."
     [id replacement]
     `(wrap-handler! :fx ~id ~replacement)))

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
