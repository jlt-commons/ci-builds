(ns fleet.canary-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.canary :as canary]))

;; ---------------------------------------------------------------------------
;; plan

(def ^:private now "2026-11-20T12:00:00Z")

(def ^:private fresh-nightly
  {:published-at "2026-11-19T21:30:15Z" :sha "dd3c4a4b0000000000000000000000000000aaaa"})

(defn- plan [& {:as over}]
  (canary/plan (merge {:requested "" :event "schedule" :latest-release "0.8.27"
                       :nightly-release nil :release-shas {"0.8.27" "9b768395ffff"}
                       :interval-hours 0 :max-age-hours 48 :last-built-at nil :now now}
                      over)))

(deftest new-release-without-a-nightly-tests-latest-only
  (let [p (plan)]
    (is (= ["0.8.27"] (:versions p)))
    (is (= "0.8.27" (:latest p)))
    (is (= "nightly SKIPPED: no vnightly release found" (:nightly p)))
    (is (nil? (:nightly-json p)))
    (is (= "canary-green-0.8.27" (:key p)))
    (is (true? (:proceed p)))
    (is (= "no interval gate" (:reason p)))))

(deftest fresh-nightly-joins-the-set-and-its-published-at-joins-the-key
  (let [p (plan :nightly-release fresh-nightly)]
    (is (= ["0.8.27" "nightly"] (:versions p)))
    (is (= "canary-green-0.8.27-nightly-20261119213015" (:key p)))
    (is (= "nightly published 2026-11-19T21:30:15Z, 14h old, at dd3c4a4b" (:nightly p)))
    (testing "the note that reaches status.json has no age"
      (is (= {:published_at "2026-11-19T21:30:15Z"
              :sha          (:sha fresh-nightly)
              :note         "nightly published 2026-11-19T21:30:15Z at dd3c4a4b"}
             (:nightly-json p))))))

(deftest stable-nightly-note-does-not-change-from-tick-to-tick
  (is (= (:nightly-json (plan :nightly-release fresh-nightly :now "2026-11-20T01:00:00Z"))
         (:nightly-json (plan :nightly-release fresh-nightly :now "2026-11-20T20:59:00Z")))))

(deftest a-newer-nightly-changes-the-key
  (is (not= (:key (plan :nightly-release fresh-nightly))
            (:key (plan :nightly-release (assoc fresh-nightly :published-at "2026-11-20T03:00:00Z"))))))

(deftest stale-nightly-is-skipped-with-a-reason
  (let [p (plan :nightly-release {:published-at "2026-11-17T00:00:00Z" :sha "aaaaaaaabbbb"})]
    (is (= ["0.8.27"] (:versions p)))
    (is (nil? (:nightly-json p)))
    (is (= "canary-green-0.8.27" (:key p)))
    (is (str/starts-with? (:nightly p)
                          "nightly SKIPPED: published 2026-11-17T00:00:00Z, 84h old, limit 48h. "))))

(deftest nightly-at-exactly-the-limit-still-runs
  (is (= ["0.8.27" "nightly"]
         (:versions (plan :nightly-release {:published-at "2026-11-18T12:00:00Z" :sha "aaaaaaaabbbb"})))))

(deftest nightly-on-the-release-commit-is-skipped-as-redundant
  (let [p (plan :nightly-release {:published-at "2026-11-19T21:30:15Z" :sha "9b768395ffff"})]
    (is (= ["0.8.27"] (:versions p)))
    (is (nil? (:nightly-json p)))
    (is (= (str "nightly SKIPPED: vnightly is 9b768395, the same commit as 0.8.27, "
                "so the row would rebuild what 0.8.27 already covers")
           (:nightly p)))))

(deftest manual-version-tests-only-that-version-and-skips-the-gates
  (let [p (plan :requested "0.8.20" :event "workflow_dispatch" :interval-hours 6
                :last-built-at "2026-11-20T11:00:00Z" :nightly-release fresh-nightly)]
    (is (= ["0.8.20"] (:versions p)))
    (is (= "manual dispatch of 0.8.20, freshness gate not applied" (:nightly p)))
    (is (nil? (:nightly-json p)))
    (is (= "0.8.27" (:latest p)))
    (is (= "canary-green-0.8.20" (:key p)))
    (is (true? (:proceed p)))
    (is (= "manual run, gates skipped" (:reason p)))))

(deftest manual-nightly-resolves-the-build-and-skips-freshness
  (let [stale {:published-at "2026-11-10T00:00:00Z" :sha "9b768395ffff"}
        p     (plan :requested "nightly" :event "workflow_dispatch" :nightly-release stale)]
    (is (= ["nightly"] (:versions p)))
    (is (= {:published_at "2026-11-10T00:00:00Z" :sha "9b768395ffff"
            :note         "manual dispatch of nightly, freshness gate not applied"}
           (:nightly-json p)))
    (is (= "canary-green-nightly" (:key p)))))

(deftest manual-nightly-that-cannot-be-resolved-throws
  (doseq [nr [nil {:published-at "2026-11-10T00:00:00Z"} {:sha "abc"}]]
    (is (= "could not resolve vnightly"
           (ex-message (try (plan :requested "nightly" :event "workflow_dispatch" :nightly-release nr)
                            nil (catch Exception e e)))))))

(deftest missing-latest-release-throws
  (is (= "could not resolve jolt's latest release"
         (ex-message (try (plan :latest-release "") nil (catch Exception e e))))))

(deftest interval-gate-holds-a-scheduled-tick-back
  (let [p (plan :interval-hours 6 :last-built-at "2026-11-20T09:30:00Z" :nightly-release fresh-nightly)]
    (is (false? (:proceed p)))
    (is (= "last run was 2h ago, interval is 6h" (:reason p)))
    (testing "the version set and key are still worked out for the status job"
      (is (= ["0.8.27" "nightly"] (:versions p)))
      (is (= "0.8.27" (:latest p)))
      (is (some? (:nightly-json p))))))

(deftest interval-gate-opens-when-due
  (is (= "due" (:reason (plan :interval-hours 6 :last-built-at "2026-11-20T05:59:00Z"))))
  (is (true? (:proceed (plan :interval-hours 6 :last-built-at "2026-11-20T05:59:00Z")))))

(deftest interval-gate-with-no-earlier-build-is-due
  (let [p (plan :interval-hours 6 :last-built-at nil)]
    (is (true? (:proceed p)))
    (is (= "due" (:reason p)))))

(deftest interval-zero-never-needs-a-lookup
  (is (= "no interval gate" (:reason (plan :interval-hours 0 :last-built-at "2026-11-20T11:59:00Z")))))

(deftest interval-only-applies-to-schedule
  (is (= "manual run, gates skipped"
         (:reason (plan :event "workflow_dispatch" :interval-hours 6 :last-built-at "2026-11-20T11:59:00Z")))))

(deftest needs-last-built-at-only-for-a-scheduled-tick-with-an-interval
  (is (true? (canary/needs-last-built-at? {:event "schedule" :interval-hours 6})))
  (is (false? (canary/needs-last-built-at? {:event "schedule" :interval-hours 0})))
  (is (false? (canary/needs-last-built-at? {:event "workflow_dispatch" :interval-hours 6}))))

(deftest parse-hours-reads-blank-as-the-default-and-rejects-junk
  (is (= 48 (canary/parse-hours nil 48 "X")))
  (is (= 48 (canary/parse-hours "" 48 "X")))
  (is (= 6 (canary/parse-hours "6" 48 "X")))
  (is (= "X is not a whole number of hours: soon"
         (ex-message (try (canary/parse-hours "soon" 48 "X") nil (catch Exception e e))))))

;; ---------------------------------------------------------------------------
;; the change gate

(deftest final-decision-combines-both-gates
  (testing "interval says no"
    (is (= {:proceed false :log "SKIPPING: last run was 2h ago, interval is 6h"}
           (canary/final-decision {:interval-ok "false" :already-green "" :reason "last run was 2h ago, interval is 6h" :key "k"}))))
  (testing "cache hit"
    (is (= {:proceed false :log "SKIPPING: canary-green-0.8.27 already passed, so jolt has not moved"}
           (canary/final-decision {:interval-ok "true" :already-green "true" :reason "due" :key "canary-green-0.8.27"}))))
  (testing "cache miss"
    (is (= {:proceed true :log "RUNNING: due"}
           (canary/final-decision {:interval-ok "true" :already-green "false" :reason "due" :key "k"}))))
  (testing "no cache step ran (manual run)"
    (is (= {:proceed true :log "RUNNING: manual run, gates skipped"}
           (canary/final-decision {:interval-ok "true" :already-green "" :reason "manual run, gates skipped" :key "k"})))))

;; ---------------------------------------------------------------------------
;; last-built-at

(defn- stub-gh
  "A client whose :get-json answers from a map of path -> value or Exception."
  [answers seen]
  {:get-json (fn [path]
               (swap! seen conj path)
               (let [a (get answers path ::none)]
                 (cond (= ::none a) (throw (ex-info "nope" {:status 404 :path path}))
                       (instance? Exception a) (throw a)
                       :else a)))})

(def ^:private runs-path
  "repos/o/ci/actions/workflows/canary.yml/runs?status=completed&per_page=12")

(deftest last-built-at-skips-gated-ticks-and-takes-the-first-run-with-a-jolt-job
  (let [seen (atom [])
        gh   (stub-gh {runs-path {:workflow_runs [{:id 3 :updated_at "2026-11-20T11:00:00Z"}
                                                  {:id 2 :updated_at "2026-11-20T08:00:00Z"}
                                                  {:id 1 :updated_at "2026-11-20T05:00:00Z"}]}
                       "repos/o/ci/actions/runs/3/jobs?per_page=100" {:jobs [{:name "plan"} {:name "status"}]}
                       "repos/o/ci/actions/runs/2/jobs?per_page=100" {:jobs [{:name "plan"} {:name "jolt 0.8.27 / fan-out"}]}
                       "repos/o/ci/actions/runs/1/jobs?per_page=100" {:jobs [{:name "jolt 0.8.26"}]}}
                      seen)]
    (is (= "2026-11-20T08:00:00Z" (canary/last-built-at gh "o/ci")))
    (is (= [runs-path "repos/o/ci/actions/runs/3/jobs?per_page=100" "repos/o/ci/actions/runs/2/jobs?per_page=100"]
           @seen))))

(deftest last-built-at-is-nil-when-no-run-built-anything
  (let [gh (stub-gh {runs-path {:workflow_runs [{:id 3 :updated_at "x"}]}
                     "repos/o/ci/actions/runs/3/jobs?per_page=100" {:jobs [{:name "plan"}]}}
                    (atom []))]
    (is (nil? (canary/last-built-at gh "o/ci")))))

(deftest last-built-at-ignores-a-job-lookup-that-fails
  (let [gh (stub-gh {runs-path {:workflow_runs [{:id 3 :updated_at "A"} {:id 2 :updated_at "B"}]}
                     "repos/o/ci/actions/runs/3/jobs?per_page=100" (ex-info "502" {:status 502})
                     "repos/o/ci/actions/runs/2/jobs?per_page=100" {:jobs [{:name "jolt 1"}]}}
                    (atom []))]
    (is (= "B" (canary/last-built-at gh "o/ci")))))

(deftest last-built-at-is-nil-when-the-run-list-fails
  (is (nil? (canary/last-built-at (stub-gh {runs-path (ex-info "403" {:status 403})} (atom [])) "o/ci"))))

;; ---------------------------------------------------------------------------
;; gather

(deftest gather-reads-latest-always-and-the-nightly-only-when-asked-for
  (let [seen (atom [])
        base {"repos/jolt-lang/jolt/releases/latest" {:tag_name "v0.8.27"}
              "repos/jolt-lang/jolt/releases/tags/vnightly" {:published_at "2026-11-19T21:30:15Z"}
              "repos/jolt-lang/jolt/git/refs/tags/vnightly" {:object {:sha "dd3c4a4b00"}}
              "repos/jolt-lang/jolt/git/refs/tags/v0.8.27" {:object {:sha "9b768395ff"}}}]
    (testing "automatic: everything"
      (is (= {:latest-release "0.8.27"
              :nightly-release {:published-at "2026-11-19T21:30:15Z" :sha "dd3c4a4b00"}
              :release-shas {"0.8.27" "9b768395ff"}}
             (canary/gather (stub-gh base seen) ""))))
    (testing "manual version: only latest"
      (reset! seen [])
      (is (= {:latest-release "0.8.27"} (canary/gather (stub-gh base seen) "0.8.20")))
      (is (= ["repos/jolt-lang/jolt/releases/latest"] @seen)))
    (testing "manual nightly: latest and the nightly, no release sha"
      (is (= {:latest-release "0.8.27"
              :nightly-release {:published-at "2026-11-19T21:30:15Z" :sha "dd3c4a4b00"}}
             (canary/gather (stub-gh base (atom [])) "nightly"))))
    (testing "no vnightly release at all is a skipped row, not an error"
      (is (= {:latest-release "0.8.27" :nightly-release nil :release-shas {}}
             (canary/gather (stub-gh (dissoc base "repos/jolt-lang/jolt/releases/tags/vnightly") (atom [])) ""))))
    (testing "any other failure is an error"
      (let [e (try (canary/gather (stub-gh (assoc base "repos/jolt-lang/jolt/releases/tags/vnightly"
                                                  (ex-info "boom" {:status 502})) (atom [])) "")
                   nil (catch Exception e e))]
        (is (= 502 (:status (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; issue-body

(defn- res [requested & projects]
  {:requested requested
   :projects (mapv (fn [[n r step]] (cond-> {:name n :result r} step (assoc :failed_step step))) projects)})

(defn- ib [& {:as over}]
  (canary/issue-body (merge {:results [(res "0.8.27" ["a" "pass"])] :fleet-result "success"
                             :versions "[\"0.8.27\"]" :nightly "nightly SKIPPED: stale"
                             :run-url "https://x/run/9" :failed-jobs []}
                            over)))

(deftest all-green-is-recovered
  (let [r (ib)]
    (is (false? (:broken? r)))
    (is (false? (:infra? r)))
    (is (= "canary: jolt breaks the fleet" (:title r)))
    (is (= (str "Recovered. The fleet builds against [\"0.8.27\"]. nightly SKIPPED: stale. "
                "[Run](https://x/run/9) Dashboard: https://jlt-commons.github.io/fleet/")
           (:body r)))))

(deftest not-run-and-pass-do-not-break
  (is (false? (:broken? (ib :results [(res "0.8.27" ["a" "pass"] ["b" "not-run"])])))))

(deftest a-failing-project-breaks-and-names-itself-and-its-version
  (let [r (ib :results [(res "0.8.27" ["glitter" "fail"] ["ebb" "pass"])
                        (res "nightly" ["glitter" "fail"] ["raylib-ios" "timed-out"])])]
    (is (true? (:broken? r)))
    (is (false? (:infra? r)))
    (is (str/includes? (:body r) "**Failing:** glitter (0.8.27), glitter (nightly), raylib-ios (nightly)\n"))
    (is (str/includes? (:body r) "**Versions tested:** [\"0.8.27\"]\n"))
    (is (str/includes? (:body r) "**Nightly:** nightly SKIPPED: stale\n"))
    (is (str/includes? (:body r) "[Run](https://x/run/9) has a per-project table for each version.\nDashboard: https://jlt-commons.github.io/fleet/\n"))
    (is (str/ends-with? (:body r) "dispatching its own ci.yml with the `jolt-version` input."))))

(deftest mismatch-and-dispatch-failed-break-too
  (doseq [bad ["mismatch" "dispatch-failed" "timed-out" "fail"]]
    (testing bad
      (let [r (ib :results [(res "nightly" ["glitter" bad])])]
        (is (true? (:broken? r)))
        (is (str/includes? (:body r) "**Failing:** glitter (nightly)"))))))

(deftest infra-alone-uses-the-infra-wording-for-both-causes
  (let [r (ib :results [(res "0.8.27" ["glitter" "infra" "Install jolt"] ["raygui-jlt" "infra" "Run clojure -M:test"] ["ebb" "infra"])])]
    (is (true? (:broken? r)))
    (is (true? (:infra? r)))
    (is (str/starts-with? (:body r) (str "Infra problems, not findings about jolt: "
                                         "ebb (0.8.27), glitter (0.8.27, failed step: Install jolt), "
                                         "raygui-jlt (0.8.27, failed step: Run clojure -M:test)\n")))
    (is (str/includes? (:body r) "could not install jolt"))
    (is (str/includes? (:body r) "could\nnot download a dependency"))
    (is (str/includes? (:body r) "**Versions tested:** [\"0.8.27\"]"))
    (is (str/ends-with? (:body r) "has the per-project table. Dashboard: https://jlt-commons.github.io/fleet/"))))

(deftest infra-plus-a-real-failure-is-reported-as-a-failure
  (let [r (ib :results [(res "0.8.27" ["glitter" "fail"] ["ebb" "infra" "Install jolt"])])]
    (is (true? (:broken? r)))
    (is (false? (:infra? r)))
    (is (str/includes? (:body r) "**Failing:** glitter (0.8.27)"))))

(deftest a-failed-fleet-with-no-results-is-still-broken
  (testing "names the failed jobs when it can"
    (let [r (ib :results [] :fleet-result "failure" :failed-jobs ["jolt 0.8.27 / fan-out" "x"])]
      (is (true? (:broken? r)))
      (is (str/includes? (:body r) "**Failing:** jolt 0.8.27 / fan-out, x"))))
  (testing "otherwise points at the run"
    (is (str/includes? (:body (ib :results [] :fleet-result "cancelled")) "**Failing:** see the run")))
  (testing "a green fleet job with no results is nothing to report"
    (is (false? (:broken? (ib :results [] :fleet-result "success"))))))

(deftest a-green-nightly-note-can-be-blank
  (is (str/includes? (:body (ib :results [(res "0.8.27" ["a" "fail"])] :nightly "")) "**Nightly:** not resolved")))

;; ---------------------------------------------------------------------------
;; report!

(defn- recording-gh
  "A client over canned issue lists; records writes as [:post path data]."
  [issues writes]
  {:get-paginated (fn [path]
                    (is (= "repos/o/ci/issues?state=open" path))
                    issues)
   :get-json      (fn [_] {:jobs []})
   :post-json     (fn [path data] (swap! writes conj [:post path data]) {:number 99})
   :patch-json    (fn [path data] (swap! writes conj [:patch path data]) {})})

(def ^:private open-issue {:number 5 :title "canary: jolt breaks the fleet" :state "open"})

(defn- report [issues results & {:as over}]
  (let [writes (atom [])
        out    (canary/report! (merge {:gh (recording-gh issues writes) :repo "o/ci" :results results
                                       :fleet-result "success" :versions "[\"0.8.27\"]" :nightly "n"
                                       :run-url "https://x/run/9" :run-id "9"}
                                      over))]
    {:out out :writes @writes}))

(deftest green-with-nothing-open-does-nothing
  (let [{:keys [out writes]} (report [] [(res "0.8.27" ["a" "pass"])])]
    (is (= [] writes))
    (is (= :nothing out))))

(deftest green-with-an-open-issue-comments-recovered-then-closes
  (let [{:keys [out writes]} (report [open-issue] [(res "0.8.27" ["a" "pass"])])]
    (is (= :closed out))
    (is (= [:post "repos/o/ci/issues/5/comments"] (take 2 (first writes))))
    (is (str/starts-with? (:body (nth (first writes) 2)) "Recovered."))
    (is (= [:patch "repos/o/ci/issues/5" {:state "closed"}] (second writes)))))

(deftest broken-with-nothing-open-creates-the-issue
  (let [{:keys [out writes]} (report [] [(res "0.8.27" ["a" "fail"])])]
    (is (= :opened out))
    (is (= 1 (count writes)))
    (is (= [:post "repos/o/ci/issues"] (take 2 (first writes))))
    (is (= "canary: jolt breaks the fleet" (:title (nth (first writes) 2))))))

(deftest broken-with-an-open-issue-comments-and-leaves-it-open
  (let [{:keys [out writes]} (report [open-issue] [(res "0.8.27" ["a" "fail"])])]
    (is (= :commented out))
    (is (= [:post "repos/o/ci/issues/5/comments"] (take 2 (first writes))))
    (is (= 1 (count writes)))))

(deftest a-pull-request-or-another-title-is-not-the-canary-issue
  (let [issues [{:number 1 :title "canary: jolt breaks the fleet" :pull_request {:url "x"}}
                {:number 2 :title "something else"}
                {:number 3 :title "canary: jolt breaks the fleet today"}]
        {:keys [out]} (report issues [(res "0.8.27" ["a" "fail"])])]
    (is (= :opened out))))

(deftest report-fetches-the-failed-jobs-only-when-it-has-to
  (let [calls  (atom [])
        writes (atom [])
        gh     (assoc (recording-gh [] writes)
                      :get-json (fn [p] (swap! calls conj p)
                                  {:jobs [{:name "jolt 0.8.27" :conclusion "failure"}
                                          {:name "plan" :conclusion "success"}]}))]
    (canary/report! {:gh gh :repo "o/ci" :results [(res "0.8.27" ["a" "fail"])] :fleet-result "failure"
                     :versions "v" :nightly "n" :run-url "u" :run-id "9"})
    (is (= [] @calls) "a results-based verdict needs no job lookup")
    (canary/report! {:gh gh :repo "o/ci" :results [] :fleet-result "failure"
                     :versions "v" :nightly "n" :run-url "u" :run-id "9"})
    (is (= ["repos/o/ci/actions/runs/9/jobs?per_page=100"] @calls))
    (is (str/includes? (:body (nth (last @writes) 2)) "**Failing:** jolt 0.8.27\n"))))

;; ---------------------------------------------------------------------------
;; outputs

(deftest outputs-text-uses-the-keys-the-plan-job-exposes
  (let [p   (plan :nightly-release fresh-nightly)
        txt (canary/outputs-text p)
        kv  (into {} (map #(str/split % #"=" 2)) (str/split-lines txt))]
    (is (= #{"latest" "versions" "nightly" "nightly_json" "key" "proceed" "reason"} (set (keys kv))))
    (is (= "[\"0.8.27\",\"nightly\"]" (kv "versions")))
    (is (= "true" (kv "proceed")))
    (is (= (:nightly-json p) (json/parse-string (kv "nightly_json") true)))
    (is (str/ends-with? txt "\n"))))

(deftest outputs-text-writes-null-when-there-is-no-nightly
  (is (= "null" (get (into {} (map #(str/split % #"=" 2)) (str/split-lines (canary/outputs-text (plan)))) "nightly_json"))))
