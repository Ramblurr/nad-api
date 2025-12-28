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
    (is (= "foo=bar" (sut/parse-response "Some.Command=foo=bar")))))

;; Mock NAD server for connection tests
(def ^:dynamic *mock-server* nil)
(def ^:dynamic *mock-port* nil)

(defn with-mock-nad-server
  "Fixture that starts a mock NAD server for testing.

  The mock server:
  - Sends `Main.Model=T778\\r` on connection (like real NAD)
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
                                  ;; Query command
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
