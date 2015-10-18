(defproject kibu/rawat "0.1.6"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [prismatic/schema "1.0.1"]
                 [com.datomic/datomic-free "0.9.5206"]]
  :aliases {"deploy" ["do" "clean," "deploy" "clojars"]}
  :lein-release {:deploy-via :shell
                 :shell ["lein" "deploy"]}
  :profiles {:dev {:dependencies [[midje "1.7.0"]]
                   :plugins [[lein-midje "3.1.3"]
                             [com.cemerick/clojurescript.test "0.3.3"]]}})
