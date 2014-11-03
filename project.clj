(defproject tofu-avoidance "0.0.1"
  :description "Simple game just avoid tofu"
  :url "https://github.com/mizyoukan/tofu-avoidance"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :min-lein-version "2.3.4"

  :source-paths ["src/clj" "src/cljs"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]]

  :plugins [[lein-cljsbuild "1.0.3"]]

  :hooks [leiningen.cljsbuild]

  :cljsbuild
  {:builds {:tofu-avoidance
            {:source-paths ["src/cljs"]
             :compiler
             {:output-to "dev-resources/public/js/tofu_avoidance.js"
              :optimizations :advanced
              :pretty-print false}}}})
