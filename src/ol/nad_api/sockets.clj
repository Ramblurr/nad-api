(ns ol.nad-api.sockets
  "Low-level socket operations for TCP connections."
  (:import
   [java.io BufferedWriter OutputStreamWriter InputStreamReader]
   [java.net InetSocketAddress Socket]))

(defn connect
  "Connects to `host`:`port` with the given `timeout-ms`.

  Returns the connected socket.
  Throws on connection failure or timeout.

  ```clojure
  (connect \"10.0.0.1\" 23 2000)
  ;=> #<Socket ...>
  ```"
  [^String host ^long port ^long timeout-ms]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. host port) timeout-ms)
    socket))

(defn close
  "Closes the socket.

  Safe to call on already-closed sockets.

  ```clojure
  (close socket)
  ;=> nil
  ```"
  [^Socket socket]
  (when (and socket (not (.isClosed socket)))
    (.close socket)))

(defn write
  "Writes `s` to the socket.

  ```clojure
  (write socket \"Main.Power?\\r\")
  ;=> nil
  ```"
  [^Socket socket ^String s]
  (let [out (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket)))]
    (.write out s)
    (.flush out)))

(defn read-until
  "Reads from socket until `delimiter` char is encountered.

  Returns the string read (excluding the delimiter).
  Throws on timeout or read error.

  ```clojure
  (read-until socket \\return 2000)
  ;=> \"Main.Power=On\"
  ```"
  [^Socket socket delimiter ^long timeout-ms]
  (.setSoTimeout socket (int timeout-ms))
  (let [in        (InputStreamReader. (.getInputStream socket))
        sb        (StringBuilder.)
        delim-int (int delimiter)]
    (loop []
      (let [ch (.read in)]
        (cond
          (= ch -1) (str sb)
          (= ch delim-int) (str sb)
          :else (do
                  (.append sb (char ch))
                  (recur)))))))
