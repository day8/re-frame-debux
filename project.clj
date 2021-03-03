(defproject    day8.re-frame/tracing "lein-git-inject/version"
  :description "A tool for inspecting code execution for re-frame applications"
  :url         "https://github.com/day8/re-frame-debux"
  :license     {"Eclipse Public License"
                "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure       "1.10.2" :scope "provided"]
                 [org.clojure/clojurescript "1.10.773" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library
                               org.clojure/google-closure-library-third-party]]
                 [thheller/shadow-cljs      "2.11.18" :scope "provided"]
                 [clojure-future-spec       "1.9.0"]
                 [re-frame                  "1.1.1" :scope "provided"]
                 [net.cgrand/macrovich      "0.2.1"]]

  :min-lein-version "2.9.0"

  :plugins      [[day8/lein-git-inject "0.0.14"]
                 [lein-shadow          "0.3.1"]
                 [lein-ancient         "0.6.15"]
                 [lein-shell           "0.5.0"]
                 [lein-eftest          "0.5.9"]
                 [lein-tach            "1.0.0"]]

  :eftest {:multithread? false}
  
  :test-selectors {:default (complement :failing)
                   :failing :failing
                   :current :current}

  :middleware   [leiningen.git-inject/middleware]

  :profiles {:dev {:dependencies  [[zprint          "0.5.1"]
                                   [eftest          "0.5.9"]
                                   [io.aviso/pretty "0.1.37"]
                                   [reloaded.repl   "0.2.4"]]
                   :source-paths   ["src" "dev"]
                   :resource-paths ["dev-resources"]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  [:target-path
   "node_modules"
   "shadow-cljs.edn"
   "resources/public/js/out"
   "resources/public/js/main.js"]
 
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/CLOJARS_USERNAME
                                    :password      :env/CLOJARS_TOKEN}]]

  :release-tasks [["deploy" "clojars"]]

  :shadow-cljs {:nrepl  {:port 8777}

                :builds {:dev
                         {:target           :browser
                          :output-dir       "resources/public/js"
                          :asset-path       "/js"
                          :compiler-options {:pretty-print true}
                          :modules          {:debux {:entries [debux.cs.test.main]}}
                          :devtools         {:http-port 8780 
                                             :http-root "resources/public"}}

                         :browser-test
                         {:target :browser-test
                          :ns-regexp ".*-test$"
                          :test-dir "target/test"
                          :compiler-options {:pretty-print true}
                          :devtools {:http-port 8790
                                     :http-root "target/test"}}

                         :karma-test
                         {:target    :karma
                          :ns-regexp "-test$"
                          :output-to "target/karma-test.js"}}}

  :shell {:commands {"karma" {:windows         ["cmd" "/c" "karma"]
                              :default-command "karma"}
                     "open"  {:windows         ["cmd" "/c" "start"]
                              :macosx          "open"
                              :linux           "xdg-open"}}}

  :aliases {"watch" ["with-profile" "dev" "do"
                     ["clean"]
                     ["shadow" "watch" "dev" "browser-test" "karma-test"]]

            "ci"    ["do"
                     ["clean"]
                     ["shadow" "compile" "karma-test"]
                     ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]}
  
  :tach {:test-runner-ns 'day8.re-frame.debux.runner
         :source-paths ["src/cljs" "src/cljc" "test"]})
