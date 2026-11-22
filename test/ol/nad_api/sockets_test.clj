(ns ol.nad-api.sockets-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ol.nad-api.sockets :as sut])
  (:import
   [java.io EOFException]
   [java.net ServerSocket Socket]))

;; Test server fixture for socket tests
(def ^:dynamic *test-server* nil)
(def ^:dynamic *test-port* nil)

(defn with-echo-server
  "Fixture that starts a simple echo server for testing."
  [f]
  (let [server       (ServerSocket. 0) ; 0 = auto-assign port
        port         (.getLocalPort server)
        server-ready (promise)]
    (try
      ;; Start server thread that echoes back what it receives
      (future
        (try
          (deliver server-ready true)
          (while (not (.isClosed server))
            (try
              (let [client (.accept server)]
                (future
                  (try
                    (let [in  (.getInputStream client)
                          out (.getOutputStream client)
                          buf (byte-array 1024)]
                      (loop []
                        (let [n (.read in buf)]
                          (when (pos? n)
                            (.write out buf 0 n)
                            (.flush out)
                            (recur)))))
                    (catch Exception _)
                    (finally
                      (.close client)))))
              (catch Exception _)))
          (catch Exception _)))
      ;; Wait for server to be ready
      (deref server-ready 1000 false)
      (binding [*test-server* server
                *test-port*   port]
        (f))
      (finally
        (.close server)))))

(use-fixtures :each with-echo-server)

(deftest connect-test
  (testing "connects to a server and returns a socket"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (try
        (is (instance? Socket socket))
        (is (.isConnected socket))
        (is (not (.isClosed socket)))
        (finally
          (sut/close socket)))))

  (testing "throws on connection timeout to unreachable host"
    (is (thrown? Exception
                 (sut/connect "10.255.255.1" 12345 100)))))

(deftest close-test
  (testing "closes an open socket"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (is (not (.isClosed socket)))
      (sut/close socket)
      (is (.isClosed socket))))

  (testing "closing already closed socket is safe"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (sut/close socket)
      (sut/close socket) ; should not throw
      (is (.isClosed socket)))))

(deftest write-test
  (testing "writes string to socket"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (try
        ;; Should not throw
        (sut/write socket "hello")
        (is true)
        (finally
          (sut/close socket))))))

(deftest read-until-test
  (testing "reads until delimiter character"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (try
        (sut/write socket "hello\r")
        (Thread/sleep 50) ; give echo server time to respond
        (let [result (sut/read-until socket \return 2000 Long/MAX_VALUE)]
          (is (= "hello" result)))
        (finally
          (sut/close socket)))))

  (testing "reads multiple segments until delimiter"
    (let [socket (sut/connect "localhost" *test-port* 1000)]
      (try
        (sut/write socket "Main.Power=On\r")
        (Thread/sleep 50) ; give echo server time to respond
        (let [result (sut/read-until socket \return 2000 Long/MAX_VALUE)]
          (is (= "Main.Power=On" result)))
        (finally
          (sut/close socket))))))

(deftest read-until-eof-test
  (testing "throws EOFException when the peer closes the connection"
    (with-open [server (ServerSocket. 0)]
      (future (.close (.accept server)))
      (let [socket (sut/connect "localhost" (.getLocalPort server) 1000)]
        (try
          (is (thrown? EOFException (sut/read-until socket \return 2000 Long/MAX_VALUE)))
          (finally
            (sut/close socket)))))))

(deftest read-until-deadline-test
  (testing "gives up at the deadline when the peer drips bytes without a delimiter"
    (with-open [server (ServerSocket. 0)]
      (future
        (with-open [client (.accept server)]
          (let [out (.getOutputStream client)]
            (while true
              (.write out (int \x))
              (.flush out)
              (Thread/sleep 50)))))
      (let [socket (sut/connect "localhost" (.getLocalPort server) 1000)]
        (try
          (let [result (deref (future
                                (try
                                  (sut/read-until socket \return 500 (+ (System/currentTimeMillis) 300))
                                  (catch java.io.IOException e e)))
                              2000
                              ::hung)]
            (is (instance? java.io.IOException result)))
          (finally
            (sut/close socket)))))))

(deftest read-until-deadline-silent-peer-test
  (testing "gives up at the deadline even when the idle timeout is longer"
    (with-open [server (ServerSocket. 0)]
      (future (with-open [_client (.accept server)] (Thread/sleep 10000)))
      (let [socket (sut/connect "localhost" (.getLocalPort server) 1000)]
        (try
          (let [result (deref (future
                                (try
                                  (sut/read-until socket \return 10000 (+ (System/currentTimeMillis) 300))
                                  (catch java.io.IOException e e)))
                              2000
                              ::hung)]
            (is (instance? java.io.IOException result)))
          (finally
            (sut/close socket)))))))
