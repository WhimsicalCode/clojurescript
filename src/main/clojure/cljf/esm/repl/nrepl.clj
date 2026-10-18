;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm.repl.nrepl
  "nREPL for cljf.esm.repl: the watcher's nREPL server (:repl
  {:nrepl-port 0}) and middleware evaluating ClojureScript in the sessions
  (cljf.esm.repl/repl) switched. Needs nREPL on the classpath, the compiler
  doesn't depend on it."
  (:require [cljf.esm.repl :as repl]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.middleware.interruptible-eval :as eval]
            [nrepl.middleware.print :as print]
            [nrepl.server :as server]
            [nrepl.transport :as t])
  (:import [java.io File]
           [java.net InetSocketAddress Socket]))

(def ^:dynamic *cljs*
  "A session's ClojureScript REPL, {:ns :opts :runtime-id}, nil while the
  session evaluates Clojure."
  nil)

(defn enter!
  "Switches the nREPL session evaluating it to ClojureScript, see
  cljf.esm.repl/repl."
  [opts]
  (let [{:keys [session]} eval/*msg*
        cljs {:ns 'cljs.user :opts opts}]
    (swap! session assoc #'*cljs* cljs)
    ;; after :cljs/quit the session binds *cljs*, and nREPL stores the eval's
    ;; bindings in the session when it ends
    (when (thread-bound? #'*cljs*) (set! *cljs* cljs))
    (println (str "; ClojureScript REPL in namespace cljs.user, " (count (repl/runtimes))
               " runtime(s) connected, :cljs/quit to leave"))
    nil))

(defn- respond-result
  "Sends the responses of a form's result r."
  [msg {:keys [value out err error ns stack ex-data] :as r} last-runtime]
  (some->> (repl/announce last-runtime (:runtime-id r)) (t/respond-to msg :out))
  (when (seq out) (t/respond-to msg :out out))
  (when (seq err) (t/respond-to msg :err err))
  (let [text (repl/format-result r)]
    (when (seq text) (t/respond-to msg :err text)))
  (if error
    (t/respond-to msg {:ex      "cljf.esm.repl/eval-error"
                       :root-ex "cljf.esm.repl/eval-error"
                       :status  #{:eval-error}})
    (when value
      (t/respond-to msg {:value value :ns (str ns)}))))

(defn- evaluate
  "Evaluates the ClojureScript of an eval or load-file message, on the
  session's thread."
  [{:keys [op code file file-path] :as msg}]
  (let [{:keys [ns opts runtime-id]} *cljs*
        last-runtime (volatile! runtime-id)
        code (if (= "load-file" op) nil code)]
    (cond
      (and code (= ":cljs/quit" (string/trim code)))
      (do (set! *cljs* nil)
          (t/respond-to msg {:value ":cljs/quit" :ns (str *ns*)}))

      (= "load-file" op)
      (let [r (repl/load-file file-path (assoc opts :contents file))]
        (respond-result msg (assoc r :ns ns) @last-runtime))

      :else
      (let [ns' (repl/eval-string code
                  ;; the editor's namespace, unless it's a Clojure one
                  (if (some-> (:ns msg) repl/cljs-ns?) (symbol (:ns msg)) ns)
                  (:file msg) opts
                  (fn [r]
                    (respond-result msg r @last-runtime)
                    (when-let [id (:runtime-id r)] (vreset! last-runtime id))))]
        (set! *cljs* (assoc *cljs* :ns ns' :runtime-id @last-runtime))))))

(defn wrap-cljs-repl
  "Evaluates eval and load-file messages of sessions switched to
  ClojureScript (see enter!) in the runtime."
  [h]
  (fn [{:keys [op session] :as msg}]
    (if (and (#{"eval" "load-file"} op)
             (instance? clojure.lang.IDeref session)
             (get @session #'*cljs*))
      ((:exec (meta session)) (:id msg)
       (fn []
         (try
           (evaluate msg)
           (catch Throwable e
             (t/respond-to msg :err (str (.getMessage e) "\n"))
             (t/respond-to msg {:ex (str (class e)) :root-ex (str (class e)) :status #{:eval-error}}))))
       #(t/respond-to msg :status :done)
       msg)
      (h msg))))

(set-descriptor! #'wrap-cljs-repl
  {:requires #{"clone"}
   ;; values are printed by the runtime
   :expects  #{"eval" "load-file" #'print/wrap-print}
   :handles  {}})

(defn- live-port?
  "Whether a server accepts connections on port."
  [port]
  (try
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 200)
      true)
    (catch Exception _ false)))

(defn- write-port-files!
  "Writes port to the port files, unless another live server's port is in
  one, deletes them on exit. Returns the files written."
  [port files]
  (let [written (doall
                  (for [path files
                        :let [f (io/file path)
                              other (when (.exists f)
                                      (try (Long/parseLong (string/trim (slurp f)))
                                        (catch NumberFormatException _ nil)))]
                        :when (not (and other (not= other port) (live-port? other)))]
                    (do (spit f (str port))
                        f)))]
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (doseq [^File f written]
                   (when (and (.exists f) (= (str port) (string/trim (slurp f))))
                     (.delete f))))))
    written))

(defn- middleware-vars
  "Resolves a middleware name (a symbol or string) to vars as nrepl.cmdline
  does. A var holding a collection of names, such as
  cider.nrepl/cider-middleware, resolves to the middleware it names. A name
  that doesn't resolve resolves to no vars, with a warning."
  [n]
  (if-let [v (if (var? n)
               n
               (try
                 (or (requiring-resolve (symbol n))
                     (throw (ex-info "no such var" {})))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "cljf.esm.repl: nREPL middleware" n "not loaded:" (.getMessage e)))
                   nil)))]
    (if (sequential? @v) (mapcat middleware-vars @v) [v])
    []))

(defn- configured-middleware
  "Returns the middleware names of :repl's :middleware, followed by those of
  nREPL's configuration (nrepl.config/config, the project's .nrepl.edn over
  the user's nrepl.edn). nREPL before 0.5 has no configuration."
  [middleware]
  (let [names  #(map symbol (if (or (symbol? %) (string? %)) [%] %))
        config (try
                 (some-> (requiring-resolve 'nrepl.config/config) deref)
                 (catch java.io.FileNotFoundException _ nil))]
    (distinct (concat (names middleware) (names (:middleware config))))))

(defn start-server!
  "Starts the nREPL server of :repl options, {:nrepl-port 0 (random)
  :nrepl-host \"127.0.0.1\" :port-files [\".nrepl-port\"] :middleware []}.
  :middleware names middleware added to nREPL's default handler, as do the
  :middleware of nREPL's configuration files. Its sessions evaluate Clojure
  in the watcher's JVM, (cljf.esm.repl/repl) switches one to ClojureScript."
  [{:keys [nrepl-port nrepl-host port-files middleware] :or {nrepl-host "127.0.0.1" port-files [".nrepl-port"]}}]
  (let [loaded (keep #(when-let [vs (seq (middleware-vars %))] [% vs])
                 (configured-middleware middleware))
        names  (map first loaded)
        extra  (mapcat second loaded)
        server (server/start-server
                 :bind nrepl-host
                 :port (if (true? nrepl-port) 0 nrepl-port)
                 :handler (apply server/default-handler #'wrap-cljs-repl extra))
        port   (:port server)
        files  (write-port-files! port port-files)]
    (println (str "[cljf.esm.repl] nREPL server on " nrepl-host ":" port
               (when (seq files) (str " (" (string/join ", " files) ")"))
               (when (seq names) (str " with " (string/join ", " names)))
               ", (cljf.esm.repl/repl) evaluates ClojureScript"))
    (flush)
    server))
