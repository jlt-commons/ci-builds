(ns fleet.status-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [fleet.status :as status]))

(defn- fx
  "Parse a status fixture. cheshire under jolt returns arrays as lazy seqs, so
  a top-level array is made a vector for assoc-in."
  [name]
  (let [d (json/parse-string (slurp (str "test/fixtures/status/" name)) true)]
    (if (sequential? d) (vec d) d)))

(def ^:private nj
  {:published_at "2026-11-20T01:00:00Z"
   :sha          "f4e82fc8eac874cae1658b7976518253027c4f6a"
   :note         "n"})

(def ^:private nj-aaaa {:published_at "x" :sha "aaaa1111" :note "n"})

(defn- run-bs
  "build with the bats defaults. Opts override: :ps :ph :op :latest :nj
  (use ::none for a nil nightly)."
  [{:keys [ps ph op latest nightly] :or {latest "0.8.27" nightly nj}} & results]
  (status/build {:prev-status  ps
                 :prev-history ph
                 :own-pin      (or op (fx "own-pin.json"))
                 :uncovered    (fx "uncovered.json")
                 :latest       latest
                 :nightly      (when-not (= ::none nightly) nightly)
                 :canary-run   "https://example/run/1"
                 :now          "2026-11-20T04:00:00Z"
                 :results      results}))

(defn- cells [out name col]
  (->> (get-in out [:status :projects]) (filter #(= name (:name %))) first :cells col))

(defn- hist [out name col] (get-in out [:history :projects (keyword name) col]))

(defn- names [out] (mapv :name (get-in out [:status :projects])))

(defn- prev-cell [name col]
  (->> (:projects (fx "prev-status.json")) (filter #(= name (:name %))) first :cells col))

(def ^:private prev-s (fx "prev-status.json"))
(def ^:private prev-h (fx "prev-history.json"))
(def ^:private not-run {:result "not-run"})

(deftest first-run-with-no-prev-files
  (let [out (run-bs {} (fx "results-latest.json"))]
    (is (= 1 (get-in out [:status :schema])))
    (is (= ["ebb" "glitter" "raygui-jlt"] (sort (names out))))
    (is (= not-run (cells out "raygui-jlt" :latest)))
    (is (= not-run (cells out "raygui-jlt" :nightly)))
    (is (= [{:name "duratom" :reason "ci.yml has no jolt-version input"}]
           (get-in out [:status :uncovered])))
    (is (= "2026-11-20T04:00:00Z" (get-in out [:status :generated_at])))
    (is (= 1 (get-in out [:history :schema])))
    (is (= 1 (count (hist out "glitter" :latest))))
    (is (= 1 (count (hist out "ebb" :latest))))
    (is (= 1 (count (hist out "glitter" :pin))))))

(deftest pin-cell-comes-from-own-pin-with-installed-null
  (let [c (cells (run-bs {}) "glitter" :pin)]
    (is (= ["pass" "0.8.24" nil] ((juxt :result :version :installed) c)))
    (is (contains? c :installed))))

(deftest latest-result-lands-in-latest-column
  (let [out (run-bs {} (fx "results-latest.json"))]
    (is (= ["pass" "0.8.27" "0.8.27"]
           ((juxt :result :version :installed) (cells out "glitter" :latest))))
    (is (= {:version "0.8.27"} (get-in out [:status :jolt :latest])))))

(deftest v-prefix-on-requested-is-stripped
  (let [out (run-bs {} (assoc (fx "results-latest.json") :requested "v0.8.27"))]
    (is (= "0.8.27" (:version (cells out "glitter" :latest))))))

(deftest nightly-result-lands-in-nightly-column
  (let [out (run-bs {} (fx "results-nightly.json"))]
    (is (= ["pass" "nightly" "0.8.27-376-gf4e82fc8"]
           ((juxt :result :version :installed) (cells out "glitter" :nightly))))
    (is (= "f4e82fc8eac874cae1658b7976518253027c4f6a" (get-in out [:status :jolt :nightly :sha])))))

(deftest adhoc-result-only-in-history
  (let [out (run-bs {:ps prev-s :ph prev-h} (fx "results-adhoc.json"))]
    (is (= "https://github.com/jlt-commons/glitter/actions/runs/42"
           (:run_url (cells out "glitter" :latest))))
    (is (= 1 (count (hist out "glitter" :adhoc))))
    (is (= "0.8.25" (:version (first (hist out "glitter" :adhoc)))))
    (is (= 0 (count (hist out "glitter" :latest))))))

(deftest results-for-a-project-outside-own-pin-go-to-history-not-the-grid
  (let [r   (assoc-in (fx "results-latest.json") [:projects 0 :name] "gone")
        out (run-bs {} r)]
    (is (nil? (some #{"gone"} (names out))))
    (is (= 1 (count (hist out "gone" :latest))))))

(deftest carry-over-with-only-own-pin-input
  (let [out (run-bs {:nightly nj-aaaa :ps prev-s :ph prev-h})]
    (is (= (prev-cell "ebb" :latest) (cells out "ebb" :latest)))
    (is (= (prev-cell "glitter" :nightly) (cells out "glitter" :nightly)))
    (is (= not-run (cells out "raygui-jlt" :latest)))))

(deftest new-latest-version-invalidates-carried-latest-cells
  (let [out (run-bs {:nightly nj-aaaa :ps prev-s :ph prev-h :latest "0.8.28"})]
    (is (= not-run (cells out "ebb" :latest)))
    (is (= not-run (cells out "glitter" :latest)))
    (is (= "pass" (:result (cells out "glitter" :nightly))))))

(deftest new-nightly-sha-invalidates-carried-nightly-cells
  (let [out (run-bs {:ps prev-s :ph prev-h})]
    (is (= "not-run" (:result (cells out "glitter" :nightly))))
    (is (= "pass" (:result (cells out "glitter" :latest))))))

(deftest null-nightly-json-with-no-previous-status-gives-null-jolt-nightly
  (let [out (run-bs {:nightly ::none})]
    (is (contains? (get-in out [:status :jolt]) :nightly))
    (is (nil? (get-in out [:status :jolt :nightly])))))

(deftest same-nightly-sha-keeps-carried-nightly-cells
  (let [out (run-bs {:nightly nj-aaaa :ps prev-s})]
    (is (= "pass" (:result (cells out "glitter" :nightly))))))

(deftest null-nightly-json-keeps-previous-nightly-cells-and-previous-jolt-nightly
  (let [out (run-bs {:nightly ::none :ps prev-s})]
    (is (= (get-in prev-s [:jolt :nightly]) (get-in out [:status :jolt :nightly])))
    (is (= "pass" (:result (cells out "glitter" :nightly))))))

(deftest pin-history-appended-only-when-run-url-is-new
  (let [out (run-bs {:ps prev-s :ph prev-h})]
    (is (= 1 (count (hist out "glitter" :pin))))
    (is (= 1 (count (hist out "ebb" :pin))))
    (let [out2 (run-bs {:ps (:status out) :ph (:history out)})]
      (is (= 1 (count (hist out2 "ebb" :pin))))
      (is (= 1 (count (hist out2 "raygui-jlt" :pin)))))))

(deftest pin-history-grows-when-the-pin-run-changes
  (let [out (run-bs {:ps prev-s :ph prev-h})
        op  (assoc-in (fx "own-pin.json") [0 :run_url]
                      "https://github.com/jlt-commons/glitter/actions/runs/99")
        out2 (run-bs {:op op :ph (:history out)})]
    (is (= 2 (count (hist out2 "glitter" :pin))))
    (is (= "https://github.com/jlt-commons/glitter/actions/runs/99"
           (:run_url (first (hist out2 "glitter" :pin)))))))

(deftest history-capped-at-30-newest-first
  (let [old (vec (for [i (range 30)]
                   {:result "pass" :version "0.8.26" :installed nil
                    :run_url (str "old/" i) :finished_at nil :failed_step nil}))
        ph  (assoc-in prev-h [:projects :glitter :latest] old)
        out (run-bs {:ph ph} (fx "results-latest.json"))
        l   (hist out "glitter" :latest)]
    (is (= 30 (count l)))
    (is (= "https://github.com/jlt-commons/glitter/actions/runs/11" (:run_url (first l))))
    (is (= "old/28" (:run_url (nth l 29))))))

(deftest failing-first-then-by-name
  (is (= ["raygui-jlt" "ebb" "glitter"] (names (run-bs {})))))

(deftest mismatch-cell-sorts-before-passing-ones
  (let [op (assoc-in (fx "own-pin.json") [2 :result] "pass")]
    (is (= ["raygui-jlt" "ebb" "glitter"] (names (run-bs {:op op} (fx "results-nightly.json")))))))

(deftest malformed-results-throw
  (is (thrown? Exception
               (run-bs {} {:requested "0.8.27" :projects "nope"}))))

(deftest unknown-result-value-is-rejected
  (is (thrown? Exception
               (run-bs {} (assoc-in (fx "results-latest.json") [:projects 0 :result] "weird")))))

;; The bats case "unparseable results file" is the file-reading edge. Here it
;; is the shape a failed parse gives the pure function: not a map.
(deftest unparseable-results-input-is-rejected
  (is (thrown? Exception (run-bs {} nil)))
  (is (thrown? Exception (run-bs {} "{not json"))))

(deftest latest-with-a-v-prefix-matches-results-and-prev
  (let [out (run-bs {:nightly nj-aaaa :ps prev-s :latest "v0.8.27"} (fx "results-latest.json"))]
    (is (= "0.8.27" (get-in out [:status :jolt :latest :version])))
    (is (= "https://github.com/jlt-commons/glitter/actions/runs/11"
           (:run_url (cells out "glitter" :latest))))
    (is (= "not-run" (:result (cells out "raygui-jlt" :latest)))))
  (let [out (run-bs {:nightly nj-aaaa :ps prev-s :latest "v0.8.27"})]
    (is (= "fail" (:result (cells out "ebb" :latest))))))

(deftest reprocessing-the-same-results-does-not-duplicate-history
  (let [rs  [(fx "results-latest.json") (fx "results-nightly.json") (fx "results-adhoc.json")]
        o1  (apply run-bs {} rs)
        o2  (apply run-bs {:ps (:status o1) :ph (:history o1)} rs)]
    (is (= (:history o1) (:history o2)))
    (is (= 1 (count (hist o2 "glitter" :adhoc))))
    (is (= 1 (count (hist o2 "glitter" :latest))))
    (is (= 1 (count (hist o2 "glitter" :nightly))))))

(deftest unchanged-ignores-generated-at-and-canary-run
  (let [s (:status (run-bs {}))]
    (is (true? (status/unchanged? s (assoc s :generated_at "2030-01-01T00:00:00Z"
                                           :canary_run "https://example/run/2"))))
    (is (false? (status/unchanged? s (assoc-in s [:jolt :latest :version] "0.8.28"))))
    (is (false? (status/unchanged? s (assoc s :uncovered []))))))

(deftest nil-pin-is-accepted-and-the-project-still-appears
  ;; R26: a project with a non-literal JOLT_VERSION has pin nil. It must not
  ;; stop the whole dashboard from updating.
  (let [op  (-> (fx "own-pin.json")
                (assoc-in [0 :pin] nil)
                (assoc-in [0 :result] "fail"))
        out (run-bs {:op op})
        p   (first (filter #(= "glitter" (:name %)) (:projects (:status out))))]
    (is (= ["ebb" "glitter" "raygui-jlt"] (sort (map :name (:projects (:status out))))))
    (is (nil? (:version (:pin (:cells p)))))
    (is (= "fail" (:result (:pin (:cells p)))))
    (is (nil? (:installed (:pin (:cells p)))))))
