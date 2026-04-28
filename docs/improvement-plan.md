# re-frame-debux — improvement plan

> Target audience: the re-frame-debux maintainer. Synthesis of three parallel surveys (rfd-pa2): current-state of the library, GitHub issue triage, philoskim/debux feature comparison. Cross-referenced with `re-frame-pair`'s recent debux survey (`/home/mike/code/re-frame-pair/docs/inspirations-debux.md`) so the recommendations also serve as a checklist of what would amplify re-frame-pair's on-demand REPL hot-swap recipe.

---

## 1. Executive summary

re-frame-debux is in **stable maintenance mode** with a now-expanded tracing surface (`day8.re-frame.tracing/{fn-traced,defn-traced,dbg,dbg-last,fx-traced,defn-fx-traced}` plus `day8.re-frame.tracing.runtime`), a working zipper-based AST walker, and a production-elision story (`tracing-stubs` jar + shadow-cljs `:ns-aliases`). The core engine works and produces useful output through re-frame-10x's Code panel.

This document began as an early-2026 recovery plan. The original emergency items have since shipped: issue #40's `loop`/`recur` hang is fixed, core dependency pins were refreshed, the `:code` payload schema is documented, `:locals` / `:if` landed, and the v0.7 feature quartet added `:once`, fx tracing, frame markers, and verbose tracing. What remains is no longer "unstall v0.6"; it is keeping the maintainer-facing roadmap truthful, closing the operator-owned upstream issue loop, and treating the new trace payloads as stable contracts for downstream tools.

**Current highest-leverage follow-ups**, in priority order:

1. **Close or annotate the upstream GitHub issues.** #36 and #37 shipped in v0.7, #34 and #35 were declined, #40 is fixed, and #38 remains an operator-side README image decision.
2. **Keep release-facing docs aligned with the shipped surface.** README and CHANGELOG now carry most of the v0.7+ feature tour; this plan and `docs/v0.6-roadmap.md` should stay historical/status documents rather than active queues.
3. **Guard the new public contracts.** The expanded runtime API, `:code` / `:trace-frames` / `:fx-effects` payloads, tap output, and tracing-stubs parity are now the highest-value regression surfaces.

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

**Half-done / smells.** Remaining TODOs in `dbgn.clj` and `util.cljc` still flag known limitations: `clojure.core` symbol cleanup not generalised (#102), macroexpanded form not captured in payload (#134), and a few indentation / skip-shape notes that need fresh triage. The old plain-list and fx-map TODOs have been retired as stale after v0.7: quoted/data-list forms stay opaque through `quote` / `:skip-all-args-type`, and per-key fx-map tracing is handled by `fx-traced` / `-emit-fx-traces!` rather than zipper descent. The `spy-*` family (`util.cljc:343–366`) appears unused. The README claims Clojure 1.8.0+ support but the project pins 1.10.3.

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

The integration is described in detail in `/home/mike/code/re-frame-pair/docs/inspirations-debux.md`. The three original re-frame-debux-side recommendations have all shipped; the original text is retained here as design history with current status notes.

**(a) Document the `:code` payload schema.** ✓ **Shipped** in commit `6b4f042`. The fields `{:form, :result, :indent-level, :syntax-order, :num-seen}` were written to `:tags :code` but their semantics were not documented anywhere — neither in the README nor in inline comments. The schema comment near `send-trace!` now names each field, gives an example shape, and notes the `tidy-macroexpanded-form` step, turning the payload into a contract for re-frame-pair, third-party 10x panels, and custom inspectors.

**(b) Make the production-mode warning loud, not silent.** ✓ **Shipped** in commit `10d27fd`. If a user's release build accidentally ships `day8.re-frame.tracing` instead of the stubs, the first trace send now emits a loud production-mode warning. The check is covered by `prod_mode_warn_test.cljc`.

**(c) Expose public `wrap-handler!` / `unwrap-handler!` runtime functions.** ✓ **Shipped** in `day8.re-frame.tracing.runtime` in commit `4ed07c9`, with follow-up coverage for missing-handler, double-wrap, sub/fx/event-fx/event-ctx, and production-stub parity. re-frame-pair can now wrap and restore registered handlers with one public call instead of synthesising macro forms at the REPL.

**Sub-tracing and fx-tracing.** ✓ **Shipped.** `wrap-sub!` / `wrap-fx!` are available as runtime conveniences, and `fx-traced` / `defn-fx-traced` shipped in commit `bae1b0d` to emit per-key `:fx-effects` entries without generalising the zipper walker. Runtime `wrap-event-fx!` / `wrap-event-ctx!` later moved to the same per-effect surface in commit `67181fa`.

---

## 6. Prioritised roadmap — status as of v0.7.x

This section is now an implementation ledger for the original roadmap. "Operator-owned" means local code work is complete or intentionally declined, but the human maintainer still needs to update upstream GitHub issue state.

### Original next session

1. **Fix #40 — `loop`/`recur` non-termination** — ✓ **Shipped.** `recur` was added to `:skip-form-itself-type` with regression coverage in commit `db9b7de`; the root `o-skip?` FQN bug was fixed in `48de2e8`; the integration wiring and a tail-position fix landed in `8d4e331`.
2. **Bump dependency pins** — ✓ **Shipped.** The tools/dependency refresh landed across the `rfd-iqz` series (`c940967` / `a1fc73e` / `acea1d0` / `4109a0c`) and commit `ee768f2`, with later Clojure / CLJS pin alignment in `48258d4`.
3. **Document the `:code` payload schema** — ✓ **Shipped** in commit `6b4f042`.
4. **Close or merge the 2020 sprint backlog** — **Operator-owned.** #36 and #37 shipped in v0.7; #34 and #35 were declined; `docs/v0.6-roadmap.md` carries close-comment templates for all four issues.
5. **README image fix or removal (#38)** — **Operator-owned.** This is still outside local code-agent scope unless the operator decides to patch the upstream README assets.

### Original next month

6. **Add `:locals` and `:if` options to `fn-traced`** — ✓ **Shipped** in commit `4d6e507`, with follow-up fixes for option parsing, varargs locals, and production stubs.
7. **Expose `wrap-handler!` / `unwrap-handler!` runtime API** — ✓ **Shipped** in commit `4ed07c9`. Follow-ups added feature detection, edge-case tests, sub/fx/event-fx/event-ctx wrappers, and tracing-stubs parity.
8. **Production-mode loud-fail check** — ✓ **Shipped** in commit `10d27fd`.
9. **End-to-end integration test** — ✓ **Shipped with caveat.** Commit `6e4f5d1` scaffolded four pending integration tests; commit `8d4e331` wired them into `bb test` and fixed the macro-walker bugs they exposed. Browser coverage continued later in the CLJS integration fixture.

### Original someday

10. **`:once` / duplicate-suppression option** — ✓ **Shipped in v0.7.0** in commit `33225e8`.
11. **fx-map tracing (#341 TODO)** — ✓ **Shipped in v0.7.0** as `fx-traced` / `defn-fx-traced` in commit `bae1b0d`, with runtime `wrap-event-fx!` / `wrap-event-ctx!` parity added in commit `67181fa`.
12. **Function entry/exit markers (#37)** — ✓ **Shipped in v0.7.0** as the `:trace-frames` tag emitted by `fn-traced` / `defn-traced` in commit `8ba53a8`.
13. **"Show all" verbosity mode (#36)** — ✓ **Shipped in v0.7.0** as the `:verbose` / `:show-all` option on `fn-traced` / `defn-traced` / `dbgn` in commit `0177254`.

### Additive shipped work not in the original roadmap

- **Single-form tracing:** `dbg` (`992fd28`) and `dbg-last` (`304ef11`).
- **Trace filtering and labelling:** `:final` / `:f` (`8eeadf6`) and `:msg` / `:m` (`b0a68b9`).
- **Tap output:** `set-tap-output!` (`18b06dc`) and later tap parity for forms, frames, and fx effects (`d207b45`).
- **Classifier parity / walker hardening:** notable fixes include `b471026`, `622a9dd`, `a13f7d8`, and the integration/browser fixtures that now exercise the tricky paths.

### Current follow-ups

- Upstream issue hygiene: close #40 / #36 / #37 as shipped, close #34 / #35 as declined, decide #38.
- Release/status polish: keep README, CHANGELOG, `docs/v0.6-roadmap.md`, and this plan aligned with the shipped surface.
- Contract coverage: preserve tests around `:code`, `:trace-frames`, `:fx-effects`, tap output, runtime wrappers, and tracing-stubs parity.

### Deliberately out of scope

- **Self-hosted ClojureScript support (#35)** — declined 2026-04-27. Architectural cost too high for the demand. Close #35 with this context.
- **Exception tracing (#34)** — declined 2026-04-27. Design tension with browser break-on-exception not worth resolving. Close #34 with this context.

---

## 7. Open questions — resolved 2026-04-27

- **Is the day8 team actively maintaining this library?** **Yes — actively maintained.** The 2020 gap was a sprint stall, not abandonment. README / milestone statement to follow when next visible release lands.
- **Is there a reason the fork doesn't expose `dbg` / single-form tracing alongside `fn-traced`?** **No deliberate omission.** `dbg` shipped in commit `992fd28`, and `dbg-last` followed in `304ef11` for thread-last pipelines.
- **Should `:locals` capture from `&env` (compile-time bindings) or use a runtime probe?** Resolved in `rfd-880` (commit `4d6e507`) — `&env` only; CLJS captures less, accepted.
- **What's the right home for re-frame-pair-specific helpers?** Resolved 2026-04-26: re-frame-debux owns the contract. `wrap-handler!` / `unwrap-handler!` shipped in `day8.re-frame.tracing/runtime` (commit `4ed07c9`); re-frame-pair consumes via the public surface (rfp-6z2, commit `a285c1d`).
- **CHANGELOG cadence?** **Yes — there should be a CHANGELOG file.** `CHANGELOG.md` now exists and carries user-visible entries for dependency bumps, option additions, runtime API changes, and production-stub parity.

---

*End of plan. Recommended first action: finish upstream issue hygiene and keep this file as a historical/status ledger, not an active queue of already-shipped work.*
