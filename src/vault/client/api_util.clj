(ns vault.client.api-util
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [org.httpkit.client :as http])
  (:import
    (clojure.lang
      ExceptionInfo)
    (java.security
      MessageDigest)))


;; ## API Utilities

(defmacro supports-not-found
  "Tries to perform the body, which likely includes an API call. If a `404` `::api-error` occurs, looks for and returns
  the value of `:not-found` in `on-fail-opts` if present"
  [on-fail-opts & body]
  `(try
     ~@body
     (catch ExceptionInfo ex#
       (let [api-fail-options# ~on-fail-opts]
         (if (and (contains? api-fail-options# :not-found)
                  (= ::api-error (:type (ex-data ex#)))
                  (= 404 (:status (ex-data ex#))))
           (:not-found api-fail-options#)
           (throw ex#))))))


(defn- ^:no-doc keyword-swap-chars
  "Rewrites keyword map keys with underscores changed to dashes."
  [value find replace]
  (let [replace-kw #(-> % name (str/replace find replace) keyword)
        xf-entry (juxt (comp replace-kw key) val)]
    (walk/postwalk
      (fn xf-maps
        [x]
        (if (map? x)
          (into {} (map xf-entry) x)
          x))
      value)))


(defn ^:no-doc kebabify-keys
  "Rewrites keyword map keys with underscores changed to dashes."
  [value]
  (keyword-swap-chars value "_" "-"))


(defn ^:no-doc snakeify-keys
  "Rewrites keyword map keys with dashes changed to underscores."
  [value]
  (keyword-swap-chars value "-" "_"))


(defn ^String encode-hex-string
  "Encode an array of bytes to hex string."
  [^bytes bytes]
  (->> (map #(format "%02x" %) bytes)
       (apply str)))


(defn ^:no-doc sha-256
  "Geerate a SHA-2 256 bit digest from a string."
  [s]
  (let [hasher (MessageDigest/getInstance "SHA-256")
        str-bytes (.getBytes (str s) "UTF-8")]
    (.update hasher str-bytes)
    (encode-hex-string (.digest hasher))))


(defn- ^:no-doc clean-body
  "Cleans up a response from the Vault API by rewriting some keywords and
  dropping extraneous information. Note that this changes the `:data` in the
  response to the original result to preserve accuracy."
  [body]
  (when body
    (let [parsed (json/parse-string body true)]
      (-> parsed
          (dissoc :data)
          (kebabify-keys)
          (assoc :data (:data parsed))
          (->> (into {} (filter (comp some? val))))))))


(defn- body-errors
  "Return string representation of errors found in body of response or nil."
  [data]
  (try
    (when-let [body (json/parse-string (:body data) true)]
      (if (:errors body)
        (str/join ", " (:errors body))
        (pr-str body)))
    (catch Exception _
      nil)))


(defn ^:no-doc api-error
  "Inspects an exception and returns a cleaned-up version if the type is well
  understood. Otherwise returns the original error."
  [ex]
  (let [data (ex-data ex)
        error (:error data)
        status (:status data)]
    (if (or error (and status (<= 400 status)))
      (let [errors (if error (ex-message error) (body-errors data))]
        (ex-info (str "Vault API server errors: " errors)
                 {:type ::api-error
                  :status status
                  :errors errors}
                 ex))
      ex)))


(defn- handle-response-errors
  "Throws exceptions from http-kit response mimicing clj-http."
  [response]
  (cond
    (:error response)
    (throw (ex-info "Error in api response" response (:error response)))

    (<= 400 (:status response 0))
    (throw (ex-info (str "status: " (:status response)) response))

    :else
    response))


(defn ^:no-doc do-api-request
  "Performs a request against the API, following redirects at most twice. The
  `request-url` should be the full API endpoint."
  [method request-url req]
  (let [redirects (::redirects req 0)]
    (when (<= 2 redirects)
      (throw (ex-info (str "Aborting Vault API request after " redirects " redirects")
                      {:method method, :url request-url})))
    (let [resp (try
                 (->
                   req
                   (cond->
                     (and (= :json (:content-type req))
                          (:body req))
                     (update :body json/generate-string))
                   (assoc :method method :url request-url)
                   (http/request)
                   (deref)
                   (handle-response-errors)
                   (update :body clean-body))
                 (catch Exception ex
                   (log/debug "Exception " ex)
                   (throw (api-error ex))))]
      (if-let [location (and (#{303 307} (:status resp))
                             (get-in resp [:headers "Location"]))]
        (do (log/debug "Retrying API request redirected to " location)
            (recur method location (assoc req ::redirects (inc redirects))))
        resp))))


(defn ^:no-doc api-request
  "Helper method to perform an API request with common headers and values.
  Currently always uses API version `v1`. The `path` should be relative to the
  version root."
  [client method path req]
  ;; Check API path.
  (when-not (and (string? path) (not (str/blank? path)))
    (throw (java.lang.IllegalArgumentException.
             (str "API path must be a non-empty string, got: "
                  (pr-str path)))))
  ;; Check client authentication.
  (when-not (some-> client :auth deref :client-token)
    (throw (java.lang.IllegalStateException.
             "Cannot call API path with unauthenticated client.")))
  ;; Call API with standard arguments.
  (do-api-request
    method
    (str (:api-url client) "/v1/" path)
    (merge
      (:http-opts client)
      {:accept :json}
      req
      {:headers (merge {"X-Vault-Token" (:client-token @(:auth client))}
                       (:headers req))})))


(defn ^:no-doc unwrap-secret
  "Common function to call the token unwrap endpoint."
  [client wrap-token]
  (do-api-request
    :post (str (:api-url client) "/v1/sys/wrapping/unwrap")
    (merge
      (:http-opts client)
      {:headers {"X-Vault-Token" wrap-token}
       :content-type :json
       :accept :json})))
