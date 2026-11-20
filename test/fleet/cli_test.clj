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
