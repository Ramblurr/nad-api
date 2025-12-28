(ns ol.nad-api.web
  "Ring handler generation from NAD introspection data.

  Generates REST API routes for supported NAD commands:
  - GET /api/Main.Power - Query current value (? operator)
  - POST /api/Main.Power - Set/modify value (=, +, - operators)

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
  "Creates GET handler for a command."
  [cmd conn]
  (fn [_request]
    (try
      (let [wire-cmd (commands/build-command cmd "?" nil)
            response (telnet/send-command conn wire-cmd)
            value    (telnet/parse-response response cmd)]
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
  if we got a confirmation, and query if not."
  [cmd conn]
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
          (let [wire-cmd     (commands/build-command cmd operator value)
                response     (telnet/send-command conn wire-cmd)
                result-value (telnet/parse-response response cmd)]
            ;; If device echoed confirmation, use it; otherwise query
            (if result-value
              (json-response 200 {:command  cmd
                                  :operator operator
                                  :value    result-value})
              ;; No confirmation - query to get current value
              (let [query-cmd    (commands/build-command cmd "?" nil)
                    query-resp   (telnet/send-command conn query-cmd)
                    query-value  (telnet/parse-response query-resp cmd)]
                (json-response 200 {:command  cmd
                                    :operator operator
                                    :value    query-value}))))
          (catch java.net.SocketTimeoutException _
            (json-response 504 {:error   "timeout"
                                :message "Device did not respond"}))
          (catch Exception e
            (json-response 503 {:error   "connection-error"
                                :message (.getMessage e)})))))))

(defn make-routes
  "Generates Reitit route data for supported commands.

  Each command becomes a route at `/api/{command}`:
  - GET handler for query operator (?)
  - POST handler for set/modify operators (=, +, -)

  Returns vector of route definitions suitable for `reitit.ring/router`.

  ```clojure
  (make-routes #{\"Main.Power\" \"Main.Volume\"} conn)
  ;=> [[\"/api/Main.Power\" {:get ... :post ...}]
  ;    [\"/api/Main.Volume\" {:get ... :post ...}]]
  ```"
  [supported-commands conn]
  (let [cmds (available-commands supported-commands)]
    (mapv (fn [cmd]
            [(str "/api/" cmd)
             {:get  (query-handler cmd conn)
              :post (modify-handler cmd conn)}])
          cmds)))

(defn handler
  "Creates a Ring handler for NAD receiver control.

  Takes an introspected connection and generates routes for all
  available commands. The connection is captured as a closure.

  ```clojure
  (let [conn (-> (telnet/connect host port timeout)
                 (telnet/introspect))]
    (handler conn))
  ;=> Ring handler function
  ```"
  [conn]
  (let [supported (:supported-commands conn)
        routes    (make-routes supported conn)]
    (ring/ring-handler
     (ring/router routes)
     (ring/create-default-handler
      {:not-found (fn [_]
                    (json-response 404 {:error   "not-found"
                                        :message "Command not found"}))}))))
