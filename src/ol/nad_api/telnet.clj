(ns ol.nad-api.telnet
  "NAD receiver telnet connection and wire format helpers.

  Telnet protocol details (from T778 testing):
  - Send format: `\\n{command}\\r`
  - Receive format: Response starts with `\\n`, ends with `\\r`
  - Default port: 23
  - Device sends `Main.Model=<model>` on initial connection"
  (:require
   [clojure.string :as str]
   [ol.nad-api.sockets :as sockets])
  (:import
   [java.net Socket]))

(def default-config
  "Default telnet connection configuration."
  {:host       nil
   :port       23
   :timeout-ms 2000})

(defn make-config
  "Creates a telnet connection config for the given `host`.

  Options:
  - `:port` - Telnet port (default 23)
  - `:timeout-ms` - Connection/read timeout in milliseconds (default 2000)

  ```clojure
  (make-config \"10.0.0.1\")
  ;=> {:host \"10.0.0.1\" :port 23 :timeout-ms 2000}

  (make-config \"10.0.0.1\" :port 2323 :timeout-ms 5000)
  ;=> {:host \"10.0.0.1\" :port 2323 :timeout-ms 5000}
  ```"
  [host & {:keys [port timeout-ms]
           :or   {port 23 timeout-ms 2000}}]
  {:host       host
   :port       port
   :timeout-ms timeout-ms})

(defn wrap-command
  "Wraps command with telnet line endings for sending.

  Protocol: Send `\\n{command}\\r`

  ```clojure
  (wrap-command \"Main.Power?\")
  ;=> \"\\nMain.Power?\\r\"
  ```"
  [cmd]
  (str "\n" cmd "\r"))

(defn unwrap-response
  "Strips line endings from response.

  Protocol: Response starts with `\\n`, ends with `\\r`

  ```clojure
  (unwrap-response \"\\nMain.Power=On\\r\")
  ;=> \"Main.Power=On\"
  ```"
  [response]
  (-> response
      (str/replace #"^\n" "")
      (str/replace #"\r$" "")))

(defn parse-response
  "Parses `Domain.Command=Value` response, returns the value.

  Returns nil if response has no equals sign.

  ```clojure
  (parse-response \"Main.Power=On\")
  ;=> \"On\"

  (parse-response \"Main.Volume=-48\")
  ;=> \"-48\"
  ```"
  [response]
  (when-let [idx (str/index-of response "=")]
    (subs response (inc idx))))

;;; Connection management

(defn connected?
  "Returns true if the connection is open.

  ```clojure
  (connected? conn)
  ;=> true
  ```"
  [{:keys [^Socket socket]}]
  (and socket
       (not (.isClosed socket))
       (.isConnected socket)))

(defn disconnect
  "Closes the connection.

  Safe to call on already-closed connections.

  ```clojure
  (disconnect conn)
  ;=> nil
  ```"
  [{:keys [socket]}]
  (sockets/close socket))

(defn- read-response
  "Reads a single response from the connection.

  Reads until carriage return delimiter, strips line endings."
  [{:keys [socket timeout-ms]}]
  (let [raw (sockets/read-until socket \return timeout-ms)]
    (unwrap-response raw)))

(defn connect
  "Connects to NAD receiver at `host`:`port`.

  Returns a connection map with:
  - `:socket` - The underlying socket
  - `:timeout-ms` - Read timeout
  - `:model` - The device model (read on connect)

  The NAD device sends `Main.Model=<model>` immediately on connection.

  ```clojure
  (connect \"10.0.0.1\" 23 2000)
  ;=> {:socket #<Socket> :timeout-ms 2000 :model \"T778\"}
  ```"
  [host port timeout-ms]
  (let [socket           (sockets/connect host port timeout-ms)
        conn             {:socket socket :timeout-ms timeout-ms}
        ;; NAD sends model on connect
        initial-response (read-response conn)
        model            (parse-response initial-response)]
    (assoc conn :model model)))

(defn send-command
  "Sends a command and returns the response.

  The command should be the raw command string (e.g., `\"Main.Power?\"`).
  Returns the unwrapped response (e.g., `\"Main.Power=On\"`).

  ```clojure
  (send-command conn \"Main.Power?\")
  ;=> \"Main.Power=On\"
  ```"
  [{:keys [socket] :as conn} cmd]
  (sockets/write socket (wrap-command cmd))
  (read-response conn))
