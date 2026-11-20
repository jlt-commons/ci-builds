(ns fleet.fanout
  "Build every jlt-commons project against one jolt version and report the
  whole fleet in a single table.

  This does NOT rebuild anything itself. Each project's own ci.yml already
  knows how to build it (raygui needs raylib natives, glitter-uikit needs
  AppKit and GTK4, glitter-gl needs a GL context under Xvfb, raylib-ios runs
  its pure scenes under jolt on Linux because its real target is a phone),
  and every one of them accepts a `jolt-version` dispatch input that overrides
  its pinned JOLT_VERSION for a single run. So this dispatches those and
  collects the answers. Reimplementing five build environments here would
  rot, and would test something other than what a contributor actually hits.

  Why it exists: jolt v0.8.0 reversed jolt.ffi/write's last two arguments
  (jolt-lang/jolt#802). Both spellings are plain integers, so a mismatch
  writes to the wrong address and reports nothing. Finding that out one
  repository at a time, days apart, is what this replaces.

  Run ids come from gh's own output. `gh workflow run` prints the URL of the
  run it created, so the id is READ from that. The guess this replaced was
  \"the newest workflow_dispatch run above a baseline recorded before
  dispatching\", a positive control that always returns a plausible id. It
  failed for real: canary run 33682325150 ran two fan-out legs at once, 0.8.0
  and 0.8.1, and BOTH claimed glitter run 33682354193, though gh had printed
  33682349678 to the 0.8.1 leg. The 0.8.1 row reported a result for a build
  of 0.8.0. Reading gh's output holds however many fan-outs overlap. Only an
  older gh that prints no URL falls back to the newest workflow_dispatch run,
  with a ::warning:: saying the attribution is a guess.

  Result classes, all per project:
  - pass: the run succeeded and a job installed the requested jolt (R4: ANY
    job counts, because glitter-gl deliberately keeps a 0.7.29 job).
  - mismatch: the run succeeded but no job's `jolt --version` is the
    requested one (nightly republished between resolve and install, or a
    project ignoring the input). Never reported as pass.
  - infra: a failed run whose cause is not jolt. Two causes. Either the
    install-jolt step failed (measured 2026-09-02: JOLT_REPO was left
    pointing at a fork that publishes only nightlies, so asking for 0.8.0
    gave curl 404 and two scheduled canaries called the fleet broken), or
    every failed job's failed step shows a dependency download failure
    (R21/R24, Maven Central 429s and missing artifacts). The failed step is
    recorded for both (R25).
  - fail: any other non-success conclusion. timed-out and dispatch-failed
    come from this loop, not from GitHub.
  - not-run: GitHub concluded `skipped` (R5).

  IO sits behind injected fns, so a test drives a whole fan-out with no
  network and no sleeping: gh (the fleet.github client map), sh, env, sleep
  and now."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [fleet.version :as version]))

(def org "jlt-commons")

(def ^:private jolt-repo "repos/jolt-lang/jolt")

(def ^:private poll-tries 150)
(def ^:private poll-interval-ms 20000)

;; ---------------------------------------------------------------------------
;; Pure: classification and rendering

(defn- failed-steps
  "Names of every failed step across the jobs, in job then step order."
  [jobs]
  (for [j jobs, s (when (sequential? (:steps j)) (:steps j)) :when (= "failure" (:conclusion s))] (str (:name s))))

(defn- deps-unavailable?
  "R24: every failed job's log was fetched and each shows a download failure
  in its failed step. A log we could not read is no evidence, so it counts
  against infra."
  [jobs logs]
  (let [failed (filter #(= "failure" (:conclusion %)) jobs)
        texts  (map #(get logs (:id %)) failed)]
    (boolean (and (seq failed)
                  (every? some? texts)
                  (version/download-failure? texts)))))

(defn classify
  "One finished run to {:result :installed :failed-step :cause :detail}.

  conclusion is GitHub's. jobs are the run's job maps ({:id :conclusion
  :steps [{:name :conclusion}]}). logs maps job id to log text, nil for a log
  that could not be fetched. :cause is :install or :deps for infra, else
  nil. :detail is the conclusion, kept for the table's FAIL (x) text."
  [{:keys [requested nightly-sha conclusion jobs logs]}]
  (let [base {:installed nil :failed-step nil :cause nil :detail conclusion}]
    (case conclusion
      "success"
      (let [{:keys [installed match?]}
            (version/pick-installed requested nightly-sha
                                    (keep #(get logs (:id %)) jobs))]
        (assoc base :result (if match? "pass" "mismatch") :installed installed))

      "skipped" (assoc base :result "not-run")

      "failure"
      (let [steps (failed-steps jobs)
            cause (cond
                    (some #(re-find #"(?i)install jolt" %) steps) :install
                    (deps-unavailable? jobs logs)                 :deps)]
        (assoc base
               :result (if cause "infra" "fail")
               :cause cause
               :failed-step (first steps)))

      (assoc base :result "fail"))))

(defn- joined [names] (str/join " " names))

(defn- icon [{:keys [result cause detail installed]}]
  (case result
    "pass"     "pass"
    "not-run"  "skipped"
    "mismatch" (str "**WRONG JOLT** (ran " (or installed "no version found") ")")
    "infra"    (if (= :deps cause)
                 "**could not download dependencies**"
                 "**could not install jolt**")
    (str "**FAIL** (" detail ")")))

(defn- split-names
  "Project names by outcome, in fleet order: {:failed :infra :nodeps
  :mismatch :noinstall}. failed holds mismatches too, as the table always
  did."
  [projects]
  (let [names (fn [pred] (mapv :name (filter pred projects)))]
    {:mismatch  (names #(= "mismatch" (:result %)))
     :failed    (names #(contains? #{"mismatch" "fail" "timed-out" "dispatch-failed"} (:result %)))
     :infra     (names #(= "infra" (:result %)))
     :nodeps    (names #(and (= "infra" (:result %)) (= :deps (:cause %))))
     :noinstall (names #(and (= "infra" (:result %)) (not= :deps (:cause %))))}))

(defn summary-md
  "The step summary: a row per project, then a line per kind of trouble."
  [{:keys [version projects]}]
  (let [{:keys [mismatch failed infra nodeps noinstall]} (split-names projects)
        lines
        (concat
         [(str "## jolt `" version "` across " org) "" "| project | result | run |" "|---|---|---|"]
         (for [pr projects]
           (str "| `" (:name pr) "` | " (icon pr) " | "
                (if (:run-url pr) (str "[run](" (:run-url pr) ")") "-") " |"))
         [""]
         (when (seq mismatch) [(str "**Ran on the wrong jolt:** " (joined mismatch))])
         (when (seq failed) [(str "**Broken by jolt `" version "`:** " (joined failed))])
         (when (seq noinstall)
           [(str "**Could not install jolt `" version "` for:** " (joined noinstall))
            ""
            "That is this fleet's own problem, not a fact about jolt. Usually"
            "the version does not exist in the repository being installed"
            "from: check each project's `JOLT_REPO`, which should be unset"
            "unless deliberately pointed at a fork."])
         (when (seq nodeps)
           [(str "**Could not download dependencies for:** " (joined nodeps))
            ""
            "That is a package-registry problem, often Maven Central rate"
            "limiting while several projects build at once, not a fact about jolt."])
         ;; Counted from the projects rather than written out, so the number
         ;; cannot go stale beside the list it counts.
         (when (and (empty? failed) (empty? infra))
           [(str "All " (count projects) " projects build against jolt `" version "`.")]))]
    (str (str/join "\n" lines) "\n")))

(defn results-json
  "The results.json map: the artifact the status build reads."
  [{:keys [requested nightly-sha finished-at run-url projects]}]
  {:requested   requested
   :nightly_sha (or nightly-sha "")
   :finished_at finished-at
   :run_url     run-url
   :projects    (mapv (fn [pr]
                        {:name        (:name pr)
                         :result      (:result pr)
                         :installed   (:installed pr)
                         :run_url     (:run-url pr)
                         :finished_at (:finished-at pr)
                         :failed_step (:failed-step pr)})
                      projects)})

;; ---------------------------------------------------------------------------
;; Edges

(defn default-sh
  "Run args with env added to the environment. Returns {:exit :out :err};
  never throws on a non-zero exit."
  [args env]
  (let [{:keys [exit out err]} (apply p/sh {:extra-env env} args)]
    {:exit exit :out out :err err}))

(defn- now-iso []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- log [& xs] (println (str/join " " xs)) (flush))

(defn- warn [& xs] (log (str "::warning::" (str/join " " xs))))

(defn- explain [e]
  (str (or (some-> (ex-data e) :status (->> (str "HTTP "))) "error") ": " (ex-message e)))

(defn resolve-version
  "The version to test: requested when given (a leading v is kept as is),
  else the tag of jolt's latest release without its v. The release list is
  public, but it goes through the client like every other call."
  [gh requested]
  (if (seq requested)
    requested
    (let [tag (some-> ((:get-json gh) (str jolt-repo "/releases/latest")) :tag_name)
          v   (some-> tag (str/replace-first #"^v" ""))]
      (when (str/blank? v)
        (throw (ex-info "could not resolve jolt's latest release" {})))
      v)))

(defn nightly-sha
  "The commit vnightly points at right now, or nil with a warning. An
  installed jolt whose -g<hex> does not prefix it is a different build."
  [gh]
  (let [sha (try (get-in ((:get-json gh) (str jolt-repo "/git/refs/tags/vnightly")) [:object :sha])
                 (catch Exception e (warn "vnightly lookup failed:" (explain e)) nil))]
    (when (str/blank? sha)
      (warn "could not resolve the vnightly sha, every nightly run will read as mismatch"))
    (when-not (str/blank? sha) sha)))

(defn- run-id-from
  "The run id in the URL gh printed, or nil."
  [text]
  (second (re-find #"/actions/runs/([0-9]+)" (or text ""))))

(defn- run-url [project id] (str "https://github.com/" org "/" project "/actions/runs/" id))

(defn- dispatch-all!
  "Dispatch ci.yml in each project. Returns {project {:run-id :need-id? :detail}}
  where :detail \"dispatch-failed\" marks a failed dispatch."
  [{:keys [sh token]} version projects]
  (log (str "::group::dispatch jolt " version))
  (let [res (reduce
             (fn [acc pr]
               (let [{:keys [exit out err]}
                     (sh ["gh" "workflow" "run" "ci.yml" "--repo" (str org "/" pr)
                          "-f" (str "jolt-version=" version)]
                         {"GH_TOKEN" token})]
                 (if (zero? exit)
                   (let [text (str out err)
                         id   (run-id-from text)]
                     (doseq [l (str/split-lines text)] (log (str "    " l)))
                     (if id
                       (do (log (str "  dispatched " pr " -> run " id))
                           (assoc acc pr {:run-id id}))
                       (do (warn "gh printed no run URL for" pr
                                 ", falling back to newest-run matching. That is unreliable when fan-outs overlap.")
                           (log (str "  dispatched " pr ", run id unknown"))
                           (assoc acc pr {:need-id? true}))))
                   (do (log (str "  FAILED to dispatch " pr))
                       (assoc acc pr {:detail "dispatch-failed"})))))
             {} projects)]
    (log "::endgroup::")
    res))

(defn- newest-dispatch-run-id
  "The newest workflow_dispatch run of ci.yml, or nil."
  [gh pr]
  (try (some-> ((:get-json gh) (str "repos/" org "/" pr
                                    "/actions/workflows/ci.yml/runs?event=workflow_dispatch&per_page=1"))
               :workflow_runs vec first :id str)
       (catch Exception _ nil)))

(defn- identify-fallback!
  "For projects whose id gh did not print: the newest workflow_dispatch run,
  waiting up to about 3.5 minutes. Returns the updated dispatch map."
  [{:keys [gh sleep]} dispatched]
  (let [need (vec (filter #(:need-id? (get dispatched %)) (keys dispatched)))]
    (if (empty? need)
      dispatched
      (do (log "::group::identify runs (fallback)")
          (sleep 10000)
          (let [found (loop [i 0 found {}]
                        (let [found (reduce (fn [f pr]
                                              (if (contains? f pr)
                                                f
                                                (if-let [id (newest-dispatch-run-id gh pr)]
                                                  (do (log (str "  " pr " -> run " id " (guessed)"))
                                                      (assoc f pr id))
                                                  f)))
                                            found need)]
                          (if (or (= (count found) (count need)) (>= i 39))
                            found
                            (do (sleep 5000) (recur (inc i) found)))))
                out   (reduce (fn [d pr]
                                (if-let [id (get found pr)]
                                  (assoc d pr {:run-id id})
                                  (do (log (str "  " pr ": no run appeared within the window"))
                                      (assoc d pr {:detail "no-run-appeared" :result "dispatch-failed"}))))
                              dispatched need)]
            (log "::endgroup::")
            out)))))

(defn- fetch-log
  "A job's log text, or nil with a warning."
  [gh pr job-id]
  (try ((:get-redirected-log gh) (str "repos/" org "/" pr "/actions/jobs/" job-id "/logs"))
       (catch Exception e
         (warn (str "could not fetch the log of " pr " job " job-id ": "
                    (subs (explain e) 0 (min 300 (count (explain e))))))
         nil)))

(defn- run-jobs [gh pr id]
  (try (vec (:jobs ((:get-json gh) (str "repos/" org "/" pr "/actions/runs/" id "/jobs?per_page=100"))))
       (catch Exception e
         (warn (str "could not list the jobs of " pr " run " id ": " (explain e)))
         [])))

(defn- finish
  "Read the finished run's jobs and the logs the verdict needs, then classify.
  Logs are fetched only here, for completed runs: the failed jobs' for a
  failure (install and download failures live there), every job's for a
  success (to read the `jolt --version` line)."
  [{:keys [gh now requested nightly-sha]} pr id conclusion]
  (let [jobs  (run-jobs gh pr id)
        want  (case conclusion
                "success" jobs
                "failure" (filter #(= "failure" (:conclusion %)) jobs)
                [])
        logs  (into {} (map (fn [j] [(:id j) (fetch-log gh pr (:id j))])) want)
        c     (classify {:requested requested :nightly-sha nightly-sha
                         :conclusion conclusion :jobs jobs :logs logs})]
    (assoc c :finished-at (now))))

(defn- poll!
  "Wait for every dispatched run to complete. Returns {project verdict}, polling in
  fleet order. A
  transport error while polling is just 'not yet'. Projects still running
  after the last try are timed-out."
  [{:keys [gh sleep] :as ctx} projects runs]
  (log "::group::wait for completion")
  (let [out (loop [i 0 pending (filterv runs projects) done {}]
              (if (or (empty? pending) (>= i poll-tries))
                done
                (let [step (reduce
                            (fn [{:keys [pending done]} pr]
                              (let [id   (:run-id (get runs pr))
                                    run  (try ((:get-json gh) (str "repos/" org "/" pr "/actions/runs/" id))
                                              (catch Exception _ nil))]
                                (if (= "completed" (:status run))
                                  (let [v (try (finish ctx pr id (:conclusion run))
                                               (catch Exception e
                                                 ;; One project's bad payload must not drop the others' results.
                                                 (warn (str "could not classify " pr " run " id ": " (ex-message e)))
                                                 {:result "fail" :detail "error" :installed nil
                                                  :failed-step nil :cause nil :finished-at ((:now ctx))}))]
                                    (log (str "  " pr ": " (:detail v)
                                              (when (:installed v) (str " (jolt " (:installed v) ")"))))
                                    {:pending pending :done (assoc done pr v)})
                                  {:pending (conj pending pr) :done done})))
                            {:pending [] :done done} pending)]
                  (when (seq (:pending step)) (sleep poll-interval-ms))
                  (recur (inc i) (:pending step) (:done step)))))]
    (log "::endgroup::")
    out))

(defn- append! [path text]
  (if (seq path)
    (spit path text :append true)
    (print text)))

(defn run!
  "Dispatch every project, wait, write the results and return an exit code
  (1 when anything failed or was infra, else 0).

  opts: :version (blank means jolt's latest release), :projects (names),
  :gh (fleet.github client map), :sh (fn [args env] -> {:exit :out :err}),
  :env (fn name -> string or nil), :sleep (fn ms), :now (fn -> ISO string).
  Reads GH_TOKEN, GITHUB_STEP_SUMMARY, GITHUB_OUTPUT, GITHUB_SERVER_URL,
  GITHUB_REPOSITORY, GITHUB_RUN_ID and RUNNER_TEMP from env. Writes
  results.json under RUNNER_TEMP/fanout-results, appends the summary, and
  writes failed, infra, artifact, results_dir and version to GITHUB_OUTPUT."
  [{:keys [version projects gh sh env sleep now]
    :or   {sh default-sh now now-iso sleep #(Thread/sleep (long %))}}]
  (let [requested (resolve-version gh version)
        sha       (when (= "nightly" requested) (nightly-sha gh))
        _         (when sha (log "vnightly is" sha))
        ctx       {:gh gh :sh sh :sleep sleep :now now :token (env "GH_TOKEN")
                   :requested requested :nightly-sha sha}
        dispatched (identify-fallback! ctx (dispatch-all! ctx requested projects))
        verdicts   (poll! ctx projects (into {} (filter (comp :run-id val)) dispatched))
        rows       (mapv (fn [pr]
                           (let [d   (get dispatched pr)
                                 id  (:run-id d)
                                 v   (get verdicts pr)
                                 url (when id (run-url pr id))]
                             (merge {:name pr :run-url url :installed nil :failed-step nil
                                     :cause nil :finished-at nil}
                                    (cond
                                      v   v
                                      (:detail d) {:result (or (:result d) (:detail d)) :detail (:detail d)}
                                      :else {:result "timed-out" :detail "timed-out"}))))
                         projects)
        {:keys [failed infra]} (split-names rows)
        dir        (str (or (env "RUNNER_TEMP") "/tmp") "/fanout-results")
        run-link   (str (env "GITHUB_SERVER_URL") "/" (env "GITHUB_REPOSITORY")
                        "/actions/runs/" (env "GITHUB_RUN_ID"))
        artifact   (str "results-" (str/replace requested "/" "-"))]
    (fs/create-dirs dir)
    (spit (str dir "/results.json")
          (json/generate-string
           (results-json {:requested requested :nightly-sha sha :finished-at (now)
                          :run-url run-link :projects rows})
           {:pretty true}))
    (append! (env "GITHUB_STEP_SUMMARY") (summary-md {:version requested :projects rows}))
    (append! (env "GITHUB_OUTPUT")
             (str "results_dir=" dir "\n"
                  "artifact=" artifact "\n"
                  "version=" requested "\n"
                  "failed=" (joined failed) "\n"
                  "infra=" (joined infra) "\n"))
    (if (and (empty? failed) (empty? infra))
      0
      (do (binding [*out* *err*]
            (println (str "failing: " (if (seq failed) (joined failed) "none")
                          "  infra: " (if (seq infra) (joined infra) "none"))))
          1))))
