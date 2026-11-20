(ns fleet.cli-test
  "9a review fix (c): the CLI layer writes no files on malformed input."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.cli :as cli])
  (:import (java.io StringWriter)))

(def ^:private fx "test/fixtures/status/")

(defn- tmp-dir [] (str (fs/create-temp-dir)))

(defn- run
  "Call f with stdout and stderr captured; {:rc :out :err}."
  [f]
  (let [o (StringWriter.) e (StringWriter.)
        rc (binding [*out* o *err* e] (f))]
    {:rc rc :out (str o) :err (str e)}))

(defn- status-args [dir & {:as over}]
  (let [m (merge {"--prev-status"  (str dir "/none-status.json")
                  "--prev-history" (str dir "/none-history.json")
                  "--own-pin"      (str fx "own-pin.json")
                  "--uncovered"    (str fx "uncovered.json")
                  "--latest"       "0.8.27"
                  "--nightly-json" "null"
                  "--canary-run"   "https://example/run/1"
                  "--now"          "2026-11-20T02:00:00Z"
                  "--out-dir"      (str dir "/out")}
                 over)]
    (vec (mapcat identity (remove (comp nil? val) m)))))

(defn- nothing-written? [dir] (not (fs/exists? (str dir "/out"))))

(defn- write! [dir name text] (spit (str dir "/" name) text) (str dir "/" name))

(deftest status-writes-both-files-on-good-input
  (let [d (tmp-dir)
        r (run #(cli/status-main (concat (status-args d) [(str fx "results-latest.json")])))]
    (is (= 0 (:rc r)))
    (is (fs/exists? (str d "/out/status.json")))
    (is (fs/exists? (str d "/out/history.json")))))

(deftest status-writes-nothing-on-malformed-input
  (testing "unparsable own-pin JSON"
    (let [d (tmp-dir)
          r (run #(cli/status-main (status-args d "--own-pin" (write! d "bad.json" "[{"))))]
      (is (= 1 (:rc r)))
      (is (nothing-written? d))))
  (testing "own-pin of the wrong shape"
    (let [d (tmp-dir)
          r (run #(cli/status-main (status-args d "--own-pin" (write! d "op.json" "{\"a\":1}"))))]
      (is (= 1 (:rc r)))
      (is (str/includes? (:err r) "malformed own-pin"))
      (is (nothing-written? d))))
  (testing "a results file with an unknown result"
    (let [d (tmp-dir)
          f (write! d "res.json" "{\"requested\":\"0.8.27\",\"projects\":[{\"name\":\"a\",\"result\":\"bogus\"}]}")
          r (run #(cli/status-main (concat (status-args d) [f])))]
      (is (= 1 (:rc r)))
      (is (str/includes? (:err r) "malformed results"))
      (is (nothing-written? d))))
  (testing "a missing results file"
    (let [d (tmp-dir)
          r (run #(cli/status-main (concat (status-args d) [(str d "/missing.json")])))]
      (is (= 1 (:rc r)))
      (is (nothing-written? d))))
  (testing "a previous status that is not an object"
    (let [d (tmp-dir)
          r (run #(cli/status-main (status-args d "--prev-status" (write! d "ps.json" "[1]"))))]
      (is (= 1 (:rc r)))
      (is (str/includes? (:err r) "malformed prev status"))
      (is (nothing-written? d))))
  (testing "an unparsable --nightly-json"
    (let [d (tmp-dir)
          r (run #(cli/status-main (status-args d "--nightly-json" "{nope")))]
      (is (= 1 (:rc r)))
      (is (nothing-written? d)))))

(deftest status-usage-errors-exit-2-and-write-nothing
  (let [d (tmp-dir)]
    (doseq [args [(status-args d "--latest" nil)
                  (status-args d "--bogus" "x")
                  (conj (status-args d) "--now")]]
      (let [r (run #(cli/status-main args))]
        (is (= 2 (:rc r)))
        (is (str/includes? (:err r) "usage"))
        (is (nothing-written? d))))))

(deftest refresh-writes-nothing-on-malformed-input
  (let [env  {"LATEST" "0.8.27" "NIGHTLY_JSON" "null" "CANARY_RUN" "https://example/run/1"}
        fleet (fn [d] (write! d "fleet.json" "{\"projects\":[],\"excluded\":{}}"))
        gh   {:get-json (fn [_] (throw (ex-info "no" {:status 404})))
              :get-raw (fn [_] (throw (ex-info "no" {:status 404})))
              :get-paginated (fn [_] [])}]
    (testing "a results file of the wrong shape"
      (let [d (tmp-dir)
            f (write! d "res.json" "{\"requested\":1}")
            r (run #(cli/refresh-main [(str d "/out") f] (assoc env "FLEET_JSON" (fleet d)) {:gh gh}))]
        (is (= 1 (:rc r)))
        (is (nothing-written? d))))
    (testing "an unparsable results file"
      (let [d (tmp-dir)
            f (write! d "res.json" "{[")
            r (run #(cli/refresh-main [(str d "/out") f] (assoc env "FLEET_JSON" (fleet d)) {:gh gh}))]
        (is (= 1 (:rc r)))
        (is (nothing-written? d))))
    (testing "an unparsable NIGHTLY_JSON"
      (let [d (tmp-dir)
            r (run #(cli/refresh-main [(str d "/out")] (assoc env "FLEET_JSON" (fleet d) "NIGHTLY_JSON" "{x") {:gh gh}))]
        (is (= 1 (:rc r)))
        (is (nothing-written? d))))
    (testing "a collector failure"
      (let [d (tmp-dir)
            bad (assoc gh :get-paginated (fn [_] (throw (ex-info "boom" {}))))
            r (run #(cli/refresh-main [(str d "/out")] (assoc env "FLEET_JSON" (fleet d)) {:gh bad}))]
        (is (= 1 (:rc r)))
        (is (nothing-written? d))))
    (testing "good input still writes both files"
      (let [d (tmp-dir)
            r (run #(cli/refresh-main [(str d "/out")] (assoc env "FLEET_JSON" (fleet d)) {:gh gh}))]
        (is (= 0 (:rc r)))
        (is (fs/exists? (str d "/out/status.json")))))))

(deftest fanout-rejects-extra-arguments
  (let [r (run #(cli/fanout-main ["0.8.27" "extra"] {} {}))]
    (is (= 2 (:rc r)))
    (is (str/includes? (:err r) "usage: fanout [version]"))))

(deftest fanout-without-a-fleet-file-exits-1-with-one-line
  (let [r (run #(cli/fanout-main ["0.8.27"] {"FLEET_JSON" "/nonexistent/fleet.json"} {}))]
    (is (= 1 (:rc r)))
    (is (str/includes? (:err r) "fanout: cannot read /nonexistent/fleet.json"))))

(deftest refresh-without-latest-asks-the-client-and-strips-the-v
  (let [d    (tmp-dir)
        asked (atom [])
        gh   {:get-json (fn [path]
                          (swap! asked conj path)
                          (if (= "repos/jolt-lang/jolt/releases/latest" path)
                            {:tag_name "v0.9.4"}
                            (throw (ex-info "no" {:status 404}))))
              :get-raw (fn [_] (throw (ex-info "no" {:status 404})))
              :get-paginated (fn [_] [])}
        env  {"NIGHTLY_JSON" "null" "CANARY_RUN" "https://example/run/1"
              "FLEET_JSON" (write! d "fleet.json" "{\"projects\":[],\"excluded\":{}}")}
        r    (run #(cli/refresh-main [(str d "/out")] env {:gh gh}))]
    (is (= 0 (:rc r)))
    (is (= ["repos/jolt-lang/jolt/releases/latest"] @asked))
    (is (str/includes? (slurp (str d "/out/status.json")) "0.9.4"))
    (is (not (str/includes? (slurp (str d "/out/status.json")) "v0.9.4")))))

(deftest refresh-with-latest-set-does-not-ask
  (let [d   (tmp-dir)
        gh  {:get-json (fn [path] (throw (ex-info (str "unexpected " path) {:status 404})))
             :get-raw (fn [_] (throw (ex-info "no" {:status 404})))
             :get-paginated (fn [_] [])}
        env {"LATEST" "0.8.27" "NIGHTLY_JSON" "null" "CANARY_RUN" "x"
             "FLEET_JSON" (write! d "fleet.json" "{\"projects\":[],\"excluded\":{}}")}
        r   (run #(cli/refresh-main [(str d "/out")] env {:gh gh}))]
    (is (= 0 (:rc r)))))

;; Task 11: fleet:plan and fleet:report.

(defn- canary-gh
  "A client answering the jolt-lang reads, and recording issue writes."
  [writes & {:keys [issues] :or {issues []}}]
  (let [answers {"repos/jolt-lang/jolt/releases/latest" {:tag_name "v0.8.27"}
                 "repos/jolt-lang/jolt/releases/tags/vnightly" {:published_at "2026-11-19T21:30:15Z"}
                 "repos/jolt-lang/jolt/git/refs/tags/vnightly" {:object {:sha "dd3c4a4b00"}}
                 "repos/jolt-lang/jolt/git/refs/tags/v0.8.27" {:object {:sha "9b768395ff"}}}]
    {:get-json      (fn [p] (or (get answers p) (throw (ex-info "unexpected" {:path p}))))
     :get-paginated (fn [_] issues)
     :post-json     (fn [p d] (swap! writes conj [:post p d]) {:number 1})
     :patch-json    (fn [p d] (swap! writes conj [:patch p d]) {})}))

(defn- kv [text] (into {} (map #(str/split % #"=" 2)) (str/split-lines text)))

(deftest plan-writes-the-job-outputs-to-github-output
  (let [d   (tmp-dir)
        out (str d "/out")
        r   (run #(cli/plan-main [] {"EVENT" "workflow_dispatch" "REQUESTED" "" "GITHUB_OUTPUT" out
                                     "GITHUB_REPOSITORY" "o/ci"}
                                 {:gh (canary-gh (atom []))}))
        o   (kv (slurp out))]
    (is (= 0 (:rc r)))
    (is (= "0.8.27" (o "latest")))
    (is (= "true" (o "proceed")))
    (is (= "manual run, gates skipped" (o "reason")))
    (is (= #{"latest" "versions" "nightly" "nightly_json" "key" "proceed" "reason"} (set (keys o))))
    (is (str/starts-with? (o "versions") "[\"0.8.27\""))))

(deftest plan-rejects-a-junk-interval-and-writes-nothing
  (let [d   (tmp-dir)
        out (str d "/out")
        r   (run #(cli/plan-main [] {"EVENT" "schedule" "INTERVAL_HOURS" "soon" "GITHUB_OUTPUT" out}
                                 {:gh (canary-gh (atom []))}))]
    (is (= 1 (:rc r)))
    (is (str/includes? (:err r) "CANARY_INTERVAL_HOURS is not a whole number"))
    (is (not (fs/exists? out)))))

(deftest plan-final-writes-proceed
  (let [d   (tmp-dir)
        out (str d "/out")
        r   (run #(cli/plan-main ["--final"] {"INTERVAL_OK" "true" "ALREADY_GREEN" "true" "REASON" "due"
                                              "KEY" "canary-green-0.8.27" "GITHUB_OUTPUT" out} {}))]
    (is (= 0 (:rc r)))
    (is (= "proceed=false\n" (slurp out)))
    (is (str/includes? (:out r) "SKIPPING: canary-green-0.8.27 already passed"))))

(deftest plan-usage-error
  (is (= 2 (:rc (run #(cli/plan-main ["--nope"] {} {}))))))

(deftest report-reads-every-results-file-and-opens-an-issue-for-a-failure
  (let [d      (tmp-dir)
        _      (fs/create-dirs (str d "/results/results-0.8.27"))
        _      (fs/create-dirs (str d "/results/results-nightly"))
        _      (spit (str d "/results/results-0.8.27/results.json")
                     "{\"requested\":\"0.8.27\",\"projects\":[{\"name\":\"a\",\"result\":\"pass\"}]}")
        _      (spit (str d "/results/results-nightly/results.json")
                     "{\"requested\":\"nightly\",\"projects\":[{\"name\":\"a\",\"result\":\"fail\"}]}")
        writes (atom [])
        r      (run #(cli/report-main [(str d "/results")]
                                      {"GITHUB_REPOSITORY" "o/ci" "VERSIONS" "[\"0.8.27\",\"nightly\"]"
                                       "RUN_URL" "https://x/run/1" "RESULT" "failure" "GITHUB_RUN_ID" "1"}
                                      {:gh (canary-gh writes)}))]
    (is (= 0 (:rc r)))
    (is (= 1 (count @writes)))
    (is (str/includes? (:body (nth (first @writes) 2)) "**Failing:** a (nightly)"))))

(deftest report-with-no-results-dir-and-a-green-fleet-does-nothing
  (let [writes (atom [])
        r      (run #(cli/report-main [(str (tmp-dir) "/results")]
                                      {"GITHUB_REPOSITORY" "o/ci" "VERSIONS" "[]" "RUN_URL" "u" "RESULT" "success"}
                                      {:gh (canary-gh writes)}))]
    (is (= 0 (:rc r)))
    (is (= [] @writes))))

(deftest report-needs-its-environment
  (let [r (run #(cli/report-main [] {} {}))]
    (is (= 2 (:rc r)))
    (is (str/includes? (:err r) "GITHUB_REPOSITORY is required"))))
