(ns ol.nad-api
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [donut.system :as ds]
   [ol.nad-api.telnet :as telnet]
   [ol.nad-api.web :as web]
   [org.httpkit.server :as http]
   [ring.middleware.defaults
    :refer [api-defaults wrap-defaults]])
  (:import
   [java.io File]
   [java.util.concurrent Executors])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- make-device-component
  "Creates a donut.system component definition for a NAD device.

  Returns an atom containing the connection to support reconnection."
  [device-config]
  #::ds{:config {:host       (:host device-config)
                 :port       (or (:port device-config) 23)
                 :timeout-ms (or (:timeout-ms device-config) 2000)}
        :start  (fn [{::ds/keys [config]}]
                  (let [{:keys [host port timeout-ms]} config
                        conn                           (-> (telnet/connect host port timeout-ms)
                                                           (telnet/introspect))]
                    (atom conn)))
        :stop   (fn [{:keys [::ds/instance]}]
                  (when instance
                    (telnet/disconnect @instance)))})

(defn- device-ref
  "Creates a local ref to a device component by name."
  [device-name]
  (ds/local-ref [(keyword device-name)]))

(defn system
  "Creates the donut.system definition.

  Dynamically creates device components based on `:nad-devices` in env config."
  [env]
  (let [nad-devices       (:nad-devices env)
        ;; Create device components: {:nad-t778 #::ds{...}, :nad-t787 #::ds{...}}
        device-components (into {}
                                (map (fn [{:keys [name] :as device-config}]
                                       [(keyword name)
                                        (make-device-component device-config)]))
                                nad-devices)
        ;; Create refs to all device components for the handler
        device-refs       (into {}
                                (map (fn [{:keys [name]}]
                                       [name (device-ref name)]))
                                nad-devices)]
    {::ds/defs
     {:env env
      :app (merge
            device-components
            {:nad-handler #::ds{:config {:devices device-refs}
                                :start  (fn [{::ds/keys [config]}]
                                          (web/handler (:devices config)))}
             :http        #::ds{:config {:http        (ds/ref [:env :http])
                                         :nad-handler (ds/local-ref [:nad-handler])}
                                :start  (fn [{::ds/keys [config]}]
                                          (let [{:keys [nad-handler]} config
                                                port                  (-> config :http :port)
                                                opts                  (merge
                                                                       {:worker-pool                (Executors/newVirtualThreadPerTaskExecutor)
                                                                        :legacy-return-value?       false
                                                                        :legacy-content-length?     false
                                                                        :legacy-unsafe-remote-addr? false}
                                                                       (:http config))]
                                            (println "Listening on" port)
                                            (http/run-server
                                             (wrap-defaults nad-handler (assoc api-defaults :static {:resources "public"}))
                                             opts)))
                                :stop   (fn [{:keys [::ds/instance]}]
                                          (when instance
                                            (http/server-stop! instance {:timeout 100})))}})}}))

(defn read-env [f]
  (aero/read-config f))

(def system_ (atom nil))

(defn start [env]
  (reset! system_ (ds/start (system env))))

(defn stop []
  (reset! system_ (ds/stop @system_)))

(defn parse-args
  "Parses command line arguments for --config-file option.

  ```clojure
  (parse-args [\"--config-file\" \"/path/to/config.edn\"])
  ;=> {:config-file \"/path/to/config.edn\"}

  (parse-args [])
  ;=> {}
  ```"
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[arg & rest-args] args]
        (if (= arg "--config-file")
          (recur (rest rest-args) (assoc opts :config-file (first rest-args)))
          (recur rest-args opts))))))

(defn xdg-config-home
  "Returns XDG_CONFIG_HOME or ~/.config as fallback."
  []
  (or (System/getenv "XDG_CONFIG_HOME")
      (str (System/getProperty "user.home") File/separator ".config")))

(defn- configuration-directory
  "Returns $CONFIGURATION_DIRECTORY if set (systemd), nil otherwise."
  []
  (System/getenv "CONFIGURATION_DIRECTORY"))

(defn find-config-file
  "Finds config file in order of precedence:

  1. `:config-file` in opts (from --config-file argument)
  2. ./config.edn
  3. $CONFIGURATION_DIRECTORY/config.edn (systemd ConfigurationDirectory)
  4. $XDG_CONFIG_HOME/nad-api/config.edn (or ~/.config/nad-api/config.edn)

  Throws if no config file is found.

  ```clojure
  (find-config-file {:config-file \"/path/to/config.edn\"})
  ;=> \"/path/to/config.edn\"
  ```"
  [opts]
  (let [candidates (remove nil?
                           [(:config-file opts)
                            "config.edn"
                            (when-let [conf-dir (configuration-directory)]
                              (str conf-dir File/separator "config.edn"))
                            (str (xdg-config-home) File/separator "nad-api" File/separator "config.edn")])]
    (or (first (filter #(.exists (io/file %)) candidates))
        (throw (ex-info "No config file found"
                        {:searched candidates
                         :hint     "Create config.edn or use --config-file <path>"})))))

(defn -main [& args]
  (let [opts        (parse-args args)
        config-file (find-config-file opts)]
    (println "Using config:" config-file)
    (start (read-env config-file))))

(comment
  (start (read-env "config.edn"))
  (stop)
  ;;
  )
