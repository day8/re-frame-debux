(defproject day8.re-frame/tracing "0.5.4-SNAPSHOT"
  :description "A tool for inspecting code execution for re-frame applications"
  :url "https://github.com/philoskim/debux"
  :license {"Eclipse Public License"
            "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"]
                 [clojure-future-spec "1.9.0"]
                 [re-frame "0.10.8" :scope "provided"]]

  :min-lein-version "2.6.0"

  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-figwheel "0.5.10"]]

  :profiles {:dev {:dependencies [[zprint "0.4.16"]
                                  [eftest "0.5.8"]
                                  [io.aviso/pretty "0.1.37"]
                                  [reloaded.repl "0.2.4"]]
                   :resource-paths ["dev-resources"]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  ["target"
   "resources/public/js/out"
   "resources/public/js/main.js"]
 
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url "https://clojars.org/repo"
                                    :username :env/CLOJARS_USERNAME
                                    :password :env/CLOJARS_PASSWORD}]]

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

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
