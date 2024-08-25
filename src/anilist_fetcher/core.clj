(ns anilist-fetcher.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import (java.time Instant))
  (:gen-class))

(def graphql-url "https://graphql.anilist.co")
(def output-file "anilist_data.json")
(def initial-rate-limit 75)
(def min-wait-time 30000) ; Minimum wait time after rate limit hit (30 seconds)
(def request-delay 1000) ; Delay between requests (1 second)

(def query "
query AnimeListQuery($page: Int, $perPage: Int) {
  Page(page: $page, perPage: $perPage) {
    media(type: ANIME) {
      type
      id
      title {
        romaji
        english
      }
      format
      episodes
      coverImage {
        extraLarge
      }
    }
  }
}
")

(def rate-limit-state (atom {:limit initial-rate-limit
                             :remaining initial-rate-limit
                             :reset 0}))

(defn update-rate-limit [response]
  (let [headers (:headers response)
        limit (Integer/parseInt (get headers "x-ratelimit-limit" (str initial-rate-limit)))
        remaining (Integer/parseInt (get headers "x-ratelimit-remaining" (str initial-rate-limit)))
        reset (Long/parseLong (get headers "x-ratelimit-reset" "0"))]
    (swap! rate-limit-state assoc
           :limit limit
           :remaining remaining
           :reset reset)))

(defn wait-for-rate-limit []
  (let [{:keys [remaining reset]} @rate-limit-state
        now (inst-ms (Instant/now))
        reset-ms (* reset 1000)]
    (when (<= remaining 1)  ; Leave a buffer of 1 request
      (let [wait-time-ms (max min-wait-time (- reset-ms now))]
        (println (str "Rate limit reached. Waiting for " (quot wait-time-ms 1000) " seconds."))
        (Thread/sleep wait-time-ms)))))

(defn fetch-page-with-retry [page per-page max-retries]
  (loop [retry-count 0]
    (wait-for-rate-limit)
    (Thread/sleep request-delay)
    (let [response (try
                     (http/post graphql-url
                                {:form-params {:query query
                                               :variables {:page page :perPage per-page}}
                                 :content-type :json
                                 :accept :json})
                     (catch Exception e
                       (if (and (= 429 (:status (ex-data e)))
                                (< retry-count max-retries))
                         :retry
                         (throw e))))]
      (if (= response :retry)
        (do
          (println (str "Rate limit hit. Retrying... (Attempt " (inc retry-count) " of " max-retries ")"))
          (Thread/sleep min-wait-time)
          (recur (inc retry-count)))
        (do
          (update-rate-limit response)
          (swap! rate-limit-state update :remaining dec)
          (-> response
              :body
              (json/parse-string true)
              (get-in [:data :Page :media])))))))

(defn create-ani-object [media]
  {:id (:id media)
   :titleRomanji (get-in media [:title :romaji])
   :titleEnglish (get-in media [:title :english])
   :coverImageUrl (get-in media [:coverImage :extraLarge])
   :format (:format media)
  })

(defn write-beautified-json [data]
  (with-open [writer (io/writer output-file)]
    (json/generate-stream data writer {:pretty true})))

(defn fetch-and-save-all-pages []
  (loop [page 1
         all-data []]
    (println "Fetching page" page)
    (let [media-page (fetch-page-with-retry page 50 3)
          ani-objects (map create-ani-object media-page)]
      (if (empty? media-page)
        (do
          (write-beautified-json all-data)
          (count all-data))
        (recur (inc page)
               (into all-data ani-objects))))))

(defn -main []
  (io/delete-file output-file true)
  (let [total-fetched (fetch-and-save-all-pages)]
    (println "AniList data has been saved to" output-file)
    (println "Total anime entries fetched:" total-fetched)))
