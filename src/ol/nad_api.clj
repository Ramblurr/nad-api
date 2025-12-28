(ns ol.nad-api
  (:require
   [ring.util.response :as response]
   [ring.middleware.defaults
    :refer [wrap-defaults api-defaults]]
   [donut.system :as ds]
   [org.httpkit.server :as http]
   [reitit.ring :as ring])
  (:gen-class))

(set! *warn-on-reflection* true)

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      {:get (fn [_request]
              (-> "Hello world"
                  (response/response)
                  (response/header "content-type" "text/html")))}]])))

(def system
  {::ds/defs
   {:app {:http #::ds{:config {:port 8002}
                      :start  (fn [{::ds/keys [config]}]
                                (let [{:keys [port]} config]
                                  (tap> config)
                                  (http/run-server
                                   (wrap-defaults
                                    handler
                                    (assoc api-defaults :static {:resources "public"}))
                                   {:port                       port
                                    :legacy-content-length?     false
                                    :legacy-unsafe-remote-addr? false})))
                      :stop   (fn [{:keys [::ds/instance]}]
                                (when instance (instance :timeout 100)))}}}})

(def system_ (atom nil))

(defn start []
  (reset! system_ (ds/start system)))

(defn stop []
  (reset! system_ (ds/stop @system_)))
(defn -main [& _args])

(comment
  (start)
  (stop)
  (handler {:uri "/" :request-method :get})
  ;;
  )
