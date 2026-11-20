(ns fleet.github-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fleet.github :as gh]))

(defn- stub
  "A request fn answering from a map of url -> response; records the requests."
  [responses seen]
  (fn [req]
    (swap! seen conj req)
    (or (get responses (:url req)) {:status 404 :headers {} :body "{\"message\":\"Not Found\"}"})))

(deftest next-link-parses-rel-next
  (is (= "https://api.github.com/x?page=2"
         (gh/next-link "<https://api.github.com/x?page=2>; rel=\"next\", <https://api.github.com/x?page=9>; rel=\"last\""))))

(deftest next-link-last-page-has-none
  (is (nil? (gh/next-link "<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=1>; rel=\"first\"")))
  (is (nil? (gh/next-link nil))))

(deftest next-link-next-listed-second
  (is (= "https://api.github.com/x?page=3"
         (gh/next-link "<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=3>; rel=\"next\""))))

(deftest get-json-parses-and-sends-auth
  (let [seen (atom [])
        c    (gh/client {:token "tok"
                         :request (stub {"https://api.github.com/repos/o/r"
                                         {:status 200 :headers {} :body "{\"name\":\"r\",\"tags\":[1,2]}"}}
                                        seen)})]
    (is (= {:name "r" :tags [1 2]} ((:get-json c) "repos/o/r")))
    (is (= "Bearer tok" (get-in (first @seen) [:headers "Authorization"])))
    (is (= false (:throw-exceptions (first @seen))))))

(deftest no-token-no-authorization-header
  (let [seen (atom [])
        c    (gh/client {:request (stub {"https://api.github.com/a" {:status 200 :headers {} :body "{}"}} seen)})]
    ((:get-json c) "/a")
    (is (not (contains? (:headers (first @seen)) "Authorization")))))

(deftest get-raw-asks-for-raw-and-returns-text
  (let [seen (atom [])
        c    (gh/client {:request (stub {"https://api.github.com/repos/o/r/contents/f"
                                         {:status 200 :headers {} :body "plain: text\n"}} seen)})]
    (is (= "plain: text\n" ((:get-raw c) "repos/o/r/contents/f")))
    (is (= "application/vnd.github.raw+json" (get-in (first @seen) [:headers "Accept"])))))

(deftest non-2xx-throws-ex-info-with-status-path-body
  (let [c (gh/client {:request (stub {} (atom []))})
        e (try ((:get-json c) "repos/o/missing") nil (catch Exception e e))]
    (is (some? e))
    (is (= 404 (:status (ex-data e))))
    (is (= "repos/o/missing" (:path (ex-data e))))
    (is (= "{\"message\":\"Not Found\"}" (:body (ex-data e))))
    (is (= 404 (gh/status-of e)))))

(deftest get-raw-non-2xx-also-throws
  (let [c (gh/client {:request (stub {"https://api.github.com/x" {:status 403 :headers {} :body "rate"}} (atom []))})
        e (try ((:get-raw c) "x") nil (catch Exception e e))]
    (is (= 403 (gh/status-of e)))))

(deftest get-paginated-follows-next-and-concatenates
  (let [seen (atom [])
        p1   "https://api.github.com/orgs/o/repos?per_page=100"
        p2   "https://api.github.com/orgs/o/repos?per_page=100&page=2"
        c    (gh/client {:request (stub {p1 {:status 200
                                             :headers {"link" (str "<" p2 ">; rel=\"next\"")}
                                             :body "[{\"n\":1},{\"n\":2}]"}
                                         p2 {:status 200 :headers {"Link" "<x>; rel=\"prev\""}
                                             :body "[{\"n\":3}]"}}
                                        seen)})]
    (is (= [{:n 1} {:n 2} {:n 3}] ((:get-paginated c) "orgs/o/repos")))
    (is (= [p1 p2] (mapv :url @seen)))))

(deftest get-paginated-keeps-an-explicit-per-page
  (let [seen (atom [])
        c    (gh/client {:request (stub {"https://api.github.com/x?per_page=5" {:status 200 :headers {} :body "[]"}} seen)})]
    (is (= [] ((:get-paginated c) "x?per_page=5")))
    (is (= ["https://api.github.com/x?per_page=5"] (mapv :url @seen)))))

(deftest get-paginated-error-on-a-later-page-throws
  (let [p1 "https://api.github.com/x?per_page=100"
        p2 "https://api.github.com/x?page=2"
        c  (gh/client {:request (stub {p1 {:status 200 :headers {"link" (str "<" p2 ">; rel=\"next\"")} :body "[1]"}
                                       p2 {:status 502 :headers {} :body "bad gateway"}}
                                      (atom []))})
        e  (try ((:get-paginated c) "x") nil (catch Exception e e))]
    (is (= 502 (gh/status-of e)))))

;; 9a review fix (a): the token goes to api.github.com only.

(defn- foreign-host? [e] (= :foreign-host (:reason (ex-data e))))

(deftest full-url-on-another-host-throws-and-sends-nothing
  (doseq [url ["https://evil.example/repos/o/r"
               "http://api.github.com/repos/o/r"
               "https://api.github.com.evil.example/repos/o/r"
               "https://api.github.com@evil.example/repos/o/r"
               "https://api.github.com:8443/repos/o/r"]]
    (testing url
      (let [seen (atom [])
            c    (gh/client {:token "tok" :request (stub {} seen)})]
        (doseq [f [:get-json :get-raw :get-paginated]]
          (let [e (try ((f c) url) nil (catch Exception e e))]
            (is (foreign-host? e))
            (is (nil? (gh/status-of e)))))
        (is (= [] @seen))))))

(deftest full-url-on-api-github-com-still-works-with-the-token
  (let [seen (atom [])
        url  "https://api.github.com/repos/o/r"
        c    (gh/client {:token "tok" :request (stub {url {:status 200 :headers {} :body "{}"}} seen)})]
    (is (= {} ((:get-json c) url)))
    (is (= "Bearer tok" (get-in (first @seen) [:headers "Authorization"])))))

(deftest link-next-on-another-host-is-refused-after-the-first-page
  (let [seen (atom [])
        p1   "https://api.github.com/x?per_page=100"
        c    (gh/client {:token "tok"
                         :request (stub {p1 {:status 200
                                             :headers {"link" "<https://evil.example/steal?page=2>; rel=\"next\""}
                                             :body "[1]"}}
                                        seen)})
        e    (try ((:get-paginated c) "x") nil (catch Exception e e))]
    (is (foreign-host? e))
    (is (= [p1] (mapv :url @seen)))))

;; 9a review fix (b): a transport failure carries no :status.

(deftest transport-exception-propagates-with-no-status
  (let [boom (ex-info "connection refused" {})
        c    (gh/client {:request (fn [_] (throw boom))})]
    (doseq [f [:get-json :get-raw :get-paginated]]
      (let [e (try ((f c) "repos/o/r") nil (catch Exception e e))]
        (is (identical? boom e))
        (is (nil? (gh/status-of e)))))))

(deftest get-redirected-log-follows-the-302-without-the-token
  (let [seen (atom [])
        blob "https://productionresultssa1.blob.core.windows.net/logs/1?sig=abc"
        c    (gh/client {:token "tok"
                         :request (stub {"https://api.github.com/repos/o/r/actions/jobs/7/logs"
                                         {:status 302 :headers {"Location" blob} :body ""}
                                         blob {:status 200 :headers {} :body "log text\n"}}
                                        seen)})]
    (is (= "log text\n" ((:get-redirected-log c) "repos/o/r/actions/jobs/7/logs")))
    (is (= 2 (count @seen)))
    (is (= "Bearer tok" (get-in (first @seen) [:headers "Authorization"])))
    (is (= blob (:url (second @seen))))
    (is (not-any? #(= "authorization" (str/lower-case (name %)))
                  (keys (:headers (second @seen)))))))

(deftest get-redirected-log-accepts-a-direct-200
  (let [c (gh/client {:token "tok"
                      :request (stub {"https://api.github.com/l" {:status 200 :headers {} :body "direct"}} (atom []))})]
    (is (= "direct" ((:get-redirected-log c) "l")))))

(deftest get-redirected-log-refuses-a-non-https-location
  (let [seen (atom [])
        c    (gh/client {:token "tok"
                         :request (stub {"https://api.github.com/l"
                                         {:status 302 :headers {"location" "http://evil.example/x"} :body ""}}
                                        seen)})
        e    (try ((:get-redirected-log c) "l") nil (catch Exception e e))]
    (is (= :bad-redirect (:reason (ex-data e))))
    (is (= 1 (count @seen)))))

(deftest get-redirected-log-redirect-without-location-throws
  (let [c (gh/client {:request (stub {"https://api.github.com/l" {:status 302 :headers {} :body ""}} (atom []))})
        e (try ((:get-redirected-log c) "l") nil (catch Exception e e))]
    (is (= 302 (gh/status-of e)))))

(deftest get-redirected-log-error-from-the-blob-host-throws
  (let [blob "https://blob.example/x"
        c    (gh/client {:request (stub {"https://api.github.com/l" {:status 302 :headers {"Location" blob} :body ""}
                                         blob {:status 403 :headers {} :body "expired"}} (atom []))})
        e    (try ((:get-redirected-log c) "l") nil (catch Exception e e))]
    (is (= 403 (gh/status-of e)))))
