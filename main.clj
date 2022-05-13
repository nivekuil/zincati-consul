#!/usr/bin/env bb

(require '[org.httpkit.server :as srv])

(def consul-url (or (System/getenv "CONSUL_HTTP_ADDR")
                    "http://localhost:8500"))
(def bind-ip (or (System/getenv "BIND_IP") "::"))
(def group->maxdown (or (some-> (System/getenv "GROUP_DOWN_MAP")
                                json/parse-string)
                        {"default" 1}))
(def bind-port (or (some-> (System/getenv "BIND_PORT") parse-long) 15535))

(defn http-get [path]
  (-> (curl/get (format "%s/v1/%s" consul-url path))
      :body json/parse-string))

(defn http-put [path data]
  (-> (curl/put (format "%s/v1/%s" consul-url path)
                {:body (json/generate-string data)})
      :body json/parse-string))

(defn http-delete [path]
  (-> (curl/delete (format "%s/v1/%s" consul-url path))
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

(defn app [{:keys [request-method uri body]}]
  (let [{:strs [group id]} (some-> body slurp json/parse-string (get "client_params"))]
    (case [request-method uri]
      [:get "/metrics"]
      {:body   (:out (shell/sh "socat" "-" "UNIX-CONNECT:/run/zincati/public/metrics.promsock"))
       :status 200}

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
@(promise)
