;; Self-hosted / REPL-driven CLJS test runner. NOT the path
;; `bb test-browser` takes — that builds the `:browser-test` shadow-
;; cljs target which auto-discovers test namespaces via
;; `:ns-regexp "-test$"` (see shadow-cljs.edn). Reach for this file
;; only when running `cljs.test/run-all-tests` from a self-hosted REPL
;; / nbb-style runner that wants the test ns set materialised at
;; require time.
;;
;; If you add a new CLJC test namespace and want it picked up here too,
;; require it below. The `:browser-test` build does NOT need an entry
;; added — its regex sweep finds new files automatically.

(ns day8.re-frame.debux.runner
  (:require [clojure.test :refer [deftest run-all-tests is]]
            [day8.re-frame.debux.common.prod-mode-warn-test]
            [day8.re-frame.debux.common.util-test]
            [day8.re-frame.debux.if-option-test]
            [day8.re-frame.debux.final-option-test]
            [day8.re-frame.dbg-test]
            [day8.re-frame.tracing.runtime-test]))

(run-all-tests #"day8.*")
