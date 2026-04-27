# re-frame-debux — improvement plan

> Target audience: the re-frame-debux maintainer. Synthesis of three parallel surveys (rfd-pa2): current-state of the library, GitHub issue triage, philoskim/debux feature comparison. Cross-referenced with `re-frame-pair`'s recent debux survey (`/home/mike/code/re-frame-pair/docs/inspirations-debux.md`) so the recommendations also serve as a checklist of what would amplify re-frame-pair's on-demand REPL hot-swap recipe.

---

## 1. Executive summary

re-frame-debux is in **stable maintenance mode** with a small, well-defined surface (`day8.re-frame.tracing/{fn-traced,defn-traced}`), a working zipper-based AST walker, and a production-elision story (`tracing-stubs` jar + shadow-cljs `:ns-aliases`). The core engine works and produces useful output through re-frame-10x's Code panel. But the project shows the fingerprints of a sprint that stalled: **dependencies are pinned to 2020 snapshots**, **issue #40 (a critical `loop`/`recur` hang) has been open since 2021**, and **four feature requests from a 2020 sprint have sat untouched** for over four years. Recent activity (2026) is limited to a `bd init` commit.

**Top-3 highest-leverage improvements**, in priority order:

1. **Fix issue #40 — `loop`/`recur` infinite macroexpansion (P0).** Critical bug that makes `fn-traced` unusable on a common Clojure idiom. Likely a missing zipper carve-out for `recur`. Estimated 1 week.
2. **Bump core dependency pins (P1).** re-frame 1.2.0 → 1.4+, shadow-cljs 2.11.22 → 2.20+, Clojure 1.10.3 → 1.11+. No breaking-API risk identified; the staleness signal alone is hurting trust in the library. Estimated 1 day.
3. **Add `:locals` and `:if` options to `fn-traced` (P1).** Two of three debux options that transfer cleanly to the re-frame-trace sink model (`:once` is the third but harder). `:locals` (~50 LOC) and `:if` (~5 LOC) widen what the tracing surface exposes by an order of magnitude with low risk. Directly amplifies re-frame-pair's on-demand-instrumentation recipe.

The `:once` option, fx/effect-map tracing (a TODO at `dbgn.clj:341`), and the four 2020 feature requests sit in a second tier — useful, but each requires design work disproportionate to its frequency-of-use.

---

## 2. Current state of re-frame-debux

**Public surface.** Two macros, `fn-traced` (`tracing.cljc:123`) and `defn-traced` (`tracing.cljc:89`), gated by `(is-trace-enabled?)` (a goog-define on `trace-enabled?`, default `false`). Both expand to `dbgn-forms` calls that wrap each form in the function body with `send-trace!`. A lower-level `dbgn` macro (line 33) is exported and a custom-macro registration API exists (`register-macros!`, line 38) but is rarely needed.

**Architecture.** The zipper machinery lives in `src/day8/re_frame/debux/dbgn.clj`. It walks each form via a custom sequential zipper (`util.cljc:20`), classifying it against the macro registry in `cs/macro_types.cljc` (control flow, definition forms, threading macros, special forms — ~50 entries) and either skipping or instrumenting accordingly. The walker is two-pass — `insert-skip` → `insert-trace` → `remove-skip` — with a maintainer TODO (`dbgn.clj:243`) questioning whether this can collapse to one pass.

**Trace sink.** `send-trace!` (`util.cljc:132`) writes through `re-frame.trace/merge-trace!` into the current trace event's `:tags :code` vector, with payload `{:form, :result, :indent-level, :syntax-order, :num-seen}`. A separate `send-form!` (line 129) sets `:tags :form` once for the outermost form. re-frame-10x picks up `:code` and renders its Code panel.

**Production split.** Two mechanisms, both documented in `README.md:74–128`:
- **Two-jar approach (Option 1)** — declare `[day8.re-frame/tracing-stubs "0.5.3"]` in `:prod` profile; the stubs jar (`tracing-stubs/src/...`) defines `defn-traced` and `fn-traced` as plain `defn` / `fn`.
- **shadow-cljs `:ns-aliases` (Option 2, since v0.5.5)** — single jar; release config aliases `day8.re-frame.tracing` to `day8.re-frame.tracing-stubs`. This is the recommended path for shadow-cljs users.

Both fail silently if misconfigured (no runtime warning), which is a real footgun — see §5.

**Tests.** ~1,482 lines across `core_test.clj`, `dbgn_test.clj`, `traced_macros_test.clj`, `indenting_test.cljc`, `common/util_test.cljc`, plus a Karma-driven CLJS browser runner. Coverage is good for macro-expansion edge cases. The gap is **integration tests** — there is no end-to-end test that fires a real re-frame event through a `fn-traced` handler and asserts the resulting `:code` tag landed in 10x's epoch buffer. Several of the closed-and-reopened bug categories (`cond` macroexpansion, indentation regressions) would have been caught earlier with one.

**Half-done / smells.** TODOs in `dbgn.clj` (lines 243, 268, 308, 341, 469, 501) and `util.cljc` (102, 110, 134) flag known limitations: plain list literals not instrumented (#268), maps/effects skipped (#341 — *"We might also want to trace inside maps, especially for fx"*), `clojure.core` symbol cleanup not generalised (#102), macroexpanded form not captured in payload (#134). The `spy-*` family (`util.cljc:343–366`) appears unused. The README claims Clojure 1.8.0+ support but the project pins 1.10.3.

**Example fixture.** `example/` exists, builds, and runs, but pins re-frame 0.10.8 (current is 1.4+) and Clojure 1.10.1 — clearly not exercised in years. A documentation-test (or a CI step that runs `lein dev` and checks for a clean compile) would notice the staleness immediately.

---

## 3. Open issues — triage

GitHub reports **6 open issues**, all 2+ years old. Themes:

| # | Title | Theme | Age | Scope | Action |
|---|---|---|---|---|---|
| [40](https://github.com/day8/re-frame-debux/issues/40) | `fn-traced`/`defn-traced` doesn't terminate with `loop`/`recur` | bug | 2021-06 | 1 week | **fix-now** (P0) |
| [38](https://github.com/day8/re-frame-debux/issues/38) | Images missing in README | docs | 2020-09 | 1 hour | easy fix or close-as-stale |
| [37](https://github.com/day8/re-frame-debux/issues/37) | Send Trace marking function entry/exit | feature | 2020-07 | 1 week | defer, depends on 10x design |
| [36](https://github.com/day8/re-frame-debux/issues/36) | Include all trace — don't filter noisy bits | feature | 2020-07 | 1 week | defer, design-required |
| [35](https://github.com/day8/re-frame-debux/issues/35) | Self-hosted ClojureScript support | feature | 2020-07 | 1 month | defer, architectural |
| [34](https://github.com/day8/re-frame-debux/issues/34) | Send exceptions to 10x | feature | 2020-07 | 1 month | defer until design discussion resolved |

**Pattern from the closed issues.** The closed log (#21, #22, #23, #29, #30, #31, #33) shows that **macro-walker fragility is a recurring source of friction**. #31 (`cond` NPE), #29 / #22 / #23 (indentation bookkeeping), and now #40 (`loop`/`recur` cycling) all stem from the same area: position/skip tracking failing when nesting is unusual or recursion involves tail calls. #31 was closed with *"Almost 100% sure this was fixed on master now"* — without a regression test. **Recommendation: add a test fixture covering each of `cond`, `cond->`, `loop`+`recur`, `letfn`, `for`, and `case` with deeply nested bodies; treat that fixture as a load-bearing regression set.**

**On the 2020 sprint backlog (#34, #35, #36, #37):** None has had any movement since opened. The honest move is one of:
- close all four with a "deferred — see roadmap §6" reference, or
- combine them into a single design-doc issue ("v0.6 — view/sub/fx tracing + exception surfacing") and let it act as a placeholder.

#38 (broken images) is a one-hour fix or a `close-as-stale`; either is fine.

---

## 4. Feature gap vs philoskim/debux

re-frame-debux retains debux's zipper engine (`dbgn`) but exposes ~20% of the upstream macro surface. Of the missing options, three transfer cleanly to the re-frame-trace sink model; the rest are console-only.

| Option | Transfers? | Effort | Value |
|---|---|---|---|
| **`:locals` / `:l`** — capture lexical bindings | yes | ~50 LOC | High. Adds `{:locals [[sym val] ...]}` to the trace payload. The single biggest readability win for post-mortem inspection. |
| **`:if`** — conditional emit | yes | ~5 LOC | Medium. One `when` guard around `send-trace!`. Useful for filtering high-frequency traces (e.g. only log when `(:status %)` is `:error`). |
| **`:once` / `:o`** — duplicate suppression | partial | ~80 LOC | Low–medium. Needs a per-form ID (gensym at macro time) plus a runtime atom of `{id → last-result}`. Doable but adds a runtime cache that needs lifecycle thought. |
| **`:final` / `:f`** | partial | small | Low. Could filter the payload to only the outermost form, but the existing two-step (`send-form!` for outer + `send-trace!` for inner) effectively already provides this with a different framing. |
| `:style`, `:js`, `:print`, `:ns` | NO | — | Console-only. re-frame-debux deliberately routes to edn trace tags; styling has no consumer in 10x. |
| `dbg`, `dbgt`, `clog`, `clogn`, `clogt` | NO | — | These are alternate macros (single-form, transducer-aware, console-output). `fn-traced` covers re-frame's needs; the rest are debux's separate use-cases. |
| `tap>` integration | maybe | small | If a user wants traces to *also* fan out to `tap>`, `send-trace!` could grow a side-channel. Low demand; defer. |
| `break` / DevTools breakpoints | NO | — | Halts the runtime; conflicts with re-frame-pair's hot-swap workflow. |

**The single most valuable port is `:locals`.** Locals capture turns `fn-traced` from "what value did this expression produce?" into "what values did this expression produce, given which bindings were in scope?". For a typical re-frame handler that walks `(let [user-id ... cart ...] ...)`, the trace is currently opaque about which inputs produced which intermediate values; `:locals` fixes that.

The `:if` option is the lowest-risk addition. Five lines of code, no new state, no design questions. It's the kind of thing that's worth shipping in the same commit as `:locals` to amortise testing effort.

---

## 5. re-frame-pair compatibility recommendations

re-frame-pair is a Claude Code skill that drives running re-frame apps via REPL. It already has hot-swap as a primitive — meaning the skill can wrap any registered handler with `fn-traced` *on demand*, with no source edits. re-frame-debux is uniquely well-positioned for this because its trace data already flows into `re-frame.trace/merge-trace!`, which re-frame-pair already reads via 10x's epoch buffer.

The integration is described in detail in `/home/mike/code/re-frame-pair/docs/inspirations-debux.md`. From re-frame-debux's side, three small changes would amplify it dramatically:

**(a) Document the `:code` payload schema.** The fields `{:form, :result, :indent-level, :syntax-order, :num-seen}` are written to `:tags :code` (`util.cljc:138–140`) but their semantics aren't documented anywhere — neither in the README nor in inline comments. Any tool that wants to consume this payload (re-frame-pair, third-party 10x panels, custom inspectors) reverse-engineers it. **A 30-line schema comment in `util.cljc` near `send-trace!`** — naming each field, giving an example shape, and noting the `tidy-macroexpanded-form` step — closes the documentation gap and turns the payload into a contract.

**(b) Make the production-mode warning loud, not silent.** Today, if a user's release build accidentally ships `day8.re-frame.tracing` (instead of the stubs), tracing runs in production with no warning. A defensive runtime check at the first `send-trace!` call — `(when (and (some-shadow-detection) (not (goog-defined? trace-enabled?))) (js/console.warn "re-frame-debux is active in what looks like a production build; check :ns-aliases"))` — costs nothing in dev and shouts at the operator if their build config drifts. Even better, add a clojure-side macro-expansion-time assertion that fires at compile when `goog.DEBUG` is false but `trace-enabled?` is true.

**(c) Expose a public `wrap-fn-traced!` runtime function.** Today, the only entry point is the `fn-traced` macro at source-edit time. re-frame-pair wants to do this at the REPL, which currently requires the skill to manually `eval` the rewriting macro form. A small public API — `(day8.re-frame.tracing/wrap-handler! kind id)` that re-registers the existing handler under the same id with `fn-traced` applied — would make the recipe one call instead of a synthesised macro form. Bonus: `(day8.re-frame.tracing/unwrap-handler! kind id)` to restore the original. ~40 LOC. This is the single most leveraged change for the on-demand-instrumentation recipe.

**Sub-tracing and fx-tracing.** A direct extension of the same idea. `(wrap-sub! id)` re-registers a subscription with `fn-traced` body; `(wrap-fx! id)` does the same for an effect handler. fx tracing in particular would fix the `dbgn.clj:341` TODO ("trace inside maps, especially for fx"): the wrapper can introspect the `:effects` map structure and emit per-effect traces without modifying the zipper walker.

---

## 6. Prioritised roadmap

Concrete sequencing. Each item has a rough effort estimate and a testable acceptance criterion.

### Next session (this week or next)

1. **Fix #40 — `loop`/`recur` non-termination** (~1 week). Most likely a missing carve-out in `cs/macro_types.cljc` for `recur` similar to the existing `:skip-form-itself-type` entries. Add a regression-test fixture covering `loop`/`recur`, `cond`, `cond->`, `letfn`, deeply-nested `for`, and `case` (this is the test gap that has let three macro-walker bugs through). [P0, blocking]
2. **Bump dependency pins** (~1 day). re-frame 1.2.0 → 1.4+, shadow-cljs 2.11.22 → 2.20+, Clojure 1.10.3 → 1.11+, project.clj `[day8.re-frame/tracing "0.5.3"]` and example/project.clj `[day8.re-frame/tracing "0.5.5"]` to the new release. Update README's Clojure 1.8.0 claim to match reality. [P1, trust-building]
3. **Document the `:code` payload schema** (~1 hour). 30-line block comment in `util.cljc` near `send-trace!`. [P1, doc, prereq for re-frame-pair integration]
4. **Close or merge the 2020 sprint backlog** (~1 hour). Either close #34/#35/#36/#37 with a roadmap pointer or roll them into one design-doc issue. [P2, hygiene]
5. **README image fix or removal** (~1 hour, #38). [P2, docs]

### Next month

6. **Add `:locals` and `:if` options to `fn-traced`** (~50 + 5 LOC, ~1 week including tests). Both ride in the same release. The `&env` capture for locals needs a CLJS branch (env shapes differ); plan for this. [P1, biggest debux→rfd port]
7. **Expose `wrap-handler!` / `unwrap-handler!` runtime API** (~40 LOC, ~3 days). The single largest amplifier for re-frame-pair's recipe. Document with examples. [P1, integration]
8. **Production-mode loud-fail check** (~20 LOC, ~1 day). Macro-time assertion + runtime console.warn when `goog.DEBUG=false` and tracing is active. [P2, footgun]
9. **End-to-end integration test** (~1 week). Spin up a re-frame fixture in karma, fire a dispatch through a `fn-traced` handler, inspect the resulting epoch's `:code` tag. Catches version-skew bugs and silent payload regressions. [P1, trust-building]

### Someday

10. **`:once` / duplicate-suppression option** (~80 LOC, ~1 week). Lower priority; complicated by gensym scoping and lifecycle. Worth doing once `:locals` is in.
11. **fx-map tracing (#341 TODO)** (~1 week). Walk effect maps inside `:effects` and emit per-effect traces. Could be implemented as a new macro `fx-traced` that wraps `reg-fx` instead of generalising `dbgn`'s walker.
12. **Function entry/exit markers (#37)** — ✓ **Shipped in v0.7.0** as the `:trace-frames` tag emitted by `fn-traced` / `defn-traced` (commit `8ba53a8`). Original plan: ~1 week. Easier once `wrap-handler!` exists — entry/exit can be emitted by the wrapper rather than the macro.
13. **"Show all" verbosity mode (#36)** — ✓ **Shipped in v0.7.0** as the `:verbose` / `:show-all` option on `fn-traced` / `defn-traced` / `dbgn` (commit `0177254`). Original plan: ~1 week. Likely a flag on `send-trace!` that disables the noise filter.

### Deliberately out of scope

- **Self-hosted ClojureScript support (#35)** — declined 2026-04-27. Architectural cost too high for the demand. Close #35 with this context.
- **Exception tracing (#34)** — declined 2026-04-27. Design tension with browser break-on-exception not worth resolving. Close #34 with this context.

---

## 7. Open questions — resolved 2026-04-27

- **Is the day8 team actively maintaining this library?** **Yes — actively maintained.** The 2020 gap was a sprint stall, not abandonment. README / milestone statement to follow when next visible release lands.
- **Is there a reason the fork doesn't expose `dbg` / single-form tracing alongside `fn-traced`?** **No deliberate omission.** `dbg` should be added for re-frame-pair (and other downstream consumers) to use at the REPL — single-expression tracing is debux's lowest-friction surface and the absence is unmet demand, not scope. Tracked as a future bead.
- **Should `:locals` capture from `&env` (compile-time bindings) or use a runtime probe?** Resolved in `rfd-880` (commit `4d6e507`) — `&env` only; CLJS captures less, accepted.
- **What's the right home for re-frame-pair-specific helpers?** Resolved 2026-04-26: re-frame-debux owns the contract. `wrap-handler!` / `unwrap-handler!` shipped in `day8.re-frame.tracing/runtime` (commit `4ed07c9`); re-frame-pair consumes via the public surface (rfp-6z2, commit `a285c1d`).
- **CHANGELOG cadence?** **Yes — there should be a CHANGELOG file.** Every dependency bump and option addition gets a user-visible-delta entry. Tracked as a future bead.

---

*End of plan. Recommended first-action: open a single tracking issue ("v0.6 roadmap") referencing this doc, then close #34/#35/#36/#37 with a pointer to it.*
