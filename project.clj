(defproject day8.re-frame/tracing "0.5.4-SNAPSHOT"
  :description "A tool for inspecting code execution for re-frame applications"
  :url "https://github.com/philoskim/debux"
  :license {"Eclipse Public License"
            "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library]]
                 [thheller/shadow-cljs "2.8.62" :scope "provided"]
                 [clojure-future-spec "1.9.0"]
                 [re-frame "0.10.9" :scope "provided"]]

  :min-lein-version "2.6.0"

  :plugins [[lein-shadow "0.1.5"]
            [lein-shell "0.5.0"]]

  :profiles {:dev {:dependencies [[zprint "0.4.16"]
                                  [eftest "0.5.8"]
                                  [io.aviso/pretty "0.1.37"]
                                  [reloaded.repl "0.2.4"]]
                   :resource-paths ["dev-resources"]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  [:target-path
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

  :shadow-cljs {:nrepl  {:port 8777}

                :builds {:dev
                         {:target           :browser
                          :output-dir       "resources/public/js"
                          :asset-path       "/js"
                          :compiler-options {:pretty-print true}
                          :modules          {:debux {:init-fn debux.cs.test.main}}
                          :devtools         {:http-port 8780
                                             :http-root "resources/public"}}
                         :karma-test
                         {:target    :karma
                          :ns-regexp "-test$"
                          :output-to "target/karma-test.js"}}}

  :aliases {"dev-auto" ["shadow" "watch" "dev"]
            "test-once" ["do"
                         ["shadow" "compile" "karma-test"]
                         ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]})
