(ns fleet.refresh
  "Collect, build and write the status files: the one sequence the canary
  and the ad-hoc fan-out publish both run (R18). Pure of CLI concerns; see
  fleet.cli for flags and environment."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [fleet.status :as status]))

(defn read-json-file
  "Parse a JSON file. A top-level array comes back as a vector. nil for a
  missing file when optional?; otherwise throws ex-info naming the file."
  [path optional?]
  (cond
    (and optional? (or (nil? path) (not (fs/exists? path)))) nil
    (not (and path (fs/exists? path))) (throw (ex-info (str "cannot read " path) {:path path}))
    :else
    (try (let [v (json/parse-string (slurp path) true)]
           (if (sequential? v) (vec v) v))
         (catch Exception e
           (throw (ex-info (str "cannot parse " path ": " (ex-message e)) {:path path}))))))

(defn- canonical
  "Maps become sorted maps all the way down, so the same data always
  serialises to the same bytes (publish compares history.json byte for byte)."
  [x]
  (cond
    (map? x)        (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) x)
    (sequential? x) (mapv canonical x)
    :else           x))

(defn json-str
  "Deterministic, pretty JSON with a trailing newline."
  [x]
  (str (json/generate-string (canonical x) {:pretty true}) "\n"))

(defn write-outputs!
  "Write {:status m :history m} as status.json and history.json under
  out-dir, creating it. Call only once the build has succeeded."
  [out-dir {:keys [status history]}]
  (fs/create-dirs out-dir)
  (spit (str out-dir "/status.json") (json-str status))
  (spit (str out-dir "/history.json") (json-str history)))

(defn refresh!
  "Collect, merge and write status.json and history.json into :out-dir.

  opts: :out-dir, :prev-dir (holds the previous files; nil or empty is fine),
  :latest, :nightly (a map, or nil to keep the previous cell), :canary-run,
  :results-files (paths), :own-pin and :uncovered (thunks returning the
  collectors' rows) and :now (an ISO string). Nothing is written unless the
  whole build succeeds."
  [{:keys [out-dir prev-dir latest nightly canary-run results-files own-pin uncovered now]}]
  (let [prev    (fn [f] (read-json-file (when prev-dir (str prev-dir "/" f)) true))
        results (mapv #(read-json-file % false) results-files)
        built   (status/build {:prev-status  (prev "status.json")
                               :prev-history (prev "history.json")
                               :own-pin      (own-pin)
                               :uncovered    (uncovered)
                               :latest       latest
                               :nightly      nightly
                               :canary-run   canary-run
                               :now          now
                               :results      results})]
    (write-outputs! out-dir built)
    built))
