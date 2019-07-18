(defproject day8.re-frame/tracing-stubs "0.5.1"
  :description "Macros for tracing functions"
  :url "https://github.com/Day8/re-frame-debux"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url "https://clojars.org/repo"
                                    :username :env/CLOJARS_USERNAME
                                    :password :env/CLOJARS_PASSWORD}]]
  
  :scm {:dir ".."}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]])
