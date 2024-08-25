(defproject anilist-fetcher "0.1.0-SNAPSHOT"
  :description "AniList GraphQL data fetcher"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [clj-http "3.12.3"]
                 [cheshire "5.10.1"]]
  :main ^:skip-aot anilist-fetcher.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
