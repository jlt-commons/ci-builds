(ns fleet.status
  "Pure merge: fan-out results, own-pin, uncovered, and the previous status
  and history in; the new status and history out. No IO."
  (:require [clojure.string :as str]))

(def ^:private valid-results
  #{"pass" "fail" "infra" "timed-out" "dispatch-failed" "mismatch" "not-run"})

(def ^:private columns [:pin :latest :nightly :adhoc])

(def ^:private history-cap 30)

(defn- strip-v [s]
  (if (string? s) (str/replace-first s #"^v" "") s))

(defn- not-run [] {:result "not-run"})

(defn- result->cell [r requested]
  {:result      (:result r)
   :version     (strip-v requested)
   :installed   (:installed r)
   :run_url     (:run_url r)
   :finished_at (:finished_at r)
   :failed_step (:failed_step r)})

(defn- column [requested latest]
  (cond
    (= "nightly" requested)      :nightly
    (= latest (strip-v requested)) :latest
    :else                        :adhoc))

(defn- fail! [msg data]
  (throw (ex-info msg data)))

(defn- validate!
  [{:keys [prev-status prev-history own-pin uncovered results]}]
  (when-not (and (sequential? own-pin)
                 (every? #(and (string? (:name %)) (or (nil? (:pin %)) (string? (:pin %)))) own-pin))
    (fail! "malformed own-pin" {}))
  (when-not (sequential? uncovered) (fail! "malformed uncovered" {}))
  (when-not (or (nil? prev-status) (map? prev-status)) (fail! "malformed prev status" {}))
  (when-not (or (nil? prev-history) (map? prev-history)) (fail! "malformed prev history" {}))
  (when-not (every? (fn [f]
                      (and (map? f)
                           (string? (:requested f))
                           (sequential? (:projects f))
                           (every? #(and (string? (:name %))
                                         (contains? valid-results (:result %)))
                                   (:projects f))))
                    results)
    (fail! "malformed results" {})))

(defn- bad? [project]
  (boolean (some #(not (#{"pass" "not-run"} (:result %))) (vals (:cells project)))))

(defn- add-entry
  "Prepend a cell to a project's column unless its run_url is already there.
  Keeps the newest 30."
  [hist {:keys [name col cell]}]
  (let [k (keyword name)
        existing (get-in hist [k col] [])]
    (if (and (some? (:run_url cell))
             (some #(= (:run_url cell) (:run_url %)) existing))
      hist
      (assoc-in hist [k col] (vec (take history-cap (cons cell existing)))))))

(defn build
  "Merge into {:status m :history m}. Keys of the input map: :prev-status,
  :prev-history (nil when absent), :own-pin, :uncovered, :latest, :nightly
  (a map, or nil to keep the previous one, R22), :canary-run, :now, :results
  (a seq of fan-out results maps). Throws ex-info on malformed input.

  An own-pin row marked :unreadable (R38) could not be read this time. The
  project keeps its previous pin cell and pin, and the outage adds no pin
  history entry, so a GitHub blip never shows as a not-run result. The same
  goes for an uncovered row marked :unreadable: it keeps its previous reason."
  [{:keys [prev-status prev-history own-pin uncovered latest nightly canary-run now results]
    :as in}]
  (validate! in)
  (let [latest        (strip-v latest)
        res           (vec (for [f results
                                 p (:projects f)]
                             {:name (:name p)
                              :col  (column (:requested f) latest)
                              :cell (result->cell p (:requested f))}))
        latest-stale? (and (some? prev-status)
                           (not= (get-in prev-status [:jolt :latest :version]) latest))
        nightly-stale? (and (some? nightly)
                            (not= (get-in prev-status [:jolt :nightly :sha]) (:sha nightly)))
        prev-cells    (into {} (map (juxt :name :cells)) (:projects prev-status))
        prev-pins     (into {} (map (juxt :name :pin)) (:projects prev-status))
        prev-reasons  (into {} (map (juxt :name :reason)) (:uncovered prev-status))
        carry-pin?    (fn [p] (and (:unreadable p) (contains? prev-cells (:name p))
                                   (get-in prev-cells [(:name p) :pin])))
        fresh         (reduce (fn [m {:keys [name col cell]}]
                                (if (= col :adhoc) m (assoc m [name col] cell)))
                              {} res)
        pick          (fn [n c stale?]
                        (or (get fresh [n c])
                            (if stale? (not-run) (or (get-in prev-cells [n c]) (not-run)))))
        projects      (vec (for [p own-pin]
                             {:name  (:name p)
                              :pin   (if (carry-pin? p) (get prev-pins (:name p)) (:pin p))
                              :cells {:pin     (or (carry-pin? p)
                                                   {:result      (:result p)
                                                    :version     (:pin p)
                                                    :installed   nil
                                                    :run_url     (:run_url p)
                                                    :finished_at (:finished_at p)
                                                    :failed_step (:failed_step p)})
                                      :latest  (pick (:name p) :latest latest-stale?)
                                      :nightly (pick (:name p) :nightly nightly-stale?)}}))
        sorted        (vec (sort-by (fn [p] [(if (bad? p) 0 1) (:name p)]) projects))
        prev-hist     (:projects prev-history)
        unreadable    (into #{} (comp (filter :unreadable) (map :name)) own-pin)
        pin-entries   (for [p projects
                            :let [pin-hist (get-in prev-hist [(keyword (:name p)) :pin])
                                  cell     (get-in p [:cells :pin])]
                            :when (and (not (contains? unreadable (:name p)))
                                       (or (not= (or (:run_url (first pin-hist)) "__none__")
                                                 (or (:run_url cell) "__none__"))
                                           (empty? pin-hist)))]
                        {:name (:name p) :col :pin :cell cell})
        hist          (reduce add-entry (or prev-hist {}) (concat res pin-entries))
        hist          (into {} (map (fn [[k v]]
                                      [k (merge (zipmap columns (repeat [])) v)]))
                            hist)]
    {:status  {:schema       1
               :generated_at now
               :canary_run   canary-run
               :jolt         {:latest  {:version latest}
                              :nightly (or nightly (get-in prev-status [:jolt :nightly]))}
               :projects     sorted
               :uncovered    (mapv (fn [u]
                                     {:name   (:name u)
                                      :reason (if (and (:unreadable u) (contains? prev-reasons (:name u)))
                                                (get prev-reasons (:name u))
                                                (:reason u))})
                                   uncovered)}
     :history {:schema 1 :projects hist}}))

(def heartbeat-hours
  "R39: a status whose content has not moved is still published once its
  previous generated_at is this old."
  6)

(defn- heartbeat-due?
  "True when old-status was generated heartbeat-hours or more before
  new-status. Unparseable timestamps are due: publishing is the safe side."
  [old-status new-status]
  (let [ms (fn [s] (try (.toEpochMilli (java.time.Instant/parse (:generated_at s)))
                        (catch Exception _ nil)))
        a (ms old-status)
        b (ms new-status)]
    (or (nil? a) (nil? b) (>= (- b a) (* heartbeat-hours 3600 1000)))))

(defn unchanged?
  "R12: two statuses are the same when they differ only in generated_at and
  canary_run, unless the old one is heartbeat-hours or more older than the new
  one (R39). The heartbeat keeps generated_at moving, about 4 commits a day,
  so the page's 12 hour staleness banner fires only when the canary has
  really stopped."
  [old-status new-status]
  (let [norm #(dissoc % :generated_at :canary_run)]
    (and (= (norm old-status) (norm new-status))
         (not (heartbeat-due? old-status new-status)))))
