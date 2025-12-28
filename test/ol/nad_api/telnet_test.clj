(ns ol.nad-api.telnet-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.nad-api.telnet :as sut])
  (:import
   [java.net ServerSocket]))

(deftest default-config-test
  (testing "default-config has expected structure"
    (is (map? sut/default-config))
    (is (nil? (:host sut/default-config)))
    (is (= 23 (:port sut/default-config)))
    (is (number? (:timeout-ms sut/default-config)))))

(deftest make-config-test
  (testing "creates config with host and defaults"
    (let [cfg (sut/make-config "10.0.0.1")]
      (is (= "10.0.0.1" (:host cfg)))
      (is (= 23 (:port cfg)))
      (is (= 2000 (:timeout-ms cfg)))))

  (testing "allows overriding port"
    (let [cfg (sut/make-config "10.0.0.1" :port 2323)]
      (is (= 2323 (:port cfg)))))

  (testing "allows overriding timeout"
    (let [cfg (sut/make-config "10.0.0.1" :timeout-ms 5000)]
      (is (= 5000 (:timeout-ms cfg)))))

  (testing "allows overriding both"
    (let [cfg (sut/make-config "10.0.0.1" :port 2323 :timeout-ms 5000)]
      (is (= "10.0.0.1" (:host cfg)))
      (is (= 2323 (:port cfg)))
      (is (= 5000 (:timeout-ms cfg))))))

(deftest wrap-command-test
  (testing "wraps command with telnet line endings"
    (is (= "\nMain.Power?\r" (sut/wrap-command "Main.Power?")))
    (is (= "\nMain.Power=On\r" (sut/wrap-command "Main.Power=On")))
    (is (= "\nMain.Volume+\r" (sut/wrap-command "Main.Volume+")))))

(deftest unwrap-response-test
  (testing "strips leading newline and trailing carriage return"
    (is (= "Main.Power=On" (sut/unwrap-response "\nMain.Power=On\r")))
    (is (= "Main.Model=T778" (sut/unwrap-response "\nMain.Model=T778\r"))))

  (testing "handles response without line endings"
    (is (= "Main.Power=On" (sut/unwrap-response "Main.Power=On"))))

  (testing "handles partial line endings"
    (is (= "Main.Power=On" (sut/unwrap-response "\nMain.Power=On")))
    (is (= "Main.Power=On" (sut/unwrap-response "Main.Power=On\r")))))

(deftest parse-response-test
  (testing "extracts value after equals sign"
    (is (= "On" (sut/parse-response "Main.Power=On")))
    (is (= "Off" (sut/parse-response "Main.Power=Off")))
    (is (= "-48" (sut/parse-response "Main.Volume=-48")))
    (is (= "T778" (sut/parse-response "Main.Model=T778")))
    (is (= "v2.24" (sut/parse-response "Main.Version=v2.24"))))

  (testing "returns nil for response without equals"
    (is (nil? (sut/parse-response "Main.Power")))
    (is (nil? (sut/parse-response ""))))

  (testing "handles values containing equals sign"
    (is (= "foo=bar" (sut/parse-response "Some.Command=foo=bar"))))

  (testing "finds specific command in multi-line response"
    (let [multi-line "Main.Temp.PSU=32\nMain.Temp.Front=28\nMain.Power=On"]
      (is (= "On" (sut/parse-response multi-line "Main.Power")))
      (is (= "32" (sut/parse-response multi-line "Main.Temp.PSU")))
      (is (= "28" (sut/parse-response multi-line "Main.Temp.Front")))))

  (testing "returns nil when command not found in multi-line"
    (let [multi-line "Main.Temp.PSU=32\nMain.Power=On"]
      (is (nil? (sut/parse-response multi-line "Main.Volume")))))

  (testing "handles T778-style temperature dump"
    (let [response (str "Main.Temp.PSU=32\n"
                        "Main.Temp.Front=28\n"
                        "Main.Temp.Surround=29\n"
                        "Main.Fan.Status=1000,0,OK\n"
                        "Main.Power=On")]
      (is (= "On" (sut/parse-response response "Main.Power")))
      (is (= "1000,0,OK" (sut/parse-response response "Main.Fan.Status"))))))

(deftest parse-command-test
  (testing "extracts command name before operator"
    (is (= "Main.Power" (sut/parse-command "Main.Power?")))
    (is (= "Main.Power" (sut/parse-command "Main.Power=On")))
    (is (= "Main.Volume" (sut/parse-command "Main.Volume+")))
    (is (= "Main.Volume" (sut/parse-command "Main.Volume-"))))

  (testing "returns nil for invalid commands"
    (is (nil? (sut/parse-command "")))
    (is (nil? (sut/parse-command "NoOperator")))))

(deftest parse-introspection-response-test
  (testing "parses multi-line introspection response into set of command names"
    (let [response "Main.Model=T778\nMain.Power=Off\nMain.Volume=-48\nMain.Source=6"]
      (is (= #{"Main.Model" "Main.Power" "Main.Volume" "Main.Source"}
             (sut/parse-introspection-response response)))))

  (testing "handles empty response"
    (is (= #{} (sut/parse-introspection-response ""))))

  (testing "filters out lines without equals sign"
    (let [response "Main.Power=On\nInvalidLine\nMain.Volume=-48"]
      (is (= #{"Main.Power" "Main.Volume"}
             (sut/parse-introspection-response response))))))

;; Mock NAD server for connection tests
(def ^:dynamic *mock-server* nil)
(def ^:dynamic *mock-port* nil)

;; Simulated introspection response (like real NAD "?" command)
(def mock-introspection-response
  (str "Main.Model=T778\r"
       "Main.Power=Off\r"
       "Main.Volume=-48\r"
       "Main.Source=6\r"
       "Main.Mute=Off\r"
       "Main.Version=v2.24\r"
       "Zone2.Power=Off\r"
       "Zone2.Volume=-60\r"))

(defn with-mock-nad-server
  "Fixture that starts a mock NAD server for testing.

  The mock server:
  - Sends `Main.Model=T778\\r` on connection (like real NAD)
  - Responds to bare `?` with introspection data
  - Echoes back commands with `=On` appended for set commands
  - Returns queried value for query commands"
  [f]
  (let [server       (ServerSocket. 0)
        port         (.getLocalPort server)
        server-ready (promise)]
    (try
      (future
        (try
          (deliver server-ready true)
          (while (not (.isClosed server))
            (try
              (let [client (.accept server)]
                (future
                  (try
                    (let [in  (.getInputStream client)
                          out (.getOutputStream client)]
                      ;; Send model on connect like real NAD
                      (.write out (.getBytes "\nMain.Model=T778\r"))
                      (.flush out)
                      ;; Read and respond to commands
                      (let [buf (byte-array 1024)]
                        (loop []
                          (let [n (.read in buf)]
                            (when (pos? n)
                              (let [cmd       (String. buf 0 n)
                                    ;; Parse: \nCommand?\r or \nCommand=Value\r
                                    cmd-clean (-> cmd
                                                  (str/replace #"^\n" "")
                                                  (str/replace #"\r$" ""))]
                                (cond
                                  ;; Bare "?" - introspection command
                                  (= cmd-clean "?")
                                  (do
                                    (.write out (.getBytes mock-introspection-response))
                                    (.flush out))

                                  ;; Query command (e.g., Main.Power?)
                                  (str/ends-with? cmd-clean "?")
                                  (let [base (subs cmd-clean 0 (dec (count cmd-clean)))]
                                    (.write out (.getBytes (str "\n" base "=On\r")))
                                    (.flush out))

                                  ;; Set command - echo it back
                                  (str/includes? cmd-clean "=")
                                  (do
                                    (.write out (.getBytes (str "\n" cmd-clean "\r")))
                                    (.flush out))

                                  ;; Increment/decrement - respond with value
                                  :else
                                  (let [base (subs cmd-clean 0 (dec (count cmd-clean)))]
                                    (.write out (.getBytes (str "\n" base "=-47\r")))
                                    (.flush out))))
                              (recur))))))
                    (catch Exception _)
                    (finally
                      (.close client)))))
              (catch Exception _)))
          (catch Exception _)))
      (deref server-ready 1000 false)
      (binding [*mock-server* server
                *mock-port*   port]
        (f))
      (finally
        (.close server)))))

(use-fixtures :each with-mock-nad-server)

(deftest connect-test
  (testing "connects to NAD receiver and returns connection"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        (is (map? conn))
        (is (contains? conn :socket))
        (is (sut/connected? conn))
        (finally
          (sut/disconnect conn)))))

  (testing "reads initial model response on connect"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        (is (= "T778" (:model conn)))
        (finally
          (sut/disconnect conn))))))

(deftest disconnect-test
  (testing "disconnects and closes socket"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (is (sut/connected? conn))
      (sut/disconnect conn)
      (is (not (sut/connected? conn)))))

  (testing "disconnecting twice is safe"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (sut/disconnect conn)
      (sut/disconnect conn) ; should not throw
      (is (not (sut/connected? conn))))))

(deftest send-command-test
  (testing "sends query command and returns response"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        (let [response (sut/send-command conn "Main.Power?")]
          (is (= "Main.Power=On" response)))
        (finally
          (sut/disconnect conn)))))

  (testing "sends set command and returns response"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        (let [response (sut/send-command conn "Main.Power=Off")]
          (is (= "Main.Power=Off" response)))
        (finally
          (sut/disconnect conn)))))

  (testing "sends increment command and returns response"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        (let [response (sut/send-command conn "Main.Volume+")]
          (is (= "Main.Volume=-47" response)))
        (finally
          (sut/disconnect conn))))))

(deftest introspect-test
  (testing "sends ? command and parses supported commands"
    (let [conn (-> (sut/connect "localhost" *mock-port* 2000)
                   (sut/introspect))]
      (try
        (is (contains? conn :supported-commands))
        (is (set? (:supported-commands conn)))
        (is (contains? (:supported-commands conn) "Main.Power"))
        (is (contains? (:supported-commands conn) "Main.Volume"))
        (is (contains? (:supported-commands conn) "Zone2.Power"))
        (finally
          (sut/disconnect conn)))))

  (testing "filters supported commands against registry"
    (let [conn (-> (sut/connect "localhost" *mock-port* 2000)
                   (sut/introspect))]
      (try
        ;; Main.Model is in introspection but only has ? operator in registry
        (is (contains? (:supported-commands conn) "Main.Model"))
        ;; Main.Power is fully supported
        (is (contains? (:supported-commands conn) "Main.Power"))
        (finally
          (sut/disconnect conn))))))

(deftest send-command-validation-test
  (testing "allows supported commands after introspection"
    (let [conn (-> (sut/connect "localhost" *mock-port* 2000)
                   (sut/introspect))]
      (try
        (let [response (sut/send-command conn "Main.Power?")]
          (is (= "Main.Power=On" response)))
        (finally
          (sut/disconnect conn)))))

  (testing "throws for unsupported commands after introspection"
    (let [conn (-> (sut/connect "localhost" *mock-port* 2000)
                   (sut/introspect))]
      (try
        (is (thrown-with-msg? Exception #"not supported"
                              (sut/send-command conn "Unsupported.Command?")))
        (finally
          (sut/disconnect conn)))))

  (testing "allows any command without introspection"
    (let [conn (sut/connect "localhost" *mock-port* 2000)]
      (try
        ;; Without introspection, any command is allowed
        (let [response (sut/send-command conn "Main.Power?")]
          (is (= "Main.Power=On" response)))
        (finally
          (sut/disconnect conn))))))
