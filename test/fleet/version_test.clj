(ns fleet.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [fleet.version :as v]))

(defn- fx [name] (slurp (str "test/fixtures/" name)))

(defn- ts-line [ts s] (str "2026-11-19T01:02:0" ts ".0000000Z " s "\n"))

(deftest release-log
  (is (= "0.8.24" (v/installed-version (fx "log-release.txt")))))

(deftest nightly-log
  (is (= "0.8.27-241-gdd3c4a4b" (v/installed-version (fx "log-nightly.txt")))))

(deftest no-version-line
  (is (nil? (v/installed-version "Successfully installed jolt vnightly to /x\n"))))

(deftest v-prefix-requested
  (is (true? (v/version-matches? "v0.8.24" "0.8.24" nil))))

(deftest release-mismatch
  (is (false? (v/version-matches? "0.8.27" "0.8.24" nil))))

(deftest nightly-sha-match
  (is (true? (v/version-matches? "nightly" "0.8.27-241-gdd3c4a4b"
                                 "dd3c4a4b0123456789abcdef0123456789abcdef"))))

(deftest nightly-stale-sha
  (is (false? (v/version-matches? "nightly" "0.8.27-241-gdd3c4a4b"
                                  "98e5ef990000000000000000000000000000000a"))))

(deftest nightly-no-sha
  (is (false? (v/version-matches? "nightly" "0.8.27-241-gdd3c4a4b" nil)))
  (is (false? (v/version-matches? "nightly" "0.8.27-241-gdd3c4a4b" ""))))

(deftest nightly-got-a-release
  (is (false? (v/version-matches? "nightly" "0.8.27" "dd3c4a4b00"))))

(defn- pin [line] (v/parse-pin (str line "\n")))

(deftest pin-input-with-default
  (is (= "0.8.24" (pin "  JOLT_VERSION: ${{ inputs.jolt-version || '0.8.24' }}"))))

(deftest pin-input-with-v-default
  (is (= "0.8.24" (pin "  JOLT_VERSION: ${{ inputs.jolt-version || 'v0.8.24' }}"))))

(deftest pin-quoted-literal
  (is (= "0.8.24" (pin "  JOLT_VERSION: \"0.8.24\""))))

(deftest pin-needs-output-is-not-literal
  (is (nil? (pin "  JOLT_VERSION: ${{ needs.jolt-version.outputs.version }}"))))

(deftest pin-comment-mentions-are-skipped
  (is (= "0.8.24" (v/parse-pin "# JOLT_VERSION: 9.9.9 is old\nenv:\n  JOLT_VERSION: \"0.8.24\"\n"))))

(deftest pin-no-key-at-all
  (is (nil? (v/parse-pin "name: x\n"))))

(deftest pin-missing-file
  (is (nil? (v/parse-pin nil))))

(deftest uncovered-in-fleet-prints-nothing
  (is (nil? (v/uncovered-reason {:has-ci? true :has-input? true :in-fleet? true :excluded ""}))))

(deftest uncovered-in-fleet-beats-excluded
  (is (nil? (v/uncovered-reason {:has-ci? false :has-input? false :in-fleet? true :excluded "why"}))))

(deftest uncovered-excluded
  (is (= "excluded: needs input"
         (v/uncovered-reason {:has-ci? true :has-input? false :in-fleet? false :excluded "needs input"}))))

(deftest uncovered-excluded-beats-no-ci
  (is (= "excluded: private demo"
         (v/uncovered-reason {:has-ci? false :has-input? false :in-fleet? false :excluded "private demo"}))))

(deftest uncovered-no-ci-yml
  (is (= "no ci.yml"
         (v/uncovered-reason {:has-ci? false :has-input? false :in-fleet? false :excluded ""}))))

(deftest uncovered-no-input
  (is (= "ci.yml has no jolt-version input"
         (v/uncovered-reason {:has-ci? true :has-input? false :in-fleet? false :excluded ""}))))

(deftest uncovered-ready
  (is (= "ready, not yet in fleet.json"
         (v/uncovered-reason {:has-ci? true :has-input? true :in-fleet? false :excluded ""}))))

(deftest pin-non-literal-with-quoted-version-in-a-trailing-comment
  (is (nil? (pin "  JOLT_VERSION: ${{ needs.x.outputs.v }} # was '0.8.24'"))))

(deftest pin-literal-followed-by-a-comment
  (is (= "0.8.24" (pin "  JOLT_VERSION: \"0.8.24\" # pinned"))))

(deftest pin-bare-literal
  (is (= "0.8.24" (pin "  JOLT_VERSION: v0.8.24"))))

(deftest installed-crlf-line
  (is (= "0.8.27-241-gdd3c4a4b"
         (v/installed-version "2026-11-18T03:48:27.0669741Z jolt v0.8.27-241-gdd3c4a4b\r\n"))))

(deftest installed-20000-line-log-version-last
  (let [filler (apply str (repeat 20000 "2026-11-18T03:48:27.0000000Z some build output line with jolt in it\n"))]
    (is (= "0.8.24" (v/installed-version (str filler "2026-11-18T03:48:28.0000000Z jolt v0.8.24\n"))))))

(defn- logs2 [a b] [(ts-line 3 (str "jolt v" a)) (ts-line 4 (str "jolt v" b))])

(deftest pick-matching-second-log
  (is (= {:installed "0.8.27" :match? true}
         (v/pick-installed "0.8.27" "" (logs2 "0.7.29" "0.8.27")))))

(deftest pick-none-match-reports-first
  (is (= {:installed "0.7.29" :match? false}
         (v/pick-installed "0.8.26" "" (logs2 "0.7.29" "0.8.27")))))

(deftest pick-nightly-sha-in-second-log
  (is (= {:installed "0.8.27-241-gdd3c4a4b" :match? true}
         (v/pick-installed "nightly" "dd3c4a4b0123456789abcdef0123456789abcdef"
                           (logs2 "0.7.29" "0.8.27-241-gdd3c4a4b")))))

(deftest pick-no-versions
  (is (= {:installed nil :match? false}
         (v/pick-installed "0.8.27" "" ["nothing\n"]))))

(deftest download-failure-maven-429
  (is (true? (v/download-failure? [(fx "log-maven-429.txt")]))))

(deftest download-failure-maven-absent
  (is (true? (v/download-failure? [(fx "log-maven-absent.txt")]))))

(deftest download-failure-test-failure
  (is (false? (v/download-failure? [(fx "log-test-failure.txt")]))))

(deftest download-failure-no-args
  (is (false? (v/download-failure? []))))

(deftest download-failure-earlier-step-printed-429-failed-step-is-a-test
  (is (false? (v/download-failure? [(fx "log-early-429.txt")]))))

(deftest download-failure-one-deps-log-and-one-test-log-is-not-infra
  (is (false? (v/download-failure? [(fx "log-maven-429.txt") (fx "log-test-failure.txt")]))))

(deftest download-failure-both-deps-logs
  (is (true? (v/download-failure? [(fx "log-maven-429.txt") (fx "log-maven-absent.txt")]))))

(deftest download-failure-no-error-line
  (is (false? (v/download-failure? [(fx "log-no-error.txt")]))))

(deftest download-failure-classpath-lookup-failed-alone-is-not-enough
  (is (false? (v/download-failure?
               [(str/join "\n" ["2026-11-20T01:00:00.0000000Z ##[group]Run x"
                                "2026-11-20T01:00:01.0000000Z [ERROR] Classpath lookup failed"
                                "2026-11-20T01:00:02.0000000Z ##[error]Process completed with exit code 1."])]))))

;; Real-shaped fixtures the reviewer asked for.

(def ^:private lsp-429-failed-step
  (str/join "\n"
            ["2026-11-20T02:00:00.0000000Z ##[group]Run bb lsp:format-check"
             "2026-11-20T02:00:01.0000000Z bb lsp:format-check"
             "2026-11-20T02:00:05.0000000Z ##[endgroup]"
             "2026-11-20T02:00:06.0000000Z Downloading from central: https://repo1.maven.org/maven2/x.pom"
             "2026-11-20T02:00:07.0000000Z Error building classpath. Could not transfer artifact x:x:pom:1 from/to central: status code: 429, reason phrase: Too Many Requests (429)"
             "2026-11-20T02:00:08.0000000Z ##[error]Process completed with exit code 1."]))

(deftest download-failure-group-run-then-429-then-error
  (is (true? (v/download-failure? [lsp-429-failed-step]))))

(def ^:private test-failure-then-post-step-429
  (str/join "\n"
            ["2026-11-20T02:00:00.0000000Z ##[group]Run bb test"
             "2026-11-20T02:00:01.0000000Z FAIL in (vec-add-test) (vector_test.clj:12)"
             "2026-11-20T02:00:02.0000000Z ##[error]Process completed with exit code 1."
             "2026-11-20T02:00:03.0000000Z Post job cleanup."
             "2026-11-20T02:00:04.0000000Z ##[group]Run post-step"
             "2026-11-20T02:00:05.0000000Z Cache save failed: status code: 429"
             "2026-11-20T02:00:06.0000000Z ##[error]cache save failed"]))

(deftest download-failure-first-error-is-a-test-failure-later-429
  (is (false? (v/download-failure? [test-failure-then-post-step-429]))))
