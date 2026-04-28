(defproject example "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure       "1.12.1"]
                 [org.clojure/clojurescript "1.12.134"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library
                               org.clojure/google-closure-library-third-party]]
                 [thheller/shadow-cljs      "3.4.5"]
                 [reagent                   "1.2.0"]
                 [re-frame                  "1.4.5"]
                 [day8.re-frame/tracing     "0.7.0"]
                 [re-com                    "2.29.2"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [org.clojure/core.async    "1.9.865"]]

  :plugins [[lein-shadow "0.4.1"]
            [lein-shell  "0.5.0"]]

  :min-lein-version "2.9.0"

  :source-paths ["src/cljs"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :shell {:commands {"open" {:windows ["cmd" "/c" "start"]
                             :macosx  "open"
                             :linux   "xdg-open"}}}

  :shadow-cljs {:nrepl      {:port 8777}
                :js-options {:node-modules-dir "../node_modules"}

                :builds {:app {:target     :browser
                               :output-dir "resources/public/js/compiled"
                               :asset-path "/js/compiled"
                               :modules    {:app {:init-fn  example.core/init
                                                  :preloads [devtools.preload
                                                             day8.re-frame-10x.preload]}}
                               :dev        {:compiler-options
                                            {:closure-defines {re-frame.trace.trace-enabled?       true
                                                               day8.re-frame.tracing.trace-enabled? true}}}
                               :release    {:build-options
                                            {:ns-aliases {day8.re-frame.tracing         day8.re-frame.tracing-stubs
                                                          day8.re-frame.tracing.runtime day8.re-frame.tracing-stubs.runtime}}}
                               :devtools   {:http-root "resources/public"
                                            :http-port 8280}}

                         :karma-test {:target    :karma
                                      :ns-regexp "-test$"
                                      :output-to "target/karma-test.js"}}}

  :aliases {"dev"          ["with-profile" "dev" "do"
                            ["shadow" "watch" "app"]]
            "prod"         ["with-profile" "prod" "do"
                            ["shadow" "release" "app"]]
            "build-report" ["with-profile" "prod" "do"
                            ["shadow" "run" "shadow.cljs.build-report" "app" "target/build-report.html"]
                            ["shell" "open" "target/build-report.html"]]
            "karma"        ["with-profile" "prod" "do"
                            ["shadow" "compile" "karma-test"]
                            ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]}

  :profiles {:dev  {:source-paths ["dev/cljs"]
                    :dependencies [[binaryage/devtools         "1.0.7"]
                                   [day8.re-frame/re-frame-10x "1.11.0"]]}
             :prod {:dependencies [[day8.re-frame/tracing-stubs "0.7.0"]]}}

  :prep-tasks [])
