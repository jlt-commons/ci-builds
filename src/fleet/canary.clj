(ns fleet.canary
  "The canary workflow's decisions: which jolt versions to test and whether
  to bother (the plan job), and whether the fleet is broken and what to tell
  the single \"canary: jolt breaks the fleet\" issue (the report job).

  The decisions are pure functions of data. The GitHub reads and writes sit
  in gather, last-built-at and report!, which take a fleet.github client.

  Two versions matter: latest, jolt's newest release, and nightly, jolt's
  rolling vnightly prerelease. A release is immutable once published, so its
  row means what it says. The nightly needs two checks before its row can be
  trusted.

  FRESH. jolt's publish-nightly runs only after its downstream library gates,
  and it publishes by staging a draft, deleting the old release, then moving
  the tag. So a lagging library withholds the NEW artifact while the PREVIOUS
  vnightly stays up and installs perfectly. Without a freshness check a
  nightly row that goes green cannot tell \"main is healthy\" from \"main
  broke four days ago and we keep testing Tuesday's binary\", and a green row
  that cannot go red reads as coverage. So the row is skipped when the
  artifact is older than the limit (48 hours by default), and a skipped row
  says so instead of passing quietly.

  NOT A DUPLICATE. Fresh is necessary and not sufficient. jolt tags vnightly
  at whatever main was when the nightly job ran, and that is regularly the
  release tag's own commit, even when main has moved on since. A row built
  from the same commit as a release row is the same data point rendered
  twice. The commits are compared live, by tag, because a recorded SHA is a
  label that can be orphaned: after a force-push of main that moves the tags,
  `git rev-parse` still answers from a local object store for a commit
  upstream no longer has.

  TWO GATES keep a 15-minute tick cheap. The interval gate holds a scheduled
  tick back until that many hours have passed since a canary last BUILT
  something (interval 0 means no gate, and then no lookup is made). The
  change gate skips a run when this exact version set already passed. It is
  an actions/cache lookup in the workflow, because a task cannot do one, so
  plan works out the key and the interval decision, the cache step runs, and
  final-decision combines them. A manual dispatch ignores both gates."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def title "canary: jolt breaks the fleet")

(def dashboard "https://jlt-commons.github.io/fleet/")

(def ^:private jolt-repo "repos/jolt-lang/jolt")

;; ---------------------------------------------------------------------------
;; Pure: plan

(defn- epoch-ms [iso]
  (try (.toEpochMilli (java.time.Instant/parse iso)) (catch Exception _ nil)))

(defn- hours-between
  "Whole hours from one ISO instant to another, or nil when either does not
  parse."
  [from to]
  (let [a (epoch-ms from) b (epoch-ms to)]
    (when (and a b) (quot (- b a) 3600000))))

(defn parse-hours
  "A whole number of hours from an env value; blank or nil is dflt. Throws
  naming the variable when it is not a number."
  [text dflt var-name]
  (if (str/blank? text)
    dflt
    (or (parse-long (str/trim text))
        (throw (ex-info (str var-name " is not a whole number of hours") {})))))

(defn- short-sha [sha] (subs sha 0 (min 8 (count sha))))

(defn- nightly-row
  "Decide the automatic nightly row against the release rows already chosen.
  Returns {:nightly text :json map-or-nil}. The json note carries no age, so
  it is the same on every tick for one nightly and the status build sees no
  change between ticks."
  [{:keys [nightly-release release-shas versions max-age-hours now]}]
  (let [pub (:published-at nightly-release)
        sha (or (:sha nightly-release) "")]
    (cond
      (str/blank? pub)
      {:nightly "nightly SKIPPED: no vnightly release found"}

      ;; The release exists but its tag does not resolve (R30). A row with no
      ;; commit cannot be checked against the releases, so it is not run.
      (str/blank? sha)
      {:nightly "nightly SKIPPED: could not resolve the vnightly tag"}

      :else
      (let [age (or (hours-between pub now) 0)]
        (if (> age max-age-hours)
          {:nightly (str "nightly SKIPPED: published " pub ", " age "h old, limit " max-age-hours
                         "h. jolt's publish-nightly is gated on its downstream library suites, "
                         "so a lagging library withholds new builds while the old one stays installable")}
          (if-let [dupe (some #(when (= sha (get release-shas %)) %) versions)]
            {:nightly (str "nightly SKIPPED: vnightly is " (short-sha sha) ", the same commit as "
                           dupe ", so the row would rebuild what " dupe " already covers")}
            {:nightly (str "nightly published " pub ", " age "h old, at " (short-sha sha))
             :json    {:published_at pub :sha sha
                       :note (str "nightly published " pub " at " (short-sha sha))}}))))))

(defn- interval-decision
  [{:keys [event interval-hours last-built-at now]}]
  (cond
    (not= "schedule" event) {:proceed true :reason "manual run, gates skipped"}
    (zero? interval-hours)  {:proceed true :reason "no interval gate"}
    :else
    (let [age (when last-built-at (hours-between last-built-at now))]
      (if (and age (< age interval-hours))
        {:proceed false :reason (str "last run was " age "h ago, interval is " interval-hours "h")}
        {:proceed true :reason "due"}))))

(defn- versions-part
  "The version set as a cache-key fragment. A new release changes it. The
  nightly needs its published_at folded in, because the literal string
  \"nightly\" is identical from one day to the next: without it the first
  green would cache a key that every later run also computes, and the nightly
  row would never run again however far main moved."
  [versions nightly-pub]
  (str (str/join "-" (map #(str/replace % #"[\[\]\" ,]" "") versions))
       (when nightly-pub (str "-" (str/replace nightly-pub #"[^0-9]" "")))))

(defn- versions-key
  "The change-gate cache key, saved only by a green run."
  [versions nightly-pub]
  (str "canary-green-" (versions-part versions nightly-pub)))

(def default-retry-hours 6)

(defn tried-key
  "The retry-backoff cache key (R35), saved by a run that did not pass: the
  version set plus the current window, epoch hours div retry-hours. A known-bad
  version set is retried once per window, while a NEW version set has a new key
  and is tried on the very next tick. retry-hours below 1 counts as 1."
  [versions nightly-pub now retry-hours]
  (let [window (quot (quot (or (epoch-ms now) 0) 3600000) (max 1 retry-hours))]
    (str "canary-tried-" (versions-part versions nightly-pub) "-" window)))

(defn plan
  "What to test and whether to bother.

  Takes :requested (the manual input, blank for automatic), :event,
  :latest-release (version without v), :nightly-release ({:published-at :sha}
  or nil), :release-shas (version -> tag commit), :interval-hours,
  :max-age-hours, :last-built-at (ISO or nil) and :now (ISO).

  :retry-hours (default 6) sizes the retry-backoff window.

  Returns :versions (vector), :proceed (the interval decision, the change gate
  is final-decision's job), :reason, :key, :tried-key (the retry-backoff cache
  key), :nightly (text for the report),
  :nightly-json (the object status.json records, or nil) and :latest.

  A manual nightly still records which build it was (R23), skipping only the
  freshness and duplicate checks, so the status page can show it."
  [{:keys [requested latest-release nightly-release] :as in}]
  (when (str/blank? latest-release)
    (throw (ex-info "could not resolve jolt's latest release" {})))
  (let [{:keys [versions nightly nightly-json nightly-pub]}
        (if-not (str/blank? requested)
          (let [note (str "manual dispatch of " requested ", freshness gate not applied")
                nj   (when (= "nightly" requested)
                       (when (or (str/blank? (:published-at nightly-release))
                                 (str/blank? (:sha nightly-release)))
                         (throw (ex-info "could not resolve vnightly" {})))
                       {:published_at (:published-at nightly-release)
                        :sha          (:sha nightly-release)
                        :note         note})]
            {:versions [requested] :nightly note :nightly-json nj})
          (let [row (nightly-row (assoc in :versions [latest-release]))]
            {:versions      (cond-> [latest-release] (:json row) (conj "nightly"))
             :nightly       (:nightly row)
             :nightly-json  (:json row)
             :nightly-pub   (when (:json row) (:published_at (:json row)))}))]
    (merge {:versions versions :nightly nightly :nightly-json nightly-json
            :latest latest-release :key (versions-key versions nightly-pub)
            :tried-key (tried-key versions nightly-pub (:now in)
                                  (or (:retry-hours in) default-retry-hours))}
           (interval-decision in))))

(defn needs-last-built-at?
  "True when plan will read last-built-at: only a scheduled tick under an
  interval gate."
  [{:keys [event interval-hours]}]
  (and (= "schedule" event) (pos? interval-hours)))

(defn final-decision
  "Combine the interval decision (interval-ok, the string \"true\" or not)
  with the two cache gates (already-green and already-tried, the cache steps'
  cache-hit outputs, blank when the step did not run). A hit on the tried key
  means a non-passing run of this version set already happened in the current
  retry window (R35). {:proceed bool :log line}."
  [{:keys [interval-ok already-green already-tried reason key]}]
  (cond
    (not= "true" interval-ok)    {:proceed false :log (str "SKIPPING: " reason)}
    (= "true" already-green)     {:proceed false :log (str "SKIPPING: " key " already passed, so jolt has not moved")}
    (= "true" already-tried)     {:proceed false :log "SKIPPING: already tried in this window"}
    :else                        {:proceed true :log (str "RUNNING: " reason)}))

(defn outputs-text
  "plan's result as the lines the job writes to $GITHUB_OUTPUT. The keys are
  the ones the plan job exposes: versions, proceed, reason, key, tried_key,
  nightly, nightly_json, latest."
  [{:keys [versions proceed reason key tried-key nightly nightly-json latest]}]
  (str "latest=" latest "\n"
       "versions=" (json/generate-string versions) "\n"
       "nightly=" nightly "\n"
       "nightly_json=" (if nightly-json (json/generate-string nightly-json) "null") "\n"
       "key=" key "\n"
       "tried_key=" tried-key "\n"
       "proceed=" proceed "\n"
       "reason=" reason "\n"))

;; ---------------------------------------------------------------------------
;; Edges: GitHub reads for plan

(defn- missing? [e] (= 404 (:status (ex-data e))))

(defn- warn [& xs] (binding [*out* *err*] (println (str/join " " xs))))

(defn gather
  "The GitHub facts plan needs, read through the client. Latest is resolved
  on every event, a manual dispatch included, because the status page shows
  what latest is whatever was asked for (R14). The nightly is read for an
  automatic run and for a manual nightly. A missing vnightly release is not an
  error, since jolt could stop publishing nightlies and the release rows would
  still be worth running: it comes back as nil and plan skips the row."
  [gh requested]
  (let [get-json (:get-json gh)
        tag      (:tag_name (get-json (str jolt-repo "/releases/latest")))
        latest   (some-> tag (str/replace-first #"^v" ""))
        _        (when (str/blank? latest)
                   (throw (ex-info "could not resolve jolt's latest release" {})))
        nightly? (or (str/blank? requested) (= "nightly" requested))
        auto?    (str/blank? requested)
        nightly  (when nightly?
                   (let [pub (try (:published_at (get-json (str jolt-repo "/releases/tags/vnightly")))
                                  (catch Exception e (when-not (missing? e) (throw e))))
                         sha (when pub
                               (try (get-in (get-json (str jolt-repo "/git/refs/tags/vnightly")) [:object :sha])
                                    (catch Exception e (when-not (missing? e) (throw e)))))]
                     (when pub {:published-at pub :sha sha})))]
    (cond-> {:latest-release latest}
      nightly? (assoc :nightly-release nightly)
      auto?    (assoc :release-shas
                      (if (:sha nightly)
                        {latest (get-in (get-json (str jolt-repo "/git/refs/tags/v" latest)) [:object :sha])}
                        {})))))

(defn last-built-at
  "When a canary last BUILT something, or nil. A gated tick still finishes as
  a run whose conclusion is success (plan and status succeeded, the matrix
  skipped), so counting runs would reset the clock every tick and an interval
  above 1 would never come due. The tell is a `jolt <version>` job, which
  exists only when the matrix ran, and a gated tick lists that job as
  skipped, so a job counts only if it is not skipped (R28). Looks at the 12 newest completed runs. A
  failed lookup is skipped with a warning and, if nothing is found, reads as
  due: the safe direction is to run."
  [gh repo]
  (let [runs (try (:workflow_runs ((:get-json gh) (str "repos/" repo "/actions/workflows/canary.yml/runs?status=completed&per_page=12")))
                  (catch Exception e (warn "canary run list failed:" (ex-message e)) nil))]
    (some (fn [{:keys [id updated_at]}]
            (let [jobs (try (:jobs ((:get-json gh) (str "repos/" repo "/actions/runs/" id "/jobs?per_page=100")))
                            (catch Exception e (warn "job list failed for run" id (ex-message e)) nil))]
              (when (some #(and (str/starts-with? (str (:name %)) "jolt ")
                                (not= "skipped" (:conclusion %)))
                          jobs)
                updated_at)))
          runs)))

;; ---------------------------------------------------------------------------
;; Pure: the report

(def ^:private finding-result? #{"fail" "mismatch" "timed-out"})
(defn- finding? [p] (finding-result? (:result p)))

;; R36: a dispatch that never started says nothing about jolt. It is our App or
;; configuration, so the issue groups it with infra. The result value on the
;; status page stays dispatch-failed.
(def ^:private infra-result? #{"infra" "dispatch-failed"})

;; R40: a run that was cancelled, never started or waited on a person is
;; not-run with the conclusion as its detail. It tested nothing, so it is
;; neither a recovery nor a jolt finding: infra.
(defn- infra? [p]
  (or (infra-result? (:result p))
      (and (= "not-run" (:result p))
           (contains? #{"cancelled" "startup_failure" "action_required"} (:detail p)))))

(defn- cells
  "Sorted, distinct \"name (version)\" strings for projects whose result
  satisfies pred. label decorates the version part."
  [results pred label]
  (->> (for [r results, p (:projects r) :when (pred p)]
         (str (:name p) " (" (label (:requested r) p) ")"))
       distinct sort vec))

(defn verdict
  "{:failed [...] :infra [...]} from the merged results. fail, mismatch and
  timed-out are findings about jolt or the build. infra (jolt not installable,
  or a dependency not downloadable) and dispatch-failed (the run never
  started) are not findings about jolt, so they are reported separately, with
  each project's failed step (for a dispatch failure, the first line gh
  printed)."
  [results]
  {:failed (cells results finding? (fn [v _] v))
   :infra  (cells results infra?
                  (fn [v p] (str v (when (:failed_step p) (str ", failed step: " (:failed_step p))))))})

(defn- lines [& ls] (str/join "\n" ls))

(defn issue-body
  "What the report job should say, decided from the MERGED results of every
  matrix leg (R16), because needs.fleet.outputs.failed holds one leg's value.

  Takes :results (parsed results.json maps), :fleet-result (the fleet job's
  result), :versions and :nightly (the plan's texts), :run-url and
  :failed-jobs (names of failed jobs, a fallback for the unclassified case).

  Returns :title, :broken? (open or update the issue when true, close it when
  false), :infra? (the failures are all infra, R25) and :body. When not
  broken the body is the recovery comment.

  The fleet job failing with no project saying why (a leg crashed before
  uploading results) is still a failure and never a quiet green."
  [{:keys [results fleet-result versions nightly run-url failed-jobs]}]
  (let [{:keys [failed infra]} (verdict results)
        unclassified? (and (empty? failed) (empty? infra) (not= "success" fleet-result))
        broken?       (boolean (or (seq failed) (seq infra) unclassified?))
        infra?        (and (seq infra) (empty? failed))
        failed-text   (str/join ", " failed)
        infra-text    (str/join ", " infra)]
    {:title   title
     :broken? broken?
     :infra?  (boolean infra?)
     :body
     (cond
       (not broken?)
       (str "Recovered. The fleet builds against " versions ". " nightly ". [Run](" run-url
            ") Dashboard: " dashboard)

       ;; A failure caused by the environment (the install source, a package
       ;; registry, dispatch access) is not filed as a fact about jolt.
       infra?
       (lines (str "Infra problems, not findings about jolt: " infra-text)
              ""
              "A project either could not install jolt (check its `JOLT_REPO`,"
              "which should be unset unless deliberately aimed at a fork, since the"
              "version may not exist in the repository it installs from), could"
              "not download a dependency (a package registry problem, often Maven"
              "Central rate limiting while several projects build at once), or"
              "could not be dispatched (the jlt-commons-ci App lacks access to the"
              "repository, or GitHub refused the dispatch), or its run was cancelled before it"
              "answered. The failed step in parentheses above shows which."
              ""
              (str "**Versions tested:** " versions)
              ""
              (str "[Run](" run-url ") has the per-project table. Dashboard: " dashboard))

       :else
       (lines "The fleet does not build against every jolt version it is meant to."
              ""
              (str "**Versions tested:** " versions)
              (str "**Failing:** " (cond (seq failed-text) failed-text
                                         (seq failed-jobs) (str/join ", " failed-jobs)
                                         :else "see the run"))
              (str "**Nightly:** " (if (str/blank? nightly) "not resolved" nightly))
              ""
              (str "[Run](" run-url ") has a per-project table for each version.")
              (str "Dashboard: " dashboard)
              ""
              "This issue closes itself once a later canary passes. Reproduce one"
              "project by dispatching its own ci.yml with the `jolt-version` input."))}))

;; ---------------------------------------------------------------------------
;; Edge: the issue

(defn- open-canary-issue
  "The number of the open issue with exactly the canary title, or nil. The
  issues endpoint lists pull requests too, which are skipped."
  [gh repo]
  (some (fn [i] (when (and (= title (:title i)) (not (:pull_request i))) (:number i)))
        ((:get-paginated gh) (str "repos/" repo "/issues?state=open"))))

(defn- failed-job-names
  "Names of this run's failed jobs, or [] when the lookup fails: the names
  only decorate the issue."
  [gh repo run-id]
  (try (->> ((:get-json gh) (str "repos/" repo "/actions/runs/" run-id "/jobs?per_page=100"))
            :jobs (filter #(= "failure" (:conclusion %))) (mapv :name))
       (catch Exception e (warn "job lookup failed:" (ex-message e)) [])))

(defn report!
  "Open, update or close the single canary issue from the merged results.
  Green closes an open issue after a recovery comment, and with none open
  says nothing. Broken comments on an open issue or opens one. Returns
  :nothing, :closed, :opened or :commented.

  opts: :gh (a fleet.github client map), :repo (owner/name), :results,
  :fleet-result, :versions, :nightly, :run-url, :run-id."
  [{:keys [gh repo results fleet-result versions nightly run-url run-id]}]
  (let [existing (open-canary-issue gh repo)
        verdict  (verdict results)
        needs-jobs? (and (empty? (:failed verdict)) (empty? (:infra verdict))
                         (not= "success" fleet-result))
        {:keys [broken? body]}
        (issue-body {:results results :fleet-result fleet-result :versions versions
                     :nightly nightly :run-url run-url
                     :failed-jobs (if needs-jobs? (failed-job-names gh repo run-id) [])})
        comment! #((:post-json gh) (str "repos/" repo "/issues/" existing "/comments") {:body body})]
    (cond
      (and (not broken?) existing)
      (do (comment!)
          ((:patch-json gh) (str "repos/" repo "/issues/" existing) {:state "closed"})
          (println (str "closed #" existing))
          :closed)

      (not broken?)
      (do (println (str "green against " versions ", nothing to report")) :nothing)

      existing
      (do (comment!) (println (str "commented on #" existing)) :commented)

      :else
      (let [issue ((:post-json gh) (str "repos/" repo "/issues") {:title title :body body})]
        (println (str "opened #" (:number issue)))
        :opened))))
