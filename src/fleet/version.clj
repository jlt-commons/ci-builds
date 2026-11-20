(ns fleet.version
  "Pure helpers for the fleet dashboard: text in, values out."
  (:require [clojure.string :as str]))

(def ^:private version-line
  #"^([0-9T:.-]+Z )?jolt v([0-9]+\.[0-9]+\.[0-9]+(-[0-9]+-g[0-9a-f]+)?)$")

(defn installed-version
  "The version from the first \"jolt v<semver>[-N-g<hex>]\" line of a log
  (after the Actions timestamp), without the leading v. nil when none."
  [log-text]
  (some (fn [line]
          (when-let [m (re-matches version-line (str/replace line #"\r$" ""))]
            (nth m 2)))
        (str/split-lines (or log-text ""))))

(defn version-matches?
  "Does the installed version satisfy the request? A nightly request needs
  the installed -g<hex> suffix to be a prefix of nightly-sha."
  [requested installed nightly-sha]
  (if (= "nightly" requested)
    (boolean
     (when (seq nightly-sha)
       (when-let [[_ sha] (re-find #"-g([0-9a-f]+)$" (or installed ""))]
         (str/starts-with? nightly-sha sha))))
    (= (str/replace-first requested #"^v" "") installed)))

(defn pick-installed
  "First installed version across the logs that matches the request (R4),
  else the first version found, else nil."
  [requested nightly-sha log-texts]
  (let [versions (keep installed-version log-texts)
        hit      (first (filter #(version-matches? requested % nightly-sha) versions))]
    (if hit
      {:installed hit :match? true}
      {:installed (first versions) :match? false})))

(def ^:private ws "[ \t]*")
(def ^:private ver "v?([0-9]+\\.[0-9]+\\.[0-9]+)")

(def ^:private pin-re
  (re-pattern
   (str "^" ws "JOLT_VERSION:" ws
        "(?:\\$\\{\\{" ws "inputs\\.jolt-version" ws "\\|\\|" ws "'" ver "'" ws "\\}\\}"
        "|\"" ver "\"|'" ver "'|" ver ")" ws "$")))

(defn parse-pin
  "The literal version from the first JOLT_VERSION: key line of a ci.yml,
  without v. nil when the key is missing or the pin is not a literal."
  [ci-yml-text]
  (when ci-yml-text
    (when-let [line (some #(when (re-find #"^\s*JOLT_VERSION:" %) %)
                          (str/split-lines ci-yml-text))]
      (let [line (str/replace line #" #.*$" "")]
        (some-> (re-matches pin-re line) rest (->> (some identity)))))))

(defn uncovered-reason
  "Why a repo is not in the fleet, or nil when it is."
  [{:keys [has-ci? has-input? in-fleet? excluded]}]
  (cond
    in-fleet?          nil
    (seq excluded)     (str "excluded: " excluded)
    (not has-ci?)      "no ci.yml"
    (not has-input?)   "ci.yml has no jolt-version input"
    :else              "ready, not yet in fleet.json"))

(defn- failed-step-section
  "Text from the last \"##[group]Run \" line before the log's first
  \"##[error]\" line, up to that error line. nil when there is no error."
  [log-text]
  (loop [lines (str/split-lines (or log-text "")) buf []]
    (when-let [line (first lines)]
      (let [tok (second (str/split line #" " 3))]
        (cond
          (= tok "##[group]Run") (recur (rest lines) [line])
          (and tok (str/starts-with? tok "##[error]")) (str/join "\n" buf)
          :else (recur (rest lines) (conj buf line)))))))

(def ^:private download-patterns
  ["status code: 429" "Too Many Requests" "Could not transfer artifact"
   "Could not find artifact" "Failed to read artifact descriptor"])

(defn download-failure?
  "True only when every given log shows a dependency download failure inside
  its failed step (R24). No logs, or a log without an error line, is false."
  [log-texts]
  (boolean
   (and (seq log-texts)
        (every? (fn [t]
                  (when-let [section (failed-step-section t)]
                    (some #(str/includes? section %) download-patterns)))
                log-texts))))
