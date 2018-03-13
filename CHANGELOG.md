# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.5.0] - Unreleased

Initial release after fork from debux.

### Added

* Closure-define to enable/disable tracing. Defaults to disabled, and is removed by Closure dead code elimination. To enable/disable tracing, set your compiler options to `:closure-defines {"debux.cs.core.trace_enabled_QMARK_"  true}`
