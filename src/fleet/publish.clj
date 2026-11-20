(ns fleet.publish
  "Commit status.json and history.json to the orphan `status` branch of
  origin (R11-R13). Git runs through an injected sh fn, so tests can point
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

(defn- open-worktree!
  "Add a detached worktree at wt on the tip of origin's status branch, or on
  a fresh orphan when the branch does not exist yet. Copies the previous
  status.json and history.json (when present) into prev."
  [sh repo-dir wt prev]
  (let [rc (:exit (sh repo-dir "git" "ls-remote" "--exit-code" "--heads" "origin" "status"))]
    (case rc
      0 (do (git! sh repo-dir "fetch" "--quiet" "origin" "+refs/heads/status:refs/remotes/origin/status")
            (git! sh repo-dir "worktree" "add" "--quiet" "--detach" wt "refs/remotes/origin/status")
            (doseq [f ["status.json" "history.json"]
                    :when (fs/exists? (str wt "/" f))]
              (fs/copy (str wt "/" f) (str prev "/" f))))
      2 (do (git! sh repo-dir "worktree" "add" "--quiet" "--detach" wt "HEAD")
            (git! sh wt "checkout" "--quiet" "--orphan" "status")
            (git! sh wt "rm" "-rfq" "."))
      (throw (ex-info (str "publish-status: cannot reach origin (ls-remote exit " rc ")")
                      {:exit rc})))))

(defn- unchanged?
  "R12: history.json byte-identical, status.json equal apart from
  generated_at and canary_run."
  [out wt]
  (and (fs/exists? (str wt "/status.json"))
       (fs/exists? (str wt "/history.json"))
       (= (slurp (str wt "/history.json")) (slurp (str out "/history.json")))
       (status/unchanged? (read-json (str wt "/status.json"))
                          (read-json (str out "/status.json")))))

(defn- attempt!
  "One try. Returns :unchanged, :published or :rejected."
  [{:keys [sh repo-dir rebuild]} out tmp]
  (let [wt   (str tmp "/wt")
        prev (str tmp "/prev")]
    (fs/delete-tree prev)
    (fs/create-dirs prev)
    (try
      (open-worktree! sh repo-dir wt prev)
      (rebuild {:prev-dir prev :out-dir out})
      (when-not (and (fs/exists? (str out "/status.json")) (fs/exists? (str out "/history.json")))
        (throw (ex-info (str "publish-status: rebuild did not write both files in " out) {})))
      (if (unchanged? out wt)
        :unchanged
        (do (fs/copy (str out "/status.json") (str wt "/status.json") {:replace-existing true})
            (fs/copy (str out "/history.json") (str wt "/history.json") {:replace-existing true})
            (git! sh wt "add" "status.json" "history.json")
            (apply git! sh wt (concat bot ["commit" "--quiet" "-m" "status: update fleet status"]))
            (if (zero? (:exit (sh wt "git" "push" "--quiet" "origin" "HEAD:refs/heads/status")))
              :published
              :rejected)))
      (finally
        (sh repo-dir "git" "worktree" "remove" "--force" wt)
        (fs/delete-tree wt)
        (sh repo-dir "git" "worktree" "prune")))))

(defn publish!
  "Publish the status files to origin's status branch.

  opts: :repo-dir (a checkout whose origin it can push to), :out-dir,
  :rebuild (a fn of {:prev-dir :out-dir} that writes status.json and
  history.json into :out-dir; run on every attempt, the first included, with
  :prev-dir holding the freshly fetched files, which may be absent) and :sh
  (see `sh`). A rejected push means someone published meanwhile, so the next
  attempt rebuilds on top of their result. Returns {:result :published
  :attempt n} or {:result :unchanged}; throws after 3 rejected pushes."
  [{:keys [out-dir rebuild] :as opts}]
  (when-not rebuild
    (throw (ex-info "publish-status: rebuild is required" {})))
  (fs/create-dirs out-dir)
  (let [out (str (fs/canonicalize out-dir))
        tmp (str (fs/create-temp-dir))]
    (try
      (loop [n 1]
        (let [r (attempt! opts out tmp)]
          (cond
            (= :unchanged r) {:result :unchanged}
            (= :published r) {:result :published :attempt n}
            (< n attempts)   (do (warn (str "push rejected on attempt " n ", retrying"))
                                 (recur (inc n)))
            :else            (throw (ex-info (str "publish-status: gave up after " attempts " rejected pushes")
                                             {:attempts attempts})))))
      (finally (fs/delete-tree tmp)))))
