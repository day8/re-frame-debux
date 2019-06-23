(defproject day8.re-frame/tracing "0.5.2-SNAPSHOT"
  :description "A tool for inspecting code execution for re-frame applications"
  :url "https://github.com/philoskim/debux"
  :license {"Eclipse Public License"
            "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.9.854" :scope "provided"]
                 [clojure-future-spec "1.9.0-alpha17"]
                 [re-frame "0.10.4" :scope "provided"]]

  :min-lein-version "2.6.0"

  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}

  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-figwheel "0.5.10"]]

  :profiles {:dev {:dependencies [[zprint "0.4.7"]
                                  [eftest "0.5.0"]
                                  [io.aviso/pretty "0.1.34"]
                                  [reloaded.repl "0.2.4"]]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  ["target"
   "resources/public/js/out"
   "resources/public/js/main.js"]

  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src" "dev"]
     :figwheel true
     :compiler {:main debux.cs.test.main
                :output-to "resources/public/js/main.js"
                :output-dir "resources/public/js/out/"
                :asset-path "js/out/"
                :optimizations :none
                :source-map true
                :pretty-print true} }]})
