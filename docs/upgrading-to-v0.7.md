# Upgrading to re-frame-debux v0.7

> Audience: downstream consumers of `day8.re-frame/tracing` —
> re-frame-pair, re-frame-10x, custom 10x panels, library users with
> handlers wired through `fn-traced` / `defn-traced`. The
> [CHANGELOG](../CHANGELOG.md) lists what landed; this doc is a
> consumer-narrative companion that answers "*should I touch my code,
> and if so where?*"

---

## TL;DR

Nothing breaks. Drop in `0.7.0`, ship. Existing `fn-traced` /
`defn-traced` call sites compile and emit the same `:code` payload
they did under `0.6.x`. **Net new surface:** six opts on the existing
macros, two new macro families (`fx-traced` / `defn-fx-traced` and
`dbg` / `dbg-last` / `dbgn`), one new namespace
(`day8.re-frame.tracing.runtime`) for REPL-driven instrumentation, a
`tap>` output channel, and two new tag streams (`:trace-frames`,
`:fx-effects`) on the trace alongside the existing `:code` / `:form`.

---

## No-action upgrade

Bump the coordinate and rebuild:

```
[day8.re-frame/tracing       "0.7.0"]   ; dev profile
[day8.re-frame/tracing-stubs "0.7.0"]   ; production profile
```

shadow-cljs `:ns-aliases` users: no config change. The
`day8.re-frame.tracing-stubs` ns gained matching stubs for every new
macro and config knob — see *[Two-jar approach](#two-jar-approach)*
below.

The `:code` payload schema is append-only — fields added in v0.7
(`:msg`, `:locals`, `:name`) appear only when callers opt in via the
new opts. Existing consumers ignoring those keys keep working.

---

## Per-feature recipes

The new opts compose freely. Set them in the optional opts-map
immediately after the macro name:

```clojure
(fn-traced {:locals true :once true :msg "checkout"} [db [_ x]] ...)
```

The same opts-map slot works on `defn-traced`, `fx-traced`,
`defn-fx-traced`, `dbg`, `dbg-last`, and `dbgn` (with minor per-macro
variations called out in their docstrings).

### `:locals` — see what fed the trace

`{:locals true}` attaches the captured handler args as `[[sym val]
...]` pairs to every `:code` entry the body produces. The single
biggest readability win for post-mortem inspection — without it, the
trace shows you *what* evaluated but not the *bindings* that led
there. Cheap; turn it on for any handler whose trace you'll be
reading offline.

### `:if` — conditional emission

`{:if pred}` runs `pred` against each per-form result; the trace fires
only when the predicate returns truthy. Use for high-frequency
handlers where you want only the interesting cases:

```clojure
(fn-traced {:if #(:error %)} [db ev] ...)   ; only :error results emit
```

### `:once` / `:o` — duplicate suppression

`{:once true}` (alias `{:o true}`) suppresses consecutive emissions
whose `(form, result)` pair matches the previous one, per call site.
State survives across handler invocations until the result actually
changes — useful for periodic timer-driven dispatches that recompute
the same value every tick. Composes cleanly with `:if`. Public reset
via `day8.re-frame.tracing/reset-once-state!`.

### `:msg` / `:m` — per-handler labelling

`{:msg "checkout-handler"}` (alias `{:m "..."}`) attaches a
developer-supplied label to each emitted `:code` entry. Per-call
dynamic — the value is evaluated at trace time, not at expansion — so
you can splice in a runtime identifier (a user id, a request id) to
distinguish output from many parallel call sites.

### `:final` / `:f` — outermost-only mode

`{:final true}` (alias `{:f true}`) emits only the outermost
(`:indent-level 0`) `:code` entry per top-level wrapping form and
suppresses every nested per-form entry. Use when the per-step zipper
trace is noise and you only care about the final value of each
handler-body expression.

### `:verbose` / `:show-all` — leaf-literal escape hatch

By default, the zipper walker skips leaf literals (numbers, strings,
booleans, keywords, chars, `nil`) for noise reduction.
`{:verbose true}` (alias `{:show-all true}`) wraps them too. Turn on
when you're debugging a literal-heavy expression (config maps,
keyword-driven dispatch tables) and the default suppression is hiding
the bit you need. Special-form skips (`recur`, `throw`, `var`,
`quote`, `catch`, `finally`) STAY honoured — instrumenting them
corrupts evaluation semantics.

### `fx-traced` / `defn-fx-traced` — for `reg-event-fx` handlers

`fn-traced` traces a `reg-event-fx` body's inner sub-forms but doesn't
surface the per-key entries of the returned effect-map. `fx-traced`
does both — it inherits all of `fn-traced`'s `:code` emission, frame
markers, and opts, AND emits one `:fx-effects` entry per key in the
returned map (`{:fx-key k :value v :t ms}`):

```clojure
(re-frame.core/reg-event-fx :checkout
  (fx-traced [_ [_ amount]]
    {:db       {:total (* 1.1 amount)}
     :http     {:method :post :body amount}
     :dispatch [:notify :checkout-done]}))
```

Switch from `fn-traced` to `fx-traced` whenever the handler returns an
effect-map and you want the per-effect breakdown that 10x's Fx panel
and re-frame-pair render. `defn-fx-traced` is the `defn` variant. For
`reg-event-ctx` handlers, add `{:ctx-mode true}`: effects are
extracted from `(:effects ctx)` before per-key emission.

### `dbg` / `dbg-last` / `dbgn` — single-form REPL tracing

For one-off probes inside a handler body without rewriting the whole
`fn` as `fn-traced`. Value-transparent — same payload schema as
`fn-traced`, one record per evaluation:

```clojure
(re-frame.core/reg-event-db :user/login
  (fn [db [_ creds]]
    (assoc db :user (dbg (lookup-user creds) {:name "login"}))))
```

`dbg-last` is the thread-last counterpart — drops into a `->>` step
slot bare or with opts. `dbgn` traces every nested sub-form (full
zipper walk) of an expression — useful for inspecting a complex
threading pipeline.

All three accept the standard opts (`:locals`, `:if`, `:once`,
`:msg`) plus a `:tap?` flag that fires `tap>` alongside the in-trace
emit. Outside any trace context they fall back to `tap>` so REPL
callers still see the value.

### Runtime API — `wrap-handler!` / `unwrap-handler!`

For tools that drive re-frame from the REPL (re-frame-pair in
particular), `day8.re-frame.tracing.runtime` exposes a wrap/unwrap
cycle that swaps an instrumented handler in and out of re-frame's
registrar without source edits:

```clojure
(require '[day8.re-frame.tracing.runtime :as rt])

(rt/wrap-handler! :event :some/event
  (fn [db [_ x]] (assoc db :n (* 2 x))))

;; ... dispatch / observe the :code stream ...

(rt/unwrap-handler! :event :some/event)   ; restore the original
```

The `replacement` argument must be a literal `(fn [...] ...)` form —
`fn-traced` operates on the AST at compile time, so a precompiled fn
value can't be re-traced. Convenience wrappers:

| Macro / fn                                   | Use when                                                                  |
| -------------------------------------------- | ------------------------------------------------------------------------- |
| `wrap-event-fx!` / `wrap-event-ctx!`         | The traced body returns an effects map / context map (per-key `:fx-effects` emission). |
| `wrap-sub!` / `wrap-fx!`                     | Convenience for `wrap-handler! :sub` / `:fx`; `wrap-sub!` accepts layer-2, signal-fn, and `:<-` shapes. |
| `unwrap-sub!` / `unwrap-fx!` / `unwrap-all!` | Restore one or all wrapped handlers.                                      |
| `wrapped?` / `wrapped-list`                  | Inspect the side-table — useful for tools badging the wrap state in UI.   |
| `runtime-api?`                               | Feature-detection predicate: `true` when this namespace is loaded.        |

Tools probing for the API at runtime should use `runtime-api?` rather
than checking individual fn names; the var name is dedicated to
advertising availability and won't be renamed in a refactor.

### `set-tap-output!` — fanning out to ad-hoc inspectors

```clojure
(require '[day8.re-frame.tracing :as t])

(t/set-tap-output! true)
(t/set-trace-frames-output! true)
```

Routes every internal trace emitter (`:form`, `:code`,
`:trace-frames`, `:fx-effects`) through `tap>` in addition to the
in-trace `merge-trace!` sink. `:trace-frames` emission to the trace
stream is now opt-in via `set-trace-frames-output!`; tap output still
receives frame markers when tap output is on. Each payload carries a
`:debux/kind` discriminator (`:form`, `:code`, `:frame-enter`,
`:frame-exit`, `:fx-effect`) so an `add-tap` consumer (custom 10x
panels, REPL probes, eval-cljs scripts) can route on it.

Independent of `re-frame.trace/trace-enabled?` — `tap>` consumers
still receive entries when tap is enabled even with tracing off.
Default off; preserves the existing trace-only behaviour.

Three other config knobs sit alongside it on the same ns:
`set-trace-frames-output!` (opt into trace-stream frame markers),
`set-print-seq-length!` (bound the per-trace pretty-print of large
sequences), and `reset-indent-level!` (recovery if a body threw
mid-emit).

---

## Trace-stream contract changes

The existing `:code` and `:form` tag streams are **unchanged** —
field semantics, ordering, and the append-only payload contract from
`0.5.x` all still hold. Two NEW tag streams appear on the trace:

| Stream          | Producer                              | Payload shape                                                                       |
| --------------- | ------------------------------------- | ----------------------------------------------------------------------------------- |
| `:trace-frames` | `fn-traced` / `defn-traced`           | `{:phase :enter :frame-id <gensym-str> :t <ms>}` paired with `{:phase :exit :frame-id <same> :t <ms>}` |
| `:fx-effects`   | `fx-traced` / `defn-fx-traced`        | `{:fx-key <k> :value <v> :t <ms>}` — one per key of the returned effect-map          |

Both are append-only vectors under the active trace's `:tags`, mirror
the `:code` discipline, and are silently dropped off-trace. Frame
markers are also off on the trace stream by default; consumers wanting
them should enable `set-trace-frames-output!`. Consumers wanting frames
out-of-trace should enable `set-tap-output!`.

`:trace-frames` exception path: only `:enter` is guaranteed — a
missing `:exit` is the signal that the body threw. Pair on
`:frame-id` to bound the `:code` entries that landed between them.

The authoritative schema lives in
`src/day8/re_frame/debux/common/util.cljc` (`send-trace!` doc-block,
the tap-channel doc-block, and the frame/fx-effects emitter
doc-blocks). Downstream tools should read keys defensively; new
optional keys may appear in future minor versions.

---

## Two-jar approach

The `day8.re-frame/tracing-stubs` jar adds matching stubs for every
v0.7 macro:

- `fn-traced` / `defn-traced` — strip opts, expand to bare `fn` /
  `defn` (existing behaviour).
- `fx-traced` / `defn-fx-traced` — same; effect-map flows through
  unchanged.
- `dbg` / `dbgn` / `dbg-last` — value-transparent; expand to the
  bare expression with no trace side effect.
- `set-tap-output!` / `set-trace-frames-output!` /
  `set-print-seq-length!` / `reset-indent-level!` — no-op `defn`s with
  matching arities, so app boot code calling them at startup doesn't
  unbound-var.
- `day8.re-frame.tracing.runtime/wrap-handler!` /
  `wrap-event-fx!` / `wrap-event-ctx!` / `wrap-sub!` / `wrap-fx!`
  — compile to bare `re-frame.core/reg-*` with no `fn-traced` wrap.
  `unwrap-*` / `wrapped?` / `wrapped-list` / `unwrap-all!` are no-op
  fns. `runtime-api?` returns `true` (the API surface IS reachable;
  the work just doesn't happen).

No `:ns-aliases` config change is required — the existing
`{day8.re-frame.tracing day8.re-frame.tracing-stubs}` mapping covers
both the parent ns and (transitively) the runtime ns under
shadow-cljs.

---

## Toolchain note

Internal change. The project's primary build path is now `bb` tasks
driven by `deps.edn` + `bb.edn` at the root; CI runs `bb test` and CD
runs `bb clojars` / `bb clojars-stubs`. `project.clj` is retained as
a transitional shim through the v0.7 line and `lein test` still runs
the Clojure-side suite.

This affects contributors and CI integrators only. **Library
consumers do NOT need to change anything** — the published artefacts
on Clojars (`day8.re-frame/tracing` and `day8.re-frame/tracing-stubs`)
are unchanged in shape and `:dependencies` declaration.
