# day8.re-frame/tracing-stubs

Production-elision counterpart to [day8.re-frame/tracing](https://github.com/day8/re-frame-debux).

Drop-in replacement that compiles `fn-traced`, `defn-traced`, `fx-traced`,
`defn-fx-traced`, `dbg`, `dbg-last`, and `dbgn` down to bare `fn` / `defn` /
form. Zero runtime cost in release builds; instrumented call sites stay in
the source unchanged.

The runtime API (`day8.re-frame.tracing.runtime/wrap-handler!`,
`wrap-event-fx!`, `wrap-event-ctx!`, `wrap-sub!`, `wrap-fx!`, and the
matching `unwrap-*` / `wrapped?` / `wrapped-list` / `unwrap-all!` helpers)
is also stubbed — the wrap macros expand to plain re-frame registrations
and the unwrap helpers become no-ops.

The runtime configuration knobs on `day8.re-frame.tracing` —
`set-tap-output!`, `set-trace-frames-output!`, `set-print-seq-length!`,
`reset-indent-level!` — ship as no-op `defn`s so app-boot wiring keeps
compiling.

See the [main project README](https://github.com/day8/re-frame-debux) for
installation and the two-jar approach (Option 1) plus the shadow-cljs
`:ns-aliases` alternative (Option 2).

## License

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
