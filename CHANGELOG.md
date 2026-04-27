# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.6.3] - 2026-04-27

#### Fixed

* Issue [#40](https://github.com/day8/re-frame-debux/issues/40) — `fn-traced` / `defn-traced` no longer hang on `loop`/`recur`. Two-part fix: `recur` carve-out in `:skip-form-itself-type` (commit db9b7de) and `o-skip?` predicate now compares against the correct fully-qualified symbol (commit 48de2e8).
* `remove-skip`'s `o-skip?` handler now re-evaluates the new loc instead of descending into the replaced form (commit 8d4e331).
* Documentation images restored: `doc/img/cursive-{1,2,3}.png` re [#38](https://github.com/day8/re-frame-debux/issues/38) (commit ff5122a).
* Test-selector parity between `lein test` and `cognitect.test-runner` — `^:failing` and `^:integration` tests are excluded by default in both runners (commit 4eee8a3).

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
