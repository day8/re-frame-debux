# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

#### Fixed

* Production stub for the runtime API. Two new files — `tracing-stubs/src/day8/re_frame/tracing/runtime.cljc` and the in-src counterpart `src/day8/re_frame/tracing_stubs/runtime.cljc` — close the gap that broke release builds (two-jar setup) for any caller requiring `day8.re-frame.tracing.runtime`. Stub `wrap-handler!` / `wrap-event-fx!` / `wrap-event-ctx!` / `wrap-sub!` / `wrap-fx!` macros compile to bare `re-frame.core/reg-event-db` (or sibling) with no `fn-traced` wrap; `unwrap-*` / `wrapped?` / `wrapped-list` / `unwrap-all!` become no-op runtime fns. Mirrors the `fn-traced` / `fx-traced` / `dbg` / `dbgn` stub pattern (commits e0bd687, bae1b0d, b3248af).
* CLJ-side `:skip-all-args-type` classifier now lists `use`, mirroring the CLJS-side entry. Without this, `(dbgn (use 'some.ns))` under JVM-CLJ fell through to the fn-call default and dbgn descended into the quoted-ns arg — extra trace noise at best, ill-formed classification for callers passing multiple symbol args. New regression test in `test/day8/re_frame/debux/regression_test.clj` pins the one-trace, no-descent contract.

## [0.7.0] - 2026-04-27

The v0.7 line closes the remaining feature gaps relative to philoskim/debux's option surface and adds two re-frame-shaped extensions on top — function entry/exit markers and per-effect tracing for `reg-event-fx` handlers. Nine additive items, no breaking changes.

#### Added

* `:once` / `:o` option on `fn-traced` / `defn-traced` / `dbg` / `dbgn` (commit 33225e8). Suppresses consecutive emissions whose `(form, result)` pair matches the previous one. Per call-site identity (gensym'd at expansion); state survives across handler invocations until the result actually changes. Useful for high-frequency dispatches where you only want to see what's NEW. Composes with `:if` cleanly. Public reset via `day8.re-frame.debux.common.util/-reset-once-state!`.
* `:verbose` / `:show-all` option on `fn-traced` / `defn-traced` / `dbgn` (commit 0177254). Wraps leaf literals (numbers, strings, booleans, keywords, chars, nil) that the default zipper walker skips for noise reduction. Special-form skips (`recur`, `throw`, `var`, `quote`, `catch`, `finally` — the `:skip-form-itself-type` set) STAY honoured because instrumenting them corrupts evaluation semantics. Resolves the `:skip-classification` open question from `docs/v0.6-roadmap.md` Pair A.
* `:final` / `:f` option on `fn-traced` / `defn-traced` / `dbg` / `dbgn` / `fx-traced` / `defn-fx-traced` (commit 8eeadf6). When set, only the outermost (indent-level 0) `:code` entry is emitted per top-level wrapping form — every nested per-form entry is suppressed. Useful when you only care about the final value of each handler-body expression and the per-step zipper trace is noise. Composes with `:if` / `:once` / `:msg`.
* `:msg` / `:m` option on `dbg` / `dbgn` / `fn-traced` / `defn-traced` / `fx-traced` / `defn-fx-traced` (commit b0a68b9). Attaches a developer-supplied label to each emitted `:code` entry so consumers can distinguish output from many parallel call sites. Per-call dynamic — the value is evaluated at trace time, not macroexpansion.
* `dbg-last` macro — thread-last-friendly counterpart to `dbg` (commit 304ef11). Sits in the natural `->>` step slot: `(->> coll (filter pred?) dbg-last (map xf))`. Value-transparent; same payload schema and opts (`:name` / `:locals` / `:if` / `:once` / `:msg` / `:tap?`) as `dbg`.
* `tap>` output channel for trace records (commit 18b06dc). New `day8.re-frame.tracing/set-tap-output!` toggle plumbs every `send-trace!` payload through `tap>` in addition to its normal in-trace sink, so any `add-tap` consumer (custom 10x panels, REPL probes, eval-cljs scripts) sees the same processed `:code` records. Default off — preserves the existing trace-only behaviour. Companion to the `:tap?` per-call opt on `dbg` / `dbg-last`.
* Function entry/exit markers — `:trace-frames` tag emitted by `fn-traced` / `defn-traced` (commit 8ba53a8). Each invocation produces a paired `{:phase :enter :frame-id …}` / `{:phase :exit :frame-id … :result …}` marker bracketing the existing `:code` payload, so consumers (10x Code panel, custom inspectors) can pair the markers and bound the intermediate `:code` entries. Off-trace: markers are silently dropped (no `tap>` fallback — frames are framework-level boundary info, not user-visible data). Exception path: only `:enter` is guaranteed; a missing `:exit` is the signal that the body threw.
* `fx-traced` / `defn-fx-traced` macros for `reg-event-fx` handlers (commit bae1b0d). Inherits all of `fn-traced`'s per-form `:code` emission, frame markers, and `:locals` / `:if` / `:once` / `:verbose` opts; ALSO emits one `:fx-effects` entry per key in the returned effect-map (`{:fx-key k :value v :t ms}`). Resolves the `dbgn.clj:353` "trace inside maps, especially for fx" TODO without modifying the zipper walker. Production stubs in both `tracing_stubs` namespaces strip opts and compile to bare `fn` / `defn`.
* Classifier parity with upstream debux — closes the four confirmed gaps (commit b471026). New `:skip-all-args-type` category: forms whose args carry compile-time semantics (protocol impls, `reify` method bodies, `var` / `quote` / `import`, etc.) get one trace at the top level with no descent into the args — strictly more informative than `:skip-form-itself-type`. `condp`'s test/expr pairs are now correctly walked, `extend` / `extend-protocol` / `extend-type` are recognised, and the previously-missing forms in `:skip-fn-ret-type` / `:skip-fn-args-type` got added.

#### Internal

* `+debux-trace-id+` runtime binding alongside the existing `+debux-dbg-opts+` / `+debux-dbg-locals+` (commit 33225e8). Each `dbgn` / `dbgn-forms` / `mini-dbgn` expansion bakes in a unique gensym'd string used as the per-call-site identity for `:once` dedup.
* `mini-dbgn` (test-only) uses a fixed `"mini-dbgn"` trace-id instead of a gensym so the macroexpansion-shape assertions in `dbgn_test.clj` stay byte-stable.
* Runtime API coverage — `wrap-event-fx!` / `wrap-event-ctx!` / `wrap-sub!` and `wrap-handler! :event` each get a dedicated deftest. Two unit-level tests pin the chain-builder choice (`reg-event-db`) and the `:sub`-kind reaction-deref → `:code` emission path; two integration tests dispatch through `reg-event-fx` and `reg-event-ctx` chains end-to-end. Closes the gap that previously left three of the four runtime-API macros without automated guard.

## [0.6.3] - 2026-04-27

#### Fixed

* Issue [#40](https://github.com/day8/re-frame-debux/issues/40) — `fn-traced` / `defn-traced` no longer hang on `loop`/`recur`. Two-part fix: `recur` carve-out in `:skip-form-itself-type` (commit db9b7de) and `o-skip?` predicate now compares against the correct fully-qualified symbol (commit 48de2e8).
* `remove-skip`'s `o-skip?` handler now re-evaluates the new loc instead of descending into the replaced form (commit 8d4e331).
* Documentation images restored: `doc/img/cursive-{1,2,3}.png` re [#38](https://github.com/day8/re-frame-debux/issues/38) (commit ff5122a).
* Test-selector parity between `lein test` and `cognitect.test-runner` — `^:failing` and `^:integration` tests are excluded by default in both runners (commit 4eee8a3).
* `defn-traced*` now propagates `:docstring` and `:meta` to the resulting var (commit a527e6a). `::ms/defn-args` had been conforming both fields all along, but `defn-traced*` only spliced `:name` and `:bs`, silently dropping any leading docstring or attr-map. Result was a traced handler whose var had no `:doc` / `:added` / `:tag` meta even though the source looked correct.

#### Added

* `dbg` single-form tracing macro for inline expression debugging (commit 992fd28).
* Runtime API: `day8.re-frame.tracing.runtime/wrap-handler!` and `unwrap-handler!` for ephemeral `fn-traced` wrap-and-restore at the REPL (commit 4ed07c9).
* `runtime-api?` feature-detection predicate for downstream consumers (commit 6b04e6b).
* `:locals` and `:if` options to `fn-traced` / `defn-traced` (commit 4d6e507).
* Production-mode loud-fail check — macro-time assertion plus `console.warn` when `goog.DEBUG=false` (commit 10d27fd).
* `:code` payload schema documented in `send-trace!` docstring (commit 6b4f042).
* End-to-end integration-test scaffolding (4 deftests in `test/.../integration_test.clj`, commits 6e4f5d1 + 8d4e331).

#### Changed

* Toolchain modernised: `deps.edn` + `bb.edn` + `shadow-cljs.edn` at the project root; CI/CD workflows ported to `bb` tasks (commits c940967 + a1fc73e + acea1d0 + 4109a0c).
* Core dependency pins bumped to current versions (commit ee768f2).
* `project.clj` retained as a transitional shim through the v0.6.x line; deletion targeted for v0.7.

#### Internal

* `docs/v0.6-roadmap.md` — milestone scope as a living document (commit 9d9fc3c).
* `docs/improvement-plan.md` — long-term roadmap synthesis (commit 20ac8f4).
* `docs/v0.6-cd-dry-run-report.md` — release-pipeline validation findings (commit f35e071).

## [0.6.1] / [0.6.2] - 2021-03-11

Both tags resolve to the same commit (ca70cb9); the "Unreleased" section was never promoted at release time.

#### Fixed

* Fix deployment of tracing-stubs.

#### Changed

* Upgrade Clojure to 1.10.3.
* Upgrade shadow-cljs to 2.11.22.
* Upgrade lein-tach to 1.1.0.
* Upgrade Karma to 6.2.0.

## [0.6.0] - 2020-05-16 

* [Changes](https://github.com/day8/re-frame-debux/compare/v0.5.6...v0.6.0).

## [0.5.5] - 2020-05-01

#### Fixed

* Fix require-macros in tracing-stubs ns for shadow-cljs compatibility. Thanks
  to [@thheller](https://github.com/thheller) for spotting the bug. 

## [0.5.4] - 2020-04-30

#### Fixed

* Fix shadow-cljs compatibility by making the `day8.re-frame.tracing-stubs` ns
  available in the main package. Thanks to [@thheller](https://github.com/thheller).
  See [#30](https://github.com/day8/re-frame-debux/issues/30).

#### Changed

* Upgrade ClojureScript to 1.10.748
* Upgrade shadow-cljs to 2.8.109
* Upgrade lein-shadow to 0.1.7
* Upgrade karma to 4.4.1
* Upgrade zprint to 0.5.1
* Upgrade eftest to 0.5.9
* Upgrade re-frame to 0.12.0

## [0.5.3] - 2019-07-18

### Breaking Changes

* Deletes obsolete `lazy-seq?` fn in `debux.common.util`. If for some reason
  you are using this function in your own code, it is no longer available so
  you will need to copy it to your own source. Re [PR 26](https://github.com/Day8/re-frame-debux/pull/26)

### Fixed

* shadow-cljs warning 'Use of undeclared Var debux.common.util/clojure' re [PR 26](https://github.com/Day8/re-frame-debux/pull/26)

## [0.5.2] - 2019-07-18

### Fixed

* figwheel.main compatibility fix re [#28](https://github.com/Day8/re-frame-debux/issues/28) @danielcompton

### Changed

* Upgraded Clojure to 1.10.1
* Upgraded ClojureScript to 1.10.520
* Upgraded clojure-future-spec to 1.9.0
* Upgraded re-frame to 0.10.8
* Upgraded zprint to 0.4.16
* Upgraded eftest to 0.5.8
* Upgraded io.aviso/pretty to 0.1.37

### Improved

* Documentation fixes and improvements. @danielcompton

## [0.5.1] - 2018-04-05

### Added

* Captured traces now include indenting information. This lets you better see the order and nesting of your function calls.

### Changed

* Don't rename symbol gensyms with three numbers or less, (e.g. `s3` is no longer renamed to `s#`).

## [0.5.0] 

Initial release after fork from debux.

### Added

* Closure-define to enable/disable tracing. Defaults to disabled, and is removed by Closure dead code elimination. To enable/disable tracing, set your compiler options to `:closure-defines {"debux.cs.core.trace_enabled_QMARK_"  true}`
