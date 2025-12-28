(ns ol.nad-api
  (:require
   [ol.nad-api.web :as web]
   [ol.nad-api.telnet :as telnet]
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
   {:env {:nad {:host "10.9.4.12"}}
    :app {:nad         #::ds{:config {:host       (ds/ref [:env :nad :host])
                                      :port       23
                                      :timeout-ms 2000}
                             :start  (fn [{::ds/keys [config]}]
                                       (let [{:keys [host port timeout-ms]} config]
                                         (-> (telnet/connect host port timeout-ms)
                                             (telnet/introspect))))
                             :stop   (fn [{:keys [::ds/instance]}]
                                       (when instance
                                         (telnet/disconnect instance)))}
          :nad-handler #::ds {:config {:nad (ds/local-ref [:nad])}
                              :start  (fn [{::ds/keys [config]}]
                                        (web/handler (:nad config)))}
          :http        #::ds{:config {:port        8002
                                      :nad-handler (ds/local-ref [:nad-handler])}
                             :start  (fn [{::ds/keys [config]}]
                                       (let [{:keys [port nad-handler]} config]
                                         (println "Listening on " port)
                                         (http/run-server
                                          (wrap-defaults
                                           nad-handler
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

(defn -main [& _args]
  (start))

(comment
  (start)
  (stop)
  (handler {:uri "/" :request-method :get})
  ;;
  )
