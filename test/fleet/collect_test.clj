(ns fleet.collect-test
  "The collectors. gh is a map of stub fns over a data 'world' read
  from test/fixtures/gh, so no network is touched."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.collect :as collect])
  (:import (java.io StringWriter)))

(def ^:private dir "test/fixtures/gh/")

(defn- fx [name] (slurp (str dir name)))

(defn- not-found [path] (ex-info (str "GitHub API 404 for " path) {:status 404 :path path}))

(defn- cli->rest
  "A gh-CLI-shaped runs fixture as the REST workflow runs response."
  [runs]
  {:workflow_runs (mapv (fn [r] {:conclusion (:conclusion r) :html_url (:url r)
                                 :updated_at (:updatedAt r) :id (:databaseId r)})
                        runs)})

(defn- world
  "Fixture-backed state: :branch, :runs (CLI shape), :jobs, :ci (text, or an
  exception to throw) per repo name, and the org :repos."
  []
  (let [names  (->> (json/parse-string (fx "org-repos.json") true) (map :name))
        names  (concat names ["glitter-gl" "glitter-uikit"])
        exists (fn [f] (.exists (java.io.File. (str dir f))))
        pick   (fn [suffix read] (into {} (for [n (distinct names) :when (exists (str n suffix))]
                                            [n (read (fx (str n suffix)))])))]
    {:repos  (json/parse-string (fx "org-repos.json") true)
     :branch (pick ".branch" str/trim)
     :runs   (pick ".runs.json" #(json/parse-string % true))
     :jobs   (pick ".jobs.json" #(json/parse-string % true))
     :ci     (assoc (pick ".ci.yml" identity)
                    "ratelimited" (ex-info "GitHub API 403 for ratelimited"
                                           {:status 403 :body "rate limit exceeded, see docs (404 is not this)"})
                    "brokenrepo" (ex-info "GitHub API 500" {:status 500}))}))

(defn- stub-gh [{:keys [repos branch runs jobs ci]}]
  (letfn [(repo-of [path] (second (re-find #"^/?repos/jlt-commons/([^/?]+)" path)))]
    {:get-json
     (fn [path]
       (let [p (repo-of path)]
         (cond
           (re-find #"/actions/workflows/ci\.yml/runs\?" path)
           (let [b (second (re-find #"branch=([^&]+)" path))]
             (if (and (contains? runs p) (= b (get branch p)))
               (cli->rest (get runs p))
               (throw (not-found path))))

           (re-find #"/actions/runs/\d+/jobs" path)
           (or (get jobs p) (throw (not-found path)))

           (contains? branch p) {:default_branch (get branch p)}
           :else (throw (not-found path)))))
     :get-raw
     (fn [path]
       (let [v (get ci (repo-of path))]
         (cond (instance? Throwable v) (throw v)
               (string? v) v
               :else (throw (not-found path)))))
     :get-paginated
     (fn [path]
       (if (re-find #"^orgs/jlt-commons/repos\?type=public" path)
         (vec repos)
         (throw (not-found path))))}))

(def ^:private fleet
  {:projects ["glitter" "glitter-gl" "glitter-uikit" "ghost"]
   :excluded {:jolt-lsp "releases jolt-lsp" :secretrepo "private"}})

(defn- warnings-of
  "Run f with stderr captured; [result stderr-text]."
  [f]
  (let [w (StringWriter.)]
    (binding [*err* w] [(f) (str w)])))

(defn- own* [w & [fl]] (collect/own-pin (stub-gh w) (or fl fleet)))
(defn- unc* [w & [fl]] (collect/uncovered (stub-gh w) (or fl fleet)))
(defn- own [w & [fl]] (first (warnings-of #(own* w fl))))
(defn- unc [w & [fl]] (first (warnings-of #(unc* w fl))))
(defn- by-name [rows n] (first (filter #(= n (:name %)) rows)))

(deftest own-pin-one-entry-per-fleet-project
  (let [rows (own (world))]
    (is (= ["glitter" "glitter-gl" "glitter-uikit" "ghost"] (mapv :name rows)))))

(deftest own-pin-pass-run-with-literal-pin
  (is (= {:name "glitter" :pin nil :result "pass"
          :run_url "https://github.com/jlt-commons/glitter/actions/runs/1"
          :finished_at "2026-11-19T10:00:00Z" :failed_step nil}
         (by-name (own (world)) "glitter"))))

(deftest own-pin-default-branch-used-failed-step-named-pin-parsed
  (let [row (by-name (own (world)) "glitter-gl")]
    (is (= "0.8.24" (:pin row)))
    (is (= "fail" (:result row)))
    (is (= "Run tests" (:failed_step row)))))

(deftest own-pin-no-push-run-is-not-run-and-non-literal-pin-is-null
  (is (= {:name "glitter-uikit" :pin nil :result "not-run"
          :run_url nil :finished_at nil :failed_step nil}
         (by-name (own (world)) "glitter-uikit"))))

(deftest own-pin-failing-api-is-not-run-warns-and-does-not-abort
  (let [[rows err] (warnings-of #(own* (world)))]
    (is (= {:name "ghost" :pin nil :result "not-run"
            :run_url nil :finished_at nil :failed_step nil :unreadable true}
           (by-name rows "ghost")))
    (is (str/includes? err "ghost"))
    (is (= 4 (count rows)))))

(deftest r38-own-pin-marks-only-reads-that-failed-as-unreadable
  (testing "a project with no completed push run is a real not-run"
    (is (not (:unreadable (by-name (own (world)) "glitter-uikit")))))
  (testing "a failing run list is unreadable and keeps the pin it did read"
    (let [w (-> (world)
                (assoc-in [:branch "zz"] "main")
                (assoc-in [:ci "zz"] "name: ci\nenv:\n  JOLT_VERSION: \"0.8.24\"\n"))
          row (by-name (own w {:projects ["zz"] :excluded {}}) "zz")]
      (is (true? (:unreadable row)))
      (is (= "not-run" (:result row)))))
  (testing "a readable row carries no marker"
    (is (not (contains? (by-name (own (world)) "glitter") :unreadable)))))

(deftest own-pin-conclusion-mapping
  (doseq [[conclusion result] [["success" "pass"] ["failure" "fail"] ["timed_out" "timed-out"]
                               ["cancelled" "not-run"] ["skipped" "not-run"]
                               ["neutral" "not-run"] [nil "not-run"]]]
    (let [w (-> (world)
                (assoc-in [:branch "zz"] "main")
                (assoc-in [:runs "zz"] [{:conclusion conclusion :url "u" :updatedAt "t" :databaseId 1}])
                (assoc-in [:jobs "zz"] {:jobs []}))
          row (by-name (own w {:projects ["zz"] :excluded {}}) "zz")]
      (testing (str conclusion " -> " result)
        (is (= result (:result row)))))))

(deftest own-pin-transport-failure-listing-runs-keeps-the-pin
  ;; Replaces "GH may be several words", which has no jolt counterpart: gh is
  ;; a map of fns here. A transport error has no :status and must degrade the
  ;; same way an HTTP error does.
  (let [gh   (assoc (stub-gh (world)) :get-json
                    (fn [path]
                      (cond (re-find #"/actions/workflows/" path) (throw (ex-info "connection reset" {}))
                            (str/ends-with? path "/glitter-gl") {:default_branch "trunk"}
                            :else (throw (not-found path)))))
        [rows err] (warnings-of #(collect/own-pin gh {:projects ["glitter-gl"] :excluded {}}))]
    (is (= [{:name "glitter-gl" :pin "0.8.24" :result "not-run"
             :run_url nil :finished_at nil :failed_step nil :unreadable true}]
           rows))
    (is (str/includes? err "glitter-gl"))))

(deftest uncovered-reasons-per-repo-fleet-members-and-archived-private-skipped
  (let [rows (unc (world))
        r    (fn [n] (:reason (by-name rows n)))]
    (is (= "excluded: releases jolt-lsp" (r "jolt-lsp")))
    (is (= "no ci.yml" (r "nociyml")))
    (is (= "ci.yml has no jolt-version input" (r "noinput")))
    (is (= "ready, not yet in fleet.json" (r "readyrepo")))
    (is (nil? (by-name rows "glitter")))
    (is (nil? (by-name rows "oldrepo")))
    (is (nil? (by-name rows "secretrepo")))))

(deftest uncovered-unreadable-repo-gets-a-reason-and-a-warning
  (let [[rows err] (warnings-of #(unc* (world)))]
    (is (= "could not read repository" (:reason (by-name rows "brokenrepo"))))
    (is (true? (:unreadable (by-name rows "brokenrepo"))))
    (is (true? (:unreadable (by-name rows "ratelimited"))))
    (testing "an unparsable ci.yml is a content problem, not an outage"
      (is (not (:unreadable (by-name rows "badyaml")))))
    (is (str/includes? err "brokenrepo"))))

(deftest uncovered-on-key-parsed-as-boolean-true-still-finds-the-input
  (is (= "ready, not yet in fleet.json"
         (:reason (by-name (unc (world) {:projects [] :excluded {}}) "glitter")))))

(deftest uncovered-403-containing-404-is-not-a-missing-ci-yml
  (let [[rows err] (warnings-of #(unc* (world)))]
    (is (= "could not read repository" (:reason (by-name rows "ratelimited"))))
    (is (str/includes? err "ratelimited"))))

(deftest uncovered-unparsable-ci-yml-is-could-not-read-with-a-warning
  (let [[rows err] (warnings-of #(unc* (world)))]
    (is (= "could not read repository" (:reason (by-name rows "badyaml"))))
    (is (str/includes? err "badyaml"))))

(deftest uncovered-transport-failure-is-could-not-read-not-no-ci-yml
  ;; jolt parses YAML in-process, so there is no external tool to miss. The
  ;; risk is a transport error (no :status) being mistaken for an explicit 404.
  (let [w            (assoc-in (world) [:ci "readyrepo"] (ex-info "connection refused" {}))
        [rows _err]  (warnings-of #(unc* w))]
    (is (= "could not read repository" (:reason (by-name rows "readyrepo"))))))
