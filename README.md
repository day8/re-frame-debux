# re-frame-debux

## Credit

[Debux](https://github.com/philoskim/debux) is a simple but useful library for debugging Clojure and ClojureScript. *re-frame-debux* is a fork of Debux, that repurposes it for tracing re-frame event and subscription handlers, and integration with [re-frame-10x](https://github.com/Day8/re-frame-10x) and re-frame's tracing API.

Longer term, we would like to investigate merging back into mainline Debux, but the changes we needed to make required quite deep surgery, so in the interests of time, we started off with this fork.

## Status

This library is still experimental and there may be bugs in the way that it instruments your code in development. There should be no issues with your code in production, as we delegate directly to the core Clojure functions. If you run into any issues, please open an issue and we can try and help.

## Prerequisites

* clojure 1.8.0 or later
* clojurescript 1.9.854 or later

## Installation

To include *re-frame-debux* in your project, simply add the following to your *project.clj* development dependencies:

```
[day8.re-frame/debux "0.5.0"] ;; not released yet
```

and this to your production dependencies (make sure they are production only):

```
[day8.re-frame/tracing "0.5.0] ;; not released yet
```

Add Closure defines to your config to enable re-frame tracing + the function tracing:

```
:cljsbuild    {:builds {:client {:compiler {:closure-defines {"re_frame.trace.trace_enabled_QMARK_" true
                                                              "debux.cs.core.trace_enabled_QMARK_"  true}}}}}}
```

## How to use

re-frame-debux provides two macros for you to use to trace your re-frame event and subscription handlers:

* `fn-traced`
* `traced-defn`

fn-traced and traced-defn replace fn and defn respectively.

Both have zero runtime and compile time cost in production builds via the `day8.re-frame/tracing` library, and are able to be turned off at dev time too via the Closure define, so you can leave the `fn-traced` and `traced-defn` macros in your code at all times.

## Two libraries

In development, you want to include the `day8.re-frame/debux` library. When you use a `day8.re-frame.tracing/fn-traced` or `day8.re-frame.tracing/traced-defn` from this library, it will emit traces to re-frame's tracing system, which can then be consumed by [re-frame-10x](https://github.com/Day8/re-frame-10x).

In production, you want to include the `day8.re-frame/tracing` library. This has the same public API as debux (`day8.re-frame.tracing/fn-traced`, `day8.re-frame.tracing/traced-defn`), but the macros simply delegate to the core `fn` and `defn` macros.

This ensures that you can keep your code instrumented at all times, but not pay any costs in production.

## License
Copyright © 2015--2018 Young Tae Kim, 2018 Day 8 Technology

Distributed under the Eclipse Public License either version 1.0 or any later version.
