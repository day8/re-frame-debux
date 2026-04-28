;; ---------------------------------------------------------------------
;; project.clj — transitional shim (rfd-iqz, planned removal in v0.7).
;; ---------------------------------------------------------------------
;;
;; The canonical toolchain post-rfd-iqz is tools.deps + bb tasks +
;; shadow-cljs.edn at the root. See README "Testing" + "Releasing"
;; sections, and deps.edn / bb.edn / shadow-cljs.edn for the
;; authoritative configuration.
;;
;; This file remains as a fallback for contributors still on lein:
;;   `lein test` — runs the Clojure-side macroexpansion tests
;;   `lein release` — deploys via CLOJARS_USERNAME/CLOJARS_TOKEN env
;; The shadow-cljs / browser-test paths are NO LONGER routed through
;; lein-shadow — use `bb test` for those. lein-shadow has been removed
;; from :plugins; the :shadow-cljs key + the lein-driven `watch` and
;; `ci` aliases have been removed (they all live in shadow-cljs.edn /
;; bb.edn now).
;;
;; To remove this file in v0.7, delete project.clj and the
;; tracing-stubs/project.clj — both are superseded by their respective
;; deps.edn files, and the GitHub workflows already drive bb tasks.

(defproject    day8.re-frame/tracing "lein-git-inject/version"
  :description "A tool for inspecting code execution for re-frame applications"
  :url         "https://github.com/day8/re-frame-debux"
  :license     {"Eclipse Public License"
                "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure       "1.12.1"  :scope "provided"]
                 [org.clojure/clojurescript "1.12.42" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library
                               org.clojure/google-closure-library-third-party]]
                 [clojure-future-spec       "1.9.0"]
                 [re-frame                  "1.4.5"    :scope "provided"]
                 [net.cgrand/macrovich      "0.2.2"]]

  :min-lein-version "2.9.0"

  :plugins      [[day8/lein-git-inject "0.0.15"]
                 [lein-ancient         "0.6.15"]
                 [lein-shell           "0.5.0"]
                 [lein-eftest          "0.6.0"]]

  :eftest {:multithread? false}
  
  :test-selectors {:default (complement :failing)
                   :failing :failing
                   :current :current}

  :middleware   [leiningen.git-inject/middleware]

  :profiles {:dev {:dependencies  [[zprint          "0.5.1"]
                                   [eftest          "0.6.0"]
                                   [io.aviso/pretty "0.1.37"]
                                   [reloaded.repl   "0.2.4"]]
                   :source-paths   ["src" "dev"]
                   :resource-paths ["dev-resources"]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  [:target-path
   "node_modules"
   "resources/public/js/out"
   "resources/public/js/main.js"]

  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/CLOJARS_USERNAME
                                    :password      :env/CLOJARS_TOKEN}]]

  :release-tasks [["deploy" "clojars"]])

;; --- removed in rfd-iqz (live in shadow-cljs.edn / bb.edn now) ---
;;   :shadow-cljs {...}                       — lifted to shadow-cljs.edn
;;   :aliases {"watch" ..., "ci" ...}         — replaced by bb watch-test / bb test
;;   :shell {...}                             — only used by the removed `ci` alias
;;   :tach {...}                              — lein-tach plugin no longer in :plugins
;;   :clean-targets entry "shadow-cljs.edn"   — file is now committed, not generated
