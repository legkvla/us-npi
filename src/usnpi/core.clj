(ns usnpi.core
  (:gen-class)
  (:require [usnpi.sync :as sync]
            [usnpi.npi :as npi]
            [usnpi.migrate :as migrate]
            [usnpi.tasks :as tasks]
            [usnpi.beat :as beat]
            [ring.util.codec]
            [ring.util.io]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [clojure.string :as str]
            [route-map.core :as routing]))

(defn form-decode [s] (clojure.walk/keywordize-keys (ring.util.codec/form-decode s)))

(def routes
  {:GET (fn [req] {:status 200 :body (pr-str req)})
   "practitioner" {:GET #'npi/get-pracitioners
                   "$batch" {:GET #'npi/get-practitioners-by-ids}
                   [:npi] {:GET #'npi/get-practitioner}}})


(defn allow [origin resp]
  (merge-with
    merge resp
    {:headers
     {"Access-Control-Allow-Origin" origin
      "Access-Control-Allow-Credentials" "true"
      "Access-Control-Expose-Headers" "Location, Content-Location, Category, Content-Type, X-total-count"}}))

(defn cors-mw [f]
  (fn [{meth :request-method  hs :headers :as req}]
    (if (= :options meth)
      (let [headers (get hs "access-control-request-headers")
            origin (get hs "origin")
            meth  (get hs "access-control-request-method")]
        {:status 200
         :body {:message "preflight complete"}
         :headers {"Access-Control-Allow-Headers" headers
                   "Access-Control-Allow-Methods" meth
                   "Access-Control-Allow-Origin" origin
                   "Access-Control-Allow-Credentials" "true"
                   "Access-Control-Expose-Headers" "Location, Content-Location, Category, Content-Type, X-total-count"}})
      (f req))))


(defn index [{uri :uri qs :query-string :as req}]
  (println "GET " uri " " qs)
  (if-let [h (routing/match [:get (str/lower-case uri)] routes)]
    (let [params (when qs (form-decode qs))]
      (-> ((:match h) (assoc req :route-params (:params h) :params params))
          (update :headers (fn [x] (merge (or x {})
                                    {"Content-Type" "application/json"
                                     "Access-Control-Allow-Origin" (str (get-in req [:headers "origin"]))
                                     "Access-Control-Allow-Credentials" "true"
                                     "Access-Control-Expose-Headers" "Location, Content-Location, Category, Content-Type, X-total-count"})))))

    {:status 404
     :body (str "Url " (str/lower-case uri) " not found " (keys routes))}))

(defn start [& [port]]
  (migrate/init)
  (tasks/init)
  (beat/start)
  (let [port (or port 8080)]
    (log/infof "Start server on port %s" port)
    (server/run-server (cors-mw #'index) {:port port})))

(defn -main [& _]
  (start))
