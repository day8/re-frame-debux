(ns day8.re-frame.tracing.runtime
  "Runtime API for swapping `fn-traced`-wrapped handlers in and out of
   re-frame's registrar without source edits.

   Today the only entry point to `fn-traced` is the macro at source-edit
   time. Tools that drive re-frame from a REPL — re-frame-pair in
   particular — want to instrument a handler **on demand**, dispatch
   the event, observe the per-form `:code` payload, then restore the
   original handler. Doing that by hand requires synthesising a
   `(reg-event-db id (fn-traced [...] ...))` form at the REPL and
   re-evaluating the user's source to restore — error-prone, and
   restore is easy to forget.

   This namespace makes the wrap → dispatch → unwrap cycle a
   first-class API:

     (wrap-handler! :event :foo/bar (fn [db [_ x]] ...))
     ;; <dispatch / observe / etc.>
     (unwrap-handler! :event :foo/bar)

   Wrap captures the original handler (as currently registered, with
   whatever interceptor chain) into a side-table. Unwrap restores
   verbatim from the side-table — bypassing `reg-event-db`'s chain
   builder, so nested interceptors and any other wrapping the original
   carried come back intact.

   Limitations:

   - The `replacement-fn` argument must be a literal `(fn [...] ...)`
     form. `fn-traced` operates on the AST at compile time; you can't
     re-trace a precompiled fn value because its body is gone. For
     re-frame-pair-style on-demand wrapping, this is what you want:
     the agent has the source available (or asks the user) and passes
     it back via `eval-cljs.sh`.

   - For `:event` handlers we register through
     `re-frame.core/reg-event-db`. Handlers originally registered with
     `reg-event-fx` / `reg-event-ctx` get a `:db-handler` chain on
     wrap; on unwrap the original chain is restored. If the
     wrapped-form's contract differs (e.g. the user's traced body
     returns an effects map instead of an app-db), use `wrap-event-fx!`
     / `wrap-event-ctx!` instead.

   - The side-table is process-local. A full page reload (or shadow-
     cljs hot-reload of this ns) drops it; that's the same as
     re-frame's own registrar and is generally what callers want."
  #?(:cljs (:require-macros [day8.re-frame.tracing.runtime]))
  (:require [re-frame.core]
            [re-frame.registrar]
            #?(:clj [day8.re-frame.tracing])))

;; ---------------------------------------------------------------------------
;; Side-table — [kind id] → original handler value
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Map of [kind id] → the registrar value that was at
  that location BEFORE wrap-handler! replaced it. Public for tools
  that want to inspect the wrap state (re-frame-pair, custom panels)."}
  wrapped-originals
  (atom {}))

;; ---------------------------------------------------------------------------
;; Unwrap — pure runtime, no macro magic
;; ---------------------------------------------------------------------------

(defn unwrap-handler!
  "Restore the handler at [kind id] to whatever was there before
   `wrap-handler!` ran. Returns true if a wrap was found and undone,
   false if [kind id] wasn't wrapped (no-op).

   Restores via the low-level `register-handler` so the original
   interceptor chain (or fn for :sub / :fx / :cofx) comes back
   verbatim — `reg-event-db` etc. wouldn't, since they'd build a
   fresh chain around whatever was stored."
  [kind id]
  (if-let [orig (get @wrapped-originals [kind id])]
    (do
      (re-frame.registrar/register-handler kind id orig)
      (swap! wrapped-originals dissoc [kind id])
      true)
    false))

(defn unwrap-sub!
  "Convenience: (unwrap-handler! :sub id)."
  [id]
  (unwrap-handler! :sub id))

(defn unwrap-fx!
  "Convenience: (unwrap-handler! :fx id)."
  [id]
  (unwrap-handler! :fx id))

;; ---------------------------------------------------------------------------
;; Wrap — macros, because fn-traced operates on the AST
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro wrap-handler!
     "Capture the current handler at [kind id] into the side-table,
      then re-register a `fn-traced`-wrapped version of `replacement`.

      `replacement` MUST be a literal `(fn [args] body...)` form so
      fn-traced can walk its AST at expansion time.

      Returns [kind id] for thread-friendly use in let-bindings.

      Dispatch by kind:
        :event → re-frame.core/reg-event-db (default; interceptor
                 chain is :db-handler-flavoured)
        :sub   → re-frame.core/reg-sub
        :fx    → re-frame.core/reg-fx

      Use wrap-event-fx! / wrap-event-ctx! for events that need
      the matching reg-event-fx / reg-event-ctx interceptor chain."
     [kind id replacement]
     (let [args+body (rest replacement)]
       `(do
          (swap! wrapped-originals assoc [~kind ~id]
                 (re-frame.registrar/get-handler ~kind ~id))
          (case ~kind
            :event (re-frame.core/reg-event-db ~id
                                               (day8.re-frame.tracing/fn-traced ~@args+body))
            :sub   (re-frame.core/reg-sub ~id
                                          (day8.re-frame.tracing/fn-traced ~@args+body))
            :fx    (re-frame.core/reg-fx ~id
                                         (day8.re-frame.tracing/fn-traced ~@args+body)))
          [~kind ~id]))))

#?(:clj
   (defmacro wrap-event-fx!
     "Like wrap-handler! but uses re-frame.core/reg-event-fx so the
      traced body returns an effects map (not an updated db)."
     [id replacement]
     (let [args+body (rest replacement)]
       `(do
          (swap! wrapped-originals assoc [:event ~id]
                 (re-frame.registrar/get-handler :event ~id))
          (re-frame.core/reg-event-fx ~id
                                      (day8.re-frame.tracing/fn-traced ~@args+body))
          [:event ~id]))))

#?(:clj
   (defmacro wrap-event-ctx!
     "Like wrap-handler! but uses re-frame.core/reg-event-ctx so the
      traced body receives and returns a context map."
     [id replacement]
     (let [args+body (rest replacement)]
       `(do
          (swap! wrapped-originals assoc [:event ~id]
                 (re-frame.registrar/get-handler :event ~id))
          (re-frame.core/reg-event-ctx ~id
                                       (day8.re-frame.tracing/fn-traced ~@args+body))
          [:event ~id]))))

#?(:clj
   (defmacro wrap-sub!
     "Convenience: (wrap-handler! :sub id replacement)."
     [id replacement]
     `(wrap-handler! :sub ~id ~replacement)))

#?(:clj
   (defmacro wrap-fx!
     "Convenience: (wrap-handler! :fx id replacement).

      Note: fx handlers receive the effect's :value as their argument
      (not the standard [db ev] / [ctx ev] shape). The trace payload
      will reflect what the user's fn does with that value — typically
      a side-effecting call, so :code will surface intermediate
      computations on the value before the side-effect fires.

      Per docs/improvement-plan.md §5, wrap-fx! is the path to
      surfacing fx-map traces (the dbgn.clj:341 'trace inside maps'
      TODO) without modifying the zipper walker."
     [id replacement]
     `(wrap-handler! :fx ~id ~replacement)))

;; ---------------------------------------------------------------------------
;; Inspect — for tools that want to know what's wrapped
;; ---------------------------------------------------------------------------

(defn ^boolean runtime-api?
  "True when this namespace is loaded — i.e. when the on-demand
   instrumentation surface (wrap-handler! / unwrap-handler! /
   wrap-fx! / wrapped? / unwrap-all! / etc.) is reachable in this
   runtime.

   Stable feature-detection hook for tools (re-frame-pair, custom
   10x panels, integrators) that want to dispatch between this
   API and a fallback path on older releases that ship only the
   fn-traced macro. The presence of the var IS the contract —
   callers probe its munged JS path via `goog.global` so they
   don't have to require the namespace at compile time:

       ;; CLJS feature-detection from outside the lib
       (boolean
         (when-let [g (some-> js/goog .-global)]
           (aget-path g [\"day8\" \"re_frame\" \"tracing\" \"runtime\"
                         \"runtime_api_QMARK_\"])))

   This is the recommended detection probe — more durable than
   probing individual fns (e.g. wrap-handler!) since the var name
   is dedicated to advertising availability and won't be renamed
   away in a refactor.

   The body is `true`; callers shouldn't read meaning into the
   return value beyond 'this fn ran'."
  []
  true)

(defn wrapped?
  "True iff [kind id] is currently wrapped (i.e. the side-table has
   an entry for it). Useful for re-frame-pair / custom panels that
   want to badge the wrapped state in their UI."
  [kind id]
  (contains? @wrapped-originals [kind id]))

(defn wrapped-list
  "Vec of [kind id] tuples currently wrapped, in no particular order."
  []
  (vec (keys @wrapped-originals)))

(defn unwrap-all!
  "Restore every currently-wrapped handler. Returns the vec of
   [kind id] tuples that were unwrapped, in the order they were
   restored.

   Useful after a debugging session to make sure no traced wrappers
   leak into subsequent dispatches; equivalent to calling
   unwrap-handler! on each entry of wrapped-list."
  []
  (let [keys-to-unwrap (vec (keys @wrapped-originals))]
    (doseq [[kind id] keys-to-unwrap]
      (unwrap-handler! kind id))
    keys-to-unwrap))
