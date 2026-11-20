(ns fleet.deps-test
  "The dependency graph. Repositories are GraphQL-shaped maps built inline,
  and the fetcher runs over a stub post fn, so nothing touches the network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.deps :as deps]))

(defn- sha [c] (apply str (repeat 40 c)))

(defn- repo
  "A repository node as the GraphQL query returns it."
  [org name & {:keys [deps tags head private archived fork]}]
  {:name name :url (str "https://github.com/" org "/" name)
   :isPrivate (boolean private) :isArchived (boolean archived) :isFork (boolean fork)
   :defaultBranchRef {:name "main" :target {:oid (or head (sha "f"))}}
   :deps (when deps {:text deps})
   :refs {:nodes (mapv (fn [[t s annotated?]]
                         {:name t :target (if annotated?
                                            {:oid (sha "9") :target {:oid s}}
                                            {:oid s})})
                       tags)}})

(def a-deps "{:deps {}}")
(def b-deps
  "{:deps {io.github.jlt-commons/a {:git/url \"https://github.com/jlt-commons/a.git\"
                                     :git/tag \"v0.1.0\" :git/sha \"1111111\"}}}")
(def c-deps
  ";; c pins b by sha only, and a by symbol under an alias
   {:deps {io.github.jlt-commons/b {:git/sha \"2222222222222222222222222222222222222222\"}}
    :aliases {:test {:extra-deps {io.github.jlt-commons/a {:git/tag \"v0.2.0\" :git/sha \"3333333\"}}}}}")
(def d-deps
  "{:deps {jolt-lang/time {:git/url \"git@github.com:jolt-lang/time\" :git/sha \"4444444444444444444444444444444444444444\"}
          org.clojure/data.json {:mvn/version \"2.5.0\"}
          local/thing {:local/root \"../thing\"}}}")

(def nodes
  {"jlt-commons" [(repo "jlt-commons" "a" :deps a-deps
                        :tags [["v0.2.0" (sha "3")] ["v0.1.0" (sha "1")] ["v1" (sha "3")]])
                  (repo "jlt-commons" "b" :deps b-deps :tags [["v0.3.0" (sha "2") true]])
                  (repo "jlt-commons" "c" :deps c-deps)
                  (repo "jlt-commons" "secret" :deps b-deps :private true)
                  (repo "jlt-commons" "old" :deps b-deps :archived true)
                  (repo "jlt-commons" "docs")]
   "jolt-lang"   [(repo "jolt-lang" "time" :deps "{:deps {}}" :head (sha "5"))
                  (repo "jolt-lang" "d" :deps d-deps)]})

(defn- graph [] (deps/build-graph (mapcat (fn [[org ns]] (map #(deps/repo-row org %) ns)) nodes)
                                  "2026-11-20T00:00:00Z"))

(defn- lib [g id] (first (filter #(= id (:id %)) (:libraries g))))

(defn- edge [g from to] (first (filter #(and (= from (:from %)) (= to (:to %))) (:edges g))))

(deftest semver-orders-numerically-and-ignores-moving-tags
  (is (= [0 10 0] (deps/semver "v0.10.0")))
  (is (= [0 6 0] (deps/semver "0.6.0")))
  (is (nil? (deps/semver "v1")))
  (is (nil? (deps/semver "vnightly")))
  (is (= "v0.10.0" (:name (deps/latest-tag [{:name "v0.9.0"} {:name "v0.10.0"} {:name "v1"}]))))
  (is (nil? (deps/latest-tag [{:name "v1"} {:name "nightly"}]))))

(deftest coordinates-resolve-by-url-then-by-symbol
  (is (= "jolt-lang/time" (deps/coord-repo 'x/y {:git/url "git@github.com:jolt-lang/time"})))
  (is (= "jlt-commons/a" (deps/coord-repo 'x/y {:git/url "https://github.com/jlt-commons/a.git/"})))
  (is (= "jlt-commons/b" (deps/coord-repo 'io.github.jlt-commons/b {:git/sha "x"})))
  (is (= "jolt-lang/time" (deps/coord-repo 'com.github.jolt-lang/time {:git/tag "v1"})))
  (is (nil? (deps/coord-repo 'org.clojure/data.json {:mvn/version "2.5.0"})))
  (is (nil? (deps/coord-repo 'local/thing {:local/root "../thing"}))))

(deftest public-active-repositories-become-libraries
  (let [g (graph)
        ids (set (map :id (:libraries g)))]
    (is (= 1 (:schema g)))
    (is (= #{"jlt-commons/a" "jlt-commons/b" "jlt-commons/c" "jlt-commons/docs"
             "jolt-lang/time" "jolt-lang/d"} ids)
        "private and archived repositories are left out")
    (testing "the latest release is the highest semver tag, annotated tags peeled"
      (is (= {:tag "v0.2.0" :sha (sha "3")} (:latest (lib g "jlt-commons/a"))))
      (is (= {:tag "v0.3.0" :sha (sha "2")} (:latest (lib g "jlt-commons/b")))))
    (testing "an untagged repository's latest is its default branch head"
      (is (= {:tag nil :sha (sha "5")} (:latest (lib g "jolt-lang/time")))))
    (is (false? (:has_deps (lib g "jlt-commons/docs"))))
    (testing "an updater can clone each library and knows its branch"
      (is (= "https://github.com/jlt-commons/a.git" (:git_url (lib g "jlt-commons/a"))))
      (is (= "main" (:default_branch (lib g "jlt-commons/a")))))))

(deftest edges-point-from-dependency-to-dependent-with-pin-state
  (let [g (graph)]
    (testing "a tag older than the latest is behind"
      (let [e (edge g "jlt-commons/a" "jlt-commons/b")]
        (is (= "behind" (:state e)))
        (is (= [{:tag "v0.1.0" :sha "1111111" :ref nil :resolved "v0.1.0" :state "behind"
                 :dep "io.github.jlt-commons/a" :path ["deps"]}]
               (:pins e)))))
    (testing "a sha matching the latest tag's commit is current"
      (is (= "current" (:state (edge g "jlt-commons/b" "jlt-commons/c"))))
      (is (= "v0.3.0" (-> (edge g "jlt-commons/b" "jlt-commons/c") :pins first :resolved))))
    (testing "an alias pin counts, matched by symbol, and says where it sits"
      (is (= "current" (:state (edge g "jlt-commons/a" "jlt-commons/c"))))
      (is (= ["aliases" "test" "extra-deps"] (-> (edge g "jlt-commons/a" "jlt-commons/c") :pins first :path))))
    (testing "an untagged dependency pinned off its head is behind"
      (is (= "behind" (:state (edge g "jolt-lang/time" "jolt-lang/d")))))
    (is (= 4 (count (:edges g))) "maven, local and out-of-org coordinates are not edges")
    (is (nil? (edge g "jlt-commons/b" "jlt-commons/secret")))))

(deftest a-sha-between-tags-is-judged-by-comparing-with-the-latest-tag
  (let [pin  (fn [s] (str "{:deps {io.github.o/a {:git/sha \"" s "\"}}}"))
        rows [(deps/repo-row "o" (repo "o" "a" :deps "{}" :tags [["v1.0.0" (sha "1")]]))
              (deps/repo-row "o" (repo "o" "ahead" :deps (pin (sha "7"))))
              (deps/repo-row "o" (repo "o" "old" :deps (pin (sha "6"))))
              (deps/repo-row "o" (repo "o" "lost" :deps (pin (sha "8"))))]
        asked (atom [])
        compare (fn [repo base head]
                  (swap! asked conj [repo base head])
                  ({(sha "7") "ahead" (sha "6") "behind"} head))
        g (deps/build-graph rows "t" {:compare compare})]
    (is (= "current" (:state (edge g "o/a" "o/ahead"))) "a commit after the latest tag contains it")
    (is (= "behind" (:state (edge g "o/a" "o/old"))))
    (is (= "unknown" (:state (edge g "o/a" "o/lost"))) "a failed comparison says nothing")
    (is (every? #(= ["o/a" "v1.0.0"] (subvec % 0 2)) @asked))
    (testing "without a compare fn the pin is unknown, not behind"
      (is (= "unknown" (:state (edge (deps/build-graph rows "t") "o/a" "o/ahead")))))))

(deftest unreadable-deps-edn-is-flagged-not-fatal
  (let [g (deps/build-graph [(deps/repo-row "o" (repo "o" "x" :deps "{:deps {"))] "t")]
    (is (true? (:unreadable (lib g "o/x"))))
    (is (empty? (:edges g)))))

(def chain
  "a <- b <- c, a <- c, a <- e, and d off on its own; x <-> y is a cycle."
  {:edges [{:from "a" :to "b"} {:from "b" :to "c"} {:from "a" :to "c"} {:from "a" :to "e"}
           {:from "x" :to "y"} {:from "y" :to "x"}]})

(deftest downstream-is-transitive
  (is (= #{"b" "c" "e"} (deps/downstream chain "a")))
  (is (= #{"c"} (deps/downstream chain "b")))
  (is (= #{} (deps/downstream chain "c")))
  (is (= #{"a" "b"} (deps/upstream chain "c"))))

(deftest update-waves-retag-each-library-after-everything-it-pins
  (testing "c pins both a and b, so it waits for b's new tag"
    (is (= {:waves [["b" "e"] ["c"]] :cycle []} (deps/update-plan chain "a"))))
  (is (= {:waves [["c"]] :cycle []} (deps/update-plan chain "b")))
  (is (= {:waves [] :cycle []} (deps/update-plan chain "c")))
  (testing "a cycle cannot be ordered and is reported instead of looping"
    (is (= {:waves [] :cycle ["x" "y"]} (deps/update-plan chain "x")))))

(deftest every-library-carries-its-plan-and-upstream
  (let [g (graph)
        a (lib g "jlt-commons/a")
        c (lib g "jlt-commons/c")]
    (is (= [["jlt-commons/b"] ["jlt-commons/c"]] (:waves a)))
    (is (= ["jlt-commons/a" "jlt-commons/b"] (:upstream c)))
    (is (= [] (:cycle a)))))

(defn- page [names cursor next]
  {:data {:organization {:repositories {:pageInfo {:hasNextPage (some? next) :endCursor next}
                                        :nodes (mapv #(repo "o" %) names)}}}})

(deftest fetch-pages-through-an-org-and-retries-a-bad-gateway
  (let [calls (atom [])
        fails (atom 1)
        post (fn [_path body]
               (swap! calls conj (get-in body [:variables :after]))
               (cond
                 (and (= 1 (count @calls)) (pos? @fails))
                 (do (swap! fails dec) (throw (ex-info "GitHub API 502" {:status 502})))
                 (nil? (get-in body [:variables :after])) (page ["a" "b"] nil "c1")
                 :else (page ["c"] "c1" nil)))
        rows (deps/fetch-org {:post-json post} "o" {:sleep (fn [_])})]
    (is (= ["a" "b" "c"] (map :name rows)))
    (is (= [nil nil "c1"] @calls))))

(deftest fetch-throws-on-graphql-errors
  (let [post (fn [_ _] {:errors [{:message "Could not resolve to an Organization"}]})
        e (try (deps/fetch-org {:post-json post} "nope" {:sleep (fn [_])}) nil
               (catch Exception e e))]
    (is (str/includes? (ex-message e) "Could not resolve"))))
