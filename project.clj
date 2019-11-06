(defproject    day8.re-frame/tracing "see :git-version below https://github.com/arrdem/lein-git-version"
  :description "A tool for inspecting code execution for re-frame applications"
  :url         "https://github.com/day8/re-frame-debux"
  :license     {"Eclipse Public License"
                "http://www.eclipse.org/legal/epl-v10.html"}

  :git-version
  {:status-to-version
   (fn [{:keys [tag version branch ahead ahead? dirty?] :as git-status}]
     (if-not (string? tag)
       ;; If git-status is nil (i.e. IntelliJ reading project.clj) then return an empty version.
       "_"
       (if (and (not ahead?) (not dirty?))
         tag
         (let [[_ major minor patch suffix] (re-find #"v?(\d+)\.(\d+)\.(\d+)(-.+)?" tag)]
           (if (nil? major)
             ;; If tag is poorly formatted then return GIT-TAG-INVALID
             "GIT-TAG-INVALID"
             (let [patch' (try (Long/parseLong patch) (catch Throwable _ 0))
                   patch+ (inc patch')]
               (str major "." minor "." patch+ suffix "-" ahead "-SNAPSHOT")))))))}

  :dependencies [[org.clojure/clojure       "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library]]
                 [thheller/shadow-cljs      "2.8.69" :scope "provided"]
                 [clojure-future-spec       "1.9.0"]
                 [re-frame                  "0.10.9" :scope "provided"]]

  :min-lein-version "2.6.0"

  :plugins [[me.arrdem/lein-git-version "2.0.3"]
            [lein-shadow                "0.1.6"]
            [lein-shell                 "0.5.0"]]

  :profiles {:dev {:dependencies [[zprint          "0.5.1"]
                                  [eftest          "0.5.9"]
                                  [io.aviso/pretty "0.1.37"]
                                  [reloaded.repl   "0.2.4"]]
                   :resource-paths ["dev-resources"]}}

  :source-paths ["src"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]

  :clean-targets ^{:protect false}
  [:target-path
   "resources/public/js/out"
   "resources/public/js/main.js"]
 
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/CLOJARS_USERNAME
                                    :password      :env/CLOJARS_PASSWORD}]]

  :release-tasks [["deploy" "clojars"]]

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

  :aliases {"dev-auto"   ["shadow" "watch" "dev"]
            "karma-once" ["do"
                          ["shadow" "compile" "karma-test"]
                          ["shell" "karma" "start" "--single-run" "--reporters" "junit,dots"]]})
