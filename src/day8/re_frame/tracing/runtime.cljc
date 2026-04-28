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

     (wrap-handler! :event :foo/bar {:locals true :once true} (fn [db [_ x]] ...))
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
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            #?(:clj [day8.re-frame.tracing :as tracing])))

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

(defn ^boolean unwrap-handler!
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
      (registrar/register-handler kind id orig)
      (swap! wrapped-originals dissoc [kind id])
      true)
    false))

(defn ^boolean unwrap-sub!
  "Convenience: (unwrap-handler! :sub id)."
  [id]
  (unwrap-handler! :sub id))

(defn ^boolean unwrap-fx!
  "Convenience: (unwrap-handler! :fx id)."
  [id]
  (unwrap-handler! :fx id))

;; ---------------------------------------------------------------------------
;; Wrap — macros, because fn-traced operates on the AST
;; ---------------------------------------------------------------------------

#?(:clj
   (do
     (defn- fn-form?
       [form]
       (and (seq? form)
            (contains? '#{fn clojure.core/fn cljs.core/fn}
                       (first form))))

     (defn- traced-call
       [trace-macro opts args+body]
       (if (nil? opts)
         `(~trace-macro ~@args+body)
         `(~trace-macro ~opts ~@args+body)))

     (defn- traced-fn-form
       [context opts form]
       (when-not (fn-form? form)
         (throw (IllegalArgumentException.
                 (str context " requires a literal (fn ...) form to trace, got: "
                      (pr-str form)))))
       (traced-call `tracing/fn-traced opts (rest form)))

     (defn- traced-fx-form
       [context opts form]
       (when-not (fn-form? form)
         (throw (IllegalArgumentException.
                 (str context " requires a literal (fn ...) form to trace, got: "
                      (pr-str form)))))
       (traced-call `tracing/fx-traced opts (rest form)))

     (defn- split-wrap-opts
       [registration-args]
       (if (map? (first registration-args))
         [(first registration-args) (rest registration-args)]
         [nil registration-args]))

     (defn- sub-registration-args
       [opts registration-args]
       (let [computation-fn (last registration-args)]
         (when-not (seq registration-args)
           (throw (IllegalArgumentException.
                   "wrap-sub! requires reg-sub args ending in a literal (fn ...) computation function")))
         (concat (butlast registration-args)
                 [(traced-fn-form "wrap-sub!" opts computation-fn)])))

     (defn- single-handler-registration
       [context opts registration-args register-form]
       (if (= 1 (count registration-args))
         (register-form (traced-fn-form context opts (first registration-args)))
         `(throw (ex-info ~(str context " expects exactly one literal (fn ...) replacement")
                          {}))))

     (defn- single-fx-registration
       [context opts registration-args register-form]
       (if (= 1 (count registration-args))
         (register-form (traced-fx-form context opts (first registration-args)))
         `(throw (ex-info ~(str context " expects exactly one literal (fn ...) replacement")
                          {}))))

     (defmacro wrap-handler!
     "Capture the current handler at [kind id] into the side-table,
      then re-register a `fn-traced`-wrapped version of the replacement
      handler.

      For `:event` and `:fx`, pass exactly one literal
      `(fn [args] body...)` replacement form.
      Optionally pass a literal opts map before the replacement fn; it
      is forwarded to `fn-traced`.

      For `:sub`, pass the full `reg-sub` argument tail:

        (wrap-handler! :sub id (fn [db q] ...))
        (wrap-handler! :sub id {:locals true} (fn [db q] ...))
        (wrap-handler! :sub id signal-fn (fn [inputs q] ...))
        (wrap-handler! :sub id :<- [:other] (fn [input q] ...))

      The final subscription computation function MUST be a literal
      `(fn ...)` form so fn-traced can walk its AST at expansion time;
      signal fns and :<- sugar are preserved untraced.

      Returns `[kind id]` on success — thread-friendly for let-bindings.
      Refuses to wrap and returns a failure map in two cases:

        * `{:ok? false :reason :already-wrapped :kind k :id id}` — there
          is already a wrap recorded at `[kind id]`. Without this guard,
          the second wrap's `get-handler` would capture the previously-
          wrapped fn as 'original', and unwrap would later restore the
          intermediate wrapper instead of the user's true original.

        * `{:ok? false :reason :no-handler :kind k :id id}` — no handler
          is registered at `[kind id]` (registrar returns nil). Without
          this guard, unwrap would re-register `nil` and corrupt the
          registrar.

      Both refusals are no-ops: the side-table and registrar are left
      untouched. Caller is expected to `unwrap-handler!` first (or
      `register-handler` first) before re-issuing the wrap.

      Dispatch by kind:
        :event → re-frame.core/reg-event-db (default; interceptor
                 chain is :db-handler-flavoured)
        :sub   → re-frame.core/reg-sub
        :fx    → re-frame.core/reg-fx

      Use wrap-event-fx! / wrap-event-ctx! for events that need
      the matching reg-event-fx / reg-event-ctx interceptor chain."
     [kind id & registration-args]
     (let [[opts registration-args] (split-wrap-opts registration-args)
           k-sym     (gensym "k__")
           id-sym    (gensym "id__")
           orig-sym  (gensym "orig__")
           sub-args   (sub-registration-args opts registration-args)
           event-form (single-handler-registration
                        "wrap-handler! :event"
                        opts
                        registration-args
                        (fn [traced]
                          `(rf/reg-event-db ~id-sym ~traced)))
           fx-form    (single-handler-registration
                        "wrap-handler! :fx"
                        opts
                        registration-args
                        (fn [traced]
                          `(rf/reg-fx ~id-sym ~traced)))]
       `(let [~k-sym    ~kind
              ~id-sym   ~id
              ~orig-sym (registrar/get-handler ~k-sym ~id-sym)]
          (cond
            (contains? @wrapped-originals [~k-sym ~id-sym])
            {:ok? false :reason :already-wrapped :kind ~k-sym :id ~id-sym}

            (nil? ~orig-sym)
            {:ok? false :reason :no-handler :kind ~k-sym :id ~id-sym}

            :else
            (do
              (swap! wrapped-originals assoc [~k-sym ~id-sym] ~orig-sym)
              (case ~k-sym
                :event ~event-form
                :sub   (rf/reg-sub ~id-sym ~@sub-args)
                :fx    ~fx-form)
              [~k-sym ~id-sym])))))
     ))

#?(:clj
   (defmacro wrap-event-fx!
     "Like wrap-handler! but uses re-frame.core/reg-event-fx so the
      traced body returns an effects map (not an updated db).

      Wraps the replacement with `fx-traced` (not bare `fn-traced`)
      so per-key entries of the returned effect-map surface as
      :fx-effects trace tags alongside the usual per-form :code
      payload. This matches the source-edit
      `(reg-event-fx :foo (fx-traced [...] ...))` trace surface —
      runtime-wrapped event-fx handlers expose the same data 10x's
      Code/Fx panels and re-frame-pair render for source-instrumented
      handlers.

      Returns `[:event id]` on success; refuses with the same
      `{:ok? false :reason :already-wrapped|:no-handler …}` shape
      as wrap-handler! when [:event id] is already wrapped or has
      no registered handler. Optionally pass a literal opts map before
      the replacement fn; it is forwarded to `fx-traced`."
     [id & registration-args]
     (let [[opts registration-args] (split-wrap-opts registration-args)
           id-sym    (gensym "id__")
           orig-sym  (gensym "orig__")
           event-form (single-fx-registration
                        "wrap-event-fx!"
                        opts
                        registration-args
                        (fn [traced]
                          `(rf/reg-event-fx ~id-sym ~traced)))]
       `(let [~id-sym   ~id
              ~orig-sym (registrar/get-handler :event ~id-sym)]
          (cond
            (contains? @wrapped-originals [:event ~id-sym])
            {:ok? false :reason :already-wrapped :kind :event :id ~id-sym}

            (nil? ~orig-sym)
            {:ok? false :reason :no-handler :kind :event :id ~id-sym}

            :else
            (do
              (swap! wrapped-originals assoc [:event ~id-sym] ~orig-sym)
              ~event-form
              [:event ~id-sym]))))))

#?(:clj
   (defmacro wrap-event-ctx!
     "Like wrap-handler! but uses re-frame.core/reg-event-ctx so the
      traced body receives and returns a context map.

      Wraps with `fx-traced` in `:ctx-mode` so per-key entries of
      the returned context's `:effects` map surface as :fx-effects
      trace tags. A context handler returns the entire context, but
      :fx-effects emission targets the `(:effects ctx)` sub-map —
      the same per-effect breakdown that wrap-event-fx! produces.

      Returns `[:event id]` on success; refuses with the same
      `{:ok? false :reason :already-wrapped|:no-handler …}` shape
      as wrap-handler! when [:event id] is already wrapped or has
      no registered handler. Optionally pass a literal opts map before
      the replacement fn; it is forwarded to `fx-traced` alongside
      `:ctx-mode true`."
     [id & registration-args]
     (let [[opts registration-args] (split-wrap-opts registration-args)
           id-sym    (gensym "id__")
           orig-sym  (gensym "orig__")
           ctx-opts  (assoc (or opts {}) :ctx-mode true)
           event-form (single-fx-registration
                        "wrap-event-ctx!"
                        ctx-opts
                        registration-args
                        (fn [traced]
                          `(rf/reg-event-ctx ~id-sym ~traced)))]
       `(let [~id-sym   ~id
              ~orig-sym (registrar/get-handler :event ~id-sym)]
          (cond
            (contains? @wrapped-originals [:event ~id-sym])
            {:ok? false :reason :already-wrapped :kind :event :id ~id-sym}

            (nil? ~orig-sym)
            {:ok? false :reason :no-handler :kind :event :id ~id-sym}

            :else
            (do
              (swap! wrapped-originals assoc [:event ~id-sym] ~orig-sym)
              ~event-form
              [:event ~id-sym]))))))

#?(:clj
   (defmacro wrap-sub!
     "Convenience: (wrap-handler! :sub id & reg-sub-args).

      Accepts layer-2, explicit signal-fn, and :<- sugar subscription
      registration shapes. Only the final computation fn is wrapped
      with fn-traced; input-signal wiring is preserved."
     [id & registration-args]
     `(wrap-handler! :sub ~id ~@registration-args)))

#?(:clj
   (defmacro wrap-fx!
     "Convenience: (wrap-handler! :fx id replacement).

      Note: fx handlers receive the effect's :value as their argument
      (not the standard [db ev] / [ctx ev] shape). The trace payload
      will reflect what the user's fn does with that value — typically
      a side-effecting call, so :code will surface intermediate
      computations on the value before the side-effect fires.

      Per docs/improvement-plan.md §5, wrap-fx! is the path to
      surfacing fx-map traces without modifying the zipper walker's
      map-handling branch in dbgn.clj."
     [id & registration-args]
     `(wrap-handler! :fx ~id ~@registration-args)))

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

(defn ^boolean wrapped?
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
