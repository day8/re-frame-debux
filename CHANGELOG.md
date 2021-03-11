# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased

#### Changed

- Upgrade Clojure to 1.10.3

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
