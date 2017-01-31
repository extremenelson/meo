(ns iwaswhere-web.graph.query
  "this namespace manages interactions with the graph data structure, which
  holds all entries and their connections."
  (:require [ubergraph.core :as uc]
            [iwaswhere-web.fulltext-search :as ft]
            [clj-time.coerce :as ctc]
            [clj-time.core :as ct]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clj-time.format :as ctf]
            [clojure.pprint :as pp]
            [matthiasn.systems-toolbox.component :as st]))

(defn entries-filter-fn
  "Creates a filter function which ensures that all tags and mentions in the
   query are contained in the filtered entry or any of it's comments, and none
   of the not-tags. Also allows filtering per day."
  [q graph]
  (fn [entry]
    (let [local-fmt (ctf/with-zone (ctf/formatters :year-month-day)
                                   (ct/default-time-zone))
          entry-day (ctf/unparse local-fmt (ctc/from-long (:timestamp entry)))
          q-day (:date-string q)
          day-match? (= q-day entry-day)

          q-timestamp (:timestamp q)
          q-ts-match? (= q-timestamp (str (:timestamp entry)))

          q-tags (set (map s/lower-case (:tags q)))
          q-not-tags (set (map s/lower-case (:not-tags q)))
          q-mentions (set (map s/lower-case (:mentions q)))

          entry-tags (set (map s/lower-case (:tags entry)))
          entry-comments (map #(uc/attrs graph %) (:comments entry))
          entry-comments-tags (apply set/union (map :tags entry-comments))
          tags (set (map s/lower-case (set/union entry-tags entry-comments-tags)))

          entry-mentions (set (map s/lower-case (:mentions entry)))
          entry-comments-mentions (apply set/union (map :mentions
                                                        (:comments entry)))
          mentions (set (map s/lower-case
                             (set/union entry-mentions entry-comments-mentions)))

          story-match? (if-let [story (:story q)]
                         (or (= story (:linked-story entry))
                             (= story (:timestamp entry)))
                         true)

          opts (:opts q)
          opts-match? (cond
                        (contains? opts ":started")
                        (let [start-ts (:start (:task entry))]
                          (when start-ts
                            (> (st/now) start-ts)))
                        (contains? opts ":due")
                        (let [due-ts (:due (:task entry))]
                          (when due-ts
                            (> (st/now) due-ts)))
                        (contains? opts ":no-start")
                        (not (:start (:task entry)))
                        (contains? opts ":no-due")
                        (not (:due (:task entry)))
                        :else true)

          match? (and (set/subset? q-tags tags)
                      (empty? (set/intersection q-not-tags tags))
                      (or (empty? q-mentions)
                          (seq (set/intersection q-mentions mentions)))
                      (or day-match? (empty? q-day))
                      (or q-ts-match? (empty? q-timestamp))
                      story-match?
                      opts-match?)]
      match?)))

(defn compare-w-upvotes
  "Sort comparator which considers upvotes first, and, if those are equal, the
   timestamp second."
  [x y]
  (let [upvotes-x (get x :upvotes 0)
        upvotes-y (get y :upvotes 0)]
    (if-not (= upvotes-x upvotes-y)
      (clojure.lang.Util/compare upvotes-y upvotes-x)
      (if (pos? upvotes-x)                                  ; when entries have upvotes, sort oldest on top
        (clojure.lang.Util/compare (:timestamp x) (:timestamp y))
        (clojure.lang.Util/compare (:timestamp y) (:timestamp x))))))

(defn get-comments
  "Extract all comments for entry."
  [entry g ts]
  (let [edges (uc/find-edges g {:dest ts :relationship :COMMENT})
        comment-ids (->> (flatten edges)
                         (remove :mirror?)
                         (map :src)
                         (sort))]
    (merge entry {:comments (vec comment-ids)})))

(defn get-tags-mentions-matches
  "Extract matching timestamps for query."
  [g query]
  (let [mapper (fn [tag-type]
                 (fn [tag]
                   (let [q {:src {tag-type tag} :relationship :CONTAINS}
                         edges (uc/find-edges g q)]
                     (set (map :dest edges)))))
        t-matched (map (mapper :tag) (map s/lower-case (:tags query)))
        pt-matched (map (mapper :ptag) (map s/lower-case (:tags query)))
        m-matched (map (mapper :mention) (map s/lower-case (:mentions query)))]
    (apply set/union (concat t-matched pt-matched m-matched))))

(defn get-nodes-for-day
  "Extract matching timestamps for query."
  [g query]
  (let [dt (ctf/parse (ctf/formatters :year-month-day) (:date-string query))]
    (set (map :dest (uc/find-edges g {:src          {:type  :timeline/day
                                                     :year  (ct/year dt)
                                                     :month (ct/month dt)
                                                     :day   (ct/day dt)}
                                      :relationship :DATE})))))

(defn get-linked-entries
  "Extract all linked entries for entry, including their comments."
  [entry g n sort-by-upvotes?]
  (let [sort-fn (fn [ids]
                  (if sort-by-upvotes?
                    (sort compare-w-upvotes ids)
                    (sort ids)))
        linked (->> (flatten (uc/find-edges g {:src n :relationship :LINKED}))
                    (map :dest)
                    (sort-fn))]
    (merge entry {:linked-entries-list (vec linked)})))

(defn extract-sorted-entries
  "Extracts nodes and their properties in descending timestamp order by looking
   for node by mapping over the sorted set and extracting attributes for each
   node.
   Warns when node not in graph. (debugging, should never happen)"
  [state query]
  (let [sort-by-upvotes? (:sort-by-upvotes query)
        g (:graph state)
        mapper-fn (fn [n]
                    (if (uc/has-node? g n)
                      (-> (uc/attrs g n)
                          (get-comments g n)
                          (get-linked-entries g n sort-by-upvotes?))
                      (log/warn "extract-sorted-entries can't find node: " n)))
        sort-fn #(into (sorted-set-by (if (:sort-asc query) < >)) %)
        matched-ids (cond
                      ; full-text search
                      (:ft-search query)
                      (sort-fn (ft/search query))

                      ; set with the one timestamp in query
                      (:timestamp query) #{(Long/parseLong (:timestamp query))}

                      ; set with timestamps matching the day
                      (:date-string query) (sort-fn (get-nodes-for-day g query))

                      ; query is for specific story
                      (:story query)
                      (get-in state [:sorted-story-entries (:story query)])

                      ; query is for tasks
                      (seq (:opts query))
                      (get-in state [:sorted-tasks])

                      ; set with timestamps matching tags and mentions
                      (or (seq (:tags query)) (seq (:mentions query)))
                      (sort-fn (get-tags-mentions-matches g query))
                      ; set with all timestamps (leads to full scan at worst)
                      :else (:sorted-entries state))
        matched-entries (map mapper-fn matched-ids)
        parent-ids (filter identity (map :comment-for matched-entries))
        parents (map mapper-fn parent-ids)
        entries (flatten [matched-entries parents])]
    (if sort-by-upvotes?
      (sort compare-w-upvotes entries)
      entries)))

(defn extract-entries-by-ts
  "Find all entries for given timestamps set."
  [current-state entry-timestamps]
  (map (fn [n]
         (let [g (:graph current-state)]
           (if (uc/has-node? g n)
             (let [entry (uc/attrs g n)]
               (when (empty? entry) (log/warn "empty node:" entry))
               entry)
             (log/warn "extract-entries-by-ts can't find node: " n))))
       entry-timestamps))

(defn find-all-hashtags
  "Finds all hashtags used in entries by finding the edges that originate from
   the :hashtags node. Merges the tags in the :pvt-displayed key of the config
   files, as those are the private keys that should be available in the
   autosuggestions."
  [current-state]
  (let [g (:graph current-state)
        ltags (map #(-> % :dest :tag) (uc/find-edges g {:src :hashtags}))
        sorted-tags (->> ltags
                         (map (fn [lt]
                                (let [tag (:val (uc/attrs g {:tag lt}))
                                      cnt (count (uc/find-edges g {:src {:tag lt}}))]
                                  [tag cnt])))
                         (sort-by second)
                         reverse
                         (map first))]
    sorted-tags))

(defn find-all-pvt-hashtags
  "Finds all private hashtags. Private hashtags are either those used
   exclusively in entries marked private, or the tags in the config key
   :pvt-tags."
  [current-state]
  (let [cfg (:cfg current-state)
        g (:graph current-state)
        ltags (map #(-> % :dest :ptag) (uc/find-edges g {:src :pvt-hashtags}))
        tags (map #(:val (uc/attrs g {:ptag %})) ltags)]
    (set/union (set tags) (:pvt-tags cfg))))

(defn find-all-mentions
  "Finds all hashtags used in entries by finding the edges that originate from
   the :hashtags node."
  [current-state]
  (let [g (:graph current-state)
        lmentions (map #(-> % :dest :mention)
                       (uc/find-edges g {:src :mentions}))
        mentions (map #(:val (uc/attrs g {:mention %})) lmentions)]
    (set mentions)))

(defn find-all-stories
  "Finds all stories in graph and returns map with the id of the story
   (creation timestamp) as key and the story node itself as value."
  [current-state]
  (let [g (:graph current-state)
        story-ids (map :dest (uc/find-edges g {:src :stories}))
        stories (into {} (map (fn [id] [id (uc/attrs g id)]) story-ids))]
    stories))

(defn find-all-books
  "Finds all :book entries in graph and returns map with the id of the book
   (creation timestamp) as key and the book node itself as value."
  [current-state]
  (let [g (:graph current-state)
        book-ids (map :dest (uc/find-edges g {:src :books}))
        books (into {} (map (fn [id] [id (uc/attrs g id)]) book-ids))]
    books))

(defn find-all-consumption-types
  "Finds all consumption types used in entries by finding the edges that
   originate from the :consumption-types node."
  [current-state]
  (let [g (:graph current-state)]
    (set (map #(-> % :dest :name)
              (uc/find-edges g {:src :consumption-types})))))

(defn comments-linked-for-entry
  "Enrich entry with comments and linked entries."
  [graph sort-by-upvotes?]
  (fn [entry]
    (let [ts (:timestamp entry)]
      (-> entry
          (get-comments graph ts)
          (get-linked-entries graph ts sort-by-upvotes?)))))

(defn get-filtered-results
  "Retrieve items to show in UI, also deliver all hashtags for autocomplete and
   some basic stats."
  [current-state query]
  (let [n (:n query)
        g (:graph current-state)
        entry-mapper (fn [entry] [(:timestamp entry) entry])
        entries (take n (filter (entries-filter-fn query g)
                                (extract-sorted-entries current-state query)))
        comment-timestamps (set (apply concat (map :comments entries)))
        comments (map #(uc/attrs g %) comment-timestamps)]
    {:entries     (mapv :timestamp entries)
     :entries-map (into {} (concat (map entry-mapper entries)
                                   (map entry-mapper comments)))}))

(defn find-entry
  "Find single entry."
  [{:keys [current-state msg-payload]}]
  (let [g (:graph current-state)
        ts (:timestamp msg-payload)]
    (if (uc/has-node? g ts)
      (let [comments-linked-mapper (comments-linked-for-entry g false)
            entry (comments-linked-mapper (uc/attrs g ts))]
        {:emit-msg [:entry/found entry]})
      (log/warn "cannot find node: " ts))))

(defn run-query
  [current-state msg-meta]
  (fn [[query-id query]]
    (let [start-ts (System/nanoTime)
          res (get-filtered-results current-state query)
          ms (/ (- (System/nanoTime) start-ts) 1000000)
          dur {:duration-ms (pp/cl-format nil "~,3f ms" ms)}]
      (log/info "Query" (:sente-uid msg-meta) query-id "took" (:duration-ms dur))
      [query-id res])))

(defn query-fn
  "Runs all queries in request, sends back to client, with all entry maps
   for the individual queries merged into one."
  [{:keys [current-state msg-payload msg-meta]}]
  (let [start-ts (System/nanoTime)
        res-mapper (run-query current-state msg-meta)
        res (mapv res-mapper (:queries msg-payload))
        res2 (reduce (fn [acc [k v]]
                       (-> acc
                           (update-in [:entries-map] merge (:entries-map v))
                           (assoc-in [:entries k] (:entries v))))
                     {:entries-map {} :entries {}}
                     res)
        ms (/ (- (System/nanoTime) start-ts) 1000000)
        dur {:duration-ms (pp/cl-format nil "~,3f ms" ms)}]
    (log/info "Query" (:sente-uid msg-meta) "took" (:duration-ms dur))
    {:emit-msg [:state/new (merge res2 dur)]}))
