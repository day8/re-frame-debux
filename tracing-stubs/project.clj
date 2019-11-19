(defproject    day8.re-frame/tracing-stubs "lein-git-inject/version"
  :description "Macros for tracing functions"
  :url         "https://github.com/Day8/re-frame-debux"
  :license     {:name "Eclipse Public License"
                :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm         {:dir ".."}

  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]]

  :plugins      [[day8/lein-git-inject "0.0.2"]]

  :middleware   [leiningen.git-inject/middleware]

  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/CLOJARS_USERNAME
                                    :password      :env/CLOJARS_PASSWORD}]]

  :release-tasks [["deploy" "clojars"]])
  

