(ns fleet.fanout-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.fanout :as fanout]))

(def ^:private fx "test/fixtures/")

(defn- fixture [name] (slurp (str fx name)))

(defn- job
  ([id conclusion] (job id conclusion []))
  ([id conclusion steps] {:id id :conclusion conclusion :steps steps}))

(defn- step [name conclusion] {:name name :conclusion conclusion})

(def ^:private release-log (fixture "log-release.txt"))
(def ^:private nightly-log (fixture "log-nightly.txt"))

(defn- jolt-log [v] (str "2026-11-20T01:00:00.0000000Z jolt v" v "\n"))

;; ---------------------------------------------------------------------------
;; classify

(deftest success-on-the-requested-jolt-is-pass
  (is (= {:result "pass" :installed "0.8.24" :failed-step nil :cause nil :detail "success"}
         (fanout/classify {:requested "0.8.24" :conclusion "success"
                           :jobs [(job 1 "success")] :logs {1 release-log}}))))

(deftest r4-one-matching-job-is-enough
  (testing "glitter-gl's deliberate 0.7.29 job beside the main one"
    (is (= {:result "pass" :installed "0.8.27"}
           (select-keys (fanout/classify {:requested "0.8.27" :conclusion "success"
                                          :jobs [(job 1 "success") (job 2 "success")]
                                          :logs {1 (jolt-log "0.7.29") 2 (jolt-log "0.8.27")}})
                        [:result :installed])))))

(deftest a-green-run_on_the_wrong_jolt_is_mismatch_never_pass
  (is (= {:result "mismatch" :installed "0.7.29"}
         (select-keys (fanout/classify {:requested "0.8.27" :conclusion "success"
                                        :jobs [(job 1 "success")] :logs {1 (jolt-log "0.7.29")}})
                      [:result :installed]))))

(deftest a-green-run-with-readable-logs-and-no-version-line-is-mismatch
  (is (= {:result "mismatch" :installed nil}
         (select-keys (fanout/classify {:requested "0.8.27" :conclusion "success"
                                        :jobs [(job 1 "success")] :logs {1 "no version here\n"}})
                      [:result :installed]))))

(deftest i1-a-green-run-whose-logs-cannot-be-read-is-infra-never-mismatch
  (testing "nil logs"
    (is (= {:result "infra" :cause :logs :failed-step "could not read logs" :installed nil}
           (select-keys (fanout/classify {:requested "0.8.27" :conclusion "success"
                                          :jobs [(job 1 "success")] :logs {1 nil}})
                        [:result :cause :failed-step :installed]))))
  (testing "one job unreadable and the other on the wrong jolt: the unreadable one may have matched"
    (is (= "infra" (:result (fanout/classify {:requested "0.8.27" :conclusion "success"
                                              :jobs [(job 1 "success") (job 2 "success")]
                                              :logs {1 nil 2 (jolt-log "0.7.29")}})))))
  (testing "one job unreadable but another matches (R4): pass"
    (is (= "pass" (:result (fanout/classify {:requested "0.8.27" :conclusion "success"
                                             :jobs [(job 1 "success") (job 2 "success")]
                                             :logs {1 nil 2 (jolt-log "0.8.27")}})))))
  (testing "no jobs listed at all"
    (is (= "infra" (:result (fanout/classify {:requested "0.8.27" :conclusion "success" :jobs [] :logs {}}))))))

(deftest nightly-needs-the-vnightly-sha-prefix
  (let [base {:requested "nightly" :conclusion "success" :jobs [(job 1 "success")] :logs {1 nightly-log}}]
    (is (= {:result "pass" :installed "0.8.27-241-gdd3c4a4b"}
           (select-keys (fanout/classify (assoc base :nightly-sha "dd3c4a4b0123456789abcdef0123456789abcdef"))
                        [:result :installed])))
    (testing "vnightly moved on after the install"
      (is (= {:result "mismatch" :installed "0.8.27-241-gdd3c4a4b"}
             (select-keys (fanout/classify (assoc base :nightly-sha "f4e82fc8eac874cae1658b7976518253027c4f6a"))
                          [:result :installed]))))
    (testing "sha unresolved reads as mismatch"
      (is (= "mismatch" (:result (fanout/classify base)))))))

(deftest r5-skipped-is-not-run
  (is (= "not-run" (:result (fanout/classify {:requested "0.8.27" :conclusion "skipped" :jobs [] :logs {}})))))

(deftest a-failed-install-jolt-step-is-infra-and-keeps-the-step-name
  (is (= {:result "infra" :cause :install :failed-step "Install jolt"}
         (select-keys (fanout/classify {:requested "0.8.0" :conclusion "failure"
                                        :jobs [(job 1 "failure" [(step "Checkout" "success")
                                                                 (step "Install jolt" "failure")])]
                                        :logs {1 (fixture "log-test-failure.txt")}})
                      [:result :cause :failed-step]))))

(deftest r21-r24-a-maven-429-in-the-failed-step-is-infra-deps
  (testing "raygui-jlt's Formatting 429"
    (is (= {:result "infra" :cause :deps :failed-step "Formatting"}
           (select-keys (fanout/classify {:requested "nightly" :conclusion "failure"
                                          :jobs [(job 1 "failure" [(step "Formatting" "failure")])]
                                          :logs {1 (fixture "log-maven-429.txt")}})
                        [:result :cause :failed-step])))))

(deftest r24-every-failed-job-must-match
  (let [jobs [(job 1 "failure" [(step "Formatting" "failure")])
              (job 2 "failure" [(step "Test" "failure")])]]
    (is (= "fail" (:result (fanout/classify {:requested "0.8.27" :conclusion "failure" :jobs jobs
                                             :logs {1 (fixture "log-maven-429.txt")
                                                    2 (fixture "log-test-failure.txt")}}))))
    (is (= "infra" (:result (fanout/classify {:requested "0.8.27" :conclusion "failure" :jobs jobs
                                              :logs {1 (fixture "log-maven-429.txt")
                                                     2 (fixture "log-maven-absent.txt")}}))))))

(deftest an-unreadable-log-is-no-evidence
  (is (= "fail" (:result (fanout/classify {:requested "0.8.27" :conclusion "failure"
                                           :jobs [(job 1 "failure" [(step "Formatting" "failure")])]
                                           :logs {1 nil}})))))

(deftest a-429-outside-the-failed-step-stays-fail
  (is (= "fail" (:result (fanout/classify {:requested "0.8.27" :conclusion "failure"
                                           :jobs [(job 1 "failure" [(step "Test" "failure")])]
                                           :logs {1 (fixture "log-early-429.txt")}})))))

(deftest an-ordinary-failure-is-fail-with-its-first-failed-step
  (is (= {:result "fail" :cause nil :failed-step "Test"}
         (select-keys (fanout/classify {:requested "0.8.27" :conclusion "failure"
                                        :jobs [(job 1 "failure" [(step "Checkout" "success")
                                                                 (step "Test" "failure")])
                                               (job 2 "failure" [(step "Other" "failure")])]
                                        :logs {1 (fixture "log-test-failure.txt")
                                               2 (fixture "log-test-failure.txt")}})
                      [:result :cause :failed-step]))))

(deftest r37-cancelled-startup-failure-and-action-required-are-not-run
  (doseq [c ["cancelled" "startup_failure" "action_required"]]
    (testing c
      (is (= {:result "not-run" :failed-step nil :detail c}
             (select-keys (fanout/classify {:requested "0.8.27" :conclusion c :jobs [] :logs {}})
                          [:result :failed-step :detail]))))))

(deftest other-conclusions-are-fail-without-a-step
  (is (= {:result "fail" :failed-step nil :detail "timed_out"}
         (select-keys (fanout/classify {:requested "0.8.27" :conclusion "timed_out" :jobs [] :logs {}})
                      [:result :failed-step :detail]))))

;; ---------------------------------------------------------------------------
;; summary-md

(defn- row [name result & {:as more}]
  (merge {:name name :result result :detail "x" :run-url (str "https://r/" name)} more))

(deftest summary-all-pass
  (is (= (str "## jolt `0.8.27` across jlt-commons\n\n"
              "| project | result | run |\n|---|---|---|\n"
              "| `a` | pass | [run](https://r/a) |\n"
              "| `b` | skipped | [run](https://r/b) |\n\n"
              "All 2 projects build against jolt `0.8.27`.\n")
         (fanout/summary-md {:version "0.8.27"
                             :projects [(row "a" "pass") (row "b" "not-run")]}))))

(deftest summary-names-each-kind-of-trouble
  (let [md (fanout/summary-md
            {:version "nightly"
             :projects [(row "m" "mismatch" :installed "0.7.29")
                        (row "f" "fail" :detail "failure")
                        (row "t" "timed-out" :detail "timed-out" :run-url nil)
                        (row "i" "infra" :cause :install)
                        (row "d" "infra" :cause :deps)]})]
    (is (str/includes? md "| `m` | **WRONG JOLT** (ran 0.7.29) | [run](https://r/m) |"))
    (is (str/includes? md "| `f` | **FAIL** (failure) |"))
    (is (str/includes? md "| `t` | **FAIL** (timed-out) | - |"))
    (is (str/includes? md "| `i` | **could not install jolt** |"))
    (is (str/includes? md "| `d` | **could not download dependencies** |"))
    (is (str/includes? md "**Ran on the wrong jolt:** m\n"))
    (is (str/includes? md "**Broken by jolt `nightly`:** m f t\n"))
    (is (str/includes? md "**Could not install jolt `nightly` for:** i\n"))
    (is (str/includes? md "check each project's `JOLT_REPO`"))
    (is (str/includes? md "**Could not download dependencies for:** d\n"))
    (is (str/includes? md "package-registry problem"))
    (is (not (str/includes? md "All ")))))

(deftest summary-says-why-a-run-did-not-answer
  (let [md (fanout/summary-md
            {:version "0.8.27"
             :projects [(row "c" "not-run" :detail "cancelled")
                        (row "l" "infra" :cause :logs)]})]
    (is (str/includes? md "| `c` | could not run (cancelled) |"))
    (is (str/includes? md "**Could not run:** c\n"))
    (is (not (str/includes? md "All ")))
    (is (str/includes? md "| `l` | **could not read the run's logs** |"))
    (is (str/includes? md "**Could not read the logs of a green run for:** l\n"))
    (is (not (str/includes? md "Could not install")))
    (is (not (str/includes? md "Broken by jolt")))))

(deftest summary-mismatch-without-a-version
  (is (str/includes? (fanout/summary-md {:version "1" :projects [(row "m" "mismatch")]})
                     "**WRONG JOLT** (ran no version found)")))

;; ---------------------------------------------------------------------------
;; run! over a fake fleet

(def ^:private run-ids {"alpha" "101" "beta" "202"})

(defn- fake-world
  "Fakes for run!. runs: project -> vector of run responses handed out one
  per poll. jobs: project -> job vector. logs: job id -> text or :fail.
  Records every call in :calls."
  [{:keys [runs jobs logs dispatch print-url? latest vnightly]
    :or   {print-url? true latest "0.9.0" vnightly "dd3c4a4b0123456789abcdef0123456789abcdef"}}]
  (let [calls   (atom [])
        polls   (atom {})
        sleeps  (atom [])
        by-path (fn [path]
                  (or (when-let [[_ pr] (re-matches #"repos/jlt-commons/([^/]+)/actions/runs/\d+" path)]
                        (let [n (get (swap! polls update pr (fnil inc 0)) pr)
                              rs (get runs pr)]
                          (get rs (min (dec n) (dec (count rs))))))
                      (when-let [[_ pr] (re-matches #"repos/jlt-commons/([^/]+)/actions/runs/\d+/jobs\?per_page=100" path)]
                        {:jobs (get jobs pr)})
                      (when (= path "repos/jolt-lang/jolt/releases/latest") {:tag_name (str "v" latest)})
                      (when (= path "repos/jolt-lang/jolt/git/refs/tags/vnightly") {:object {:sha vnightly}})
                      (when-let [[_ pr] (re-matches #"repos/jlt-commons/([^/]+)/actions/workflows/ci.yml/runs\?event=workflow_dispatch&per_page=1" path)]
                        {:workflow_runs [{:id (Long/parseLong (get run-ids pr))}]})
                      (throw (ex-info (str "unexpected " path) {:status 404}))))]
    {:calls calls :sleeps sleeps
     :gh {:get-json (fn [path] (swap! calls conj [:get path]) (by-path path))
          :get-redirected-log
          (fn [path]
            (swap! calls conj [:log path])
            (let [id  (Long/parseLong (second (re-find #"jobs/(\d+)/logs" path)))
                  txt (get logs id)]
              (if (= :fail txt) (throw (ex-info "gone" {:status 410})) txt)))}
     :sh (fn [args env]
           (swap! calls conj [:sh args env])
           (let [pr (second (re-find #"jlt-commons/(.*)" (nth args 5)))]
             (cond
               (and dispatch (= :fail (get dispatch pr))) {:exit 1 :out "" :err "HTTP 422"}
               print-url? {:exit 0 :out (str "https://github.com/jlt-commons/" pr "/actions/runs/" (get run-ids pr) "\n") :err ""}
               :else {:exit 0 :out "" :err ""})))
     :sleep (fn [ms] (swap! sleeps conj ms))
     :now (constantly "2026-11-20T03:00:00Z")}))

(defn- env-for [dir & [token]]
  (let [m {"GH_TOKEN" (or token "tok") "RUNNER_TEMP" dir
           "GITHUB_STEP_SUMMARY" (str dir "/summary.md") "GITHUB_OUTPUT" (str dir "/output.txt")
           "GITHUB_SERVER_URL" "https://github.com" "GITHUB_REPOSITORY" "jlt-commons/ci-builds"
           "GITHUB_RUN_ID" "999"}]
    #(get m %)))

(defn- tmp [] (str (fs/create-temp-dir)))

(defn- run-fake [world version & {:keys [token]}]
  (let [dir (tmp)
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        rc  (binding [*out* out *err* err]
              (fanout/run! (assoc (select-keys world [:gh :sh :sleep :now])
                                  :version version :projects ["alpha" "beta"] :env (env-for dir token))))]
    {:rc rc :dir dir :stdout (str out) :stderr (str err)
     :results (json/parse-string (slurp (str dir "/fanout-results/results.json")) true)
     :summary (slurp (str dir "/summary.md"))
     :output (slurp (str dir "/output.txt"))}))

(def ^:private ok-run {:status "completed" :conclusion "success"})

(deftest run-passes-two-projects-after-waiting-once
  (let [w (fake-world {:runs {"alpha" [{:status "in_progress"} ok-run] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                       :logs {1 (jolt-log "0.8.27") 2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")]
    (is (= 0 (:rc r)))
    (is (= [20000] @(:sleeps w)))
    (testing "dispatch used gh with the token and the version"
      (is (= [:sh ["gh" "workflow" "run" "ci.yml" "--repo" "jlt-commons/alpha" "-f" "jolt-version=0.8.27"]
              {"GH_TOKEN" "tok"}]
             (first (filter #(= :sh (first %)) @(:calls w))))))
    (testing "logs came only after completion, for every job of a success"
      (is (= [[:log "repos/jlt-commons/beta/actions/jobs/2/logs"]
              [:log "repos/jlt-commons/alpha/actions/jobs/1/logs"]]
             (filterv #(= :log (first %)) @(:calls w)))))
    (testing "results.json has the bash-era shape"
      (is (= {:requested "0.8.27" :nightly_sha ""
              :finished_at "2026-11-20T03:00:00Z"
              :run_url "https://github.com/jlt-commons/ci-builds/actions/runs/999"
              :projects [{:name "alpha" :result "pass" :installed "0.8.27"
                          :run_url "https://github.com/jlt-commons/alpha/actions/runs/101"
                          :finished_at "2026-11-20T03:00:00Z" :failed_step nil :detail "success"}
                         {:name "beta" :result "pass" :installed "0.8.27"
                          :run_url "https://github.com/jlt-commons/beta/actions/runs/202"
                          :finished_at "2026-11-20T03:00:00Z" :failed_step nil :detail "success"}]}
             (:results r))))
    (is (str/includes? (:summary r) "All 2 projects build against jolt `0.8.27`."))
    (is (= (str "results_dir=" (:dir r) "/fanout-results\nartifact=results-0.8.27\nversion=0.8.27\nfailed=\ninfra=\n")
           (:output r)))))

(deftest run-reports-failures-and-infra-and-exits-1
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "failure"}]
                              "beta"  [{:status "completed" :conclusion "failure"}]}
                       :jobs {"alpha" [(job 1 "failure" [(step "Formatting" "failure")])]
                              "beta"  [(job 2 "failure" [(step "Install jolt" "failure")])]}
                       :logs {1 (fixture "log-maven-429.txt") 2 "boom\n"}})
        r (run-fake w "nightly")]
    (is (= 1 (:rc r)))
    (is (= [{:name "alpha" :result "infra" :failed_step "Formatting" :installed nil}
            {:name "beta" :result "infra" :failed_step "Install jolt" :installed nil}]
           (mapv #(select-keys % [:name :result :failed_step :installed]) (:projects (:results r)))))
    (is (= "dd3c4a4b0123456789abcdef0123456789abcdef" (:nightly_sha (:results r))))
    (is (str/includes? (:summary r) "**Could not install jolt `nightly` for:** beta"))
    (is (str/includes? (:summary r) "**Could not download dependencies for:** alpha"))
    (is (str/includes? (:output r) "failed=\ninfra=alpha beta\n"))
    (is (str/includes? (:stderr r) "infra: alpha beta"))))

(deftest run-wrong-jolt-is-mismatch-and-failed
  (let [w (fake-world {:runs {"alpha" [ok-run] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                       :logs {1 (jolt-log "0.8.27") 2 (jolt-log "0.8.26")}})
        r (run-fake w "0.8.27")]
    (is (= 1 (:rc r)))
    (is (= ["pass" "mismatch"] (mapv :result (:projects (:results r)))))
    (is (= "0.8.26" (:installed (second (:projects (:results r))))))
    (is (str/includes? (:summary r) "**Ran on the wrong jolt:** beta"))
    (is (str/includes? (:output r) "failed=beta\n"))))

(deftest run-a-dispatch-failure-is-dispatch-failed-and-never-polled
  (let [w (fake-world {:dispatch {"beta" :fail}
                       :runs {"alpha" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")]}
                       :logs {1 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")
        b (second (:projects (:results r)))]
    (is (= 1 (:rc r)))
    (is (= {:result "dispatch-failed" :run_url nil :finished_at nil :failed_step "HTTP 422"}
           (select-keys b [:result :run_url :finished_at :failed_step])))
    (is (str/includes? (:stdout r) "\n  gh: HTTP 422\n"))
    (is (not-any? #(and (= :get (first %)) (str/includes? (second %) "beta")) @(:calls w)))))

(defn- with-clock
  "The world with a fake clock that only sleep advances, so a test can watch
  the wall-clock deadline pass without waiting. Returns [world clock-atom]."
  [w]
  (let [t (atom (.toEpochMilli (java.time.Instant/parse "2026-11-20T03:00:00Z")))]
    [(assoc w
            :sleep (fn [ms] (swap! (:sleeps w) conj ms) (swap! t + ms))
            :now (fn [] (str (java.time.Instant/ofEpochMilli @t))))
     t]))

(deftest i5-run-gives-up-45-minutes-after-dispatch-and-marks-the-rest-timed-out
  (let [[w t] (with-clock (fake-world {:runs {"alpha" [{:status "in_progress"}] "beta" [ok-run]}
                                       :jobs {"beta" [(job 2 "success")]}
                                       :logs {2 (jolt-log "0.8.27")}}))
        r (run-fake w "0.8.27")]
    (is (= 1 (:rc r)))
    (testing "20s sleeps until 45 minutes have passed: 2700s / 20s"
      (is (= 135 (count @(:sleeps w))))
      (is (= (* 45 60 1000) (- @t (.toEpochMilli (java.time.Instant/parse "2026-11-20T03:00:00Z"))))))
    (testing "what finished is written, the rest is timed-out"
      (is (= ["timed-out" "pass"] (mapv :result (:projects (:results r))))))
    (is (str/includes? (:summary r) "| `alpha` | **FAIL** (timed-out) |"))))

(deftest i5-a-slow-poll-pass-costs-time-not-iterations
  (testing "each pass takes 10 minutes of wall clock (a slow log read), so the deadline is hit after 5 passes"
    (let [[w t] (with-clock (fake-world {:runs {"alpha" [{:status "in_progress"}] "beta" [ok-run]}
                                         :jobs {"beta" [(job 2 "success")]}
                                         :logs {2 (jolt-log "0.8.27")}}))
          w (update w :gh assoc :get-json
                    (let [base (:get-json (:gh w))]
                      (fn [path]
                        (when (str/includes? path "/alpha/actions/runs/101") (swap! t + (* 10 60 1000)))
                        (base path))))
          r (run-fake w "0.8.27")]
      (is (= 4 (count @(:sleeps w))))
      (is (= ["timed-out" "pass"] (mapv :result (:projects (:results r))))))))

(deftest run-without-a-printed-url-warns-and-takes-the-newest-dispatch-run
  (let [w (fake-world {:print-url? false
                       :runs {"alpha" [ok-run] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                       :logs {1 (jolt-log "0.8.27") 2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")]
    (is (= 0 (:rc r)))
    (is (str/includes? (:stdout r) "::warning::gh printed no run URL for alpha"))
    (is (= "https://github.com/jlt-commons/alpha/actions/runs/101" (:run_url (first (:projects (:results r))))))
    (is (= 10000 (first @(:sleeps w))))))

(deftest run-a-log-that-cannot-be-fetched-warns-and-is-no-evidence
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "failure"}] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "failure" [(step "Formatting" "failure")])]
                              "beta" [(job 2 "success")]}
                       :logs {1 :fail 2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")]
    (is (= "fail" (:result (first (:projects (:results r))))))
    (is (str/includes? (:stdout r) "::warning::could not fetch the log of alpha job 1"))))

(deftest run-blank-version-tests-the-latest-release
  (let [w (fake-world {:latest "0.9.1"
                       :runs {"alpha" [ok-run] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                       :logs {1 (jolt-log "0.9.1") 2 (jolt-log "0.9.1")}})
        r (run-fake w "")]
    (is (= "0.9.1" (:requested (:results r))))
    (is (str/includes? (:output r) "version=0.9.1\n"))
    (is (= "jolt-version=0.9.1" (nth (second (first (filter #(= :sh (first %)) @(:calls w)))) 7)))))

(deftest run-a-fallback-that-finds-no-run-is-dispatch-failed-and-failed
  (let [w (-> (fake-world {:print-url? false
                           :runs {"beta" [ok-run]}
                           :jobs {"beta" [(job 2 "success")]}
                           :logs {2 (jolt-log "0.8.27")}})
              (update :gh assoc :get-json
                      (let [base (:get-json (:gh (fake-world {:print-url? false
                                                              :runs {"beta" [ok-run]}
                                                              :jobs {"beta" [(job 2 "success")]}
                                                              :logs {2 (jolt-log "0.8.27")}})))]
                        (fn [path]
                          (if (str/includes? path "/alpha/actions/workflows/")
                            {:workflow_runs []}
                            (base path))))))
        r (run-fake w "0.8.27")
        a (first (:projects (:results r)))]
    (is (= 1 (:rc r)))
    (is (= {:result "dispatch-failed" :run_url nil} (select-keys a [:result :run_url])))
    (is (str/includes? (:output r) "failed=\ninfra=alpha\n"))
    (is (str/includes? (:summary r) "| `alpha` | could not run (no-run-appeared) | - |"))
    (is (str/includes? (:summary r) "**Could not run:** alpha\n"))
    (is (str/includes? (:summary r) "says nothing about jolt"))
    (is (not (str/includes? (:summary r) "Broken by jolt")))
    (is (not (str/includes? (:summary r) "All ")))))

(deftest run-one-malformed-project-is-fail-and_does_not_drop_the_others
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "failure"}] "beta" [ok-run]}
                       :jobs {"alpha" [{:id 1 :conclusion "failure" :steps 42}]
                              "beta" [(job 2 "success")]}
                       :logs {1 "x" 2 (jolt-log "0.8.27")}})
        w (update w :gh assoc :get-json
                  (let [base (:get-json (:gh w))]
                    (fn [path]
                      (if (str/includes? path "/alpha/actions/runs/101/jobs")
                        (throw (ex-info "boom secret-data" {:token "SECRET"}))
                        (base path)))))
        r (run-fake w "0.8.27")]
    (is (= 1 (:rc r)))
    (is (= [{:name "alpha" :result "fail" :failed_step nil}
            {:name "beta" :result "pass" :failed_step nil}]
           (mapv #(select-keys % [:name :result :failed_step]) (:projects (:results r)))))
    (is (str/includes? (:output r) "failed=alpha\n"))))

(deftest run-an-exception-while-classifying-is-fail-with-a-warning-without-ex-data
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "failure"}] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "failure" [(step nil "failure")])]
                              "beta" [(job 2 "success")]}
                       :logs {1 "x" 2 (jolt-log "0.8.27")}})
        w (update w :gh assoc :get-redirected-log
                  (let [base (:get-redirected-log (:gh w))]
                    (fn [path]
                      (if (str/includes? path "/jobs/1/")
                        (throw (RuntimeException. "log layer exploded"))
                        (base path)))))
        r (run-fake w "0.8.27")]
    (is (= 1 (:rc r)))
    (is (= ["fail" "pass"] (mapv :result (:projects (:results r)))))))

(deftest classify-tolerates-malformed-steps
  (is (= "fail" (:result (fanout/classify {:requested "1" :conclusion "failure"
                                           :jobs [{:id 1 :conclusion "failure" :steps 42}
                                                  {:id 2 :conclusion "failure" :steps [{:conclusion "failure"}]}]
                                           :logs {1 "x" 2 "x"}})))))

(deftest i1-a-log-that-fails_once_is_read_again_and_the_run_passes
  (let [w     (fake-world {:runs {"alpha" [ok-run] "beta" [ok-run]}
                           :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                           :logs {1 (jolt-log "0.8.27") 2 (jolt-log "0.8.27")}})
        fails (atom 0)
        w     (update w :gh assoc :get-redirected-log
                      (let [base (:get-redirected-log (:gh w))]
                        (fn [path]
                          (if (and (str/includes? path "/jobs/1/") (< @fails 1))
                            (do (swap! fails inc) (throw (ex-info "flaky" {:status 502})))
                            (base path)))))
        r     (run-fake w "0.8.27")]
    (is (= 0 (:rc r)))
    (is (= ["pass" "pass"] (mapv :result (:projects (:results r)))))
    (is (= 1 @fails))))

(deftest i1-a-log-that-stays-unreadable-is-infra-not-mismatch
  (let [w (fake-world {:runs {"alpha" [ok-run] "beta" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")] "beta" [(job 2 "success")]}
                       :logs {1 :fail 2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")
        a (first (:projects (:results r)))]
    (is (= 1 (:rc r)))
    (is (= {:result "infra" :failed_step "could not read logs"} (select-keys a [:result :failed_step])))
    (testing "the read was tried twice"
      (is (= 2 (count (filter #(= [:log "repos/jlt-commons/alpha/actions/jobs/1/logs"] %) @(:calls w))))))
    (is (str/includes? (:output r) "failed=\ninfra=alpha\n"))))

(deftest r33-a-dispatch-error-line-is-printed-indented-and-never-leaks-the-token
  (let [token "ghs_s3cr3tT0ken"
        w (fake-world {:dispatch {"beta" :fail}
                       :runs {"alpha" [ok-run]}
                       :jobs {"alpha" [(job 1 "success")]}
                       :logs {1 (jolt-log "0.8.27")}})
        ;; gh does not print its token, but a hostile first line that starts
        ;; with a workflow command and echoes it must still do neither.
        w (assoc w :sh (let [base (:sh w)]
                         (fn [args env]
                           (if (str/includes? (nth args 5) "beta")
                             {:exit 1 :out ""
                              :err (str "::set-output name=x::y token " (get env "GH_TOKEN") "\nsecond line\n")}
                             (base args env)))))
        r (run-fake w "0.8.27" :token token)
        b (second (:projects (:results r)))]
    (testing "R41: the runner trims leading whitespace, so the line is prefixed, not just indented"
      (is (some #(= "  gh: ::set-output name=x::y token ***" %) (str/split-lines (:stdout r))))
      (is (not-any? #(str/starts-with? (str/trim %) "::set-output") (str/split-lines (:stdout r))))
      (testing "the only `::` lines are the loop's own group markers"
        (is (every? #(re-matches #"::(group|endgroup)::.*" %)
                    (filter #(str/starts-with? (str/trim %) "::") (str/split-lines (:stdout r)))))))
    (testing "first line only, in the row"
      (is (= "::set-output name=x::y token ***" (:failed_step b))))
    (testing "the token is nowhere in the output"
      (doseq [text [(:stdout r) (:stderr r) (:summary r) (:output r) (json/generate-string (:results r))]]
        (is (not (str/includes? text token)))))))

(deftest r40-a-cancelled-project-is-untested-so-the-run-fails-and-never_reads_as_all_pass
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "cancelled"}] "beta" [ok-run]}
                       :jobs {"beta" [(job 2 "success")]}
                       :logs {2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")
        a (first (:projects (:results r)))]
    (is (= 1 (:rc r)))
    (is (= {:result "not-run" :detail "cancelled"} (select-keys a [:result :detail])))
    (is (str/includes? (:output r) "failed=\ninfra=alpha\n"))
    (is (not (str/includes? (:summary r) "All ")))
    (is (str/includes? (:summary r) "**Could not run:** alpha\n"))
    (is (str/includes? (:summary r) "| `alpha` | could not run (cancelled) |"))
    (is (str/includes? (:stderr r) "infra: alpha"))))

(deftest r40-a-plain-skipped-run-is-still-not-run-and-passes
  (let [w (fake-world {:runs {"alpha" [{:status "completed" :conclusion "skipped"}] "beta" [ok-run]}
                       :jobs {"beta" [(job 2 "success")]}
                       :logs {2 (jolt-log "0.8.27")}})
        r (run-fake w "0.8.27")]
    (is (= 0 (:rc r)))))
