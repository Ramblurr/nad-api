(ns ol.nad-api.web-test
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.nad-api.telnet :as telnet]
   [ol.nad-api.web :as sut])
  (:import
   [java.net ServerSocket]))

;;; Unit tests (no connection required)

(deftest available-commands-test
  (testing "intersects supported-commands with registry"
    (let [supported #{"Main.Power" "Main.Volume" "Unknown.Cmd"}
          available (sut/available-commands supported)]
      (is (contains? available "Main.Power"))
      (is (contains? available "Main.Volume"))
      (is (not (contains? available "Unknown.Cmd")))))

  (testing "returns empty set when no overlap"
    (is (= #{} (sut/available-commands #{"Unknown.Cmd" "Another.Unknown"}))))

  (testing "returns empty set for nil input"
    (is (= #{} (sut/available-commands nil))))

  (testing "returns empty set for empty input"
    (is (= #{} (sut/available-commands #{})))))

;;; Mock NAD server for integration tests

(def ^:dynamic *mock-server* nil)
(def ^:dynamic *mock-port* nil)

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
  "Fixture that starts a mock NAD server for testing."
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

;;; Route generation tests

(deftest make-device-routes-test
  (testing "generates routes for device with commands plus discovery and reconnect"
    (let [conn   {:supported-commands #{"Main.Power" "Main.Volume"}}
          routes (sut/make-device-routes "test-device" conn)]
      ;; 2 command routes + 1 discovery route + 1 reconnect route
      (is (= 4 (count routes)))
      (is (some #(= "/api/test-device" (first %)) routes))
      (is (some #(= "/api/test-device/reconnect" (first %)) routes))
      (is (some #(= "/api/test-device/Main.Power" (first %)) routes))
      (is (some #(= "/api/test-device/Main.Volume" (first %)) routes))))

  (testing "device discovery route has only GET handler"
    (let [conn            {:supported-commands #{"Main.Power"}}
          routes          (sut/make-device-routes "test-device" conn)
          discovery-route (first (filter #(= "/api/test-device" (first %)) routes))
          [_ handlers]    discovery-route]
      (is (fn? (:get handlers)))
      (is (nil? (:post handlers)))))

  (testing "reconnect route has only POST handler"
    (let [conn            {:supported-commands #{"Main.Power"}}
          routes          (sut/make-device-routes "test-device" conn)
          reconnect-route (first (filter #(= "/api/test-device/reconnect" (first %)) routes))
          [_ handlers]    reconnect-route]
      (is (fn? (:post handlers)))
      (is (nil? (:get handlers)))))

  (testing "command routes have GET and POST handlers"
    (let [conn          {:supported-commands #{"Main.Power"}}
          routes        (sut/make-device-routes "test-device" conn)
          command-route (first (filter #(= "/api/test-device/Main.Power" (first %)) routes))
          [_ handlers]  command-route]
      (is (fn? (:get handlers)))
      (is (fn? (:post handlers)))))

  (testing "returns discovery and reconnect routes even for empty commands"
    (let [conn   {:supported-commands #{}}
          routes (sut/make-device-routes "test-device" conn)]
      (is (= 2 (count routes)))
      (is (some #(= "/api/test-device" (first %)) routes))
      (is (some #(= "/api/test-device/reconnect" (first %)) routes)))))

;;; Handler integration tests

(deftest handler-api-root-test
  (testing "GET /api returns list of all devices"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [response (h {:uri "/api" :request-method :get})]
          (is (= 200 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"])))
          (let [body (json/read-json (:body response) :key-fn keyword)]
            (is (map? (:devices body)))
            (is (contains? (:devices body) :nad-t778))
            (is (= "localhost" (get-in body [:devices :nad-t778 :host])))
            (is (= "T778" (get-in body [:devices :nad-t778 :model])))))
        (finally
          (telnet/disconnect conn))))))

(deftest handler-device-discovery-test
  (testing "GET /api/{device} returns device info and supported commands"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [response (h {:uri "/api/nad-t778" :request-method :get})]
          (is (= 200 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"])))
          (let [body (json/read-json (:body response) :key-fn keyword)]
            ;; Device info
            (is (= "nad-t778" (get-in body [:device :name])))
            (is (= "localhost" (get-in body [:device :host])))
            (is (= *mock-port* (get-in body [:device :port])))
            (is (= "T778" (get-in body [:device :model])))
            ;; Supported commands with metadata
            (is (map? (:supportedCommands body)))
            (is (contains? (:supportedCommands body) :Main.Power))
            (let [power-cmd (get-in body [:supportedCommands :Main.Power])]
              (is (contains? power-cmd :operators))
              (is (vector? (:operators power-cmd))))))
        (finally
          (telnet/disconnect conn))))))

(deftest handler-get-test
  (testing "GET queries device and returns JSON"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [response (h {:uri "/api/nad-t778/Main.Power" :request-method :get})]
          (is (= 200 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"])))
          (let [body (json/read-json (:body response) :key-fn keyword)]
            (is (= "Main.Power" (:command body)))
            (is (string? (:value body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "GET returns 404 for unknown command"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [response (h {:uri "/api/nad-t778/Unknown.Command" :request-method :get})]
          (is (= 404 (:status response))))
        (finally
          (telnet/disconnect conn)))))

  (testing "GET returns 404 for unknown device"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [response (h {:uri "/api/unknown-device/Main.Power" :request-method :get})]
          (is (= 404 (:status response))))
        (finally
          (telnet/disconnect conn))))))

(deftest handler-post-test
  (testing "POST with = operator sets value"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "=" :value "Off"})
              response (h {:uri            "/api/nad-t778/Main.Power"
                           :request-method :post
                           :body           (java.io.StringReader. body)})]
          (is (= 200 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "Main.Power" (:command resp-body)))
            (is (= "=" (:operator resp-body)))
            (is (= "Off" (:value resp-body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "POST with + operator increments"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "+"})
              response (h {:uri            "/api/nad-t778/Main.Volume"
                           :request-method :post
                           :body           (java.io.StringReader. body)})]
          (is (= 200 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "Main.Volume" (:command resp-body)))
            (is (= "+" (:operator resp-body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "POST returns 400 for missing operator"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:value "On"})
              response (h {:uri            "/api/nad-t778/Main.Power"
                           :request-method :post
                           :body           (java.io.StringReader. body)})]
          (is (= 400 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "missing-operator" (:error resp-body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "POST returns 400 for invalid operator"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "!"})
              response (h {:uri            "/api/nad-t778/Main.Power"
                           :request-method :post
                           :body           (java.io.StringReader. body)})]
          (is (= 400 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "invalid-operator" (:error resp-body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "POST returns 400 for missing value with = operator"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))
          h    (sut/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "="})
              response (h {:uri            "/api/nad-t778/Main.Power"
                           :request-method :post
                           :body           (java.io.StringReader. body)})]
          (is (= 400 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "missing-value" (:error resp-body)))))
        (finally
          (telnet/disconnect conn))))))

(deftest handler-multiple-devices-test
  (testing "handler supports multiple devices"
    (let [conn1 (-> (telnet/connect "localhost" *mock-port* 2000)
                    (telnet/introspect))
          conn2 (-> (telnet/connect "localhost" *mock-port* 2000)
                    (telnet/introspect))
          h     (sut/handler {"device-1" conn1 "device-2" conn2})]
      (try
        ;; Both devices accessible
        (let [resp1 (h {:uri "/api/device-1/Main.Power" :request-method :get})
              resp2 (h {:uri "/api/device-2/Main.Power" :request-method :get})]
          (is (= 200 (:status resp1)))
          (is (= 200 (:status resp2))))
        ;; Root lists both devices
        (let [response (h {:uri "/api" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (contains? (:devices body) :device-1))
          (is (contains? (:devices body) :device-2)))
        (finally
          (telnet/disconnect conn1)
          (telnet/disconnect conn2))))))
