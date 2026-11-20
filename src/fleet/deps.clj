(ns fleet.deps
  "The library dependency graph across the jlt-commons and jolt-lang
  organizations. One GraphQL query per page of an organization's repositories
  returns each repository's root deps.edn, its tags and its default branch
  head. Every git coordinate in a deps.edn that names a repository in one of
  the organizations becomes an edge from the dependency to the dependent.

  The point is the release order. When a library gets a new tag, everything
  downstream of it has to move its pin and retag, and a library that pins two
  of them waits for the later one. update-plan gives those waves.

  Coordinates resolve by :git/url, then by an io.github.OWNER/NAME or
  com.github.OWNER/NAME symbol. :local/root (dev overrides, subprojects inside
  one repository) and :mvn/version are not edges. Only the root deps.edn of a
  repository is read."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def orgs ["jlt-commons" "jolt-lang"])

(def ^:private coord-keys #{:git/url :git/tag :git/sha :git/ref :local/root :mvn/version})

(defn- warn [& msg] (binding [*out* *err*] (println (str "deps: " (str/join " " msg)))))

;;; ------------------------------------------------------------- versions

(defn semver
  "[major minor patch] for a tag like v1.2.3 or 1.2.3, or nil. Moving tags
  such as v1 or vnightly are not releases."
  [tag]
  (when-let [[_ a b c] (re-matches #"v?(\d+)\.(\d+)\.(\d+)" (str tag))]
    [(parse-long a) (parse-long b) (parse-long c)]))

(defn latest-tag
  "The tag map with the highest semver :name, or nil."
  [tags]
  (->> tags (filter (comp semver :name)) (sort-by (comp semver :name)) last))

;;; ---------------------------------------------------------- coordinates

(defn read-deps
  "Parse deps.edn text, or nil. EDN first; some deps.edn carry a regex
  literal, which only the Clojure reader takes."
  [text]
  (try
    (edn/read-string {:default (fn [_tag v] v)} text)
    (catch Exception _
      (try (binding [*read-eval* false] (read-string text))
           (catch Exception _ nil)))))

(defn- coord? [v] (and (map? v) (some #(contains? v %) coord-keys)))

(defn dep-coords
  "Every [dep-symbol coord-map path] triple anywhere in a parsed deps.edn
  form, so :deps, :extra-deps, :override-deps and aliases all count. path is
  the keys leading to the map that holds the coordinate, as strings without
  the colon, e.g. [\"aliases\" \"test\" \"extra-deps\"]."
  [form]
  (letfn [(kname [k] (if (keyword? k) (subs (str k) 1) (str k)))
          (walk [x path acc]
            (cond
              (map? x)  (reduce-kv (fn [a k v]
                                     (if (coord? v)
                                       (conj a [k v path])
                                       (walk v (conj path (kname k)) a)))
                                   acc x)
              (coll? x) (reduce (fn [a v] (walk v path a)) acc x)
              :else     acc))]
    (walk form [] [])))

(defn- url-repo
  "owner/name from a GitHub url in https, ssh or scp form."
  [url]
  (when-let [[_ owner name] (re-find #"github\.com[/:]([^/]+)/([^/]+?)(?:\.git)?/*$" (str/trim (str url)))]
    (str owner "/" name)))

(defn- symbol-repo [k]
  (when-let [[_ owner name] (re-matches #"(?:io|com)\.github\.([^/]+)/(.+)" (str k))]
    (str owner "/" name)))

(defn coord-repo
  "The owner/name repository a coordinate pins, or nil when it is not a git pin."
  [k coord]
  (when-not (or (:local/root coord) (:mvn/version coord))
    (or (url-repo (:git/url coord)) (symbol-repo k))))

;;; ---------------------------------------------------------------- graph

(defn repo-row
  "A repository node from the GraphQL query, flattened."
  [org node]
  {:org       org
   :name      (:name node)
   :url       (:url node)
   :private   (boolean (:isPrivate node))
   :archived  (boolean (:isArchived node))
   :head      (get-in node [:defaultBranchRef :target :oid])
   :branch    (get-in node [:defaultBranchRef :name])
   :deps-text (get-in node [:deps :text])
   :tags      (mapv (fn [r] {:name (:name r)
                             :sha  (or (get-in r [:target :target :oid]) (get-in r [:target :oid]))})
                    (get-in node [:refs :nodes]))})

(defn- sha-match? [full pin]
  (and (seq full) (seq pin) (str/starts-with? (str full) (str pin))))

(defn- pin-state
  "Judge one pin against the library it pins. current: the latest tag, its
  commit, the default branch head, or a commit that contains the latest tag.
  behind: an older tag, or a commit the latest tag is not in (for an untagged
  library, anything but the head). unknown: a :git/ref, a tag the library does
  not have, or a commit that could not be compared.

  compare is a fn of [repo-id base head] returning GitHub's compare status
  (\"ahead\", \"behind\", \"identical\", \"diverged\") or nil. It is called
  only for a bare sha on a tagged library that matches no tag and not the head."
  [lib {tag :git/tag sha :git/sha ref :git/ref} compare]
  (let [latest   (:latest lib)
        by-sha   (when sha (some #(when (sha-match? (:sha %) sha) (:name %)) (:tags lib)))
        resolved (or (when (some #(= tag (:name %)) (:tags lib)) tag) by-sha)
        bare     (and sha (nil? tag))
        state    (cond
                   ref                                           "unknown"
                   tag (cond (= tag (:tag latest)) "current"
                             resolved              "behind"
                             :else                 "unknown")
                   (not bare)                                    "unknown"
                   (sha-match? (:sha latest) sha)                "current"
                   (sha-match? (:head lib) sha)                  "current"
                   (nil? (:tag latest))                          "behind"
                   by-sha                                        "behind"
                   :else (case (when compare (compare (:id lib) (:tag latest) sha))
                           ("ahead" "identical") "current"
                           ("behind" "diverged") "behind"
                           "unknown"))]
    {:tag tag :sha sha :ref ref :resolved resolved :state state}))

(defn- worst [states]
  (cond (some #{"behind"} states) "behind"
        (some #{"current"} states) "current"
        :else "unknown"))

(defn- reachable
  "Every node reachable from start along next-fn, start excluded unless a
  cycle returns to it."
  [next-fn start]
  (loop [seen #{} todo (vec (next-fn start))]
    (if-let [n (peek todo)]
      (if (seen n)
        (recur seen (pop todo))
        (recur (conj seen n) (into (pop todo) (next-fn n))))
      seen)))

(defn- index [edges k v] (reduce (fn [m e] (update m (k e) (fnil conj []) (v e))) {} edges))

(defn downstream
  "Every library that transitively depends on id."
  [{:keys [edges]} id]
  (disj (reachable (index edges :from :to) id) id))

(defn upstream
  "Every library id transitively depends on."
  [{:keys [edges]} id]
  (disj (reachable (index edges :to :from) id) id))

(defn update-plan
  "The order to retag everything downstream of id after id gets a new tag.
  Each wave holds libraries whose in-graph dependencies are all in earlier
  waves (or are id itself), so a wave can be retagged in parallel once the
  previous one is tagged. Libraries in a dependency cycle with id cannot be
  ordered; they are listed under :cycle with id, and left out of the waves."
  [g id]
  (let [down  (downstream g id)
        loopy (if (contains? (reachable (index (:edges g) :from :to) id) id)
                (conj (into #{} (filter #(contains? (upstream g id) %)) down) id)
                #{})
        nodes (apply disj down loopy)
        preds (index (filter #(and (nodes (:to %)) (nodes (:from %))) (:edges g)) :to :from)]
    (loop [placed #{} left nodes waves []]
      (let [ready (sort (filter #(every? placed (preds %)) left))]
        (if (empty? ready)
          {:waves waves :cycle (vec (sort (into loopy left)))}
          (recur (into placed ready) (apply disj left ready) (conj waves (vec ready))))))))

(defn build-graph
  "{:schema 1 :generated_at now :orgs [..] :libraries [..] :edges [..]} from
  repo-row maps. Private and archived repositories are left out, as is
  anything they pin. Each library carries its :upstream ids and the :waves
  and :cycle of its update-plan. opts: :compare, see pin-state."
  ([rows now] (build-graph rows now {}))
  ([rows now {:keys [compare]}]
  (let [rows  (remove #(or (:private %) (:archived %)) rows)
        libs  (mapv (fn [r]
                      (let [form (when (:deps-text r) (read-deps (:deps-text r)))
                            lt   (latest-tag (:tags r))]
                        (when (and (:deps-text r) (nil? form))
                          (warn (str (:org r) "/" (:name r)) "deps.edn does not parse, no edges read"))
                        {:id         (str (:org r) "/" (:name r))
                         :org        (:org r)
                         :name       (:name r)
                         :url        (:url r)
                         :git_url    (str "https://github.com/" (:org r) "/" (:name r) ".git")
                         :default_branch (:branch r)
                         :latest     {:tag (:name lt) :sha (or (:sha lt) (:head r))}
                         :head       (:head r)
                         :tags       (:tags r)
                         :has_deps   (some? (:deps-text r))
                         :unreadable (boolean (and (:deps-text r) (nil? form)))
                         :coords     (when (map? form) (dep-coords form))}))
                    rows)
        by-id (into {} (map (juxt (comp str/lower-case :id) identity)) libs)
        edges (->> libs
                   (mapcat (fn [dependent]
                             (for [[k coord path] (:coords dependent)
                                   :let [dep (some-> (coord-repo k coord) str/lower-case by-id)]
                                   :when (and dep (not= (:id dep) (:id dependent)))]
                               [[(:id dep) (:id dependent)]
                                (assoc (pin-state dep coord compare) :dep (str k) :path path)])))
                   (reduce (fn [m [k pin]] (update m k (fnil conj []) pin)) {})
                   (map (fn [[[from to] pins]]
                          {:from from :to to
                           :pins (vec (distinct pins))
                           :dep (:dep (first pins))
                           :state (worst (map :state pins))}))
                   (sort-by (juxt :from :to))
                   vec)
        g     {:edges edges}]
    {:schema       1
     :generated_at now
     :orgs         orgs
     :libraries    (->> libs
                        (mapv (fn [l]
                                (let [{:keys [waves cycle]} (update-plan g (:id l))]
                                  (-> l
                                      (dissoc :coords :tags :head)
                                      (assoc :upstream (vec (sort (upstream g (:id l))))
                                             :waves waves
                                             :cycle cycle)))))
                        (sort-by :id)
                        vec)
     :edges        edges})))

;;; ---------------------------------------------------------------- fetch

(def query
  "One page of an organization's repositories with their root deps.edn, up to
  100 tags newest first, and the default branch head. Pages of 25: at 50,
  GitHub answered 502 for jlt-commons."
  "query($org: String!, $after: String) {
  organization(login: $org) {
    repositories(first: 25, after: $after, orderBy: {field: NAME, direction: ASC}) {
      pageInfo { hasNextPage endCursor }
      nodes {
        name url isArchived isFork isPrivate
        defaultBranchRef { name target { oid } }
        deps: object(expression: \"HEAD:deps.edn\") { ... on Blob { text } }
        refs(refPrefix: \"refs/tags/\", first: 100, orderBy: {field: TAG_COMMIT_DATE, direction: DESC}) {
          nodes { name target { oid ... on Tag { target { oid } } } }
        }
      }
    }
  }
}")

(def ^:private attempts 4)

(defn- post-retrying
  "post-json, retrying a 502/503/504 or a status-less failure (a dropped
  connection) with a growing pause. GraphQL errors throw."
  [gh body sleep]
  (loop [n 1]
    (let [r (try {:ok ((:post-json gh) "graphql" body)}
                 (catch Exception e
                   (let [s (:status (ex-data e))]
                     (if (and (< n attempts) (or (nil? s) (#{502 503 504} s)))
                       {:retry e}
                       (throw e)))))]
      (if-let [e (:retry r)]
        (do (warn "retrying after" (ex-message e))
            (sleep (* 2000 n))
            (recur (inc n)))
        (let [resp (:ok r)]
          (when (seq (:errors resp))
            (let [msg (str/join "; " (map :message (:errors resp)))]
              (if (get-in resp [:data :organization])
                (warn "partial GraphQL errors:" msg)
                (throw (ex-info (str "GraphQL: " msg) {:errors (:errors resp)})))))
          resp)))))

(defn fetch-org
  "Every repository node of org. gh needs :post-json; opts :sleep (ms fn)."
  ([gh org] (fetch-org gh org {}))
  ([gh org {:keys [sleep] :or {sleep #(Thread/sleep %)}}]
   (loop [after nil acc []]
     (let [resp  (post-retrying gh {:query query :variables {:org org :after after}} sleep)
           repos (get-in resp [:data :organization :repositories])
           acc   (into acc (:nodes repos))]
       (if (get-in repos [:pageInfo :hasNextPage])
         (recur (get-in repos [:pageInfo :endCursor]) acc)
         acc)))))

(defn github-compare
  "A compare fn for build-graph over gh's :get-json. A failure warns and
  returns nil, which leaves the pin unknown."
  [gh]
  (fn [repo base head]
    (try (:status ((:get-json gh) (str "repos/" repo "/compare/" base "..." head)))
         (catch Exception e
           (warn "could not compare" repo (str base "..." head) (ex-message e))
           nil))))

(defn collect
  "The graph for every organization in orgs, fetched through gh."
  [gh now]
  (build-graph (mapcat (fn [org] (map #(repo-row org %) (fetch-org gh org))) orgs)
               now
               {:compare (github-compare gh)}))
