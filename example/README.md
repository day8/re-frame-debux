# Re-frame Debux Example

Runnable feature tour for `day8.re-frame/tracing` with re-frame 10x.

## Getting Started

```bash
npm install
cd example
bb clean
bb dev
```

Run `npm install` from the repository root if `node_modules/` is not present yet.

1. Browse to [http://localhost:8280/](http://localhost:8280/).
2. Open the re-frame 10x window with `ctrl-h`.
3. Use the buttons to dispatch traced events and inspect the Code, Fx, and trace-frame data.

The bb tasks use `deps.edn` and the local checkout of `day8.re-frame/tracing`, so local source edits are reflected without installing a jar.

## Builds

```bash
bb compile   # one development compile
bb release   # production compile with tracing namespaces aliased to stubs
bb karma     # legacy karma target
```

`project.clj` is retained as a Leiningen compatibility shim for existing workflows. New work should use the bb tasks above.

## Dependency Surface

The example pins the v0.7 tracing line and current demo dependencies:

```clojure
day8.re-frame/tracing       {:local/root ".."}
day8.re-frame/tracing-stubs {:local/root "../tracing-stubs"} ; release alias
re-frame/re-frame           {:mvn/version "1.4.5"}
day8.re-frame/re-frame-10x  {:mvn/version "1.11.0"}
thheller/shadow-cljs        {:mvn/version "3.4.5"}
```
