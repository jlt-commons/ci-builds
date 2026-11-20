(ns fleet.refresh-test
  "Ports test/refresh-status.bats. The collectors are thunks returning the
  status fixtures, so nothing touches the network."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [fleet.cli :as cli]
            [fleet.publish :as publish]
            [fleet.refresh :as refresh])
  (:import (java.io StringWriter)))

(def ^:private fx "test/fixtures/status/")

(defn- fixture [name] (json/parse-string (slurp (str fx name)) true))

(defn- tmp-dir [] (str (fs/create-temp-dir)))

(defn- opts [out & more]
  (apply assoc {:out-dir      out
                :latest       "0.8.27"
                :nightly      nil
                :canary-run   "https://example/run/1"
                :results-files [(str fx "results-latest.json")]
                :own-pin      #(vec (fixture "own-pin.json"))
                :uncovered    #(vec (fixture "uncovered.json"))
                :now          "2026-11-20T02:00:00Z"}
         more))

(deftest refresh-writes-status-and-history-from-collectors-and-results
  (let [out (str (tmp-dir) "/out")]
    (refresh/refresh! (opts out))
    (let [s (json/parse-string (slurp (str out "/status.json")) true)
          h (json/parse-string (slurp (str out "/history.json")) true)]
      (is (= 1 (:schema s)))
      (is (= ["ebb" "glitter" "raygui-jlt"] (sort (map :name (:projects s)))))
      (is (= 1 (:schema h))))))

(deftest refresh-needs-latest
  (let [out (str (tmp-dir) "/out")
        err (StringWriter.)
        rc  (binding [*err* err]
              (cli/refresh-main [out] {"NIGHTLY_JSON" "null" "CANARY_RUN" "x"} {}))]
    (is (= 2 rc))
    (is (re-find #"LATEST" (str err)))
    (is (not (fs/exists? out)))))

(deftest two-publishes-differing-only-in-clock-and-canary-run-make-one-commit
  (let [root   (tmp-dir)
        origin (str root "/origin.git")
        work   (str root "/work")
        g      (fn [dir & args] (apply publish/sh dir "git" args))
        nightly {:published_at "2026-11-20T01:00:00Z" :sha "f4e82fc8eac874cae1658b7976518253027c4f6a"
                 :note "nightly published 2026-11-20T01:00:00Z at f4e82fc8"}
        run    (fn [n]
                 (publish/publish!
                  {:repo-dir work :out-dir (str root "/out" n) :sh publish/sh
                   :rebuild (fn [{:keys [prev-dir out-dir]}]
                              (refresh/refresh! (opts out-dir
                                                      :prev-dir prev-dir
                                                      :nightly nightly
                                                      :canary-run (str "https://example/run/" n)
                                                      :now (str "2026-11-20T02:00:0" n "Z"))))}))]
    (g root "init" "-q" "--bare" origin)
    (g root "init" "-q" "-b" "main" work)
    (g work "config" "user.email" "t@example.com")
    (g work "config" "user.name" "t")
    (g work "remote" "add" "origin" origin)
    (spit (str work "/README") "x\n")
    (g work "add" "README")
    (g work "commit" "-q" "-m" "init")
    (g work "push" "-q" "origin" "main")
    (is (= :published (:result (run 1))))
    (is (= :unchanged (:result (run 2))))
    (is (= "1\n" (:out (g origin "rev-list" "--count" "status"))))))
