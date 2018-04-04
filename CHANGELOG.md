# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.5.1] - 2018-04-05

### Added

* Captured traces now include indenting information. This lets you better see the order and nesting of your function calls.

### Changed

* Don't rename symbol gensyms with three numbers or less, (e.g. `s3` is no longer renamed to `s#`).

## [0.5.0] 

Initial release after fork from debux.

### Added

* Closure-define to enable/disable tracing. Defaults to disabled, and is removed by Closure dead code elimination. To enable/disable tracing, set your compiler options to `:closure-defines {"debux.cs.core.trace_enabled_QMARK_"  true}`
