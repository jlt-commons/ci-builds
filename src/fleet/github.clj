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
  redirect target on any host."
  [req]
  (http/get (:url req) (assoc (dissoc req :url) :follow-redirects false)))

(defn- request!
  "One GET. Returns the response map, or throws ex-info on a non-2xx status."
  [{:keys [token request]} path accept]
  (let [resp ((or request default-request)
              {:url              (url-for path)
               :throw-exceptions false
               :headers          (cond-> {"Accept"               accept
                                          "X-GitHub-Api-Version" "2022-11-28"}
                                   (seq token) (assoc "Authorization" (str "Bearer " token)))})
        status (:status resp)]
    (if (and (integer? status) (<= 200 status 299))
      resp
      (throw (ex-info (str "GitHub API " status " for " path)
                      {:status status :path path :body (:body resp)})))))

(def ^:private json-accept "application/vnd.github+json")

(defn- parse [body] (json/parse-string body true))

(defn client
  "A map of fns {:get-json :get-raw :get-paginated}, each taking a path
  (\"repos/o/r\", \"/repos/o/r\" or a full URL). opts: :token, :request (a fn
  from {:url :headers :throw-exceptions} to {:status :headers :body})."
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
                          acc))))})

(defn env-client
  "A client with the token from GH_TOKEN (none when unset)."
  []
  (client {:token (System/getenv "GH_TOKEN")}))

(defn status-of
  "The HTTP status carried by an exception from this namespace, or nil."
  [e]
  (:status (ex-data e)))
