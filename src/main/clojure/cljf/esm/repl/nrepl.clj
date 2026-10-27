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
            [clojure.edn :as edn]
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

(defn- cljs-session?
  "Whether msg's session is switched to ClojureScript (see enter!)."
  [{:keys [session]}]
  (and (instance? clojure.lang.IDeref session)
       (some? (get @session #'*cljs*))))

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
    (if (and (#{"eval" "load-file"} op) (cljs-session? msg))
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

(defn- warn [& xs]
  (binding [*out* *err*]
    (apply println "cljf.esm.repl:" xs)
    (flush)))

(defn- middleware?
  "Whether v, a var, can be nREPL middleware: it holds a fn."
  [v]
  (and (bound? v) (fn? @v)))

(defn- middleware-vars
  "Resolves a middleware name (a symbol or string) to vars as nrepl.cmdline
  does. A var holding a collection of names, such as
  cider.nrepl/cider-middleware, resolves to the middleware it names. A name
  that doesn't load, or isn't middleware, resolves to no vars, with a
  warning: Throwable, as loading a namespace compiled for a newer Java
  throws an Error."
  [n]
  (let [v (if (var? n)
            n
            (try
              (or (requiring-resolve (symbol n))
                  (throw (ex-info "no such var" {})))
              (catch Throwable e
                (warn "nREPL middleware" n "not loaded:" (.getMessage e))
                nil)))]
    (cond
      (nil? v)                                  []
      (and (bound? v) (coll? @v) (not (map? @v))) (mapcat middleware-vars @v)
      (middleware? v)                           [v]
      :else (do (warn "nREPL middleware" n "left out: neither a fn nor a collection of middleware names")
                []))))

(defn- project-config
  "The project's nREPL configuration, .nrepl.edn, {} without one. One that
  can't be read is {}, with a warning."
  []
  (let [f (io/file ".nrepl.edn")]
    (if (.exists f)
      (try
        (with-open [r (java.io.PushbackReader. (io/reader f))]
          (or (edn/read r) {}))
        (catch Throwable e
          (warn ".nrepl.edn not read:" (.getMessage e))
          {}))
      {})))

(defn- configured-middleware
  "Returns the middleware names of :repl's :middleware, followed by those of
  the project's .nrepl.edn, whose other keys don't apply. The user's
  nrepl.edn (nrepl.config) isn't read: its middleware is for the user's
  Clojure projects, the compiler's classpath may not have it."
  [middleware]
  (let [names #(map symbol (if (or (symbol? %) (string? %)) [%] %))]
    (distinct (concat (names middleware) (names (:middleware (project-config)))))))

(defn cljs-env
  "Returns the build's compiler environment for a message of a session
  switched to ClojureScript, nil for other messages. cider-nrepl's
  ClojureScript completion and info (clj-suitable) read it. It's a copy of
  a value no recompile is changing (see cljf.esm.repl/compiler-env-snapshot),
  which some of cider's ops (macroexpand) change as they analyze."
  [msg]
  (when (cljs-session? msg)
    (some-> (repl/compiler-env-snapshot) atom)))

(defn- suitable-ns
  "The name of clj-suitable's namespace, suffix after suitable., as
  cider-nrepl's completion (loaded) has it: one inlining clj-suitable
  (mranderson) renames its namespaces, the var of its complete-for-nrepl
  tells how."
  [suffix]
  (let [base (or (some-> (resolve 'cider.nrepl.middleware.complete/suitable-complete-for-nrepl)
                   deref meta :ns ns-name str)
                 "suitable.complete-for-nrepl")]
    (symbol (string/replace base #"complete-for-nrepl$" suffix))))

(defn- suitable-eval
  "Returns a clj-suitable eval fn evaluating in the runtime, as suitable's
  shadow-cljs support does with shadow's cljs-eval, giving up after
  timeout ms."
  [runtime-id timeout]
  (fn [ns code]
    (try
      (let [ns (if (some-> ns repl/cljs-ns?) (symbol ns) 'cljs.user)
            r  (repl/cljs-eval code {:ns ns :runtime-id runtime-id :timeout timeout})
            v  (first (:results r))]
        {:error (or (:error r) (some-> (seq (:warnings r)) pr-str))
         :value (when (and (string? v) (not (:error r))) (edn/read-string v))})
      (catch Exception e
        {:error (.getMessage e)}))))

(def ^:private require-timeout
  ;; ms the first completion in a runtime waits for suitable.js-introspection
  ;; to load in it, a cold dev server compiles and transforms it
  30000)

(def ^:private complete-timeout
  ;; ms a completion waits for the runtime's JavaScript properties: they're
  ;; left out of the completion after it, the runtime may be paused
  1000)

(defn- introspection!
  "Whether suitable.js-introspection is loaded in runtime runtime-id,
  waiting for it up to complete-timeout ms. Requires it in the background
  the first time, and again after it failed (warned about once per
  runtime): a cold dev server can take longer than one completion. The
  state is the runtime's (cljf.esm.repl/runtime-data), forgotten when it
  disconnects or the build stops."
  [runtime-id]
  (let [loading (repl/update-runtime-data! runtime-id ::introspection
                  ;; a delay: swap! may call this more than once
                  #(or % (delay (future
                                  ((suitable-eval runtime-id require-timeout)
                                   "cljs.user" (str "(require '" (suitable-ns "js-introspection") ")"))))))
        result  (some-> loading force (deref complete-timeout ::loading))]
    (cond
      (or (nil? result) (= ::loading result)) false
      (:error result)
      (do (repl/update-runtime-data! runtime-id ::introspection #(when-not (identical? % loading) %))
          (when-not (repl/runtime-data runtime-id ::introspection-warned)
            (repl/update-runtime-data! runtime-id ::introspection-warned (constantly true))
            (warn (suitable-ns "js-introspection") "not loaded in runtime" runtime-id
              "(no JavaScript property completions in it until it is):" (:error result)))
          false)
      :else true)))

(defonce ^:private suitable-complete
  ;; clj-suitable's handle-completion-msg!, see load-suitable!
  (atom nil))

(defn- load-suitable!
  "Loads cider-nrepl's completion and its clj-suitable, and keeps
  clj-suitable's handle-completion-msg!. Returns whether it did, warns when
  it didn't. At startup: cider loads its middleware's namespaces on their
  first message, and loading one on two threads at once (with a completion
  asking the runtime) fails."
  []
  (try
    (require 'cider.nrepl.middleware.complete)
    (let [f (requiring-resolve (symbol (str (suitable-ns "complete-for-nrepl")) "handle-completion-msg!"))]
      (reset! suitable-complete f)
      (some? f))
    (catch Throwable e
      (warn "clj-suitable not loaded, no JavaScript property completions:" (.getMessage e))
      false)))

(defn- js-completions
  "Returns clj-suitable's completions of JavaScript properties for a complete
  message of a session switched to ClojureScript, nil while
  suitable.js-introspection loads in the runtime or when it doesn't answer
  within complete-timeout ms."
  [{:keys [session prefix symbol] :as msg}]
  (try
    (when-let [complete! @suitable-complete]
      (when-let [runtime-id (repl/target-id (select-keys (:opts (get @session #'*cljs*)) [:runtime-id :tag]))]
        (when (introspection! runtime-id)
          (complete! (assoc msg :symbol (str (or prefix symbol)))
            (suitable-eval runtime-id complete-timeout)
            (fn [_])))))
    (catch Throwable e
      (warn "JavaScript completions failed:" (.getMessage e))
      nil)))

(defn- appending-transport
  "Returns a transport appending the candidates (a future) to the
  :completions of the responses sent through it, waiting for them up to
  complete-timeout ms."
  [transport candidates]
  (reify t/Transport
    (recv [_] (t/recv transport))
    (recv [_ timeout] (t/recv transport timeout))
    (send [this response]
      (t/send transport (if (contains? response :completions)
                          (update response :completions
                            #(vec (concat % (deref candidates complete-timeout nil))))
                          response))
      this)))

(defn wrap-cljs-complete
  "Completes JavaScript properties for CIDER's complete messages asking for
  them (:enhanced-cljs-completion?) in sessions switched to ClojureScript.
  cider-nrepl's own completions of them are clj-suitable's for piggieback and
  shadow-cljs, which these sessions aren't. The runtime is asked while cider
  completes from the compiler environment."
  [h]
  (fn [{:keys [op enhanced-cljs-completion?] :as msg}]
    (if (and (#{"complete" "cider/complete"} op) (cljs-session? msg))
      (let [msg (dissoc msg :enhanced-cljs-completion?)]
        (if enhanced-cljs-completion?
          (h (update msg :transport appending-transport (future (js-completions msg))))
          (h msg)))
      (h msg))))

(set-descriptor! #'wrap-cljs-complete
  {:requires #{"clone"}
   :expects  #{"complete" "cider/complete"}
   :handles  {}})

(defn- cider-middleware
  "The middleware to add for cider-nrepl's middleware among vars: cider's
  ClojureScript completion of JavaScript properties when its complete is
  there. Adds cljs-env to cider-nrepl's providers of ClojureScript compiler
  environments (cider-nrepl 0.61 or later) when it's loaded."
  [vars]
  (when-let [providers (when (find-ns 'cider.nrepl.middleware.util.cljs)
                         (resolve 'cider.nrepl.middleware.util.cljs/cljs-env-providers))]
    (alter-var-root providers #(if (some #{#'cljs-env} %) % (conj % #'cljs-env)))
    (when (and (some #(= 'cider.nrepl/wrap-complete (symbol %)) vars)
               (load-suitable!))
      [#'wrap-cljs-complete])))

(defn- server-middleware
  "Returns [names vars]: the names of the middleware of :repl's :middleware
  and the project's .nrepl.edn (see configured-middleware) that loaded, and
  the vars to add to nREPL's default handler for them."
  [middleware]
  (let [loaded (keep #(when-let [vs (seq (middleware-vars %))] [% vs])
                 (configured-middleware middleware))
        vars   (distinct (mapcat second loaded))]
    [(map first loaded)
     (into (vec vars) (cider-middleware vars))]))

(defn start-server!
  "Starts the nREPL server of :repl options, {:nrepl-port 0 (random)
  :nrepl-host \"127.0.0.1\" :port-files [\".nrepl-port\"] :middleware []}.
  :middleware names middleware added to nREPL's default handler, as does
  the :middleware of the project's .nrepl.edn. Its sessions evaluate Clojure
  in the watcher's JVM, (cljf.esm.repl/repl) switches one to ClojureScript."
  [{:keys [nrepl-port nrepl-host port-files middleware] :or {nrepl-host "127.0.0.1" port-files [".nrepl-port"]}}]
  (let [[names extra] (server-middleware middleware)
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
