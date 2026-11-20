(ns fleet.publish-test
  "Publishing to the status branch: a local bare repo stands in for origin, and
  rebuild is a function (the in-process REBUILD)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.publish :as publish]))

(defn- git
  "Run git in dir, return trimmed stdout; throw on a non-zero exit."
  [dir & args]
  (let [{:keys [exit out err]} (apply publish/sh dir "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " err) {:exit exit})))
    (str/trim out)))

(defn- tmp-dir [] (str (fs/create-temp-dir)))

(defn- repo
  "A work clone with an initial commit on main, pushed to a fresh bare origin.
  Returns {:work :origin :out :root}."
  []
  (let [root   (tmp-dir)
        origin (str root "/origin.git")
        work   (str root "/work")]
    (git root "init" "-q" "--bare" origin)
    (git root "init" "-q" "-b" "main" work)
    (git work "config" "user.email" "t@example.com")
    (git work "config" "user.name" "t")
    (git work "remote" "add" "origin" origin)
    (spit (str work "/README") "x\n")
    (git work "add" "README")
    (git work "commit" "-q" "-m" "init")
    (git work "push" "-q" "origin" "main")
    {:root root :origin origin :work work :out (str root "/out")}))

(defn- write-json! [path m] (spit path (json/generate-string m)))

(defn- rebuild-at
  "A rebuild that writes the same content each time except generated_at (the
  given ISO instant) and canary_run."
  [generated-at]
  (fn [{:keys [out-dir]}]
    (fs/create-dirs out-dir)
    (write-json! (str out-dir "/status.json")
                 {:schema 1 :generated_at generated-at :canary_run (str "run-" (System/nanoTime))
                  :projects ["a"]})
    (write-json! (str out-dir "/history.json") {:schema 1 :adhoc []})))

(defn- rebuild-fixed
  "Same content each time, generated_at is the current instant (seconds
  apart, well inside the heartbeat)."
  [dirs]
  ((rebuild-at (str (java.time.Instant/now))) dirs))

(defn- pub [{:keys [work out]} rebuild]
  (publish/publish! {:repo-dir work :out-dir out :rebuild rebuild :sh publish/sh}))

(defn- remote-show [{:keys [origin]} f]
  (json/parse-string (git origin "show" (str "status:" f)) true))

(defn- remote-head [{:keys [origin]}] (git origin "rev-parse" "status"))

(deftest creates-the-branch-on-first-publish
  (let [r (repo)
        res (pub r rebuild-fixed)]
    (is (= :published (:result res)))
    (is (= "1" (git (:origin r) "rev-list" "--count" "status")))
    (is (= 1 (count (:projects (remote-show r "status.json")))))
    (is (= 1 (:schema (remote-show r "history.json"))))
    ;; orphan: no README from main
    (is (not (zero? (:exit (publish/sh (:origin r) "git" "show" "status:README")))))
    (is (= "github-actions[bot]" (git (:origin r) "log" "-1" "--format=%an" "status")))))

(deftest no-commit-when-only-generated-at-and-canary-run-changed
  (let [r (repo)
        _ (pub r rebuild-fixed)
        before (remote-head r)
        res (pub r rebuild-fixed)]
    (is (= :unchanged (:result res)))
    (is (= before (remote-head r)))))

(deftest r39-heartbeat-publishes-an-unchanged-status-once-it-is-6h-old
  (testing "5h59m59s later: still unchanged"
    (let [r (repo)
          _ (pub r (rebuild-at "2026-11-20T00:00:00Z"))
          before (remote-head r)
          res (pub r (rebuild-at "2026-11-20T05:59:59Z"))]
      (is (= :unchanged (:result res)))
      (is (= before (remote-head r)))))
  (testing "exactly 6h later: a commit that only moves generated_at"
    (let [r (repo)
          _ (pub r (rebuild-at "2026-11-20T00:00:00Z"))
          before (remote-head r)
          res (pub r (rebuild-at "2026-11-20T06:00:00Z"))]
      (is (= :published (:result res)))
      (is (not= before (remote-head r)))
      (is (= "2026-11-20T06:00:00Z" (:generated_at (remote-show r "status.json")))))))

(deftest commits-when-history-changes
  (let [r (repo)
        _ (pub r rebuild-fixed)
        before (remote-head r)
        res (pub r (fn [{:keys [out-dir]}]
                     (write-json! (str out-dir "/status.json") {:schema 1 :projects ["a"]})
                     (write-json! (str out-dir "/history.json") {:schema 1 :adhoc [1]})))]
    (is (= :published (:result res)))
    (is (not= before (remote-head r)))
    (is (= 1 (count (:adhoc (remote-show r "history.json")))))))

(defn- rival-push!
  "Clone the status branch, replace status.json with m, commit as someone
  else and push: a competing publish."
  [{:keys [origin root]} m msg]
  (let [c (str (tmp-dir) "/rival")]
    (git root "clone" "-q" "--branch" "status" origin c)
    (write-json! (str c "/status.json") m)
    (git c "-c" "user.email=r@example.com" "-c" "user.name=rival" "commit" "-q" "-am" msg)
    (git c "push" "-q" "origin" "status")))

(deftest retries-after-a-concurrent-push-and-keeps-both-results
  (let [r        (repo)
        _        (pub r rebuild-fixed)
        attempts (atom 0)
        ;; Adds its own project to whatever prev-dir holds. On the first
        ;; attempt a rival pushes "theirs" after the fetch, so our push is
        ;; rejected and attempt 2 must rebuild on top of it.
        rebuild  (fn [{:keys [prev-dir out-dir]}]
                   (when (= 1 (swap! attempts inc))
                     (rival-push! r {:schema 1 :projects ["a" "theirs"]} "rival"))
                   (let [prev (json/parse-string (slurp (str prev-dir "/status.json")) true)]
                     (write-json! (str out-dir "/status.json") (update prev :projects conj "ours"))
                     (spit (str out-dir "/history.json") (slurp (str prev-dir "/history.json")))))
        res      (pub r rebuild)]
    (is (= :published (:result res)))
    (is (= 2 (:attempt res)))
    (is (= 2 @attempts))
    (is (= ["a" "ours" "theirs"] (sort (:projects (remote-show r "status.json")))))))

(deftest gives-up-after-three-rejections
  (let [r        (repo)
        _        (pub r rebuild-fixed)
        attempts (atom 0)
        rebuild  (fn [{:keys [out-dir]}]
                   (let [n (swap! attempts inc)]
                     (rival-push! r {:schema 1 :projects [(str "rival" n)]} (str "rival " n))
                     (write-json! (str out-dir "/status.json") {:schema 1 :projects [(str "ours" n)]})
                     (write-json! (str out-dir "/history.json") {:schema 1})))
        e        (try (pub r rebuild) nil (catch Exception e e))]
    (is (some? e))
    (is (str/includes? (ex-message e) "gave up after 3"))
    (is (= 3 @attempts))))

(deftest fails-without-rebuild
  (let [r (repo)
        e (try (pub r nil) nil (catch Exception e e))]
    (is (some? e))
    (is (str/includes? (ex-message e) "rebuild"))))

(deftest leaves-no-worktree-behind
  (let [r (repo)]
    (pub r rebuild-fixed)
    (is (= 1 (count (str/split-lines (git (:work r) "worktree" "list")))))))

(deftest errors-carry-no-prefix-of-their-own
  ;; The CLI adds `publish-status:`; a second one in the message read as a stutter.
  (let [e (try (publish/publish! {:out-dir (str (fs/create-temp-dir))}) nil
               (catch Exception e e))]
    (is (= "rebuild is required" (ex-message e)))))
