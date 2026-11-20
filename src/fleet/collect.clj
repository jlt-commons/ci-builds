(ns fleet.collect
  "The network collectors: each fleet project's latest push CI run with its
  pin, and the org repos the fleet does not cover. gh is a map of the
  fleet.github fns ({:get-json :get-raw :get-paginated}), so tests stub it.
  Any failure to read one repo degrades that repo's row and warns on stderr;
  it never aborts the collection. A row whose read failed carries
  :unreadable true (R38), which tells the status build to keep that project's
  previous cell or reason instead of recording the outage as a result."
  (:require [clojure.string :as str]
            [fleet.version :as version]
            [jolt.yaml :as yaml]))

(def org "jlt-commons")

(defn- warn [who & msg]
  (binding [*out* *err*]
    (println (str who ": " (str/join " " msg)))))

(defn- explain [e]
  (str (or (some-> (ex-data e) :status (->> (str "HTTP ")))
           "error")
       ": " (ex-message e)))

(defn- ci-path [p] (str "repos/" org "/" p "/contents/.github/workflows/ci.yml"))

(defn- run-row [name pin result run-url at step]
  {:name name :pin pin :result result :run_url run-url :finished_at at :failed_step step})

(defn- unreadable-row
  "A row for a project whose run could not be read: not-run, and marked so the
  build does not mistake the outage for a result."
  [name pin]
  (assoc (run-row name pin "not-run" nil nil nil) :unreadable true))

(defn- conclusion->result [conclusion]
  (case conclusion
    "success"   "pass"
    "failure"   "fail"
    "timed_out" "timed-out"
    "not-run"))

(defn- failed-step [gh p run-id]
  (try
    (let [jobs (:jobs ((:get-json gh) (str "repos/" org "/" p "/actions/runs/" run-id "/jobs?per_page=100")))]
      (->> jobs
           (filter #(= "failure" (:conclusion %)))
           (mapcat :steps)
           (filter #(= "failure" (:conclusion %)))
           first
           :name))
    (catch Exception e
      (warn "collect-own-pin" p "could not read jobs" (explain e))
      nil)))

(defn- own-pin-of [gh p]
  (let [branch (try (:default_branch ((:get-json gh) (str "repos/" org "/" p)))
                    (catch Exception e
                      (warn "collect-own-pin" p "could not read repository:" (explain e))
                      nil))]
    (if-not branch
      (unreadable-row p nil)
      (let [pin  (try (version/parse-pin ((:get-raw gh) (ci-path p)))
                      (catch Exception e
                        (warn "collect-own-pin" p "could not read ci.yml:" (explain e))
                        nil))
            runs (try (:workflow_runs
                       ((:get-json gh)
                        (str "repos/" org "/" p "/actions/workflows/ci.yml/runs?branch="
                             (java.net.URLEncoder/encode ^String branch "UTF-8")
                             "&event=push&status=completed&per_page=1")))
                      (catch Exception e
                        (warn "collect-own-pin" p "could not list runs:" (explain e))
                        ::failed))]
        (cond
          (= ::failed runs) (unreadable-row p pin)
          (empty? runs)     (run-row p pin "not-run" nil nil nil)
          :else
          (let [r      (first runs)
                result (conclusion->result (:conclusion r))]
            (run-row p pin result (:html_url r) (:updated_at r)
                     (when (= "fail" result) (failed-step gh p (:id r))))))))))

(defn own-pin
  "One row per fleet project, in fleet order: {:name :pin :result :run_url
  :finished_at :failed_step}. fleet is {:projects [...] :excluded {...}}."
  [gh fleet]
  (mapv #(own-pin-of gh %) (:projects fleet)))

(defn- has-input?
  "Does the workflow declare a workflow_dispatch input named jolt-version?
  YAML 1.1 readers turn an unquoted `on` key into boolean true."
  [doc]
  (let [on (or (get doc "on") (get doc true))]
    (boolean (and (map? on)
                  (let [inputs (get-in on ["workflow_dispatch" "inputs"])]
                    (and (map? inputs) (contains? inputs "jolt-version")))))))

(def ^:private unreadable "could not read repository")

(defn- reason-for
  "The uncovered reason for repo p, nil when it is covered. A string, or the
  keyword ::unreadable when the repository could not be read at all (R38)."
  [gh fleet p]
  (let [in-fleet? (boolean (some #{p} (:projects fleet)))
        excluded  (get (:excluded fleet) (keyword p))]
    (if (or in-fleet? (seq excluded))
      (version/uncovered-reason {:in-fleet? in-fleet? :excluded excluded})
      (let [ci (try {:text ((:get-raw gh) (ci-path p))}
                    (catch Exception e
                      (if (= 404 (:status (ex-data e)))
                        {:missing true}
                        (do (warn "collect-uncovered" p unreadable ":" (explain e))
                            {:error true}))))]
        (cond
          (:error ci)   ::unreadable
          (:missing ci) (version/uncovered-reason {:has-ci? false})
          :else
          (try (version/uncovered-reason {:has-ci? true :has-input? (has-input? (yaml/load (:text ci)))})
               (catch Exception e
                 (warn "collect-uncovered" p "could not parse ci.yml:" (explain e))
                 unreadable)))))))

(defn uncovered
  "Public, non-archived org repos the fleet does not cover, each with a
  reason: [{:name :reason}]. A repo that could not be read has the reason
  \"could not read repository\" and :unreadable true (R38)."
  [gh fleet]
  (->> ((:get-paginated gh) (str "orgs/" org "/repos?type=public"))
       (filter #(and (not (:archived %)) (not (:private %))))
       (keep (fn [{:keys [name]}]
               (when-let [r (reason-for gh fleet name)]
                 (if (= ::unreadable r)
                   {:name name :reason unreadable :unreadable true}
                   {:name name :reason r}))))
       vec))
