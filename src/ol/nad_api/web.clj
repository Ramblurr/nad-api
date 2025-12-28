(ns ol.nad-api.web
  "Ring handler generation from NAD introspection data.

  Generates REST API routes for supported NAD commands, namespaced by device:
  - GET /api/{device}/Main.Power - Query current value (? operator)
  - POST /api/{device}/Main.Power - Set/modify value (=, +, - operators)
  - GET /api/{device} - Device discovery (info + supported commands)
  - GET /api - List all devices

  Routes are generated only for commands that appear in both:
  - The device's introspection data (supported-commands set)
  - The commands registry (`ol.nad-api.commands/commands`)"
  (:require
   [charred.api :as json]
   [clojure.set :as set]
   [ol.nad-api.commands :as commands]
   [ol.nad-api.telnet :as telnet]
   [reitit.ring :as ring]
   [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(defn- deref-conn
  "Dereferences connection if it's an atom, otherwise returns as-is."
  [conn-or-atom]
  (if (instance? clojure.lang.Atom conn-or-atom)
    @conn-or-atom
    conn-or-atom))

(defn available-commands
  "Returns the set of commands available for routing.

  Intersection of device's `supported-commands` and the registry.

  ```clojure
  (available-commands #{\"Main.Power\" \"Main.Volume\" \"Unknown.Cmd\"})
  ;=> #{\"Main.Power\" \"Main.Volume\"}
  ```"
  [supported-commands]
  (if (seq supported-commands)
    (set/intersection supported-commands (set (keys commands/commands)))
    #{}))

(defn- json-response
  "Creates a Ring response with JSON body and content-type header."
  [status body]
  (-> (response/response (json/write-json-str body))
      (response/status status)
      (response/header "Content-Type" "application/json")))

(defn- parse-request-body
  "Parses JSON request body, returns map with keyword keys."
  [request]
  (when-let [body (:body request)]
    (try
      (json/read-json (slurp body) :key-fn keyword)
      (catch Exception _
        nil))))

(defn- query-handler
  "Creates GET handler for a command.

  `conn-ref` can be a connection map or an atom containing one."
  [cmd conn-ref]
  (fn [_request]
    (try
      (let [conn     (deref-conn conn-ref)
            wire-cmd (commands/build-command cmd "?" nil)
            response (telnet/send-command conn wire-cmd)
            value    (commands/coerce-value cmd (telnet/parse-response response cmd))]
        (json-response 200 {:command cmd :value value}))
      (catch java.net.SocketTimeoutException _
        (json-response 504 {:error   "timeout"
                            :message "Device did not respond"}))
      (catch Exception e
        (json-response 503 {:error   "connection-error"
                            :message (.getMessage e)})))))

(defn- modify-handler
  "Creates POST handler for a command.

  T778 behavior: The device pushes unsolicited temperature data periodically.
  SET commands may or may not echo confirmation. We send the command, check
  if we got a confirmation, and query if not.

  `conn-ref` can be a connection map or an atom containing one."
  [cmd conn-ref]
  (fn [request]
    (let [{:keys [operator value]} (parse-request-body request)]
      (cond
        (nil? operator)
        (json-response 400 {:error   "missing-operator"
                            :message "Request body must include 'operator'"})

        (not (commands/valid-operator? cmd operator))
        (json-response 400 {:error           "invalid-operator"
                            :message         (str "Operator '" operator "' is not valid for " cmd)
                            :valid_operators (vec (get-in commands/commands [cmd :operators]))})

        (and (= operator "=") (nil? value))
        (json-response 400 {:error   "missing-value"
                            :message "Operator '=' requires a 'value'"})

        :else
        (try
          (let [conn         (deref-conn conn-ref)
                wire-cmd     (commands/build-command cmd operator value)
                response     (telnet/send-command conn wire-cmd)
                result-value (telnet/parse-response response cmd)]
            ;; If device echoed confirmation, use it; otherwise query
            (if result-value
              (json-response 200 {:command  cmd
                                  :operator operator
                                  :value    (commands/coerce-value cmd result-value)})
              ;; No confirmation - query to get current value
              (let [query-cmd   (commands/build-command cmd "?" nil)
                    query-resp  (telnet/send-command conn query-cmd)
                    query-value (telnet/parse-response query-resp cmd)]
                (json-response 200 {:command  cmd
                                    :operator operator
                                    :value    (commands/coerce-value cmd query-value)}))))
          (catch java.net.SocketTimeoutException _
            (json-response 504 {:error   "timeout"
                                :message "Device did not respond"}))
          (catch Exception e
            (json-response 503 {:error   "connection-error"
                                :message (.getMessage e)})))))))

(defn- device-discovery-handler
  "Creates GET handler for /api/{device} that returns device info and supported commands.

  `conn-ref` can be a connection map or an atom containing one."
  [device-name conn-ref]
  (fn [_request]
    (let [conn (deref-conn conn-ref)
          cmds (available-commands (:supported-commands conn))]
      (json-response
       200
       {:device            {:name  device-name
                            :host  (:host conn)
                            :port  (:port conn)
                            :model (:model conn)}
        :supportedCommands (into {}
                                 (map (fn [cmd]
                                        [cmd (get commands/commands cmd)]))
                                 (sort cmds))}))))

(defn- api-root-handler
  "Creates GET handler for /api that lists all devices.

  `devices` is a map of device-name -> connection (or atom containing one)."
  [devices]
  (fn [_request]
    (json-response
     200
     {:devices (into {}
                     (map (fn [[device-name conn-ref]]
                            (let [conn (deref-conn conn-ref)]
                              [device-name {:host  (:host conn)
                                            :port  (:port conn)
                                            :model (:model conn)}])))
                     (sort-by first devices))})))

(defn- reconnect-handler
  "Creates POST handler for /api/{device}/reconnect.

  Disconnects and reconnects to the device, refreshing the telnet connection.
  Requires `conn-ref` to be an atom so the connection can be swapped."
  [device-name conn-ref]
  (fn [_request]
    (if-not (instance? clojure.lang.Atom conn-ref)
      (json-response 501 {:error   "not-implemented"
                          :message "Reconnect not available (connection not mutable)"})
      (try
        (let [new-conn (swap! conn-ref telnet/reconnect)]
          (json-response 200 {:device device-name
                              :status "reconnected"
                              :host   (:host new-conn)
                              :port   (:port new-conn)
                              :model  (:model new-conn)}))
        (catch Exception e
          (json-response 503 {:error   "reconnect-failed"
                              :message (.getMessage e)}))))))

(defn make-device-routes
  "Generates Reitit route data for a single device's supported commands.

  Each command becomes a route at `/api/{device-name}/{command}`:
  - GET handler for query operator (?)
  - POST handler for set/modify operators (=, +, -)

  Also includes:
  - GET /api/{device-name} for device discovery
  - POST /api/{device-name}/reconnect to force reconnection

  `conn-ref` can be a connection map or an atom containing one.
  For reconnect to work, `conn-ref` must be an atom.

  Returns vector of route definitions suitable for `reitit.ring/router`.

  ```clojure
  (make-device-routes \"nad-t778\" conn)
  ;=> [[\"/api/nad-t778\" {:get ...}]
  ;    [\"/api/nad-t778/reconnect\" {:post ...}]
  ;    [\"/api/nad-t778/Main.Power\" {:get ... :post ...}]
  ;    [\"/api/nad-t778/Main.Volume\" {:get ... :post ...}]]
  ```"
  [device-name conn-ref]
  (let [conn      (deref-conn conn-ref)
        supported (:supported-commands conn)
        cmds      (available-commands supported)
        prefix    (str "/api/" device-name)]
    (into [[prefix {:get (device-discovery-handler device-name conn-ref)}]
           [(str prefix "/reconnect") {:post (reconnect-handler device-name conn-ref)}]]
          (map (fn [cmd]
                 [(str prefix "/" cmd)
                  {:get  (query-handler cmd conn-ref)
                   :post (modify-handler cmd conn-ref)}]))
          cmds)))

(defn handler
  "Creates a Ring handler for NAD receiver control with multiple devices.

  Takes a map of device-name -> introspected connection and generates
  routes for all devices and their commands.

  ```clojure
  (handler {\"nad-t778\" conn1 \"nad-t787\" conn2})
  ;=> Ring handler function
  ```"
  [devices]
  (let [device-routes (mapcat (fn [[device-name conn]]
                                (make-device-routes device-name conn))
                              devices)
        all-routes    (into [["/api" {:get (api-root-handler devices)}]]
                            device-routes)]
    (ring/ring-handler
     (ring/router all-routes)
     (ring/create-default-handler
      {:not-found (fn [_]
                    (json-response 404 {:error   "not-found"
                                        :message "Device or command not found"}))}))))
