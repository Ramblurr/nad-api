(ns ol.nad-api.sockets
  "Low-level socket operations for TCP connections."
  (:import
   [java.io
    BufferedReader
    BufferedWriter
    EOFException
    IOException
    InputStreamReader
    OutputStreamWriter]
   [java.net InetSocketAddress Socket SocketTimeoutException]))

(set! *warn-on-reflection* true)

;; Cache of socket -> BufferedReader to avoid losing buffered data between reads.
;; Explicitly managed: entries added on first read, removed on close.
(defonce ^:private readers (atom {}))

(defn- get-reader
  "Gets or creates the BufferedReader for a socket.

  Caches the reader so we always get the same one for a given socket,
  preserving any buffered data between reads."
  ^BufferedReader [^Socket socket]
  (or (get @readers socket)
      (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
        (swap! readers assoc socket reader)
        reader)))

(defn- clear-reader!
  "Removes the cached reader for a socket."
  [socket]
  (swap! readers dissoc socket))

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
  "Closes the socket and clears any cached reader.

  Safe to call on already-closed sockets.

  ```clojure
  (close socket)
  ;=> nil
  ```"
  [^Socket socket]
  (when (and socket (not (.isClosed socket)))
    (.close socket))
  (clear-reader! socket))

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
  Throws `EOFException` when the peer has closed the connection,
  `SocketTimeoutException` when the peer is silent for `timeout-ms`, and
  `IOException` when the epoch-millis `deadline-ms` passes first.

  ```clojure
  (read-until socket \\return 2000 (+ (System/currentTimeMillis) 5000))
  ;=> \"Main.Power=On\"
  ```"
  [^Socket socket delimiter ^long timeout-ms ^long deadline-ms]
  (let [^BufferedReader in (get-reader socket)
        sb                 (StringBuilder.)
        delim-int          (int delimiter)]
    (loop []
      (let [remaining (- deadline-ms (System/currentTimeMillis))]
        (when-not (pos? remaining)
          (throw (IOException. "Read deadline passed before the line ended")))
        (.setSoTimeout socket (int (max 1 (min timeout-ms remaining)))))
      (let [ch (try
                 (.read in)
                 (catch SocketTimeoutException e
                   (if (< (System/currentTimeMillis) deadline-ms)
                     (throw e)
                     (throw (IOException. "Read deadline passed before the line ended")))))]
        (cond
          (= ch -1) (throw (EOFException. "Connection closed by peer"))
          (= ch delim-int) (str sb)
          :else (do
                  (.append sb (char ch))
                  (recur)))))))
