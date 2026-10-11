;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm.repl
  "A REPL into the pages running a build of cljf.esm/watch (:esm-repl).

  Forms are analyzed against the watcher's compiler environment and
  compiled in REPL mode (see cljs.compiler/*esm-repl*), the runtime
  (cljf.esm.repl-runtime) evaluates them. Messages go through the Vite
  plugin: `repl-send` events on the watcher's stdout, `repl <json>` lines on
  its stdin, the plugin relays them over Vite's websocket.

    (runtimes)                       ; the pages connected
    (cljs-eval \"(+ 1 2)\")            ;=> {:results [\"3\"] :out \"\" :err \"\" ...}
    (cljs-eval \"(foo)\" {:ns 'my.app :runtime-id 3})
    (repl)                           ; in nREPL: this session evaluates ClojureScript

  Forms are evaluated in the runtime focused last (preferring visible ones),
  unless given :runtime-id or :tag (see tag!)."
  (:refer-clojure :exclude [load-file])
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljf.esm :as esm]
            [cljs.repl]
            [cljs.source-map :as sm]
            [cljs.util :as util]
            [cljs.vendor.clojure.data.json :as json]
            [cljs.vendor.clojure.tools.reader :as reader]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io File StringReader]
           [java.util Base64]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent.locks ReentrantLock]))

(defonce ^:private build
  ;; the watcher's build, see cljf.esm/watch
  (atom nil))

(defonce ^:private runtimes-
  ;; {runtime-id {:runtime-id :url :title :user-agent :visible :focused-at
  ;; :connected-at :tag}}
  (atom {}))

(defonce ^:private pending
  ;; requests waiting for their runtime's reply, {id {:promise :runtime}}
  (atom {}))

(defonce ^:private request-ids (atom 0))

(defonce ^:private snippets (atom 0))

(defn- default-send! [runtime-id msg]
  (esm/event! "repl-send" {:runtime runtime-id :msg msg}))

(defn start!
  "Called by the watcher once it built, b is the build: :compiler-env,
  :options, :lock, :compile! (see cljf.esm/watch), and :send!, sending
  messages to runtimes, the Vite plugin's by default. Starts an nREPL server
  under :repl {:nrepl-port 0}, see cljf.esm.repl.nrepl."
  [b]
  (reset! build b)
  (let [{:keys [repl]} (:options b)]
    (when (and (map? repl) (contains? repl :nrepl-port) (:nrepl-port repl))
      (future
        (try
          ((requiring-resolve 'cljf.esm.repl.nrepl/start-server!) repl)
          (catch Throwable e
            (binding [*out* *err*]
              (println "cljf.esm.repl: nREPL server not started:" (.getMessage e)))))))))

(defn stop!
  "Forgets the build and its runtimes."
  []
  (reset! build nil)
  (reset! runtimes- {}))

(defn- fail-pending! [runtime-id message]
  (doseq [[_ {:keys [promise runtime]}] @pending
          :when (= runtime runtime-id)]
    (deliver promise {:error message})))

(declare untag-others! console-eval!)

(defn handle-message!
  "Handles a message of a runtime, a JSON string."
  [s]
  (let [{:keys [op runtime] :as msg} (json/read-str s :key-fn keyword)
        now (System/currentTimeMillis)]
    (case op
      "hello"  (let [tag    (:tag msg)
                     ;; a page opened by a tagged page has a copy of its
                     ;; sessionStorage, the tag stays the other runtime's
                     taken? (and tag (some #(and (= tag (:tag %)) (not= runtime (:runtime-id %)))
                                       (vals @runtimes-)))]
                 (swap! runtimes- update runtime
                   (fn [r]
                     (cond-> (merge r {:runtime-id   runtime
                                       :url          (:url msg)
                                       :title        (:title msg)
                                       :user-agent   (:userAgent msg)
                                       :visible      (boolean (:visible msg))
                                       :tag          (when-not taken? tag)
                                       :root         (:root msg)
                                       :connected-at (or (:connected-at r) now)})
                       (:focused msg) (assoc :focused-at now))))
                 (when taken?
                   (future (untag-others! nil runtime))))
      "focus"  (swap! runtimes-
                 (fn [rs]
                   (if (contains? rs runtime)
                     (update rs runtime #(cond-> (assoc % :visible (boolean (:visible msg))
                                                     :title (:title msg))
                                           (:focused msg) (assoc :focused-at now)))
                     rs)))
      "bye"    (do (swap! runtimes- dissoc runtime)
                   (fail-pending! runtime (str "Runtime " runtime " disconnected")))
      ;; a runtime only answers its own requests
      "result" (when-let [{:keys [promise] :as req} (get @pending (:id msg))]
                 (when (= runtime (:runtime req))
                   (deliver promise msg)))
      "console-eval" (future (console-eval! runtime msg))
      nil)))

(defn runtimes
  "The runtimes connected, the pages running the build, by :runtime-id."
  []
  (->> (vals @runtimes-)
       (sort-by :runtime-id)
       (mapv #(select-keys % [:runtime-id :url :title :tag :visible :focused-at :user-agent]))))

(defn- default-runtime
  "The runtime focused last, visible ones first, else the one connected
  last."
  []
  (->> (vals @runtimes-)
       (sort-by (juxt #(if (:visible %) 1 0) #(or (:focused-at %) 0) :runtime-id))
       last))

(defn- target
  "The runtime opts target: :runtime-id, :tag or the default."
  [{:keys [runtime-id tag]}]
  (or (cond
        runtime-id (get @runtimes- runtime-id)
        tag        (first (filter #(= tag (:tag %)) (vals @runtimes-)))
        :else      (default-runtime))
      (throw (ex-info (cond
                        runtime-id (str "No runtime " runtime-id ", see (cljf.esm.repl/runtimes)")
                        tag        (str "No runtime tagged " (pr-str tag) ", see (cljf.esm.repl/runtimes)")
                        :else      "No JavaScript runtime connected: open a page running the build, or start a server running it")
               {:runtime-id runtime-id :tag tag :runtimes (runtimes)}))))

(defn- request!
  "Sends msg to runtime runtime-id, returns its reply, {:error ...} if it
  doesn't reply within timeout ms."
  [runtime-id msg timeout]
  (let [id (swap! request-ids inc)
        p  (promise)]
    (swap! pending assoc id {:promise p :runtime runtime-id})
    (try
      ((or (:send! @build) default-send!) runtime-id (assoc msg :id id))
      (let [reply (deref p timeout ::timeout)]
        (if (= ::timeout reply)
          {:error (str "No reply from runtime " runtime-id " within " timeout "ms")}
          reply))
      (finally
        (swap! pending dissoc id)))))

(defn- untag-others!
  "Removes tag from the runtimes other than runtime-id, all of runtime
  runtime-id's tag if tag is nil."
  [tag runtime-id]
  (doseq [{other :runtime-id} (vals @runtimes-)
          :when (if tag
                  (and (not= runtime-id other) (= tag (get-in @runtimes- [other :tag])))
                  (= runtime-id other))]
    (request! other {:op "tag" :tag nil} 5000)
    (swap! runtimes- #(cond-> % (contains? % other) (assoc-in [other :tag] nil)))))

(defn tag!
  "Tags runtime runtime-id (a page) with tag, to target it with {:tag tag},
  taking it from any other runtime. The page keeps it when reloaded (in
  sessionStorage), its runtime id changes."
  [runtime-id tag]
  (target {:runtime-id runtime-id})
  (when tag
    (untag-others! tag runtime-id))
  (let [reply (request! runtime-id {:op "tag" :tag tag} 5000)]
    (when-not (:error reply)
      (swap! runtimes- assoc-in [runtime-id :tag] tag))
    (:value reply)))

;; Compiling forms

(defn- the-build []
  (or @build
      (throw (ex-info "No build, the REPL runs in the JVM of cljf.esm/watch with :esm-repl" {}))))

(defn- locking* [^ReentrantLock lock f]
  (.lock lock)
  (try
    (f)
    (finally
      (.unlock lock))))

(defn- with-build
  "Calls f with the build's compiler environment and compiler bindings,
  holding the build lock: recompiles change the compiler environment too."
  [f]
  (let [{:keys [compiler-env options lock]} (the-build)]
    (locking* lock
      (fn []
        (env/with-compiler-env compiler-env
          (with-bindings (merge (esm/compiler-bindings options)
                           {#'ana/*cljs-static-fns*  (:static-fns options)
                            #'ana/*checked-arrays*   (:checked-arrays options)
                            #'ana/*unchecked-if*     false
                            #'ana/*unchecked-arrays* false})
            (f)))))))

(defn- warning-handler
  "Collects the warnings of the analyzer into the atom warnings."
  [warnings]
  (fn [warning-type env extra]
    (when (#{true :warning :error} (warning-type ana/*cljs-warnings*))
      (when-let [s (ana/error-message warning-type extra)]
        (swap! warnings conj {:message s
                              :file    (some-> ana/*cljs-file* str)
                              :line    (:line env)
                              :column  (:column env)})))))

(defn- repl-env [ns]
  (assoc (ana/empty-env)
    :ns (ana/get-namespace ns)
    :context :return
    :def-emits-var true))

(defn- ensure-ns!
  "Creates namespace ns in the analyzer if it doesn't exist, as in-ns does."
  [ns]
  (when-not (ana/get-namespace ns)
    (swap! env/*compiler* assoc-in [::ana/namespaces ns] {:name ns})))

(defn cljs-ns?
  "Whether ns is a ClojureScript namespace of the build or the REPL."
  [ns]
  (let [{:keys [compiler-env]} (the-build)]
    (contains? (::ana/namespaces @compiler-env) (symbol ns))))

(defn- build-ns? [ns]
  (contains? (:cljf.esm/namespaces @env/*compiler*) ns))

(defn- goog-shim? [ns]
  (or (= "goog" (str ns)) (some? (comp/esm-goog-shim-exports (str ns)))))

(defn- refs
  "The runtime's references of the namespaces of an emitted form,
  [alias ns kind path]: see cljf.esm.repl-runtime/load-ref."
  [{:keys [refs goog-lib-refs]}]
  (concat
    (for [ns (sort refs)
          :let [sym (symbol ns)]]
      (cond
        (goog-shim? sym) [(comp/esm-ns-alias ns) ns "module" (util/ns->relpath sym :js)]
        (build-ns? sym)  [(comp/esm-ns-alias ns) ns "ns" (util/ns->relpath sym :js)]
        :else            [(comp/esm-ns-alias ns) ns "ns" nil]))
    (for [ns (sort goog-lib-refs)]
      [(str (comp/esm-ns-alias ns) "$") ns "default" (comp/esm-goog-lib-path ns)])))

(defn- ns-refs
  "The runtime's references of namespaces nses, which a REPL require loads."
  [nses]
  (keep (fn [ns]
          (when (and (symbol? ns) (build-ns? ns))
            [(comp/esm-ns-alias ns) (str ns) "ns" (util/ns->relpath ns :js)]))
    nses))

(defn- analyze
  "Analyzes form in namespace ns, returns {:ast :warnings :ns}, ns the
  namespace after the form, i.e. of an ns form."
  [form ns file analyze-deps?]
  (let [warnings (atom [])]
    (binding [ana/*cljs-ns*               ns
              ana/*cljs-file*             file
              ana/*analyze-deps*          analyze-deps?
              ana/*cljs-warning-handlers* [(warning-handler warnings)]]
      (let [ast (ana/analyze (repl-env ns) form nil (:options @env/*compiler*))]
        {:ast ast :warnings @warnings :ns ana/*cljs-ns*}))))

(defn- compile-form
  "Analyzes and emits form in namespace ns, returns {:ast :warnings :ns
  :js :refs :changed?}, changed? whether the form changed the namespace's
  analysis, or {:ns-form ...} for ns, require, ... forms: the namespaces
  they require are compiled, then they're analyzed again."
  [form ns file]
  (with-build
    (fn []
      (ensure-ns! ns)
      (let [before (ana/get-namespace ns)
            {:keys [ast] :as analyzed} (analyze form ns file false)]
        (if (#{:ns :ns*} (:op ast))
          (assoc analyzed :ns-form true)
          (let [source-map (atom {:source-map (sorted-map) :gen-line 0})
                emitted    (binding [ana/*cljs-ns*                   ns
                                     comp/*source-map-data*          source-map
                                     comp/*source-map-data-gen-col*  (AtomicLong.)]
                             (comp/emit-esm-repl ast))]
            ;; Closure Library namespaces run by the compatibility layer are
            ;; written for the namespaces using them
            (when (seq (:goog-lib-refs emitted))
              (swap! env/*compiler* update-in [::ana/namespaces ns :cljf.esm/goog-libs]
                (fnil into #{}) (map str (:goog-lib-refs emitted)))
              (esm/install-goog-libs env/*compiler* (:options @env/*compiler*)))
            (assoc analyzed
              :js (:js emitted)
              :source-map @source-map
              :refs (refs emitted)
              :changed? (not (identical? before (ana/get-namespace ns))))))))))

(defn- warnings-error [warnings]
  (str "Form not evaluated: " (count warnings) " warning(s)"))

(defn- format-warnings
  "Warnings as printed by the compiler, for the REPL's err output."
  [warnings]
  (apply str
    (for [{:keys [message file line column]} warnings]
      (str "WARNING: " message
        (when file (str " at " file (when line (str ":" line (when column (str ":" column))))))
        "\n"))))

(defn- stamps
  "The stamps of the last compiles of namespaces nses, {ns stamp}, see
  cljs.compiler/emit-esm-repl-footer."
  [nses]
  (with-build
    (fn []
      (into {}
        (keep (fn [ns]
                (when-let [stamp (:cljf.esm/repl-stamp (ana/get-namespace ns))]
                  [(str ns) stamp])))
        nses))))

(defn- await-hot-reload
  "Waits for runtime runtime-id to run the modules of the last compiles of
  namespaces nses, the ones it has loaded, i.e. their hot reload applied.
  Returns an error message if it didn't."
  [runtime-id nses timeout]
  (let [want (stamps nses)]
    (when (seq want)
      (let [{:keys [value error]} (request! runtime-id {:op "await-stamps" :stamps want :timeout timeout}
                                    (+ timeout 5000))]
        (cond
          error             error
          (= "false" value) (str "The hot reload didn't apply in runtime " runtime-id " within " timeout "ms"))))))

(defn- outstanding-warnings []
  (some->> (:warnings @build) deref vals (apply concat)))

(defn- compile-namespaces!
  "Compiles namespaces nses (of the build or new, required at the REPL)
  with the watcher, if runtime is given and the namespaces are loaded,
  waits for their hot reload. Returns {:warnings :compile-error
  :hot-reload-error :note}."
  [nses runtime opts]
  (let [{:keys [compile! options]} (the-build)]
    (swap! (:cljf.esm/repl-mains options) into nses)
    (let [{:keys [type warnings message] :as event} (compile! {:nses nses :force true})]
      (cond
        (= "error" type)
        {:compile-error message}

        (seq (outstanding-warnings))
        {:warnings warnings
         :note     (str "hot reload is paused by " (count (outstanding-warnings)) " warning(s)")}

        :else
        {:warnings warnings
         :hot-reload-error (when runtime
                             (await-hot-reload (:runtime-id runtime) nses (:hot-reload-timeout opts 10000)))}))))

(defn- source-ns?
  "Whether namespace ns has a ClojureScript source the build can compile."
  [ns]
  (some? (util/ns->source ns)))

(defn- load-ns-form!
  "The REPL's ns, require, ... forms: compiles the namespaces required that
  aren't part of the build (the watcher compiles them from now on), and
  those required with :reload (:reload-all), loads them in the runtime."
  [form ns file {:keys [ast warnings]} opts]
  (let [deps     (remove string? (:deps ast))
        ;; :reload and :reload-all apply to the namespaces the form requires
        reload?  (some #{:reload :reload-all} (concat (vals (:reload ast)) (mapcat vals (vals (:reloads ast)))))
        reloads  (if reload? (set deps) #{})
        npm      (filter string? (:deps ast))
        new      (with-build
                   (fn []
                     (->> deps
                          (remove #(or (build-ns? %) (goog-shim? %)))
                          (filter source-ns?)
                          doall)))
        compile  (distinct (concat new (filter #(with-build (fn [] (build-ns? %))) reloads)))
        runtime  (try (target opts) (catch Exception _ nil))]
    (cond
      (seq npm)
      {:error (str "npm modules can't be required at the REPL, require them in a namespace of the build: "
                (string/join ", " npm))}

      (seq warnings)
      {:warnings warnings :error (warnings-error warnings)}

      :else
      (let [{:keys [compile-error] :as compiled} (when (seq compile) (compile-namespaces! compile runtime opts))]
        (if compile-error
          {:error compile-error}
          ;; analyzed again now that its dependencies are, checks :refer
          (let [{:keys [warnings] :as analyzed} (with-build #(analyze form ns file true))]
            (if (seq warnings)
              {:warnings warnings :error (warnings-error warnings)}
              (let [loaded (when runtime
                             (request! (:runtime-id runtime)
                               {:op "load" :refs (with-build #(doall (ns-refs deps)))}
                               (:timeout opts 30000)))
                    error  (or (:hot-reload-error compiled) (:error loaded))]
                (cond-> {:value    "nil"
                         :ns       (:ns analyzed)
                         :warnings (:warnings compiled)}
                  runtime          (assoc :runtime-id (:runtime-id runtime))
                  (:note compiled) (assoc :note (:note compiled))
                  error            (assoc :error error))))))))))

;; REPL special functions, evaluated in the JVM

(defn- doc-string
  "The documentation of name, as cljs.repl/doc prints it."
  [ns name]
  (with-out-str
    (let [env (repl-env ns)
          m   (cond
                (cljs.repl/special-doc-map name)
                (assoc (cljs.repl/special-doc-map name) :name name :special-form true)

                (ana-api/find-ns name)
                (select-keys (ana-api/find-ns name) [:name :doc])

                :else
                (when-let [v (ana/no-warn (ana-api/resolve env name))]
                  (-> (select-keys v [:ns :name :doc :forms :arglists :macro])
                      (update :name #(symbol (clojure.core/name %))))))]
      (when m
        (println "-------------------------")
        (println (str (when-let [ns (:ns m)] (str ns "/")) (:name m)))
        (cond
          (:forms m) (doseq [f (:forms m)] (println "  " f))
          (:arglists m) (prn (let [a (:arglists m)] (if (= 'quote (first a)) (second a) a))))
        (cond
          (:special-form m) (println "Special Form")
          (:macro m)        (println "Macro"))
        (println " " (:doc m))))))

(defn- named-publics [ns]
  (->> (ana-api/ns-publics ns)
       (remove (comp :anonymous val))
       keys
       sort))

(def ^:private special-fns
  {'doc     (fn [ns [_ name]]
              {:out (doc-string ns name)})
   'source  (fn [ns [_ name]]
              {:out (str (or (cljs.repl/source-fn (repl-env ns) name) "Source not found") "\n")})
   'dir     (fn [ns [_ ns-sym]]
              (let [ns-sym (or (get-in (ana/get-namespace ns) [:requires ns-sym]) ns-sym)]
                {:out (apply str (map #(str % "\n") (named-publics ns-sym)))}))
   'apropos (fn [_ [_ s]]
              (let [matches? (if (instance? java.util.regex.Pattern s)
                               #(re-find s (str %))
                               #(string/includes? (str %) (str s)))]
                {:value (pr-str (sort (for [ns (ana-api/all-ns)
                                            sym (named-publics ns)
                                            :when (matches? sym)]
                                        (symbol (str ns) (str sym)))))}))})

(defn- special-fn
  "The special function of form, unless the name is a var of namespace ns."
  [ns form]
  (when (and (seq? form) (symbol? (first form)))
    (let [sym (first form)
          sym (if (#{"cljs.repl" "clojure.core" "cljs.core" "clojure.repl"} (namespace sym))
                (symbol (name sym))
                sym)]
      (when (and (nil? (namespace sym))
                 (not (get-in (ana/get-namespace ns) [:defs sym]))
                 (not (get-in (ana/get-namespace ns) [:uses sym])))
        (get '{in-ns :in-ns load-file :load-file} sym (special-fns sym))))))

;; Evaluating

(declare load-file*)

(defonce ^:private snippet-maps
  ;; the source maps of the forms evaluated last and the name of their
  ;; source in stack traces, {js-file [json name]}, see map-stack
  (atom {}))

(defn- snippet-source-map
  "The source map of a compiled form, to its source (the text it was read
  from) named file. The runtime compiles the form's code with new Function,
  which puts two lines before it. Kept for map-stack."
  [{:keys [source-map]} file source js-file shown-file]
  (when (and source (seq (:source-map source-map)))
    (let [json (sm/encode {file (:source-map source-map)}
                 {:lines               (+ (:gen-line source-map) 3)
                  :file                js-file
                  :preamble-line-count 2
                  :sources-content     [source]})]
      (swap! snippet-maps #(-> (if (< 200 (count %)) {} %) (assoc js-file [json shown-file])))
      json)))

(defn- inline-source-map [json]
  (when json
    (str "\n//# sourceMappingURL=data:application/json;base64,"
      (.encodeToString (Base64/getEncoder) (.getBytes ^String json "UTF-8")))))

(def ^:private decoded-maps
  ;; output source maps decoded, {path [last-modified decoded]}
  (atom {}))

(defn- decode-map [json]
  (sm/decode (json/read-str json :key-fn keyword)))

(defn- module-source-map
  "The decoded source map of the output file at path (relative to the output
  directory) and the directory its sources are relative to, nil if it has
  none."
  [path]
  (let [f (io/file (util/output-directory (:options (the-build))) (str path ".map"))]
    (when (.exists f)
      (let [mtime (.lastModified f)
            [cached decoded] (get @decoded-maps path)]
        [(if (= cached mtime)
           decoded
           (let [decoded (decode-map (slurp f))]
             (swap! decoded-maps assoc path [mtime decoded])
             decoded))
         (some-> (.getParent (io/file path)) (str "/"))]))))

(defn- original-position
  "The original position of 1-based line and column in decoded source map
  m, [source line column], 1-based."
  [m line column]
  (when-let [cols (get m (dec line))]
    (when-let [[_ [{:keys [source line col]}]] (or (first (rsubseq cols <= (dec column)))
                                                   (first cols))]
      [source (inc line) (inc col)])))

(defn- map-location
  "The ClojureScript location of url:line:column of a stack trace in runtime
  runtime-id, nil if it has no source map."
  [runtime-id url line column]
  (let [root (get-in @runtimes- [runtime-id :root])]
    (when-let [[m dir shown] (cond
                               (string/starts-with? url "cljs-repl/")
                               (when-let [[json shown] (get @snippet-maps url)]
                                 [(decode-map json) nil shown])

                               (and root (string/starts-with? url root))
                               (module-source-map (first (string/split (subs url (count root)) #"[?#]"))))]
      (when-let [[source line column] (original-position m line column)]
        (str (cond
               shown                                         shown
               (and dir (not (string/starts-with? source "/"))) (str dir source)
               :else                                         source)
          ":" line ":" column)))))

(defn- map-stack
  "Stack trace stack of runtime runtime-id with its locations mapped to the
  ClojureScript sources, without the frames of the REPL's evaluation after
  the form's."
  [runtime-id stack]
  (when stack
    (let [lines (string/split-lines stack)
          ;; mapped to its source already in Vite's ssr module runner
          lines (take-while #(not (re-find #"/cljf/esm/repl_runtime\.(?:js|cljs):" %)) lines)]
      (->> lines
           (map #(string/replace % #"((?:https?://|file://|cljs-repl/)[^\s()]+):(\d+):(\d+)"
                   (fn [[match url line column]]
                     (or (try
                           (map-location runtime-id url (Long/parseLong line) (Long/parseLong column))
                           (catch Exception _ nil))
                         match))))
           (string/join "\n")))))

(defn- eval-js
  "Evaluates a compiled form in the runtime."
  [{:keys [js refs] :as compiled} ns runtime {:keys [timeout print-length print-level repl source file] :as opts}]
  (let [n       (swap! snippets inc)
        js-file (str "cljs-repl/" ns "-" n ".js")
        shown   (or file "<cljs repl>")
        file    (if (or (nil? file) (= "<cljs repl>" file))
                  (str "cljs-repl/" ns "-" n ".cljs")
                  file)]
    (request! (:runtime-id runtime)
      {:op          "eval"
       :code        (str js "\n//# sourceURL=" js-file
                      (inline-source-map (snippet-source-map compiled file source js-file shown)))
       :refs        refs
       :repl        (boolean repl)
       :await       (boolean (:await opts))
       :keep        (:keep opts)
       :printLength (if (contains? opts :print-length) print-length *print-length*)
       :printLevel  (if (contains? opts :print-level) print-level *print-level*)}
      (or timeout 30000))))

(defn- eval-form
  "Evaluates form in namespace ns, returns {:ns :value :out :err :warnings
  :error :stack :ex-data :runtime-id}, :ns the namespace evaluating the next
  form."
  [form ns file opts]
  (let [special (with-build #(special-fn ns form))]
    (cond
      (= :in-ns special)
      (let [[_ [quote' ns-sym]] form]
        (if-not (and (= 'quote quote') (symbol? ns-sym))
          {:ns ns :error "Argument to in-ns must be a quoted symbol"}
          (do (with-build #(ensure-ns! ns-sym))
              {:ns ns-sym :value "nil"})))

      (= :load-file special)
      (assoc (load-file* (second form) nil opts) :ns ns)

      special
      (merge {:ns ns :value "nil"} (with-build #(special ns form)))

      :else
      (let [{:keys [warnings ns-form changed?] :as compiled} (compile-form form ns file)]
        (cond
          ns-form
          (merge {:ns ns} (load-ns-form! form ns file compiled opts))

          (and (seq warnings) (not (:warnings-ok opts)))
          {:ns ns :warnings warnings :error (warnings-error warnings)}

          :else
          (let [runtime (target opts)
                reply   (eval-js compiled ns runtime (assoc opts :file file))]
            (when changed?
              ((:run-hooks! (the-build)) [ns]))
            (merge {:ns ns :warnings warnings :runtime-id (:runtime-id runtime)}
              (select-keys reply [:value :out :err :error])
              (when-let [stack (:stack reply)]
                {:stack (map-stack (:runtime-id runtime) stack)})
              (when-let [d (:exData reply)] {:ex-data d}))))))))

(defn- forms
  "The forms of string or reader source, read lazily: an in-ns or ns form
  changes the namespace reading the forms after it, see eval-forms."
  [source file]
  (ana/forms-seq* (if (string? source) (StringReader. source) source) file))

(defn- eval-forms
  "Evaluates the forms of source (a string) from namespace ns, calls
  on-result with each form's result (see eval-form) until one fails.
  Returns the namespace after them."
  [source ns file opts on-result]
  (let [{:keys [compiler-env]} (the-build)
        opts (cond-> opts (string? source) (assoc :source source))]
    ;; #= would evaluate Clojure while reading
    (binding [ana/*cljs-ns*      ns
              reader/*read-eval* false]
      (env/with-compiler-env compiler-env
        (loop [forms (forms source file)]
          ;; not [form & more], next would read the form after it now
          (let [forms (try
                        (seq forms)
                        (catch Exception e
                          (on-result {:ns ana/*cljs-ns* :error (str "Could not read: " (.getMessage e))})
                          nil))
                form  (first forms)
                more  (rest forms)]
            ;; nil and false are forms too
            (if (and forms (not= :cljs/quit form))
              (let [result (try
                             (eval-form form ana/*cljs-ns* file opts)
                             (catch Exception e
                               {:ns ana/*cljs-ns*
                                :error (.getMessage e)
                                :ex-data (some-> (ex-data e) (dissoc :runtimes) not-empty pr-str)}))]
                (set! ana/*cljs-ns* (:ns result))
                (on-result result)
                (if (:error result)
                  ana/*cljs-ns*
                  (recur more)))
              ana/*cljs-ns*)))))))

(defn- canonical [^File f]
  (.getCanonicalFile f))

(defn- in-dirs? [^File f dirs]
  (let [path (.getPath (canonical f))]
    (some #(string/starts-with? path (str % File/separator)) dirs)))

(defn- load-file*
  "Loads file path: hot reloads its namespace, compiled by the watcher,
  waiting for the hot reload in the runtime, unless contents (an editor's
  buffer) differ from the file, or the file isn't in a source directory:
  then evaluates its forms."
  [path contents opts]
  (let [f    (io/file path)
        disk (when (.exists f) (slurp f))
        ns   (try (:ns (ana/parse-ns f)) (catch Exception _ nil))]
    (if (or (nil? disk) (and contents (not= contents disk)) (nil? ns)
            (not (in-dirs? f (:watch-dirs (the-build)))))
      (let [out     (StringBuilder.)
            err     (StringBuilder.)
            results (atom [])]
        (eval-forms (or contents disk) 'cljs.user (str path) opts
          (fn [r]
            (swap! results conj r)
            (some->> (:out r) (.append out))
            (some->> (:err r) (.append err))
            (some->> (:warnings r) seq format-warnings (.append err))))
        (let [failed (first (filter :error @results))]
          (merge {:value "nil"
                  :out   (str out)
                  :err   (str err "; evaluated the forms, not hot reloaded: reload hooks not run\n")}
            (select-keys failed [:error :stack :ex-data]))))
      (let [{:keys [compile! options]} (the-build)
            runtime (try (target opts) (catch Exception _ nil))]
        (when-not (with-build #(build-ns? ns))
          (swap! (:cljf.esm/repl-mains options) conj ns))
        ;; nil when the watcher compiled the file already (saved), the page may
        ;; not have applied the hot reload yet either way
        (let [{:keys [type warnings message] :as event} (compile! {:files [f]})]
          (cond
            (= "error" type)
            {:error message :ex-data (pr-str (dissoc event :type :message))}

            (seq (outstanding-warnings))
            {:value    "nil"
             :warnings warnings
             :err      (str (format-warnings (outstanding-warnings))
                         "; hot reload is paused until the warnings are fixed\n")}

            :else
            (let [error (when runtime
                          (await-hot-reload (:runtime-id runtime) [ns] (:hot-reload-timeout opts 10000)))]
              (cond-> {:value "nil" :warnings (vec warnings)}
                error (assoc :error error)))))))))

;; API

(defn load-file
  "Loads ClojureScript file path into the runtime, see cljs-eval for opts:
  hot reloads its namespace (compiled by the watcher) and waits for the
  runtime to apply it, unless the file isn't in a source directory, whose
  forms are evaluated. Returns {:value :warnings :error}."
  ([path] (load-file path {}))
  ([path opts]
   (let [{:keys [compiler-env]} (the-build)]
     (binding [ana/*cljs-ns* 'cljs.user]
       (env/with-compiler-env compiler-env
         (load-file* path (:contents opts) opts))))))

(defn cljs-eval
  "Evaluates the forms of string code in the runtime, like shadow-cljs'
  cljs-eval. Returns {:results [printed value] :out :err :warnings :ns
  :runtime-id}, with :error (and :stack, :ex-data) when a form failed:
  forms after it aren't evaluated, its result is :cljf.esm.repl/failed. A
  form with warnings isn't evaluated, unless :warnings-ok.

  opts:
  - :ns - evaluate in this namespace, default cljs.user
  - :runtime-id - evaluate in this runtime, see runtimes
  - :tag - evaluate in the runtime with this tag, see tag!
  - :await - wait for a promise's value
  - :timeout - give up waiting after these ms, default 30000
  - :warnings-ok - evaluate forms with warnings
  - :print-length, :print-level - print the values with these"
  ([code] (cljs-eval code {}))
  ([code {:keys [ns] :or {ns 'cljs.user} :as opts}]
   (let [results (atom [])
         ns'     (eval-forms code (symbol ns) "<cljs repl>" opts #(swap! results conj %))
         rs      @results
         failed  (first (filter :error rs))]
     (merge
       {:results    (mapv #(if (:error %) ::failed (:value %)) rs)
        :out        (apply str (keep :out rs))
        :err        (apply str (keep :err rs))
        :warnings   (vec (mapcat :warnings rs))
        :ns         ns'
        :runtime-id (some :runtime-id rs)}
       (select-keys failed [:error :stack :ex-data])))))

(defn- console-eval!
  "Evaluates the forms of a page's cljs_eval (see
  cljf.esm.repl-runtime/console-eval) in its runtime, in the namespace of
  its last in-ns unless given one, replies with the result."
  [runtime-id {:keys [rid code ns] :as msg}]
  (let [ns     (or (some-> ns symbol) (get-in @runtimes- [runtime-id :console-ns]) 'cljs.user)
        result (try
                 (cljs-eval code {:ns ns :runtime-id runtime-id :await (:await msg) :keep rid})
                 (catch Exception e
                   {:error (.getMessage e)}))]
    (when-let [ns' (:ns result)]
      (swap! runtimes- #(cond-> % (contains? % runtime-id) (assoc-in [runtime-id :console-ns] ns'))))
    ((or (:send! @build) default-send!) runtime-id
     {:op     "console-result"
      :rid    rid
      :result (-> (select-keys result [:results :out :err :warnings :error :stack])
                  (update :results #(mapv (fn [r] (when-not (= ::failed r) r)) %))
                  (assoc :ns (some-> (:ns result) str)))})))

(defn eval-string
  "Evaluates the forms of code from namespace ns for an interactive REPL,
  calling on-result with each form's result: {:ns :value :out :err
  :warnings :error :stack :ex-data :runtime-id}. Sets *1, *2, *3 and *e in
  the runtime. Returns the namespace after them."
  [code ns file opts on-result]
  (eval-forms code ns (or file "<cljs repl>") (assoc opts :repl true) on-result))

(defn format-result
  "The text an interactive REPL prints for result r besides its value: the
  warnings, the error and a note."
  [{:keys [warnings error stack ex-data note]}]
  (str (format-warnings warnings)
    (when error
      (str (if (and stack (string/includes? stack error)) stack (str error (when stack (str "\n" stack))))
        (when ex-data (str "\n" ex-data))
        "\n"))
    (when note (str "; " note "\n"))))

(defn announce
  "The note an interactive REPL prints when the runtime evaluating forms
  changed, last the runtime before."
  [last runtime-id]
  (when (and runtime-id (not= last runtime-id))
    (let [{:keys [url tag]} (get @runtimes- runtime-id)]
      (str "; now evaluating in runtime " runtime-id
        (when url (str " (" url ")"))
        (when tag (str " tagged " (pr-str tag)))
        "\n"))))

(defn repl
  "Switches the nREPL session evaluating it to ClojureScript, evaluated in
  the runtime (see cljs-eval for opts), until :cljs/quit. Outside nREPL
  reads forms from *in*."
  ([] (repl {}))
  ([opts]
   (the-build)
   (if-let [enter! (when-let [msg (some-> (resolve 'nrepl.middleware.interruptible-eval/*msg*) deref)]
                     (when (:session msg)
                       (requiring-resolve 'cljf.esm.repl.nrepl/enter!)))]
     (enter! opts)
     (loop [ns 'cljs.user last nil]
       (print (str ns "=> "))
       (flush)
       (let [line (read-line)]
         (when (and line (not= ":cljs/quit" (string/trim line)))
           (let [runtime (volatile! last)
                 ns'     (eval-string line ns nil opts
                           (fn [{:keys [value out err runtime-id] :as r}]
                             (some-> (announce @runtime runtime-id) print)
                             (when runtime-id (vreset! runtime runtime-id))
                             (some-> out print)
                             (binding [*out* *err*]
                               (some-> err print)
                               (print (format-result r))
                               (flush))
                             (some-> value println)))]
             (recur ns' @runtime))))))))
