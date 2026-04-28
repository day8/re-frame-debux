(ns day8.re-frame.debux.common.util
  "Utilities common for clojure and clojurescript"
  (:refer-clojure :exclude [coll?])
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.zip :as z]
            [clojure.walk :as walk]
            [clojure.repl :as repl]
            [re-frame.trace :as trace]))

(defn map->seq[m]
  (reduce
    (fn [r [k v]]
      (concat r [k v]))
    []
    m))
  
;;; zipper
(defn sequential-zip [root]
  (z/zipper #(or (sequential? %) (map? %)) 
            (fn [x]
              (cond 
                (map? x)    (with-meta (map->seq x) (meta x))
                :else       x))
            (fn [x children]
              (cond 
                (vector? x) (with-meta (vec children) (meta x))
                (map? x)    (with-meta
                              (reduce
                                (fn [r [k v]]
                                    (assoc r k v))
                                  {}
                                  (partition 2 children))
                              (meta x))
                :else children))
            root))

(defn right-or-next [loc]
  (if-let [right (z/right loc)]
    ;; in case of (... (+ a b) c) or (... a b)
    right
    (if (sequential? (z/node loc))
      (let [rightmost (-> loc z/down z/rightmost)]
        (if (sequential? (z/node rightmost))
          ;; in case of (... (+ a (* b c)))
          (recur rightmost)

          ;; in case of (... (+ a b))
          (-> rightmost z/next)))

      ;; in case of (... a)
      (-> loc z/next))))


;; Tidy up macroexpansions

(def auto-gensym-pattern #"(.*)__\d+__auto__")              ;; form#
(def anon-gensym-pattern #"G__\d+")                         ;; (gensym)
(def named-gensym-pattern #?(:clj #"(.*?)\d{4,}"            ;; (gensym 'form), must match at least 4 numbers so we don't catch symbols with trailing numbers
                             :cljs #"(.*?)\d+"))            ;; (gensym 'form), CLJS gensym counters start at 1
(def anon-param-pattern #"p(\d+)__\d+#")                    ;; #(%1 %2 %3)

(defn form-tree-seq [form]
  (tree-seq
    sequential?
    seq
    form))

(defn with-gensyms-names
  "Reverse gensym'd names to their original source form to make them easier to read."
  [form mapping]
  (let [gen-name (fn [result name]
                   (if-not (contains? result (str name "#"))
                     (str name "#")
                     (->> (iterate inc 2)
                          (map #(str name % "#"))
                          (filter #(not (contains? result %)))
                          (first))))
        name-for (fn [result sym-name]
                   (if-let [groups (re-matches auto-gensym-pattern sym-name)]
                     (gen-name result (second groups))
                     (if (re-matches anon-gensym-pattern sym-name)
                       (gen-name result "gensym")
                       (if-let [groups (re-matches named-gensym-pattern sym-name)]
                         (gen-name result (second groups))
                         (if-let [groups (re-matches anon-param-pattern sym-name)]
                           (str "%" (second groups)))))))]
    (reduce (fn [result sym-name]
              (if (contains? result sym-name)
                result
                (if-let [new-name (name-for result sym-name)]
                  (assoc result sym-name new-name)
                  result)))
            mapping
            (->> (form-tree-seq form)
                 (filter #(and (symbol? %) (nil? (namespace %))))
                 (map name)))))

(defn with-symbols-names
  "Tidy up fully qualified names that have aliases in the existing namespace."
  ;; TODO: handle FQN's other than clojure.core
  [form {:keys [context refers aliases] :as state} mapping]
  (reduce (fn [result sym]
            (if (= "clojure.core" (namespace sym))
              (assoc result (pr-str sym) (name sym))
              result))
          mapping
          (->> (form-tree-seq form)
               ;; TODO: use qualified-symbol? once we are on Clojure 1.9
               (filter #(and (symbol? %) (namespace %))))))

(defn tidy-macroexpanded-form
  "Takes a macroexpanded form and tidies it up to be more readable by
  unmapping gensyms and replacing fully qualified namespaces with aliases
  or nothing if the function is referred."
  [form state]
  ;; Mapping is a mapping of String:String which represent symbols
  (let [mapping (->> {}
                     (with-gensyms-names form)
                     (with-symbols-names form state))]
    (loop [loc (sequential-zip form)]
      (if (z/end? loc)
        (z/root loc)
        (if (symbol? (z/node loc))
          (recur (z/next (z/edit loc (fn [sym] (symbol (get mapping (pr-str sym) sym))))))
          (recur (z/next loc)))))))

;;; ----------------------------------------------------------------------
;;; Trace sink — re-frame-trace integration
;;; ----------------------------------------------------------------------
;;;
;;; The two functions below are the only writers to re-frame's trace
;;; stream from re-frame-debux. Everything in `dbgn`'s zipper walk
;;; eventually funnels through one of them.
;;;
;;; `send-form!` writes a single `:form` tag once per traced function
;;; invocation, carrying the outermost macroexpanded form.
;;;
;;; `send-trace!` is called once per instrumented sub-form during the
;;; function's body. It accumulates each call into a vector under the
;;; `:code` tag of the current trace event (`re-frame.trace/*current-trace*`).
;;;
;;; Each entry in `:code` has this shape — this is the contract that
;;; re-frame-10x's "Code" panel and any other downstream consumer
;;; (e.g. re-frame-pair surfacing :code as :debux/code in its epoch
;;; coercion) reads:
;;;
;;;   {:form          <hiccup-printable form, post tidy-macroexpanded-form>
;;;    :result        <evaluated value of that form>
;;;    :indent-level  <int, nesting depth in the original source>
;;;    :syntax-order  <int, position in evaluation order>
;;;    :num-seen      <int, count of duplicate emissions for :once dedup>
;;;    :msg           <opt: developer-supplied label, from :msg / :m on
;;;                    the surrounding dbg / dbgn / fn-traced /
;;;                    defn-traced — see send-trace! whitelist>}
;;;
;;; Field semantics:
;;;
;;; - **`:form`** is the user-readable source form that was traced.
;;;   `tidy-macroexpanded-form` (above, line 100ish) replaces fully-
;;;   qualified names from `clojure.core` with their short forms and
;;;   strips gensym-suffixed names from `let`/`loop`/`for`-introduced
;;;   bindings. Macro-generated traces precompute that form; direct
;;;   `send-trace!` callers still get tidied here. The result is
;;;   human-readable, but NOT necessarily round-trippable through the
;;;   reader if the source contained reader-conditional or
;;;   shadow-cljs-specific forms.
;;;
;;; - **`:result`** is the value the form evaluated to. Stored as the
;;;   live value (no pr-str coercion here — that's the consumer's call,
;;;   typically with `set-print-seq-length!` to bound large collections).
;;;
;;; - **`:indent-level`** mirrors the form's nesting depth in the source
;;;   (0 = top-level call within the fn body; 1 = one form deep; etc.).
;;;   re-frame-10x uses this for tree-view indentation.
;;;
;;; - **`:syntax-order`** is the form's position in evaluation order
;;;   (zipper-walk order, depth-first L→R). Stable across runs of the
;;;   same handler. Used for tie-breaking when `:indent-level` matches.
;;;
;;; - **`:num-seen`** counts how many times this exact form has been
;;;   emitted previously in the SAME trace event. Always 0 today;
;;;   cross-event `:once` duplicate suppression is tracked separately
;;;   in a process-local side table keyed by trace id and syntax order.
;;;
;;; Example payload shape after one traced dispatch through a handler
;;; defined as `(fn-traced [db [_ x]] (let [n (* 2 x)] (assoc db :n n)))`:
;;;
;;;   {:form (let [n (* 2 x)] (assoc db :n n))
;;;    :result {:n 10 ...}
;;;    :indent-level 0 :syntax-order 0 :num-seen 0}
;;;   {:form (* 2 x)         :result 10 :indent-level 1 :syntax-order 1 :num-seen 0}
;;;   {:form (assoc db :n n) :result {:n 10 ...} :indent-level 1 :syntax-order 2 :num-seen 0}
;;;
;;; Stability: this shape has been stable since v0.5.x. If a future
;;; release adds new fields, append-only is the contract — existing
;;; consumers keep working.

;;; Production-mode loud-fail check (improvement-plan §5(b)).
;;; If a release build accidentally bundles the live
;;; day8.re-frame.tracing namespace instead of swapping to the stubs
;;; (via :ns-aliases or the production profile), tracing runs in
;;; production with no signal — bloating the bundle and emitting
;;; trace noise into 10x. The first send-trace! call in such a build
;;; fires a one-shot `console.warn` so the operator notices.
;;;
;;; goog.DEBUG is the closure-define that's true under :optimizations
;;; :none / dev and false under :optimizations :advanced / release.
;;; A live trace path running with goog.DEBUG=false is the smoking
;;; gun for "release build, tracing not stubbed".

(defonce ^:private prod-mode-warned? (atom false))

(defn ^:private maybe-warn-production-mode! []
  #?(:cljs
     (when-not @prod-mode-warned?
       (try
         (when (false? js/goog.DEBUG)
           (reset! prod-mode-warned? true)
           (js/console.warn
            (str "re-frame-debux: send-trace! is firing in a build with "
                 "goog.DEBUG=false. The day8.re-frame.tracing namespace "
                 "is loaded and active in what looks like a production "
                 "build (advanced compilation usually sets goog.DEBUG to "
                 "false). This bloats your bundle and emits trace noise "
                 "into 10x. Check your build config: shadow-cljs users "
                 "should set :ns-aliases to redirect day8.re-frame.tracing "
                 "→ day8.re-frame.tracing-stubs in release builds; "
                 "lein/cljsbuild users should put day8.re-frame/tracing-stubs "
                 "in the production profile instead of day8.re-frame/tracing. "
                 "See https://github.com/day8/re-frame-debux#two-libraries "
                 "for details. (This warning fires once per session.)")))
         (catch :default _
           ;; If goog.DEBUG isn't accessible (bare CLJS without Closure?),
           ;; mark as warned so we don't keep retrying.
           (reset! prod-mode-warned? true))))
     :clj nil))

;;; ----------------------------------------------------------------------
;;; tap> output channel — set-tap-output!
;;; ----------------------------------------------------------------------
;;;
;;; When enabled, every internal trace emitter ALSO routes its payload
;;; to `tap>`, so any `add-tap` consumer (re-frame-10x, an ad-hoc REPL
;;; probe, scripts/eval-cljs.sh) sees the FULL trace stream alongside
;;; the existing `trace/merge-trace!` sink. Default off — preserves
;;; the trace-only behaviour. Independent of trace state: even
;;; out-of-trace, an emitter call (e.g. `send-trace!` via
;;; `spy-first`/`last`/`comp` at the REPL) will emit to `tap>` when
;;; this flag is true.
;;;
;;; Each tap> payload carries a `:debux/kind` discriminator so
;;; consumers can route on it. The kinds and shapes:
;;;
;;;   {:debux/kind :form
;;;    :form        <tidied whole-form>}
;;;   ;; one per traced fn-call, from `send-form!` (mirrors :tags :form)
;;;
;;;   {:debux/kind :code
;;;    :form ... :result ... :indent-level ... :syntax-order ...
;;;    :num-seen ... [optional :locals :name :msg]}
;;;   ;; one per instrumented sub-form, from `send-trace!` (mirrors
;;;   ;; the :tags :code entry; see the contract block above
;;;   ;; send-trace! for field semantics)
;;;
;;;   {:debux/kind :frame-enter
;;;    :frame-id    <gensym string, paired with the matching :exit>
;;;    :t           <ms timestamp>
;;;    :msg         <opt: developer-supplied label>}
;;;   {:debux/kind :frame-exit
;;;    :frame-id    <same id as the paired :enter>
;;;    :t           <ms timestamp>
;;;    :msg         <opt: developer-supplied label>}
;;;   ;; one pair per fn-traced/defn-traced invocation, from
;;;   ;; `-send-frame-enter!` / `-send-frame-exit!` (mirrors :tags
;;;   ;; :trace-frames). Exception path: only :enter fires — same
;;;   ;; missing-:exit signal as on the trace channel.
;;;
;;;   {:debux/kind :fx-effect
;;;    :fx-key      <key from the effect-map>
;;;    :value       <value at that key>
;;;    :t           <ms timestamp; shared across all keys of one return>}
;;;   ;; one per key of an `fx-traced` body's effect-map return, from
;;;   ;; `-emit-fx-traces!` (mirrors :tags :fx-effects).
;;;
;;; Distinct from `send-trace-or-tap!`'s out-of-trace fallback, which
;;; tags its payload with `:debux/dbg true` — that's a separate
;;; per-dbg-call-site channel. A consumer can branch on either flag.

(defonce ^:private tap-output? (atom false))
(defonce ^:private trace-frames-output? (atom false))

(defn set-tap-output!
  "When `enabled?` is truthy, every debux trace emitter (`send-form!`,
   `send-trace!`, `-send-frame-enter!`, `-send-frame-exit!`,
   `-emit-fx-traces!`) also calls `tap>` so any `add-tap` consumer
   sees the trace alongside `trace/merge-trace!`. Each payload carries
   a `:debux/kind` discriminator (`:form`, `:code`, `:frame-enter`,
   `:frame-exit`, `:fx-effect`). False by default — preserves the
   existing trace-only behaviour. Independent of whether re-frame's
   trace machinery is enabled."
  [enabled?]
  (reset! tap-output? (boolean enabled?)))

(defn set-trace-frames-output!
  "When `enabled?` is truthy, `fn-traced` / `defn-traced` /
   `fx-traced` invocations emit paired `:enter` / `:exit` markers onto
   the active trace's :tags :trace-frames vector. False by default so
   normal tracing does not pay frame marker timestamp / merge overhead.

   Independent of `set-tap-output!`: tap output still emits frame
   payloads when tap output is on."
  [enabled?]
  (reset! trace-frames-output? (boolean enabled?)))

(defn frame-markers-enabled?
  "Internal predicate used by macro expansions to avoid calling frame
   marker emitters unless a trace-frame or tap consumer is enabled."
  []
  (or @trace-frames-output? @tap-output?))

(defn send-form! [form]
  (when (or @tap-output? @#'trace/trace-enabled?)
    (maybe-warn-production-mode!)
    (when @#'trace/trace-enabled?
      (trace/merge-trace! {:tags {:form form}}))
    (when @tap-output? (tap> {:debux/kind :form :form form}))))

(defn send-trace! [code-trace]
  ;; Gate on tap-output? OR trace-enabled? before any payload work
  ;; (form tidying, entry assembly, get-in over *current-trace*). When
  ;; both are off, the live tracing.cljc ns is still loaded but no
  ;; consumer wants the entry — bail before constructing one. The OR
  ;; preserves set-tap-output!'s documented "independent of re-frame's
  ;; trace machinery" contract: tap> still fires when tap-output? is
  ;; true even with trace-enabled? off.
  ;;
  ;; `@#'trace/trace-enabled?` deref rather than the bare
  ;; `trace/trace-enabled?` symbol so the var's `:tag` metadata (a
  ;; fn-instance — an inheritance from re-frame's CLJS-flavoured
  ;; `^boolean` def that mangles under JVM-CLJ macroexpand) doesn't
  ;; propagate through `or`'s internal let-binding and trigger a
  ;; tagToClass compile-time error.
  (when (or @tap-output? @#'trace/trace-enabled?)
    (maybe-warn-production-mode!)
    (let [;; :locals is an optional extension key
          ;; emitted by trace* when fn-traced was called with
          ;; {:locals true}. It carries [[sym val] ...] pairs captured
          ;; from the function args. Whitelisted explicitly so the
          ;; payload contract stays small (10x's Code panel reads
          ;; specific keys; merging arbitrary extras would be brittle).
          ;; :name (label from a `dbg` call) follows the same
          ;; whitelist convention; consumers can branch on its presence.
          ;; :msg (developer-supplied label, set by the :msg / :m opt on
          ;; the surrounding dbg / dbgn / fn-traced / defn-traced) joins
          ;; the same whitelist — propagated by the macro layer onto each
          ;; emitted code-trace, surfaced as a top-level :msg key.
          entry (cond-> {:form         (if (::form-tidied? (meta code-trace))
                                         (:form code-trace)
                                         (tidy-macroexpanded-form (:form code-trace) {}))
                         :result       (:result code-trace)
                         :indent-level (:indent-level code-trace)
                         :syntax-order (:syntax-order code-trace)
                         :num-seen     (:num-seen code-trace)}
                  (contains? code-trace :locals)
                  (assoc :locals (:locals code-trace))
                  (contains? code-trace :name)
                  (assoc :name (:name code-trace))
                  (contains? code-trace :msg)
                  (assoc :msg (:msg code-trace)))]
      ;; TODO: also capture macroexpanded form? Might be useful in some cases?
      (when trace/trace-enabled?
        (let [code (get-in trace/*current-trace* [:tags :code] [])]
          (trace/merge-trace! {:tags {:code (conj code entry)}})))
      ;; Optional tap> sink — see set-tap-output! above. Independent of
      ;; the re-frame trace channel so add-tap consumers (10x, ad-hoc REPL
      ;; probes) see entries even when trace-enabled? is off. The
      ;; `:debux/kind :code` discriminator lets consumers route the
      ;; payload alongside :form / :frame-enter / :frame-exit /
      ;; :fx-effect emissions on the same tap stream.
      (when @tap-output? (tap> (assoc entry :debux/kind :code))))))

;; Sink dispatch for the dbg macro. Inside a re-frame trace
;; event (`*current-trace*` bound non-nil during with-trace) accumulate
;; onto the active event's :tags :code via send-trace!. Outside, fall
;; back to tap> so REPL callers still see output. Out-of-trace tap>
;; payloads carry `:debux/dbg true` so `add-tap` consumers can branch.
;;
;; Why a separate helper rather than inlining in the macro: keeps the
;; `*current-trace*` reference + the tap-fallback logic in CLJ source
;; (testable via plain deftest) instead of macro-expanded boilerplate
;; at every dbg call-site.

(defn send-trace-or-tap!
  "If a re-frame trace event is in flight, accumulate `payload` onto
   the active event's :tags :code via `send-trace!`. Otherwise tap>
   so REPL callers still see output.

   `tap-also?` — when true, ALSO tap> alongside the in-trace emit
   (for callers that want both signals). Out-of-trace, tap> always
   fires regardless. Returns nil."
  [payload tap-also?]
  (if (some? trace/*current-trace*)
    (do (send-trace! payload)
        (when tap-also? (tap> (assoc payload :debux/dbg true))))
    (tap> (assoc payload :debux/dbg true)))
  nil)

;;; ----------------------------------------------------------------------
;;; Frame markers — entry/exit bracketing for fn-traced / defn-traced
;;; ----------------------------------------------------------------------
;;;
;;; When `set-trace-frames-output!` is enabled, each fn-traced'd /
;;; defn-traced'd handler invocation emits a pair of markers onto the
;;; active trace's :tags :trace-frames vector:
;;;
;;;   {:phase :enter :frame-id "frame_42" :t <ms> :msg <opt>}
;;;   {:phase :exit  :frame-id "frame_42" :t <ms> :msg <opt>}
;;;
;;; The frame-id is a gensym'd string baked into the macroexpansion at
;;; the call site; both markers carry the same id so a consumer (10x
;;; Code panel, custom inspector) can pair them and bracket the
;;; intermediate :code entries that landed between them.
;;;
;;; Trace channel: off-trace (no `*current-trace*`) — markers are
;;; silently dropped. Frame markers are framework-level boundary info,
;;; not user-visible data, so unlike `send-trace-or-tap!` there's no
;;; automatic tap> fallback.
;;;
;;; Opt-in tap channel: when `set-tap-output!` is enabled, both
;;; markers ALSO surface as `{:debux/kind :frame-enter|:frame-exit ...}`
;;; tap> payloads, independent of `*current-trace*`. A consumer opting
;;; into the tap stream gets the boundary signal needed to pair `:code`
;;; entries with their fn-traced/defn-traced invocation.
;;;
;;; Frame markers are invocation-boundary events. They intentionally
;;; ignore :if, :once, and :final, which only gate per-form :code
;;; emissions. When provided, :msg/:m is copied onto frame markers so
;;; consumers can correlate the boundary pair with labelled code entries,
;;; including cases where the code entries are fully suppressed.
;;;
;;; Exception path: only :enter is guaranteed. If the wrapped body
;;; throws, no :exit marker fires — consumers can detect a missing
;;; :exit (a :enter with no matching :exit by frame-id) as a thrown
;;; invocation. Wrapping in try/finally to always emit :exit was
;;; considered and rejected: an exception with no result is a
;;; meaningfully different signal than a successful return, and the
;;; missing-exit pattern preserves that signal. The same missing-exit
;;; signal carries through to the tap> stream.

(defn- now-ms []
  #?(:cljs (.now js/Date)
     :clj  (System/currentTimeMillis)))

(defn -send-frame-enter!
  "Emit a `:enter` marker on the active trace's :trace-frames vector.
   No-op on the trace channel unless `set-trace-frames-output!` is on,
   trace-enabled? is on, and a trace is in flight. Independently, when
   `set-tap-output!` is enabled, also emits a
   `{:debux/kind :frame-enter ...}` payload to tap>. Internal — called
   by the fn-traced / defn-traced expansion at body-entry."
  ([frame-id]
   (-send-frame-enter! frame-id nil))
  ([frame-id msg]
   (let [trace? (and @trace-frames-output?
                     (some? trace/*current-trace*)
                     @#'trace/trace-enabled?)
         tap?   @tap-output?]
     (when (or trace? tap?)
       (let [t     (now-ms)
             entry (cond-> {:phase    :enter
                            :frame-id frame-id
                            :t        t}
                     msg (assoc :msg msg))]
         (when trace?
           (let [frames (get-in trace/*current-trace* [:tags :trace-frames] [])]
             (trace/merge-trace!
               {:tags {:trace-frames (conj frames entry)}})))
         (when tap?
           (tap> (cond-> {:debux/kind :frame-enter
                          :frame-id   frame-id
                          :t          t}
                   msg (assoc :msg msg)))))))
   nil))

(defn -send-frame-exit!
  "Emit an `:exit` marker. Mirrors `-send-frame-enter!`: trace-channel
   emission is opt-in via `set-trace-frames-output!` and no-op
   off-trace, and when `set-tap-output!` is enabled an independent
   `{:debux/kind :frame-exit ...}` payload also lands on tap>. The
   `result` arg is accepted for call-site compatibility but is not
   stored on the marker; consumers that need the return value should
   read the surrounding :code entry. Internal — called by the
   fn-traced / defn-traced expansion right before returning."
  ([frame-id result]
   (-send-frame-exit! frame-id result nil))
  ([frame-id _result msg]
   (let [trace? (and @trace-frames-output?
                     (some? trace/*current-trace*)
                     @#'trace/trace-enabled?)
         tap?   @tap-output?]
     (when (or trace? tap?)
       (let [t     (now-ms)
             entry (cond-> {:phase    :exit
                            :frame-id frame-id
                            :t        t}
                     msg (assoc :msg msg))]
         (when trace?
           (let [frames (get-in trace/*current-trace* [:tags :trace-frames] [])]
             (trace/merge-trace!
               {:tags {:trace-frames (conj frames entry)}})))
         (when tap?
           (tap> (cond-> {:debux/kind :frame-exit
                          :frame-id   frame-id
                          :t          t}
                   msg (assoc :msg msg)))))))
   nil))

(defn -emit-fx-traces!
  "When a `fx-traced` body returns an effect-map (the standard reg-event-fx
   contract: `{:db ... :http {...} :dispatch [...]}`), emit one entry
   per key onto the active trace's :tags :fx-effects vector. Each entry
   is `{:fx-key <k> :value <v> :t <ms>}`. No-op on the trace channel
   when trace-enabled? is off or no trace is in flight; no-op entirely
   when the return isn't a map (a malformed handler — the misuse is
   reported via re-frame's normal error path, not here).

   When `set-tap-output!` is enabled, ALSO emits one
   `{:debux/kind :fx-effect ...}` payload per effect-key to tap>,
   independent of trace state. All keys of one return share a single
   timestamp so consumers can group them.

   :fx-effects is a separate :tags key from :code so consumers reading
   the :code panel don't see fx entries inflating the form-by-form
   trace."
  [effect-map]
  (when (map? effect-map)
    (let [trace? (and (some? trace/*current-trace*) @#'trace/trace-enabled?)
          tap?   @tap-output?]
      (when (or trace? tap?)
        (let [t   (now-ms)
              ;; Stable iteration order so consumers see effects in
              ;; key-order; the underlying re-frame fx executor doesn't
              ;; rely on this, but the trace stream is more readable.
              new (mapv (fn [[k v]]
                          {:fx-key k :value v :t t})
                        effect-map)]
          (when trace?
            (let [existing (get-in trace/*current-trace* [:tags :fx-effects] [])]
              (trace/merge-trace! {:tags {:fx-effects (into existing new)}})))
          (when tap?
            (doseq [entry new]
              (tap> (assoc entry :debux/kind :fx-effect))))))))
  nil)

;;; ----------------------------------------------------------------------
;;; :once / duplicate-suppression state
;;; ----------------------------------------------------------------------
;;;
;;; When `:once` is set on `fn-traced` / `defn-traced` / `dbg` / `dbgn`,
;;; emission is gated on whether the (form, result) pair has been seen
;;; before. The atom below holds a per-form hash of the last result, not
;;; the live result value, so consecutive identical emissions get
;;; suppressed across handler invocations without retaining large user
;;; values such as app-db maps.
;;;
;;; Form identity is `[trace-id syntax-order]`:
;;;
;;; - **`trace-id`** is a gensym'd string baked into the macroexpansion
;;;   of `dbgn-forms` / `dbgn` / `dbg`. One trace-id per macro
;;;   expansion site — stable across runtime invocations of the same
;;;   compiled handler, distinct between separate fn-traced'd handlers
;;;   in the same file.
;;; - **`syntax-order`** is the per-form index assigned by `insert-trace`
;;;   during the zipper walk (depth-first, left-to-right). Stable
;;;   across runs of the same body.
;;;
;;; Lifecycle: process-local atom; resets only on explicit
;;; `-reset-once-state!` (public re-export:
;;; `day8.re-frame.tracing/reset-once-state!`). Hot-reload of a macro-call site produces a
;;; fresh `trace-id` (the gensym is re-generated), so old keys for
;;; that site become orphaned. To keep long-running sessions bounded,
;;; the state prunes the oldest half of entries when it grows past
;;; `once-state-limit`. Tests reset the atom in the trace-capture
;;; fixture so dedup state doesn't leak across deftests.
(def ^:private once-state-limit 10000)
(def ^:private once-state-prune-to (quot once-state-limit 2))

(defonce ^:private once-state (atom {:next-order 0
                                     :entries    {}}))

(defn- result-fingerprint [result]
  (hash result))

(defn- normalize-once-state
  "Accept the current state shape and the pre-fingerprint map shape
   that may survive across a REPL hot-reload because `once-state` is a
   defonce. The migration hashes old retained values once, then drops
   those references when the caller CASes the normalized state back in."
  [state]
  (if (and (map? state) (contains? state :entries))
    state
    (let [entries (into {}
                        (map-indexed
                          (fn [idx [k result]]
                            [k {:fingerprint (result-fingerprint result)
                                :seen-order  (inc idx)}])
                          state))]
      {:next-order (count entries)
       :entries    entries})))

(defn- prune-once-entries [entries]
  (if (<= (count entries) once-state-limit)
    entries
    (let [keep-keys (->> entries
                         (sort-by (comp :seen-order val) >)
                         (take once-state-prune-to)
                         (map key)
                         set)]
      (select-keys entries keep-keys))))

(defn -reset-once-state!
  "Drop all `:once` dedup state. Used by the integration-test fixture
   (so cross-test contamination doesn't make a previous test's last
   emission silence the next one) and exposed publicly so REPL callers
   running a long live-debug session can clear the slate without
   waiting for a hot-reload to invalidate keys.

   Public callers should prefer the re-export at
   `day8.re-frame.tracing/reset-once-state!`; this internal name (with
   the leading dash) stays for in-tree call sites that already require
   `common.util` directly."
  []
  (reset! once-state {:next-order 0
                      :entries    {}}))

(defn- next-once-state [state k fingerprint]
  (let [{:keys [entries next-order]} (normalize-once-state state)
        prev                        (get entries k ::unseen)]
    (if (and (not= prev ::unseen)
             (= (:fingerprint prev) fingerprint))
      [state false]
      (let [order    (inc next-order)
            entries' (-> entries
                         (assoc k {:fingerprint fingerprint
                                   :seen-order  order})
                         prune-once-entries)]
        [{:next-order order
          :entries    entries'}
         true]))))

(defn -once-emit?
  "Returns true if a `:once`-gated form should emit its trace right
   now, false if the same (form, result) pair was the most recent
   emission and should be suppressed.

   Side effect: when emit-allowed (returns true), the new result is
   hashed and recorded as the latest fingerprint for
   `[trace-id syntax-order]`, so the next call with the same result
   returns false without retaining the live result value.

   `nil` and `false` are distinguishable from `::unseen` (the sentinel
   for 'never emitted'), so a form that legitimately produces a stable
   `nil` result emits ONCE (on first sighting) and then dedupes."
  [trace-id syntax-order new-result]
  (let [k           [trace-id syntax-order]
        fingerprint (result-fingerprint new-result)]
    (loop []
      (let [state               @once-state
            [state' emit?]      (next-once-state state k fingerprint)]
        (if (identical? state state')
          emit?
          (if (compare-and-set! once-state state state')
            emit?
            (recur)))))))

;;; For internal debugging
(defmacro d
  "The internal macro to debug dbg macro.
   <form any> a form to be evaluated"
  [form]
  `(let [return# ~form]
     (println ">> dbg_:" (pr-str '~form) "=>\n" (pr-str return#) "<<")
     return#))


;;; indent-level control
(def indent-level* (atom 1))

(defn reset-indent-level! []
  (reset! indent-level* 1))


;;; print-seq-length
(def print-seq-length* (atom 100))

(defn set-print-seq-length! [num]
  (reset! print-seq-length* num))


;;; general
(defmacro read-source [sym]
  `(-> (repl/source ~sym)
       with-out-str
       read-string))

(defn cljs-env? [env]
  (boolean (:ns env)))

(defn vec->map
  "Transsub-forms a vector into an array-map with key/value pairs.
  (def a 10)
  (def b 20)
  (vec-map [a b :c [30 40]])
  => {:a 10 :b 20 ::c :c :[30 40] [30 40]}"
  [v]
  (apply array-map
         (mapcat (fn [elm]
                   `[~(keyword (str elm)) ~elm])
                 v)))

(defn replace-& [v]
  (walk/postwalk-replace {'& ''&} v))



;;; symbol with namespace
#?(:clj
   (defn- var->symbol [v]
     (let [m    (meta v)
           ns   (str (ns-name (:ns m)))
           name (str (:name m))]
       (symbol ns name))))

#?(:clj
   (defn- ns-symbol-for-clj [sym]
     (if-let [v (resolve sym)]
       (var->symbol v)
       sym)))

#?(:clj
   (defn- ns-symbol-for-cljs [sym env]
     (if-let [meta ((requiring-resolve 'cljs.analyzer.api/resolve) env sym)]
       ;; normal symbol
       (if (:local meta)
         sym
         (let [[ns name] (str/split (str (:name meta)) #"/")]
           ;; The special symbol `.` must be handled in the following special symbol part.
           ;; However, the special symbol `.` returns meta {:name / :ns nil}, which may be a bug.
           (if (nil? ns)
             sym
             (symbol ns name))))
       ;; special symbols except for `.`
       sym)))

#?(:clj
   (defn ns-symbol [sym & [env]]
     (if (symbol? sym)
       (if (cljs-env? env)
         (ns-symbol-for-cljs sym env)
         (ns-symbol-for-clj sym))
       sym)))


;;; print
(defn take-n-if-seq [n result]
  (if (seq? result)
    (take (or n @print-seq-length*) result)
    result))

(defn truncate [s]
  (if (> (count s) 70)
    (str (.substring s 0 70) " ...")
    s))

(defn- make-bars-
  [times]
  (apply str (repeat times "|")))

(def make-bars (memoize make-bars-))

(defn prepend-bars
  [line indent-level]
  (str (make-bars indent-level) " " line))

(defn print-form-with-indent
  [form indent-level]
  ;; TODO: trace this information somehow
  (println (prepend-bars form indent-level))
  (flush))

(defn form-header [form & [msg]]
  (str (truncate (pr-str form))
       (and msg (str "   <" msg ">"))
       " =>"))


(defn prepend-blanks
  [lines]
  (mapv #(str "  " %) lines))

(defn pprint-result-with-indent
  [result indent-level]
  ;; TODO: trace this information somehow
  (let [res    result
        result (with-out-str (pp/pprint res))
        pprint (str/trim result)]
    (println (->> (str/split pprint #"\n")
                  prepend-blanks
                  (mapv #(prepend-bars % indent-level))
                  (str/join "\n")))
    (flush)))

(defn insert-blank-line []
  (println " ")
  (flush))


;;; parse options
(defn parse-opts
  "Parse trailing macro option tokens into the normalized opts map used
   by dbgn/dbg-style trace emitters.

   Recognized forms:
     number        -> {:n number}
     string        -> {:msg string}
     :if pred      -> {:if pred}
     :js           -> {:js true}
     :once or :o   -> {:once true}
     :final or :f  -> {:final true}
     :msg/:m value -> {:msg value}
     :verbose      -> {:verbose true}
     :show-all     -> {:verbose true}
     :style/:s val -> {:style val}
     :clog         -> {:clog true}

   Unrecognized options (typos, or options from a future debux version
   this fork hasn't picked up yet) are logged via console.warn (cljs)
   or *err* (clj) and skipped — the loop continues so prior and later
   recognized options are preserved instead of being silently dropped
   when the cond falls through.

   Callers that already accept an opts map, such as fn-traced and
   dbgn-forms, pass those maps through directly instead of using this
   trailing-token parser."
  [opts]
  (loop [opts opts
         acc  {}]
    (let [f (first opts)
          s (second opts)]
      (cond
        (empty? opts)
        acc

        (number? f)
        (recur (next opts) (assoc acc :n f))

        (string? f)
        (recur (next opts) (assoc acc :msg f))

        (= f :if)
        (recur (nnext opts) (assoc acc :if s))

        ;;; for clojurescript
        (= f :js)
        (recur (next opts) (assoc acc :js true))

        (#{:once :o} f)
        (recur (next opts) (assoc acc :once true))

        (#{:final :f} f)
        (recur (next opts) (assoc acc :final true))

        (#{:msg :m} f)
        (recur (nnext opts) (assoc acc :msg s))

        (#{:verbose :show-all} f)
        (recur (next opts) (assoc acc :verbose true))

        (#{:style :s} f)
        (recur (nnext opts) (assoc acc :style s))

        (= f :clog)
        (recur (next opts) (assoc acc :clog true))

        :else
        (do
          #?(:cljs (js/console.warn
                    (str "[debux] parse-opts: ignoring unrecognized option "
                         (pr-str f)))
             :clj  (binding [*out* *err*]
                     (println
                      (str "[debux] parse-opts: ignoring unrecognized option "
                           (pr-str f)))))
          (recur (next opts) acc))))))


;;; quote the value parts of a map
(defn quote-val [[k v]]
  `[~k '~v])

(defn quote-vals [m]
  (->> (map quote-val m)
       (into {})))


;;; for recur processing
(defn include-recur? [form]
  (((comp set flatten) form) 'recur))

#?(:clj
   (defn final-target? [sym targets env]
     (let [ns-sym (ns-symbol sym env)]
       (or (get targets ns-sym)
           (some #(= % ns-sym)
                 '[clojure.core/defn clojure.core/defn- clojure.core/fn
                   cljs.core/defn cljs.core/defn- cljs.core/fn])))))

(defn o-skip?
  "True iff `sym` is the fully-qualified `o-skip` macro name. Used by
   `insert-o-skip-for-recur` (skip.cljc) to detect a node that's
   already been wrapped on a prior pass and avoid re-wrapping.

   The fqn was `'debux.common.macro-specs/o-skip` — left over from
   the upstream philoskim/debux library before this fork renamed the
   namespace to `day8.re-frame.debux.common.macro-specs`. With the
   stale fqn the predicate ALWAYS returned false, so the recur-walker
   re-wrapped already-wrapped nodes and `dbgn` macroexpansion
   diverged on `loop`+`recur` (issue #40).

   Other callers (skip-place? at line 478ish) already used the right
   fqn — only this defn was stale."
  [sym]
  (= 'day8.re-frame.debux.common.macro-specs/o-skip sym))

(declare remove-d)

;;; spy functions
(def spy-first
  (fn [result quoted-form indent]
    (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
    (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
    ;(print-form-with-indent (form-header quoted-form) indent)
    ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
    result))

(def spy-last
  (fn [quoted-form indent result]
    (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
    (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
    ;(print-form-with-indent (form-header quoted-form) indent)
    ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
    result))

(defn spy-comp [quoted-form indent form]
  (fn [& arg]
    (let [result (apply form arg)]
      (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
      (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
      ;(print-form-with-indent (form-header quoted-form) indent)
      ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
      result)))

;; Remove trace info

(defn debux-skip-symbol? [sym]
  (contains? #{'day8.re-frame.debux.common.macro-specs/skip-outer
               'day8.re-frame.debux.common.macro-specs/skip
               'day8.re-frame.debux.common.macro-specs/o-skip
               :day8.re-frame.debux.common.macro-specs/skip-place}
             sym))

(defn spy-first? [sym]
  (= 'day8.re-frame.debux.common.util/spy-first sym))

(defn remove-d [form d-sym]
  ;; TODO: should we instead look to rewrite the quoted/spied forms
  ;; at macro compile time, rather than filtering them out
  ;; when the trace is being emitted?
  (loop [loc (sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (d ...)
        (and (seq? node)
             (or (= d-sym (first node))
                 (debux-skip-symbol? (first node))
                 (spy-first? (first node))))
        ;; We take the third node, because the first two are
        ;; (d <indent-level> ...)
        (recur (z/replace loc (last node)))

        ;; in case of spy-last
        (and (seq? node)
             (= `spy-last (first node)))
        (recur (z/replace loc (last node)))

        :else
        (recur (z/next loc))))))
