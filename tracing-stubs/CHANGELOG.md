# Change Log

All notable changes to `day8.re-frame/tracing-stubs` are documented in this
file. The stub jar tracks the same release line as the parent
`day8.re-frame/tracing` artefact — versions are cut in lockstep, and each
section here lists only the surface-area changes that affect consumers of
the stub package.

This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

## [0.7.0] - 2026-04-27

#### Added

- `fx-traced` / `defn-fx-traced` macros — production stubs for the new
  `reg-event-fx`-shaped tracers in the parent `day8.re-frame.tracing`
  namespace. Strip the leading opts map (which `fn` / `defn` won't accept)
  and compile down to the bare `fn` / `defn`.
- `dbg`, `dbgn`, `dbg-last` macros — production stubs for the inline
  debug-tracing macros. Compile down to the bare expression (or the
  threaded value, in `dbg-last`'s case) so release builds incur zero
  runtime cost.
- `set-tap-output!`, `set-print-seq-length!`, `reset-indent-level!` —
  no-op `defn`s for the runtime configuration knobs the dev-side ns
  re-exports from `debux.common.util`. App-boot wiring of
  `(set-tap-output! true)` now compiles cleanly against the stubs ns
  instead of failing with an unbound-var error.
- Runtime API stub namespace — `day8.re-frame.tracing.runtime` is now
  available in the stub package. `wrap-handler!` / `wrap-event-fx!` /
  `wrap-event-ctx!` / `wrap-sub!` / `wrap-fx!` macros compile to bare
  re-frame registrations with no `fn-traced` wrap; `unwrap-*` /
  `wrapped?` / `wrapped-list` / `unwrap-all!` become no-op runtime fns.
  Closes the gap that previously broke release builds for any caller
  requiring `day8.re-frame.tracing.runtime`.

## [0.6.2] - 2021-03-11

#### Fixed

- Fix deployment of tracing-stubs.

## [0.5.5] - 2020-05-01

#### Fixed

- Fix `require-macros` in tracing-stubs ns for shadow-cljs compatibility.
  Thanks to [@thheller](https://github.com/thheller) for spotting the bug.

## [0.5.4] - 2020-04-30

#### Fixed

- Fix shadow-cljs compatibility by making the `day8.re-frame.tracing-stubs`
  ns aliasable.

## Earlier releases

For pre-0.5.4 history, see the parent project's
[CHANGELOG](https://github.com/day8/re-frame-debux/blob/master/CHANGELOG.md).
