(ns fleet.cli
  "Command-line entry points behind the fleet:* tasks. Each *-main takes its
  arguments (and, where it reads the environment, an env map) and returns an
  exit code; the task wrapper in deps.edn passes it to System/exit. Failures
  print one line to stderr, exit 1, and write no files. Usage errors exit 2."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [fleet.canary :as canary]
            [fleet.collect :as collect]
            [fleet.fanout :as fanout]
            [fleet.github :as github]
            [fleet.publish :as publish]
            [fleet.refresh :as refresh]
            [fleet.status :as status]))

(defn- err [& xs] (binding [*out* *err*] (println (str/join " " xs))))

(defn- usage-error [usage msg]
  (err (str "usage: " usage))
  (when msg (err msg))
  2)

(defn- guarded
  "Run f. An integer result is the exit code, anything else counts as 0; an
  exception prints one line and becomes exit 1."
  [who f]
  (try (let [r (f)] (if (integer? r) r 0))
       (catch Exception e
         (err (str who ":") (ex-message e))
         1)))

(defn- now-iso []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- parse-nightly
  "NIGHTLY_JSON / --nightly-json: the literal null, or a JSON object."
  [text]
  (let [v (try (json/parse-string text true)
               (catch Exception _ (throw (ex-info "nightly JSON is not valid JSON" {}))))]
    (when-not (or (nil? v) (map? v))
      (throw (ex-info "nightly JSON must be an object or null" {})))
    v))

(defn- load-fleet [env]
  (refresh/read-json-file (or (get env "FLEET_JSON") "fleet.json") false))

(defn- gh-of [deps] (or (:gh deps) (github/env-client)))

(defn- print-json [rows] (print (refresh/json-str rows)) (flush))

(defn own-pin-main
  "fleet:own-pin: print own-pin.json on stdout."
  ([args] (own-pin-main args (into {} (System/getenv)) {}))
  ([_args env deps]
   (guarded "own-pin" #(print-json (collect/own-pin (gh-of deps) (load-fleet env))))))

(defn uncovered-main
  "fleet:uncovered: print uncovered.json on stdout."
  ([args] (uncovered-main args (into {} (System/getenv)) {}))
  ([_args env deps]
   (guarded "uncovered" #(print-json (collect/uncovered (gh-of deps) (load-fleet env))))))

(def ^:private status-usage
  (str "status --prev-status F --prev-history F --own-pin F --uncovered F\n"
       "       --latest VERSION --nightly-json JSON|null --canary-run URL --now ISO --out-dir D [results.json ...]"))

(def ^:private status-flags
  {"--prev-status" :prev-status "--prev-history" :prev-history "--own-pin" :own-pin
   "--uncovered" :uncovered "--latest" :latest "--nightly-json" :nightly-json
   "--canary-run" :canary-run "--now" :now "--out-dir" :out-dir})

(defn- parse-status-args
  "{:opts m :results [paths]} or {:error msg}."
  [args]
  (loop [args (seq args) opts {} results []]
    (cond
      (nil? args) {:opts opts :results results}
      (= "--" (first args)) {:opts opts :results (into results (rest args))}
      (str/starts-with? (first args) "-")
      (if-let [k (status-flags (first args))]
        (if (next args)
          (recur (nnext args) (assoc opts k (second args)) results)
          {:error (str (first args) " needs a value")})
        {:error (str "unknown flag " (first args))})
      :else (recur (next args) opts (conj results (first args))))))

(defn status-main
  "fleet:status: the pure merge, with its own flags."
  [args]
  (let [{:keys [opts results error]} (parse-status-args args)
        missing (remove opts [:own-pin :uncovered :latest :nightly-json :canary-run :now :out-dir])]
    (cond
      error         (usage-error status-usage error)
      (seq missing) (usage-error status-usage (str "missing " (str/join ", " (map name missing))))
      :else
      (guarded "build-status"
               #(let [{:keys [prev-status prev-history own-pin uncovered latest nightly-json
                              canary-run now out-dir]} opts
                      read (fn [p optional?] (refresh/read-json-file p optional?))
                      built (status/build {:prev-status  (read prev-status true)
                                           :prev-history (read prev-history true)
                                           :own-pin      (read own-pin false)
                                           :uncovered    (read uncovered false)
                                           :latest       latest
                                           :nightly      (parse-nightly nightly-json)
                                           :canary-run   canary-run
                                           :now          now
                                           :results      (mapv (fn [f] (read f false)) results)})]
                  (refresh/write-outputs! out-dir built))))))

(def ^:private refresh-usage "refresh <out-dir> [results.json ...]   (env: LATEST NIGHTLY_JSON CANARY_RUN)")

(defn- require-env
  "The first missing name among names, or nil."
  [env names]
  (first (remove #(seq (get env %)) names)))

(defn- refresh-opts
  "The refresh! options for <out-dir> [results ...] under env. Throws on a
  bad NIGHTLY_JSON."
  [[out-dir & results] env deps]
  (let [fleet (load-fleet env)
        gh    (gh-of deps)]
    {:out-dir       out-dir
     :prev-dir      (get env "PREV_DIR")
     :latest        (or (not-empty (get env "LATEST"))
                        (fanout/resolve-version gh ""))
     :nightly       (parse-nightly (get env "NIGHTLY_JSON"))
     :canary-run    (get env "CANARY_RUN")
     :results-files (vec results)
     :own-pin       #(collect/own-pin gh fleet)
     :uncovered     #(collect/uncovered gh fleet)
     :now           (now-iso)}))

(defn refresh-main
  "fleet:refresh: collect, build and write <out-dir>/status.json and
  history.json. Env: NIGHTLY_JSON, CANARY_RUN (required), LATEST (default: jolt's latest release via the GitHub client), PREV_DIR,
  FLEET_JSON, GH_TOKEN."
  ([args] (refresh-main args (into {} (System/getenv)) {}))
  ([args env deps]
   (if-let [v (require-env env ["NIGHTLY_JSON" "CANARY_RUN"])]
     (do (err (str "refresh-status: " v " is required")) (usage-error refresh-usage nil))
     (if (empty? args)
       (usage-error refresh-usage nil)
       (guarded "refresh-status" #(refresh/refresh! (refresh-opts args env deps)))))))

(defn publish-main
  "fleet:publish: run in a checkout whose origin it can push to. Refreshes
  in-process on every attempt, then commits to origin's status branch."
  ([args] (publish-main args (into {} (System/getenv)) {}))
  ([args env deps]
   (if-let [v (require-env env ["NIGHTLY_JSON" "CANARY_RUN"])]
     (do (err (str "publish-status: " v " is required"))
         (usage-error "publish <out-dir> [results.json ...]   (env: LATEST NIGHTLY_JSON CANARY_RUN)" nil))
     (if (empty? args)
       (usage-error "publish <out-dir> [results.json ...]" nil)
       (guarded "publish-status"
                #(let [[out-dir & results] args
                       base (refresh-opts [out-dir] env deps)
                       res  (publish/publish!
                             {:repo-dir "." :out-dir out-dir :sh publish/sh
                              :rebuild (fn [{:keys [prev-dir out-dir]}]
                                         (refresh/refresh! (assoc base
                                                                  :out-dir out-dir
                                                                  :prev-dir prev-dir
                                                                  :results-files (vec results)
                                                                  :now (now-iso))))})]
                   (println (if (= :unchanged (:result res))
                              "status: unchanged"
                              (str "status: published (attempt " (:attempt res) ")")))))))))

(defn fanout-main
  "fleet:fanout [version]: dispatch every fleet project against a jolt
  version and wait. A blank or missing version means jolt's latest release.
  Exit 1 when anything failed or was infra. Env: GH_TOKEN, FLEET_JSON and
  the GITHUB_* variables fan-out writes its outputs to. deps may carry
  :gh, :sh, :sleep and :now fns."
  ([args] (fanout-main args (into {} (System/getenv)) {}))
  ([args env deps]
   (if (> (count args) 1)
     (usage-error "fanout [version]" nil)
     (guarded "fanout"
              #(fanout/run! (merge (select-keys deps [:sh :sleep :now])
                                   {:version  (first args)
                                    :projects (:projects (load-fleet env))
                                    :gh       (gh-of deps)
                                    :env      (fn [k] (get env k))}))))))

(defn- append-output!
  "Append text to the file GITHUB_OUTPUT names, or print it when unset."
  [env text]
  (if-let [path (not-empty (get env "GITHUB_OUTPUT"))]
    (spit path text :append true)
    (print text))
  (flush))

(defn- hours-or-default
  "canary/parse-hours, but a bad value prints a ::warning:: naming the
  variable and falls back to dflt (R29): a typo in a repo variable must not
  stop the plan, and with it the status job, on every event."
  [text dflt var-name]
  (try (canary/parse-hours text dflt var-name)
       (catch Exception e
         (println (str "::warning::" (ex-message e) ", using " dflt))
         dflt)))

(defn- plan!
  "The decide step: read GitHub, work out the plan, write the job outputs."
  [env deps]
  (let [gh        (gh-of deps)
        requested (or (get env "REQUESTED") "")
        event     (get env "EVENT")
        ;; Only a scheduled tick reads the interval at all.
        interval  (if (= "schedule" event)
                    (hours-or-default (get env "INTERVAL_HOURS") 0 "CANARY_INTERVAL_HOURS")
                    0)
        max-age   (hours-or-default (get env "NIGHTLY_MAX_AGE_HOURS") 48 "NIGHTLY_MAX_AGE_HOURS")
        repo      (get env "GITHUB_REPOSITORY")
        in        (merge {:requested      requested
                          :event          event
                          :interval-hours interval
                          :max-age-hours  max-age
                          :now            (now-iso)}
                         (canary/gather gh requested))
        in        (cond-> in
                    (canary/needs-last-built-at? in) (assoc :last-built-at (canary/last-built-at gh repo)))
        result    (canary/plan in)]
    (println (if (str/blank? requested)
               (str "testing " (json/generate-string (:versions result)))
               (str "manual dispatch, testing " requested " only")))
    (println (:nightly result))
    (append-output! env (canary/outputs-text result))))

(defn- final!
  "The final-decision step: both gates into the job's `proceed`."
  [env]
  (let [{:keys [proceed log]} (canary/final-decision {:interval-ok   (get env "INTERVAL_OK")
                                                      :already-green (get env "ALREADY_GREEN")
                                                      :reason        (get env "REASON")
                                                      :key           (get env "KEY")})]
    (append-output! env (str "proceed=" proceed "\n"))
    (println log)))

(defn plan-main
  "fleet:plan: the canary plan job's decide step. With --final, the step
  after the change-gate cache lookup. Env for the decide step: REQUESTED,
  EVENT, INTERVAL_HOURS, NIGHTLY_MAX_AGE_HOURS, GITHUB_REPOSITORY, GH_TOKEN,
  GITHUB_OUTPUT. Env for --final: INTERVAL_OK, ALREADY_GREEN, REASON, KEY."
  ([args] (plan-main args (into {} (System/getenv)) {}))
  ([args env deps]
   (case (vec args)
     []          (guarded "plan" #(plan! env deps))
     ["--final"] (guarded "plan" #(final! env))
     (usage-error "plan [--final]" nil))))

(defn- read-results
  "Every results.json under dir, parsed. A missing dir is no results."
  [dir]
  (if (fs/exists? dir)
    (mapv #(refresh/read-json-file (str %) false)
          (sort (map str (fs/glob dir "**/results.json"))))
    []))

(defn report-main
  "fleet:report [results-dir]: open, update or close the canary issue from the
  merged results.json files (default dir: results). Env: RESULT (the fleet
  job's result), VERSIONS, NIGHTLY, RUN_URL, GITHUB_REPOSITORY,
  GITHUB_RUN_ID, GH_TOKEN."
  ([args] (report-main args (into {} (System/getenv)) {}))
  ([args env deps]
   (if (> (count args) 1)
     (usage-error "report [results-dir]" nil)
     (if-let [v (require-env env ["GITHUB_REPOSITORY" "VERSIONS" "RUN_URL"])]
       (do (err (str "report: " v " is required")) (usage-error "report [results-dir]" nil))
       (guarded "report"
                #(canary/report! {:gh           (gh-of deps)
                                  :repo         (get env "GITHUB_REPOSITORY")
                                  :results      (read-results (or (first args) "results"))
                                  :fleet-result (get env "RESULT")
                                  :versions     (get env "VERSIONS")
                                  :nightly      (get env "NIGHTLY")
                                  :run-url      (get env "RUN_URL")
                                  :run-id       (get env "GITHUB_RUN_ID")}))))))
