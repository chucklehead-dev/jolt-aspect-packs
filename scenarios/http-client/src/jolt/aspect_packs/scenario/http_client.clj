(ns jolt.aspect-packs.scenario.http-client
  (:require [clj-http.lite.core :as http]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.http-client :as client]
            [jolt.http.core :as core]
            [jolt.http.net :as net]
            [jolt.http.tls :as tls]))

(ffi/defcfn c-socket "socket" [:int :int :int] :int)
(ffi/defcfn c-bind "bind" [:int :pointer :int] :int)
(ffi/defcfn c-listen "listen" [:int :int] :int)
(ffi/defcfn c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)
(ffi/defcfn c-accept "accept" [:int :pointer :pointer] :int :blocking)
(ffi/defcfn c-getsockname "getsockname" [:int :pointer :pointer] :int)
(ffi/defcfn c-poll "poll" [:pointer :int :int] :int :blocking)

(def ^:private af-inet 2)
(def ^:private sock-stream 1)
(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
(def ^:private sol-socket (if macos? 0xffff 1))
(def ^:private so-reuseaddr (if macos? 4 2))
(def ^:private pollin 1)
(def ^:private accept-timeout-ms 3500)

(defn- loopback-sockaddr
  [port]
  (let [address (ffi/alloc 16)]
    (dotimes [i 16]
      (ffi/write address :uint8 0 i))
    (if macos?
      (do
        (ffi/write address :uint8 16 0)
        (ffi/write address :uint8 af-inet 1))
      (ffi/write address :uint16 af-inet 0))
    (ffi/write address :uint8 (bit-and (bit-shift-right port 8) 0xff) 2)
    (ffi/write address :uint8 (bit-and port 0xff) 3)
    (ffi/write address :uint8 127 4)
    (ffi/write address :uint8 1 7)
    address))

(defn- listen-loopback!
  []
  (let [fd (c-socket af-inet sock-stream 0)]
    (when (neg? fd)
      (throw (ex-info "socket() failed" {})))
    (try
      (let [reuse (ffi/alloc 4)]
        (try
          (ffi/write reuse :int 1 0)
          (when (neg? (c-setsockopt fd sol-socket so-reuseaddr reuse 4))
            (throw (ex-info "setsockopt() failed" {})))
          (finally
            (ffi/free reuse))))
      (let [address (loopback-sockaddr 0)]
        (try
          (when (neg? (c-bind fd address 16))
            (throw (ex-info "bind() failed" {})))
          (finally
            (ffi/free address))))
      (when (neg? (c-listen fd 4))
        (throw (ex-info "listen() failed" {})))
      (let [address (ffi/alloc 16)
            length (ffi/alloc 4)]
        (try
          (ffi/write length :uint 16 0)
          (when (neg? (c-getsockname fd address length))
            (throw (ex-info "getsockname() failed" {})))
          {:fd fd
           :port (+ (bit-shift-left (ffi/read address :uint8 2) 8)
                    (ffi/read address :uint8 3))}
          (finally
            (ffi/free address)
            (ffi/free length))))
      (catch Throwable error
        (net/close fd)
        (throw error)))))

(defn- accept-raw!
  [fd]
  ;; Bound the regression path before accept rather than relying on close(fd)
  ;; from another thread to wake a blocked accept portably.
  (let [pollfd (ffi/alloc 8)]
    (try
      (dotimes [i 8]
        (ffi/write pollfd :uint8 0 i))
      (ffi/write pollfd :int fd 0)
      (ffi/write pollfd :uint16 pollin 4)
      (let [ready (c-poll pollfd 1 accept-timeout-ms)]
        (cond
          (zero? ready)
          (throw (ex-info "server accept deadline elapsed" {}))

          (neg? ready)
          (throw (ex-info "poll() before accept failed" {}))

          :else
          (let [raw (c-accept fd ffi/null ffi/null)]
            (when (neg? raw)
              (throw (ex-info "accept() failed" {})))
            raw)))
      (finally
        (ffi/free pollfd)))))

(defn- content-length
  [head]
  (if-let [[_ value] (re-find #"(?i)\r\ncontent-length:\s*([0-9]+)" head)]
    (parse-long value)
    0))

(defn- read-complete-request-method!
  [stream]
  (let [read! (jolt.host/ref-get stream :read)]
    (loop [received ""]
      (let [end (str/index-of received "\r\n\r\n")]
        (if (and end
                 (>= (count received)
                     (+ end 4 (content-length (subs received 0 end)))))
          (first (str/split (subs received 0 end) #" "))
          (if-let [bytes (read! stream nil)]
            (recur (str received (core/ba->latin1 bytes)))
            (throw (ex-info "peer closed before the complete request arrived"
                            {}))))))))

(defn- write-ok!
  [stream]
  ((jolt.host/ref-get stream :write)
   stream
   (core/latin1->ba "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")))

(defn- start-stale-pooled-tls!
  [cert key accept-retry?]
  (let [{:keys [fd port]} (listen-loopback!)
        methods (atom [])
        done (promise)
        listener-open? (atom true)
        close-listener! (fn []
                          (when (compare-and-set! listener-open? true false)
                            (net/close fd)))]
    (future
      (try
        ;; The first response is reusable. The server then reads the complete
        ;; second request before closing the raw transport without close_notify,
        ;; making the remote effect ambiguous rather than merely pre-send.
        (let [raw (accept-raw! fd)]
          (try
            (let [stream (tls/tls-wrap-server raw cert key)]
              (swap! methods conj (read-complete-request-method! stream))
              (write-ok! stream)
              (swap! methods conj (read-complete-request-method! stream)))
            (finally
              (net/close raw))))
        ;; A GET is permitted to reach this accept. For POST the listener is
        ;; closed instead; a replay mutant therefore cannot disguise itself as
        ;; the original strict TLS EOF.
        (when accept-retry?
          (let [raw (accept-raw! fd)]
            (try
              (let [stream (tls/tls-wrap-server raw cert key)]
                (swap! methods conj (read-complete-request-method! stream))
                (write-ok! stream))
              (finally
                (net/close raw)))))
        (deliver done {:ok? true})
        (catch Throwable error
          (deliver done {:ok? false :error error}))
        (finally
          (try (close-listener!) (catch Throwable _ nil)))))
    {:port port
     :methods methods
     :done done
     :close-listener! close-listener!}))

(defn- response-body
  [response]
  (some-> response :body core/ba->latin1))

(defn- unexpected-tls-eof?
  [error]
  (and (instance? javax.net.ssl.SSLException error)
       (= tls/unexpected-transport-eof-message (ex-message error))))

(defn- run-stale-case!
  [cert key method accept-retry? replay-after-ambiguous?]
  (core/pool-clear!)
  (let [server (start-stale-pooled-tls! cert key accept-retry?)
        url (str "https://127.0.0.1:" (:port server) "/")
        request! (case method :get client/get :post client/post)
        options {:insecure? true :socket-timeout 3000}]
    (try
      (let [first-response (request! url options)
            pooled-after-first (core/pool-count)
            second (try
                     {:response (request! url options)}
                     (catch Throwable error
                       (if replay-after-ambiguous?
                         ;; Deliberately wrong request-level policy: retry the
                         ;; POST after the selected client correctly reports
                         ;; that the server closed after receiving it.
                         {:response (request! url options)
                          :replay-trigger error}
                         {:error error})))
            server-result (deref (:done server) 4000 ::server-timeout)]
        {:first-body (response-body first-response)
         :pooled-after-first pooled-after-first
         :second-body (response-body (:response second))
         :second-error (:error second)
         :replay-trigger (:replay-trigger second)
         :server-result server-result
         :methods @(:methods server)})
      (finally
        (core/pool-clear!)
        (try ((:close-listener! server)) (catch Throwable _ nil))))))

(defn- require-case!
  [description predicate result]
  (when-not (predicate result)
    (throw (ex-info description
                    (-> result
                        (dissoc :second-error :replay-trigger)
                        (assoc :second-error-class
                               (some-> (:second-error result) class str)
                               :replay-trigger-class
                               (some-> (:replay-trigger result) class str)))))))

(defn- get-retry-observed?
  [result]
  (and (= "ok" (:first-body result))
       (= 1 (:pooled-after-first result))
       (= "ok" (:second-body result))
       (nil? (:second-error result))
       (= {:ok? true} (:server-result result))
       (= ["GET" "GET" "GET"] (:methods result))))

(defn- post-exactly-once?
  [result]
  (and (= "ok" (:first-body result))
       (= 1 (:pooled-after-first result))
       (nil? (:second-body result))
       (unexpected-tls-eof? (:second-error result))
       (= {:ok? true} (:server-result result))
       (= ["POST" "POST"] (:methods result))))

(defn request!
  [request]
  (http/request request))

(defn -main
  [& [cert key]]
  (when-not (and cert key)
    (throw (ex-info "certificate and key paths are required" {})))
  (let [get-result (run-stale-case! cert key :get true false)
        post-result (run-stale-case! cert key :post false false)
        ;; This is a causal red control, not another supported behavior. Apply
        ;; an explicitly wrong request-level policy after the real client
        ;; reports the ambiguous EOF, and prove the server sees a third POST.
        replay-mutant (run-stale-case! cert key :post true true)]
    (require-case! "GET did not retry once after stale pooled TLS EOF"
                   get-retry-observed? get-result)
    (require-case! "POST was not preserved as an exactly-once wire attempt"
                   post-exactly-once? post-result)
    (require-case! "POST replay mutant did not reach a duplicate wire request"
                   #(and (unexpected-tls-eof? (:replay-trigger %))
                         (= ["POST" "POST" "POST"] (:methods %)))
                   replay-mutant)
    (require-case! "POST exactly-once oracle accepted the replay mutant"
                   #(not (post-exactly-once? %)) replay-mutant)
    (println "HTTP-CLIENT-STALE-POOLED-COMPATIBILITY OK")))
