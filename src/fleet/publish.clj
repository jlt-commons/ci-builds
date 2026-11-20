(ns fleet.publish
  "Commit data files to the orphan `status` branch of origin (R11-R13):
  status.json and history.json by default, or any other set, such as
  deps.json, which leaves the files it does not name alone. Git runs through an injected sh fn, so tests can point
  it at a local bare repository."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [fleet.status :as status]))

(def ^:private attempts 3)

(def ^:private bot ["-c" "user.name=github-actions[bot]"
                    "-c" "user.email=41898282+github-actions[bot]@users.noreply.github.com"])

(defn sh
  "Run a command in dir. Returns {:exit :out :err}; never throws on a
  non-zero exit."
  [dir & args]
  (let [{:keys [exit out err]} (apply p/sh {:dir dir} args)]
    {:exit exit :out out :err err}))

(defn- git!
  "git in dir via sh; throws ex-info on a non-zero exit."
  [sh dir & args]
  (let [{:keys [exit err] :as r} (apply sh dir "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim (str err)))
                      {:exit exit :args args})))
    r))

(defn- warn [& msg] (binding [*out* *err*] (println (str "publish-status: " (str/join " " msg)))))

(defn- read-json [path] (json/parse-string (slurp path) true))

(def ^:private status-files ["status.json" "history.json"])

(defn- open-worktree!
  "Add a detached worktree at wt on the tip of origin's status branch, or on
  a fresh orphan when the branch does not exist yet. Copies the previous
  copies of files (when present) into prev."
  [sh repo-dir wt prev files]
  (let [rc (:exit (sh repo-dir "git" "ls-remote" "--exit-code" "--heads" "origin" "status"))]
    (case rc
      0 (do (git! sh repo-dir "fetch" "--quiet" "origin" "+refs/heads/status:refs/remotes/origin/status")
            (git! sh repo-dir "worktree" "add" "--quiet" "--detach" wt "refs/remotes/origin/status")
            (doseq [f files
                    :when (fs/exists? (str wt "/" f))]
              (fs/copy (str wt "/" f) (str prev "/" f))))
      2 (do (git! sh repo-dir "worktree" "add" "--quiet" "--detach" wt "HEAD")
            (git! sh wt "checkout" "--quiet" "--orphan" "status")
            (git! sh wt "rm" "-rfq" "."))
      (throw (ex-info (str "cannot reach origin (ls-remote exit " rc ")")
                      {:exit rc})))))

(defn- status-unchanged?
  "R12: history.json byte-identical, status.json equal apart from
  generated_at and canary_run."
  [out wt]
  (and (fs/exists? (str wt "/status.json"))
       (fs/exists? (str wt "/history.json"))
       (= (slurp (str wt "/history.json")) (slurp (str out "/history.json")))
       (status/unchanged? (read-json (str wt "/status.json"))
                          (read-json (str out "/status.json")))))

(defn- unchanged?
  "The status files use status-unchanged?. Any other set is unchanged when
  every file is already on the branch and (same? old new) holds for each,
  both parsed as JSON."
  [same? files out wt]
  (if same?
    (every? (fn [f] (and (fs/exists? (str wt "/" f))
                         (same? (read-json (str wt "/" f)) (read-json (str out "/" f)))))
            files)
    (status-unchanged? out wt)))

(defn- attempt!
  "One try. Returns :unchanged, :published or :rejected."
  [{:keys [sh repo-dir rebuild files message] same? :unchanged?} out tmp]
  (let [wt   (str tmp "/wt")
        prev (str tmp "/prev")]
    (fs/delete-tree prev)
    (fs/create-dirs prev)
    (try
      (open-worktree! sh repo-dir wt prev files)
      (rebuild {:prev-dir prev :out-dir out})
      (when-not (every? #(fs/exists? (str out "/" %)) files)
        (throw (ex-info (str "rebuild did not write " (str/join ", " files) " in " out) {})))
      (if (unchanged? same? files out wt)
        :unchanged
        (do (doseq [f files]
              (fs/copy (str out "/" f) (str wt "/" f) {:replace-existing true}))
            (apply git! sh wt "add" files)
            (apply git! sh wt (concat bot ["commit" "--quiet" "-m" message]))
            (if (zero? (:exit (sh wt "git" "push" "--quiet" "origin" "HEAD:refs/heads/status")))
              :published
              :rejected)))
      (finally
        (sh repo-dir "git" "worktree" "remove" "--force" wt)
        (fs/delete-tree wt)
        (sh repo-dir "git" "worktree" "prune")))))

(defn publish!
  "Publish data files to origin's status branch.

  opts: :repo-dir (a checkout whose origin it can push to), :out-dir,
  :files (default status.json and history.json), :unchanged? (a fn of the old
  and new parsed JSON of each file; the default is the status files' rule),
  :message (the commit subject), :rebuild (a fn of {:prev-dir :out-dir} that
  writes the files into :out-dir; run on every attempt, the first included, with
  :prev-dir holding the freshly fetched files, which may be absent) and :sh
  (see `sh`). A rejected push means someone published meanwhile, so the next
  attempt rebuilds on top of their result. Returns {:result :published
  :attempt n} or {:result :unchanged}; throws after 3 rejected pushes."
  [{:keys [out-dir rebuild] :as opts}]
  (when-not rebuild
    (throw (ex-info "rebuild is required" {})))
  (fs/create-dirs out-dir)
  (let [opts (merge {:files status-files :message "status: update fleet status"} opts)
        out (str (fs/canonicalize out-dir))
        tmp (str (fs/create-temp-dir))]
    (try
      (loop [n 1]
        (let [r (attempt! opts out tmp)]
          (cond
            (= :unchanged r) {:result :unchanged}
            (= :published r) {:result :published :attempt n}
            (< n attempts)   (do (warn (str "push rejected on attempt " n ", retrying"))
                                 (recur (inc n)))
            :else            (throw (ex-info (str "gave up after " attempts " rejected pushes")
                                             {:attempts attempts})))))
      (finally (fs/delete-tree tmp)))))
