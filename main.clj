#!/usr/bin/env bb

(require '[org.httpkit.server :as srv])
(require '[clojure.java.io :as io])
(import [java.net UnixDomainSocketAddress StandardProtocolFamily]
        [java.nio.channels SocketChannel]
        [java.nio.charset StandardCharsets]
        [java.nio ByteBuffer])

(def consul-url (or (System/getenv "CONSUL_HTTP_ADDR")
                    "http://localhost:8500"))
(def socket? (str/starts-with? consul-url "unix:"))
(def extra-curl-args (when socket?
                       {:raw-args ["-g" "--unix-socket" (str/replace-first consul-url "unix://" "")]}))
(def bind-ip (or (System/getenv "BIND_IP") "127.0.0.1"))
(def metrics-ip (System/getenv "METRICS_IP"))
(def metrics-port (or (System/getenv "METRICS_PORT") 9090))
(def group->maxdown (or (some-> (System/getenv "GROUP_DOWN_MAP")
                                json/parse-string)
                        {"default" 1}))
(def bind-port (or (some-> (System/getenv "BIND_PORT") parse-long) 15535))

(defn http-get [path]
  (-> (curl/get (format "%s/v1/%s" (if socket? "http://d" consul-url) path)
                extra-curl-args)
      :body json/parse-string))

(defn http-put [path data]
  (-> (curl/put (format "%s/v1/%s" (if socket? "http://d" consul-url) path)
                (merge {:body (json/generate-string data)}
                       extra-curl-args))
      :body json/parse-string))

(defn http-delete [path]
  (-> (curl/delete (format "%s/v1/%s" (if socket? "http://d" consul-url) path)
                   extra-curl-args)
      :body json/parse-string))

(defn all-acquired [group]
  (try (http-get (format "kv/fleetlock/%s/?recurse=true" group))
       (catch Exception e
         (if (= 404 (:status (ex-data e)))
           []
           (throw e)))))

(defn acquired? [group id]
  (try (http-get (format "kv/fleetlock/%s/%s" group id))
       true
       (catch Exception e
         (if (= 404 (:status (ex-data e)))
           false
           (throw e)))))

(defn metrics [_]
  (let [socket-address (UnixDomainSocketAddress/of "/run/zincati/public/metrics.promsock")
        sc             (SocketChannel/open  socket-address)
        bb             (ByteBuffer/allocate 10024)]
    (.read sc bb)
    (.flip bb)
    {:body    (.toString (.decode StandardCharsets/UTF_8 bb))
     :headers {"content-type" "text/plain; version=0.0.4"}
     :status  200}))

(defn app [{:keys [request-method uri body]}]
  (let [{:strs [group id]} (some-> body slurp json/parse-string (get "client_params"))]
    (case [request-method uri]
      [:post "/v1/pre-reboot"]
      (if (acquired? group id)
        {:body   "already acquired"
         :status 200}
        (let [node-count       (count (http-get "catalog/nodes"))
              alive-node-count node-count ;; TODO
              max-down         (group->maxdown group 1)
              acquired         (all-acquired group)
              currently-down   (+ (count acquired) (- node-count alive-node-count))]
          (println "handling pre-reboot for" id "with group" group "acquired" acquired)
          (if (< currently-down max-down)
            (if (http-put (format "kv/fleetlock/%s/%s" group id) acquired)
              {:body   ""
               :status 200}
              {:body   "error writing to consul kv"
               :status 500})
            {:body   "too many nodes down, cannot lock yet"
             :status 503})))

      [:post "/v1/steady-state"]
      (do
        (http-delete (format "kv/fleetlock/%s/%s" group id))
        {:body   ""
         :status 200}))))
(srv/run-server app {:ip bind-ip :port bind-port})
(println "Zincati listening on" bind-ip "port" bind-port)
(when metrics-ip
  (srv/run-server metrics {:ip metrics-ip :port metrics-port})
  (println "Metrics proxy listening on" metrics-ip "port" metrics-port))
@(promise)
