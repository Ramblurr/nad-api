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

(set! *warn-on-reflection* true)

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

  The T778 pushes unsolicited temperature data periodically, so responses
  often contain multiple lines (temp readings mixed with actual response).
  Pass `cmd` to find the specific command you care about.

  Returns nil if response has no equals sign or command not found.

  ```clojure
  (parse-response \"Main.Power=On\")
  ;=> \"On\"

  (parse-response \"Main.Volume=-48\")
  ;=> \"-48\"

  ;; Multi-line response with unsolicited temp data
  (parse-response \"Main.Temp.PSU=32\\nMain.Power=On\" \"Main.Power\")
  ;=> \"On\"
  ```"
  ([response]
   (parse-response response nil))
  ([response cmd]
   (if cmd
     ;; Search for the specific command line in multi-line response
     (let [prefix (str cmd "=")
           lines  (str/split-lines response)]
       (some (fn [line]
               (when (str/starts-with? line prefix)
                 (subs line (count prefix))))
             lines))
     ;; Original behavior - first = sign
     (when-let [idx (str/index-of response "=")]
       (subs response (inc idx))))))

(def ^:private operator-pattern #"[?=+\-]")

(defn parse-command
  "Extracts the command name from a command string.

  Strips the operator (`?`, `=`, `+`, `-`) and any value.

  ```clojure
  (parse-command \"Main.Power?\")
  ;=> \"Main.Power\"

  (parse-command \"Main.Power=On\")
  ;=> \"Main.Power\"

  (parse-command \"Main.Volume+\")
  ;=> \"Main.Volume\"
  ```"
  [cmd]
  (when (and (not (str/blank? cmd))
             (re-find operator-pattern cmd))
    (first (str/split cmd operator-pattern 2))))

(defn parse-introspection-response
  "Parses a multi-line introspection response into a set of command names.

  The introspection response from the bare `?` command contains lines like:
  `Main.Power=Off`
  `Main.Volume=-48`

  Returns a set of command names (e.g., `#{\"Main.Power\" \"Main.Volume\"}`).

  ```clojure
  (parse-introspection-response \"Main.Power=Off\\nMain.Volume=-48\")
  ;=> #{\"Main.Power\" \"Main.Volume\"}
  ```"
  [response]
  (->> (str/split-lines response)
       (keep (fn [line]
               (when-let [idx (str/index-of line "=")]
                 (subs line 0 idx))))
       (into #{})))

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
  - `:host` - The device hostname/IP
  - `:port` - The device port
  - `:timeout-ms` - Read timeout
  - `:model` - The device model (read on connect)

  The NAD device sends `Main.Model=<model>` immediately on connection.

  ```clojure
  (connect \"10.0.0.1\" 23 2000)
  ;=> {:socket #<Socket> :host \"10.0.0.1\" :port 23 :timeout-ms 2000 :model \"T778\"}
  ```"
  [host port timeout-ms]
  (let [socket           (sockets/connect host port timeout-ms)
        conn             {:socket socket :host host :port port :timeout-ms timeout-ms}
        ;; NAD sends model on connect
        initial-response (read-response conn)
        model            (parse-response initial-response)]
    (assoc conn :model model)))

(defn- read-all-available
  "Reads all available responses from the connection.

  The T778 pushes unsolicited data (temperature readings) periodically.
  This function reads all available lines until timeout, collecting both
  unsolicited data and command responses.

  Uses a short inter-line timeout to detect end of data quickly.
  Returns lines joined by newlines (no trailing newline)."
  [{:keys [socket]} read-timeout-ms]
  (let [lines (java.util.ArrayList.)]
    (try
      (loop []
        (let [response (sockets/read-until socket \return read-timeout-ms)]
          (.add lines (unwrap-response response))
          (recur)))
      (catch java.net.SocketTimeoutException _
        ;; Timeout means no more data - that's expected
        (str/join "\n" lines)))))

(defn introspect
  "Sends the introspection command `?` and discovers supported commands.

  Returns the connection with `:supported-commands` added - a set of
  command names that the device supports.

  ```clojure
  (-> (connect \"10.0.0.1\" 23 2000)
      (introspect))
  ;=> {:socket ... :model \"T778\" :supported-commands #{\"Main.Power\" ...}}
  ```"
  [{:keys [socket] :as conn}]
  ;; Use a shorter timeout for introspection reads since we need to detect end
  (sockets/write socket (wrap-command "?"))
  (let [response  (read-all-available conn 500)
        supported (parse-introspection-response response)]
    (assoc conn :supported-commands supported)))

(defn reconnect
  "Disconnects and reconnects to the NAD receiver.

  Closes the existing socket and creates a new connection using the
  stored host, port, and timeout configuration. Re-introspects the device.

  Returns a new connection map with fresh socket and updated model/commands.

  ```clojure
  (reconnect conn)
  ;=> {:socket #<Socket> :host \"10.0.0.1\" :port 23 ...}
  ```"
  [{:keys [host port timeout-ms] :as conn}]
  (disconnect conn)
  (-> (connect host port timeout-ms)
      (introspect)))

(defn- validate-command!
  "Validates that a command is supported (if introspection data is present).

  Throws if the command is not in the supported set."
  [{:keys [supported-commands]} cmd]
  (when supported-commands
    (let [cmd-name (parse-command cmd)]
      (when-not (contains? supported-commands cmd-name)
        (throw (ex-info (str "Command '" cmd-name "' is not supported by this device")
                        {:command            cmd
                         :command-name       cmd-name
                         :supported-commands supported-commands}))))))

(def ^:private default-read-timeout-ms
  "Default timeout for reading command responses.

  Short enough to not block too long, but long enough for device to respond."
  500)

(defn send-command
  "Sends a command and returns all available response data.

  The command should be the raw command string (e.g., `\"Main.Power?\"`).
  Returns all available response lines as a multi-line string.

  The T778 pushes unsolicited data (temperature) periodically, so responses
  may contain multiple lines. Use `parse-response` with a command name to
  extract the specific value you need.

  If the connection has been introspected (has `:supported-commands`),
  validates that the command is supported before sending.

  ```clojure
  (send-command conn \"Main.Power?\")
  ;=> \"Main.Temp.PSU=36\\nMain.Power=On\"
  ```"
  [{:keys [socket] :as conn} cmd]
  (validate-command! conn cmd)
  (sockets/write socket (wrap-command cmd))
  (read-all-available conn default-read-timeout-ms))
