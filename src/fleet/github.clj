(ns fleet.github
  "A small GitHub REST client over jolt.http-client. IO sits behind a request
  function so tests stub it. A non-2xx response throws ex-info with
  {:status n :path p :body b}; callers branch on (:status (ex-data e))."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [jolt.http-client :as http]))

(def api-base "https://api.github.com")

(defn- header
  "Case-insensitive header lookup."
  [headers k]
  (some (fn [[hk v]] (when (= (str/lower-case (name hk)) k) v)) headers))

(defn next-link
  "The rel=\"next\" URL from a Link header value, or nil."
  [link-header]
  (when link-header
    (some (fn [part]
            (when-let [[_ url rel] (re-find #"<([^>]*)>\s*;\s*rel=\"?([^\";]+)\"?" part)]
              (when (= "next" rel) url)))
          (str/split link-header #",\s*(?=<)"))))

(defn- api-url?
  "True for api-base itself or a URL under it. The character after the host
  must be /, ? or the end, so api.github.com.evil.example and
  api.github.com@evil.example do not pass."
  [url]
  (and (str/starts-with? url api-base)
       (let [c (get url (count api-base))]
         (or (nil? c) (= \/ c) (= \? c)))))

(defn- url-for
  "The request URL for a path. A full URL must be on api-base: the token
  rides on every request, so any other host throws ex-info with
  {:reason :foreign-host} before a request is made."
  [path]
  (cond
    (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" path)
    (if (api-url? path)
      path
      (throw (ex-info (str "refusing to send the GitHub token to a foreign host: " path)
                      {:reason :foreign-host :url path})))
    (str/starts-with? path "/") (str api-base path)
    :else                       (str api-base "/" path)))

(defn- with-per-page [path]
  (if (re-find #"[?&]per_page=" path)
    path
    (str path (if (str/includes? path "?") "&" "?") "per_page=100")))

(defn- default-request
  "No redirects: the client would replay the Authorization header to a
  redirect target on any host. A request carries :method (:post or :patch for
  the writes, otherwise a GET) and, for the writes, a string :body."
  [req]
  (let [send (case (:method req) :post http/post :patch http/patch http/get)]
    (send (:url req) (assoc (dissoc req :url :method) :follow-redirects false))))

(defn- request!
  "One request, a GET unless method and body are given. Returns the response
  map, or throws ex-info on a non-2xx status."
  [{:keys [token request]} path accept & [method body]]
  (let [resp ((or request default-request)
              (cond-> {:url              (url-for path)
                       :throw-exceptions false
                       :headers          (cond-> {"Accept"               accept
                                                  "X-GitHub-Api-Version" "2022-11-28"}
                                           (seq token) (assoc "Authorization" (str "Bearer " token))
                                           body        (assoc "Content-Type" "application/json"))}
                method (assoc :method method)
                body   (assoc :body body)))
        status (:status resp)]
    (if (and (integer? status) (<= 200 status 299))
      resp
      (throw (ex-info (str "GitHub API " status " for " path)
                      {:status status :path path :body (:body resp)})))))

(defn- redirected-log!
  "GET path with the token. GitHub answers a job-log request with a 302 to a
  signed blob-storage URL. That URL is fetched WITHOUT the Authorization
  header: the signature is the credential, and the token must not go to a
  host we did not choose. A 2xx on the first request is returned as is. A
  Location that is not https throws {:reason :bad-redirect}."
  [{:keys [token request]} path]
  (let [req  (or request default-request)
        resp (req {:url              (url-for path)
                   :throw-exceptions false
                   :headers          (cond-> {"Accept"               "application/vnd.github+json"
                                              "X-GitHub-Api-Version" "2022-11-28"}
                                       (seq token) (assoc "Authorization" (str "Bearer " token)))})
        status (:status resp)
        loc    (header (:headers resp) "location")]
    (cond
      (and (integer? status) (<= 200 status 299)) (:body resp)

      (and (contains? #{301 302 303 307 308} status) (seq loc))
      (do (when-not (str/starts-with? loc "https://")
            (throw (ex-info (str "refusing a non-https log redirect for " path)
                            {:reason :bad-redirect :path path})))
          (let [r2 (req {:url loc :throw-exceptions false :headers {}})
                s2 (:status r2)]
            (if (and (integer? s2) (<= 200 s2 299))
              (:body r2)
              (throw (ex-info (str "log host answered " s2 " for " path)
                              {:status s2 :path path :body (:body r2)})))))

      :else (throw (ex-info (str "GitHub API " status " for " path)
                            {:status status :path path :body (:body resp)})))))

(def ^:private json-accept "application/vnd.github+json")

(defn- parse [body] (json/parse-string body true))

(defn client
  "A map of fns {:get-json :get-raw :get-paginated :get-redirected-log
  :post-json :patch-json}, each taking a path (\"repos/o/r\", \"/repos/o/r\" or a full URL). opts: :token, :request (a fn
  from {:url :headers :throw-exceptions, plus :method and :body on writes} to
  {:status :headers :body}). :post-json and :patch-json take a path and a map
  to send as JSON, and return the parsed reply."
  [opts]
  {:get-json      (fn [path] (parse (:body (request! opts path json-accept))))
   :get-raw       (fn [path] (:body (request! opts path "application/vnd.github.raw+json")))
   :get-paginated (fn [path]
                    (loop [url (with-per-page path) acc []]
                      (let [resp (request! opts url json-accept)
                            body (parse (:body resp))
                            acc  (if (sequential? body) (into acc body) (conj acc body))]
                        (if-let [nxt (next-link (header (:headers resp) "link"))]
                          (recur nxt acc)
                          acc))))
   :get-redirected-log (fn [path] (redirected-log! opts path))
   :post-json     (fn [path data]
                    (parse (:body (request! opts path json-accept :post (json/generate-string data)))))
   :patch-json    (fn [path data]
                    (parse (:body (request! opts path json-accept :patch (json/generate-string data)))))})

(defn env-client
  "A client with the token from GH_TOKEN (none when unset)."
  []
  (client {:token (System/getenv "GH_TOKEN")}))

(defn status-of
  "The HTTP status carried by an exception from this namespace, or nil."
  [e]
  (:status (ex-data e)))
