(ns user
  (:require [io.aviso.repl]
            [eftest.runner :refer [find-tests run-tests]]
            [eftest.report]
            [reloaded.repl :refer [reset]]))

(reloaded.repl/set-init! #())

(io.aviso.repl/install-pretty-exceptions)

(defn test-all []
  (run-tests (find-tests "test") {:report eftest.report.pretty/report
                                  :multithread? false}))

(defn reset-and-test []
  (reset)
  (test-all))
