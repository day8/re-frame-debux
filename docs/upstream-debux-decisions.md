# Upstream Debux Decisions

This document records useful upstream `philoskim/debux` ideas that were
evaluated for re-frame-debux but intentionally not carried across.

## Tagged Literal Trace Shorthands

Upstream debux v0.7 added reader tags such as `#d/dbg` and `#d/dbgn`
for no-option tracing forms. re-frame-debux is not carrying these over
for now.

Reasons:

- Reader tags are global input syntax. Adding `data_readers.clj` makes a
  shorthand visible anywhere the project is on the classpath, including
  build tools and REPL sessions that may not have opted into tracing.
- The shorthand only covers the no-options case. re-frame-debux's useful
  tracing surface now depends on options such as `:msg`, `:if`, `:once`,
  `:final`, and `:tap?`; switching between reader tags and ordinary
  macro calls would add a second style for the least capable form.
- Production elision is clearer with explicit macro namespaces. The
  existing `day8.re-frame.tracing` and tracing-stubs namespaces make the
  dev/release boundary visible in `require` forms and shadow-cljs
  aliases; reader tags obscure that boundary.

The explicit forms remain the supported spelling:

```clojure
(day8.re-frame.tracing/dbg value)
(day8.re-frame.tracing/dbgn form :msg "label")
```

Revisit only if downstream users have a concrete ergonomics problem that
cannot be solved by namespace aliases or editor snippets.
