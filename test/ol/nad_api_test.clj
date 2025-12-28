(ns ol.nad-api-test
  "End-to-end tests for the NAD REST API.

  Tests the complete flow: NAD connection -> introspection -> web handler -> HTTP requests"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.nad-api.telnet :as telnet]
   [ol.nad-api.web :as web])
  (:import
   [java.net ServerSocket]))

;;; Mock NAD server that simulates real device behavior

(def ^:dynamic *mock-port* nil)

(def mock-introspection-response
  (str "Main.Model=T778\r"
       "Main.Power=Off\r"
       "Main.Volume=-48\r"
       "Main.Source=6\r"
       "Main.Mute=Off\r"
       "Main.Version=v2.24\r"
       "Zone2.Power=Off\r"
       "Zone2.Volume=-60\r"
       "Zone2.Source=3\r"
       "Zone2.Mute=Off\r"))

;; Simulated device state
(def device-state (atom {:power  "Off"
                         :volume "-48"
                         :mute   "Off"
                         :source "6"}))

(defn with-mock-nad-server
  "Fixture that starts a mock NAD server with stateful behavior."
  [f]
  (let [server       (ServerSocket. 0)
        port         (.getLocalPort server)
        server-ready (promise)]
    ;; Reset device state for each test
    (reset! device-state {:power  "Off"
                          :volume "-48"
                          :mute   "Off"
                          :source "6"})
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

                                  ;; Power query
                                  (= cmd-clean "Main.Power?")
                                  (do
                                    (.write out (.getBytes (str "\nMain.Power=" (:power @device-state) "\r")))
                                    (.flush out))

                                  ;; Power set
                                  (str/starts-with? cmd-clean "Main.Power=")
                                  (let [value (subs cmd-clean 11)]
                                    (swap! device-state assoc :power value)
                                    (.write out (.getBytes (str "\nMain.Power=" value "\r")))
                                    (.flush out))

                                  ;; Volume query
                                  (= cmd-clean "Main.Volume?")
                                  (do
                                    (.write out (.getBytes (str "\nMain.Volume=" (:volume @device-state) "\r")))
                                    (.flush out))

                                  ;; Volume set
                                  (str/starts-with? cmd-clean "Main.Volume=")
                                  (let [value (subs cmd-clean 12)]
                                    (swap! device-state assoc :volume value)
                                    (.write out (.getBytes (str "\nMain.Volume=" value "\r")))
                                    (.flush out))

                                  ;; Volume increment
                                  (= cmd-clean "Main.Volume+")
                                  (let [new-vol (str (inc (Integer/parseInt (:volume @device-state))))]
                                    (swap! device-state assoc :volume new-vol)
                                    (.write out (.getBytes (str "\nMain.Volume=" new-vol "\r")))
                                    (.flush out))

                                  ;; Volume decrement
                                  (= cmd-clean "Main.Volume-")
                                  (let [new-vol (str (dec (Integer/parseInt (:volume @device-state))))]
                                    (swap! device-state assoc :volume new-vol)
                                    (.write out (.getBytes (str "\nMain.Volume=" new-vol "\r")))
                                    (.flush out))

                                  ;; Mute query
                                  (= cmd-clean "Main.Mute?")
                                  (do
                                    (.write out (.getBytes (str "\nMain.Mute=" (:mute @device-state) "\r")))
                                    (.flush out))

                                  ;; Mute set
                                  (str/starts-with? cmd-clean "Main.Mute=")
                                  (let [value (subs cmd-clean 10)]
                                    (swap! device-state assoc :mute value)
                                    (.write out (.getBytes (str "\nMain.Mute=" value "\r")))
                                    (.flush out))

                                  ;; Source query
                                  (= cmd-clean "Main.Source?")
                                  (do
                                    (.write out (.getBytes (str "\nMain.Source=" (:source @device-state) "\r")))
                                    (.flush out))

                                  ;; Source set
                                  (str/starts-with? cmd-clean "Main.Source=")
                                  (let [value (subs cmd-clean 12)]
                                    (swap! device-state assoc :source value)
                                    (.write out (.getBytes (str "\nMain.Source=" value "\r")))
                                    (.flush out))

                                  ;; Generic query - return "On"
                                  (str/ends-with? cmd-clean "?")
                                  (let [base (subs cmd-clean 0 (dec (count cmd-clean)))]
                                    (.write out (.getBytes (str "\n" base "=On\r")))
                                    (.flush out))

                                  ;; Generic set - echo back
                                  (str/includes? cmd-clean "=")
                                  (do
                                    (.write out (.getBytes (str "\n" cmd-clean "\r")))
                                    (.flush out))

                                  ;; Increment/decrement - return -47
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
      (binding [*mock-port* port]
        (f))
      (finally
        (.close server)))))

(use-fixtures :each with-mock-nad-server)

;;; End-to-end tests

(deftest e2e-connection-and-introspection-test
  (testing "connects to device and discovers supported commands"
    (let [conn (-> (telnet/connect "localhost" *mock-port* 2000)
                   (telnet/introspect))]
      (try
        (is (= "T778" (:model conn)))
        (is (set? (:supported-commands conn)))
        (is (contains? (:supported-commands conn) "Main.Power"))
        (is (contains? (:supported-commands conn) "Main.Volume"))
        (is (contains? (:supported-commands conn) "Zone2.Power"))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-query-state-test
  (testing "GET request returns current device state"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; Query power
        (let [response (handler {:uri "/api/nad-t778/Main.Power" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "Main.Power" (:command body)))
          (is (= "Off" (:value body))))

        ;; Query volume
        (let [response (handler {:uri "/api/nad-t778/Main.Volume" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "Main.Volume" (:command body)))
          (is (= "-48" (:value body))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-set-power-test
  (testing "POST request changes device power state"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; Turn power on
        (let [body      (json/write-json-str {:operator "=" :value "On"})
              response  (handler {:uri            "/api/nad-t778/Main.Power"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "On" (:value resp-body))))

        ;; Verify state changed
        (let [response (handler {:uri "/api/nad-t778/Main.Power" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "On" (:value body))))

        ;; Turn power off
        (let [body      (json/write-json-str {:operator "=" :value "Off"})
              response  (handler {:uri            "/api/nad-t778/Main.Power"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "Off" (:value resp-body))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-volume-control-test
  (testing "volume can be set, incremented, and decremented"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; Set volume to -30
        (let [body      (json/write-json-str {:operator "=" :value "-30"})
              response  (handler {:uri            "/api/nad-t778/Main.Volume"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "-30" (:value resp-body))))

        ;; Increment volume
        (let [body      (json/write-json-str {:operator "+"})
              response  (handler {:uri            "/api/nad-t778/Main.Volume"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "-29" (:value resp-body))))

        ;; Decrement volume
        (let [body      (json/write-json-str {:operator "-"})
              response  (handler {:uri            "/api/nad-t778/Main.Volume"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "-30" (:value resp-body))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-mute-toggle-test
  (testing "mute can be toggled on and off"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; Mute on
        (let [body      (json/write-json-str {:operator "=" :value "On"})
              response  (handler {:uri            "/api/nad-t778/Main.Mute"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "On" (:value resp-body))))

        ;; Verify mute is on
        (let [response (handler {:uri "/api/nad-t778/Main.Mute" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "On" (:value body))))

        ;; Mute off
        (let [body     (json/write-json-str {:operator "=" :value "Off"})
              response (handler {:uri            "/api/nad-t778/Main.Mute"
                                 :request-method :post
                                 :body           (java.io.StringReader. body)})]
          (is (= 200 (:status response))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-source-selection-test
  (testing "source can be changed"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; Query current source
        (let [response (handler {:uri "/api/nad-t778/Main.Source" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "6" (:value body))))

        ;; Change to source 3
        (let [body      (json/write-json-str {:operator "=" :value "3"})
              response  (handler {:uri            "/api/nad-t778/Main.Source"
                                  :request-method :post
                                  :body           (java.io.StringReader. body)})
              resp-body (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "3" (:value resp-body))))

        ;; Verify source changed
        (let [response (handler {:uri "/api/nad-t778/Main.Source" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "3" (:value body))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-error-handling-test
  (testing "returns 404 for unknown commands"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        (let [response (handler {:uri "/api/nad-t778/Unknown.Command" :request-method :get})]
          (is (= 404 (:status response)))
          (let [body (json/read-json (:body response) :key-fn keyword)]
            (is (= "not-found" (:error body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "returns 400 for invalid operator"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "!"})
              response (handler {:uri            "/api/nad-t778/Main.Power"
                                 :request-method :post
                                 :body           (java.io.StringReader. body)})]
          (is (= 400 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "invalid-operator" (:error resp-body)))))
        (finally
          (telnet/disconnect conn)))))

  (testing "returns 400 for missing value with = operator"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        (let [body     (json/write-json-str {:operator "="})
              response (handler {:uri            "/api/nad-t778/Main.Power"
                                 :request-method :post
                                 :body           (java.io.StringReader. body)})]
          (is (= 400 (:status response)))
          (let [resp-body (json/read-json (:body response) :key-fn keyword)]
            (is (= "missing-value" (:error resp-body)))))
        (finally
          (telnet/disconnect conn))))))

(deftest e2e-home-assistant-workflow-test
  (testing "simulates Home Assistant switch integration workflow"
    (let [conn    (-> (telnet/connect "localhost" *mock-port* 2000)
                      (telnet/introspect))
          handler (web/handler {"nad-t778" conn})]
      (try
        ;; HA polls state with GET
        (let [response (handler {:uri "/api/nad-t778/Main.Power" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= 200 (:status response)))
          (is (= "Off" (:value body))))

        ;; HA turns switch on with POST
        (let [body     (json/write-json-str {:operator "=" :value "On"})
              response (handler {:uri            "/api/nad-t778/Main.Power"
                                 :request-method :post
                                 :body           (java.io.StringReader. body)})]
          (is (= 200 (:status response))))

        ;; HA polls state again - should be On
        (let [response (handler {:uri "/api/nad-t778/Main.Power" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "On" (:value body))))

        ;; HA turns switch off with POST
        (let [body     (json/write-json-str {:operator "=" :value "Off"})
              response (handler {:uri            "/api/nad-t778/Main.Power"
                                 :request-method :post
                                 :body           (java.io.StringReader. body)})]
          (is (= 200 (:status response))))

        ;; Final state check
        (let [response (handler {:uri "/api/nad-t778/Main.Power" :request-method :get})
              body     (json/read-json (:body response) :key-fn keyword)]
          (is (= "Off" (:value body))))
        (finally
          (telnet/disconnect conn))))))
