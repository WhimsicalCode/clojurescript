;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm
  "Compiles ClojureScript to ES modules, one module per namespace, without
  the Google Closure Compiler. npm packages and relative JavaScript /
  TypeScript files required with string requires are emitted as plain
  imports, resolving and bundling is left to a bundler such as Vite."
  (:require [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.js-deps :as deps]
            [cljs.util :as util]
            [cljs.vendor.clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io File]
           [java.net URL]
           [java.util.concurrent ExecutionException Executors Future LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def default-opts
  {:module-format  :esm
   :optimizations  :none
   :output-dir     "out"
   :language-out   :es2020
   :static-fns     true
   :cache-analysis true
   :source-map     true})

(def goog-shims
  "The subset of the Closure Library cljs.core and friends depend on,
  reimplemented as ES modules. Paths are relative to the output directory and
  the cljf/esm resource directory."
  ["goog.js"
   "goog/array.js"
   "goog/math.js"
   "goog/object.js"
   "goog/string.js"
   "goog/userAgent/product.js"])

(def ^:dynamic *generated-sources*
  "Generated namespaces of the build, {ns File}, see :test-runner."
  {})

(defn- source-for-ns
  "Returns the URL of the ClojureScript source of namespace ns on the
  classpath, nil if ns isn't a ClojureScript namespace."
  ^URL [ns]
  (if-let [^File f (get *generated-sources* ns)]
    (.toURL (.toURI f))
    (util/ns->source ns)))

(defn- jar-url? [^URL url]
  (= "jar" (.getProtocol url)))

(defn- source-file
  "Returns a File for a source URL. Sources in jars are copied to the output
  directory, like cljs.closure does."
  ^File [^URL url ns output-dir]
  (if (jar-url? url)
    (let [ext  (util/ext url)
          file (io/file output-dir (util/ns->relpath ns ext))]
      (when (or (not (.exists file))
                (< (.lastModified file) (util/last-modified url)))
        (util/mkdirs file)
        (with-open [in (io/input-stream url)]
          (io/copy in file)))
      file)
    (io/file url)))

(def ^:private parse-ns-cache (atom {}))

(defn- parse-ns
  "ana/parse-ns, cached by file and modification time, the watcher finds
  sources on every change."
  [^File file]
  (let [k [(.getPath file) (.lastModified file)]]
    (or (get @parse-ns-cache k)
        (let [ns-info (ana/parse-ns file)]
          (swap! parse-ns-cache assoc k ns-info)
          ns-info))))

(defn- main-namespaces
  "The :main namespaces, a symbol or a collection of them, and :extra-main's,
  which tools add to them (the Vite plugin, the namespaces of the pages'
  scripts)."
  [{:keys [main extra-main]}]
  (distinct (concat (cond (coll? main) main main [main]) extra-main)))

(defn- js-entry-namespaces
  "The namespaces of the vars :js-entries export."
  [{:keys [js-entries]}]
  (->> (vals js-entries) (mapcat (comp vals :exports)) (map (comp symbol namespace)) distinct))

(defn- mains
  "The namespaces a build compiles with their dependencies: the :preloads,
  the main namespaces, those :js-entries export from, and under :esm-repl
  the REPL runtime and the namespaces required at the REPL (::repl-mains, an
  atom)."
  [{:keys [preloads esm-repl ::repl-mains] :as opts}]
  (concat (when esm-repl [comp/esm-repl-runtime])
          preloads (main-namespaces opts) (js-entry-namespaces opts)
          (some-> repl-mains deref sort)))

(defn- parse-sources-parallel!
  "Parses the ns forms of namespaces and their transitive dependencies with
  a pool of threads, filling parse-ns' cache for find-sources' walk, which
  is sequential (its order is the build's)."
  [namespaces output-dir]
  (let [pool  (Executors/newFixedThreadPool (.availableProcessors (Runtime/getRuntime)))
        seen  (java.util.concurrent.ConcurrentHashMap.)
        ;; one for this thread until it has submitted every root: tasks
        ;; finishing meanwhile mustn't count down to none before then
        tasks (java.util.concurrent.atomic.AtomicInteger. 1)
        done  (java.util.concurrent.CountDownLatch. 1)
        finish! #(when (zero? (.decrementAndGet tasks)) (.countDown done))
        visit (fn visit [ns]
                (when (nil? (.putIfAbsent seen ns true))
                  (.incrementAndGet tasks)
                  (.execute pool
                    ^Runnable
                    (bound-fn []
                      (try
                        (when-let [url (source-for-ns ns)]
                          (let [ns-info (parse-ns (source-file url ns output-dir))]
                            (doseq [r (cons 'cljs.core (map symbol (remove string? (:requires ns-info))))]
                              (visit r))))
                        ;; find-sources' walk reports it
                        (catch Throwable _)
                        (finally
                          (finish!)))))))]
    (try
      (try
        (run! visit (map symbol namespaces))
        (finally
          (finish!)))
      (.await done)
      (finally
        (.shutdown pool)))))

(defn find-sources
  "Returns the parsed ns info of namespaces and their transitive ClojureScript
  dependencies in dependency order."
  [namespaces {:keys [output-dir parallel-build]}]
  (when parallel-build
    (parse-sources-parallel! namespaces output-dir))
  (letfn [(visit [{:keys [seen order] :as state} ns]
            (if (contains? seen ns)
              state
              (if-let [url (source-for-ns ns)]
                (let [file    (source-file url ns output-dir)
                      ns-info (parse-ns file)
                      state   (reduce visit
                                (update state :seen conj ns)
                                (->> (:requires ns-info)
                                     (remove string?)
                                     (map symbol)
                                     (cons 'cljs.core)
                                     (remove #{ns})))]
                  (update state :order conj (assoc ns-info :source-file file)))
                (update state :seen conj ns))))]
    (:order (reduce visit {:seen #{} :order []} (map symbol namespaces)))))

(defn- output-file ^File [ns {:keys [output-dir]}]
  (io/file output-dir (util/ns->relpath ns :js)))

(defn- install-goog-shims [{:keys [output-dir]}]
  (doseq [path goog-shims]
    (let [res  (io/resource (str "cljf/esm/" path))
          dest (io/file output-dir path)]
      (when (or (not (.exists dest))
                (not= (slurp res) (slurp dest)))
        (util/mkdirs dest)
        (spit dest (slurp res))))))

(defn- spit-if-changed [^File f content]
  (when (or (not (.exists f)) (not= content (slurp f)))
    (util/mkdirs f)
    (spit f content)))

(defn- relative-import
  "Import path of file to from file from, both relative to the output dir."
  [from to]
  (let [rel (-> (.relativize (.toPath (.getParentFile (io/file "/" from))) (.toPath (io/file "/" to)))
                str
                (string/replace File/separator "/"))]
    (if (string/starts-with? rel ".") rel (str "./" rel))))

(def ^:private goog-base "goog-lib/goog/base.js")

(defn- indirect-eval
  "Makes Closure Library's direct eval calls (goog.json.parse, base.js'
  module loader) indirect: a direct eval keeps minifiers from renaming any
  binding it can see, i.e. the top level of the chunk it's bundled into."
  [src]
  (string/replace src #"(?<![\w$.])eval\(" "(0, eval)("))

(defn- goog-base-module
  "Closure Library's base.js as an ES module exporting goog, also installed as
  the global goog Closure Library code expects. The debug loader is disabled,
  dependencies are loaded by imports."
  []
  (let [src (slurp (io/resource "goog/base.js"))]
    (str "// Closure Library base.js as an ES module, see cljf.esm/install-goog-libs\n"
         "globalThis.CLOSURE_NO_DEPS = true;\n"
         "globalThis.CLOSURE_DEFINES = Object.assign({'goog.ENABLE_DEBUG_LOADER': false}, globalThis.CLOSURE_DEFINES);\n"
         (-> src
             indirect-eval
             (string/replace #"(?m)^var COMPILED = false;" "var COMPILED = false; // eslint-disable-line")
             (string/replace #"(?m)^var goog = goog \|\| \{\};" "var goog = {};")
             (string/replace #"(?m)^goog\.global =\s*[^;]*;" "goog.global = globalThis;"))
         ;; base.js isn't strict mode, ES modules are: uids of non-extensible
         ;; objects can't be properties, see cljf/esm/goog.js
         "\nconst frozenUids = new WeakMap();\n"
         "const getUid = goog.getUid;\n"
         "goog.getUid = function(obj) {\n"
         "  if (Object.isExtensible(obj) || Object.prototype.hasOwnProperty.call(obj, goog.UID_PROPERTY_)) return getUid(obj);\n"
         "  let uid = frozenUids.get(obj);\n"
         "  if (uid === undefined) frozenUids.set(obj, (uid = ++goog.uidCounter_));\n"
         "  return uid;\n"
         "};\n"
         "if (goog.getHashCode) goog.getHashCode = goog.getUid;\n"
         "\nglobalThis.goog = goog;\nexport { goog };\n")))

(defn- goog-file-module
  "A Closure Library file as an ES module importing the files providing its
  requires, goog.module files are run by goog.loadModule."
  [lib {:keys [file requires module]}]
  (let [src     (indirect-eval (slurp (io/resource file)))
        out     (str "goog-lib/" file)
        imports (->> requires
                     (keep #(:file (lib %)))
                     (remove #{file "goog/base.js"})
                     distinct)]
    (str "import { goog } from \"" (relative-import out goog-base) "\";\n"
         (apply str (map #(str "import \"" (relative-import out (str "goog-lib/" %)) "\";\n") imports))
         (if (= :goog module)
           (str "goog.loadModule(function(exports) {'use strict';\n" src "\n;return exports;});\n")
           src))))

(def ^:private installed-goog-libs
  "The Closure Library namespaces install-goog-libs wrote last, by output
  directory."
  (atom {}))

(defn install-goog-libs
  "Writes the Closure Library namespaces used without an ES module shim
  (recorded by the compiler as :cljf.esm/goog-libs of the namespaces using
  them) and the files they depend on as ES modules run by Closure Library's
  base.js. goog-lib/ns/<ns>.js default exports namespace ns."
  [compiler-env {:keys [output-dir]}]
  (let [idx  (:js-dependency-index @compiler-env)
        lib  #(deps/closure-lib idx %)
        nses (into (sorted-set) (mapcat ::goog-libs) (vals (::ana/namespaces @compiler-env)))
        out  (.getCanonicalPath (io/file output-dir))]
    ;; the modules depend on the namespaces only (and the classpath, which a
    ;; process doesn't change): the watcher's recompiles skip writing them
    ;; again while they're the ones written last, and base.js is in place
    (when (and (seq nses)
               (not (and (= nses (get @installed-goog-libs out))
                         (.exists (io/file output-dir goog-base)))))
      (let [files (loop [queue (vec (keep lib nses)) seen {}]
                    (if-let [{:keys [file requires] :as info} (first queue)]
                      (if (or (contains? seen file) (= "goog/base.js" file))
                        (recur (subvec queue 1) seen)
                        (recur (into (subvec queue 1) (keep lib requires))
                          (assoc seen file info)))
                      (vals seen)))]
        (spit-if-changed (io/file output-dir goog-base) (goog-base-module))
        (doseq [info files]
          (spit-if-changed (io/file output-dir "goog-lib" (:file info)) (goog-file-module lib info)))
        (doseq [ns nses]
          (when-let [{:keys [file]} (lib ns)]
            (let [out (comp/esm-goog-lib-path ns)]
              (spit-if-changed (io/file output-dir out)
                (str "import { goog } from \"" (relative-import out goog-base) "\";\n"
                     (when-not (= "goog/base.js" file)
                       (str "import \"" (relative-import out (str "goog-lib/" file)) "\";\n"))
                     "export default goog.module.get(\"" ns "\");\n")))))
        (swap! installed-goog-libs assoc out nses)))))

(defn- write-package-json
  "Marks the output directory as ES modules for Node.js."
  [{:keys [output-dir]}]
  (let [f (io/file output-dir "package.json")]
    (when-not (.exists f)
      (util/mkdirs f)
      (spit f (json/write-str {:type "module"})))))

(defn- js-entry-path [entry]
  (str "cljs-esm-entries/" (name entry) ".js"))

(defn- write-js-entries
  "Writes the :js-entries modules, {name {:exports {js-name ns/var}}}: each
  exports vars of the build under JavaScript names, like shadow-cljs'
  :exports. The namespaces are compiled, the entries are bundle entry points,
  importing the :preloads first, and under :esm-repl the REPL runtime, as main
  namespaces do: the REPL evaluates in a server running an entry."
  [{:keys [output-dir js-entries preloads esm-repl]}]
  (doseq [[entry {:keys [exports]}] js-entries
          :let [path (js-entry-path entry)]]
    (spit-if-changed (io/file output-dir path)
      (apply str
        (concat
          (for [preload (distinct (map symbol (cond->> preloads esm-repl (cons comp/esm-repl-runtime))))]
            (str "import \"" (relative-import path (util/ns->relpath (symbol preload) :js)) "\";\n"))
          (for [[js-name sym] (sort-by (comp name key) exports)]
          (str "export { " (comp/esm-var-name (name sym)) " as " (name js-name) " } from \""
               (relative-import path (util/ns->relpath (symbol (namespace sym)) :js)) "\";\n")))))))

(defn- check-js-entries
  "Throws when a :js-entries export names a var the build doesn't define."
  [compiler-env {:keys [js-entries]}]
  (doseq [[entry {:keys [exports]}] js-entries
          [js-name sym] exports
          :when (not (get-in @compiler-env [::ana/namespaces (symbol (namespace sym)) :defs (symbol (name sym))]))]
    (throw (ex-info (str "js-entries " (name entry) " exports " (name js-name) ", undefined var " sym)
             {:entry entry :export js-name :var sym}))))

(defn- write-build-info
  "Writes cljs-esm.json to the output directory, the build's entry points for
  bundlers: the modules of the main namespaces and the :js-entries."
  [{:keys [output-dir js-entries] :as opts}]
  (write-js-entries opts)
  (spit-if-changed (io/file output-dir "cljs-esm.json")
    (json/write-str
      {:main (into (sorted-map)
               (map (fn [ns] [(str ns) (util/ns->relpath (symbol ns) :js)]))
               (main-namespaces opts))
       :entries (into (sorted-map)
                  (map (fn [entry] [(name entry) (js-entry-path entry)]))
                  (keys js-entries))
       :mode (some-> (:mode opts) name)})))

(defn- normalize-closure-defines
  "Normalizes :closure-defines keys to the munged names goog-define vars are
  looked up by."
  [defines]
  (into {}
    (map (fn [[k v]]
           [(if (or (symbol? k) (keyword? k))
              (str (comp/munge (symbol (namespace k) (name k))))
              k)
            v]))
    defines))

(defn- ns-api
  "The parts of a namespace's analysis its dependents' compiled output
  depends on: its vars (cljs.test's run-tests lists a namespace's tests) and
  arities of fns invoked directly under :static-fns."
  [ns-analysis]
  (into {}
    (map (fn [[sym v]]
           [sym (-> (select-keys v [:fn-var :variadic? :max-fixed-arity :dynamic
                                    :protocol-symbol :protocol])
                    ;; arities, param names differ between compiles (gensyms)
                    (assoc :arities (map count (:method-params v))))]))
    (:defs ns-analysis)))

(defn- api [compiler-env ns]
  (ns-api (get-in @compiler-env [::ana/namespaces ns])))

(defn- cached-analysis
  "A namespace's analysis from the analysis cache of its last compile, nil
  without one."
  [source-file {:keys [output-dir]}]
  (let [f (ana/cache-file source-file (ana/parse-ns source-file) output-dir)]
    ;; cljs.core's can be a resource of the compiler's
    (when (and (instance? File f) (.exists ^File f))
      (case (util/ext f)
        "edn"  (edn/read-string (slurp f))
        "json" (let [{:keys [reader read]} @ana/transit]
                 (with-open [in (io/input-stream f)]
                   (read (reader in :json ana/transit-read-opts))))))))

(def ^:private ^:dynamic *api-changed*
  "Namespaces of a build recompiled with a different api, an atom: the
  namespaces requiring them are recompiled too, their output may depend on
  it. Like cljs.closure's :recompile-dependents, without recompiling the
  dependents of every namespace recompiled."
  nil)

(defn- missing-preloads?
  "Whether the output of namespace ns lacks the imports of the :preloads (and
  the REPL runtime) it needs as a main namespace, compiled before it was one,
  i.e. before a page loaded it."
  [ns ^File dest opts]
  (boolean
    (when-let [preloads (and (.exists dest) (seq (comp/esm-preloads ns opts)))]
      (let [out (slurp dest)]
        (not-every? #(string/includes? out (str "import \"" (comp/esm-ns-path ns %) "\";")) preloads)))))

(def ^:private core-exts
  "The output files of cljs.core, its compiled module's last: compile-ns
  checks that one."
  [".js.map" ".d.ts" ".cljs.cache.json" ".js"])

(defn- core-output ^File [opts ext]
  (let [js (.getPath (output-file 'cljs.core opts))]
    (io/file (str (subs js 0 (- (count js) 3)) ext))))

(defn- core-cache-dir
  "The per-user cache's entry of cljs.core's output compiled with opts:
  keyed by the compiler (a hash of its sources when run from them),
  cljs.core's source (its path and content), and the options its output
  depends on."
  ^File [^File source-file opts]
  (io/file ((requiring-resolve 'cljf.main/cache-dir) "cljs-core")
    ((requiring-resolve 'cljf.main/sha1-hex)
     (pr-str [(util/clojurescript-version)
              (.getCanonicalPath source-file)
              ((requiring-resolve 'cljf.main/file-hash) source-file)
              (into (sorted-map)
                (-> (ana/build-affecting-options opts)
                    (merge (select-keys opts [:source-map :source-map-path :source-map-pretty-print
                                              :source-map-timestamp :esm-dts :emit-constants]))
                    (update :closure-defines #(into (sorted-map) %))))]))))

(defn- rewrite-map-file
  "Source map JSON map-json with the module path old (its \"file\") replaced
  by new, written as the map writes it: compact maps escape slashes, pretty
  printed ones (:source-map-pretty-print) don't. nil if map-json doesn't
  have it."
  [^String map-json old new]
  (some (fn [escape?]
          (let [from (json/write-str old :escape-slash escape?)]
            (when (string/includes? map-json from)
              (string/replace map-json from (json/write-str new :escape-slash escape?)))))
    [true false]))

(defn- restore-core!
  "When the output directory lacks cljs.core's output (a cold build), copies
  it from the per-user cache, compiled by an earlier build with the same
  compiler, cljs.core and options: the build then reads it as its own up to
  date output (the analysis cache round trips exactly). Returns whether it
  did."
  [{:keys [source-file]} opts]
  (let [dir  (core-cache-dir source-file opts)
        dest (output-file 'cljs.core opts)]
    (when (and (.exists (io/file dir ".complete")) (not (.exists dest)))
      (let [old   (slurp (io/file dir "file"))
            mtime (.lastModified ^File source-file)
            ;; the map names its module by absolute path; cljs.core is
            ;; compiled instead if it can't be renamed
            map   (when (some #{".js.map"} core-exts)
                    (or (rewrite-map-file (slurp (io/file dir "core.js.map")) old (comp/url-path dest))
                        ::stale))]
        (when-not (= ::stale map)
          ((requiring-resolve 'cljf.main/hold!) dir)
          (util/mkdirs dest)
          (doseq [ext core-exts
                  :let [from (io/file dir (str "core" ext))
                        to   (core-output opts ext)]]
            (if (= ext ".js.map")
              (spit to map)
              (io/copy from to))
            ;; as compile-file leaves them
            (when (#{".js" ".cljs.cache.json"} ext)
              (.setLastModified to mtime)))
          true)))))

(defn- store-core!
  "Copies cljs.core's output this build compiled to the per-user cache,
  unless it has it."
  [{:keys [source-file]} opts]
  (let [dir (core-cache-dir source-file opts)]
    (when (and (not (.exists (io/file dir ".complete")))
               (every? #(.exists (core-output opts %)) core-exts))
      (let [tmp (io/file (str dir ".tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime)))]
        (try
          (.mkdirs tmp)
          (doseq [ext core-exts]
            (io/copy (core-output opts ext) (io/file tmp (str "core" ext))))
          (spit (io/file tmp "file") (comp/url-path (output-file 'cljs.core opts)))
          (spit (io/file tmp ".complete") "")
          (java.nio.file.Files/move (.toPath tmp) (.toPath dir)
            (into-array [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
          ((requiring-resolve 'cljf.main/prune!) (.getParentFile dir))
          ;; another build stored it meanwhile, or the cache isn't writable
          (catch Exception _
            ((requiring-resolve 'cljf.main/delete-tree!) tmp)))))))

(defn- compile-ns
  "Compiles a namespace if its output isn't up to date, or when a namespace
  it requires was recompiled with a different api. Up to date namespaces
  aren't analyzed by compile-file: reads their analysis (from the analysis
  cache), or each of their dependents, compiled in parallel, would analyze
  them concurrently."
  [{:keys [ns source-file requires]} opts]
  (let [dest   (output-file ns opts)
        force? (or (and *api-changed* (some @*api-changed* (map symbol (remove string? requires))))
                   (missing-preloads? ns dest opts))
        opts   (cond-> opts force? (assoc :force true))
        stale? (and *api-changed*
                    (or (:force opts) (comp/requires-compilation? source-file dest opts)))
        before (when stale? (ns-api (cached-analysis source-file opts)))
        ret    (comp/compile-file source-file dest opts)]
    (when-not (get-in @env/*compiler* [::ana/namespaces ns :defs])
      (if (= 'cljs.core ns)
        (comp/with-core-cljs opts (fn []))
        (ana/analyze-file source-file opts)))
    (when (and stale? (not= before (api env/*compiler* ns)))
      (swap! *api-changed* conj ns))
    ret))

(definterface ICompileTask
  (^long taskIndex []))

(defn- task-index ^long [^ICompileTask t]
  (.taskIndex t))

(defn- compile-parallel
  "Compiles inputs (in dependency order) with a pool of threads, a namespace
  is compiled once all its dependencies have been. A namespace is only
  submitted to the pool once its dependencies are compiled: threads never
  wait for each other, every thread compiles while there's a namespace
  ready. Ready namespaces are compiled in dependency order (earliest
  first), as they were started before: macros may read the analysis of
  namespaces they don't require (i.e. sci's copy-var, of cljs.analyzer.api).
  Their reads through cljs.analyzer.api wait for namespaces earlier in the
  order (ana/*await-ns*), as a sequential build compiled them already, with
  another pool thread compiling meanwhile. After a failure, the namespaces
  not requiring the failed one are compiled still; throws the first failure
  once they have been."
  [inputs opts]
  (let [threads    (or (:parallel-build-threads opts)
                       (.availableProcessors (Runtime/getRuntime)))
        ^java.util.concurrent.ThreadPoolExecutor pool
        (java.util.concurrent.ThreadPoolExecutor. (int threads) (int threads) 60
          java.util.concurrent.TimeUnit/SECONDS
          (java.util.concurrent.PriorityBlockingQueue.))
        by-ns      (into {} (map (juxt :ns identity)) inputs)
        ;; the dependencies earlier in the order, like waiting for the
        ;; namespaces submitted before (a cycle doesn't wait forever)
        index      (into {} (map-indexed (fn [i {:keys [ns]}] [ns i])) inputs)
        deps       (into {}
                     (map (fn [{:keys [ns requires]}]
                            [ns (into #{} (comp (remove string?) (map symbol)
                                            (filter #(when-let [i (index %)] (< i (index ns)))))
                                  requires)]))
                     inputs)
        dependents (reduce-kv (fn [m ns ds] (reduce #(update %1 %2 (fnil conj []) ns) m ds)) {} deps)
        remaining  (into {} (map (fn [[ns ds]] [ns (java.util.concurrent.atomic.AtomicInteger. (count ds))])) deps)
        pending    (java.util.concurrent.atomic.AtomicInteger. (count inputs))
        done       (java.util.concurrent.CountDownLatch. 1)
        failure    (atom nil)
        ;; counted down once each namespace is compiled (or failed)
        compiled   (into {} (map (fn [{:keys [ns]}] [ns (java.util.concurrent.CountDownLatch. 1)])) inputs)
        await-ns   (fn [target]
                     (let [ti (index target)
                           ci (index ana/*cljs-ns*)
                           ^java.util.concurrent.CountDownLatch l (compiled target)]
                       ;; only namespaces before the current one, which a
                       ;; sequential build compiled already: never a cycle.
                       ;; Not while holding the analyzer's load lock (a macro
                       ;; namespace's top level resolving while it loads):
                       ;; the awaited namespace's compile may need the lock
                       (when (and ti ci (< (long ti) (long ci)) (pos? (.getCount l))
                                  (not (Thread/holdsLock ana/load-mutex)))
                         ;; another thread compiles meanwhile, the waiting one
                         ;; holds a pool thread
                         (locking pool
                           (.setMaximumPoolSize pool (inc (.getMaximumPoolSize pool)))
                           (.setCorePoolSize pool (inc (.getCorePoolSize pool))))
                         (try
                           (.await l)
                           (finally
                             (locking pool
                               (.setCorePoolSize pool (dec (.getCorePoolSize pool)))
                               (.setMaximumPoolSize pool (dec (.getMaximumPoolSize pool)))))))))
        finish!    (fn [] (when (zero? (.decrementAndGet pending)) (.countDown done)))
        ;; the namespaces requiring a failed one, transitively, are never
        ;; ready: counted as finished, their waiters (await-ns) let go
        skipped    (java.util.concurrent.ConcurrentHashMap/newKeySet)
        skip!      (fn skip! [ns]
                     (doseq [d (dependents ns)]
                       (when (.add ^java.util.Set skipped d)
                         (.countDown ^java.util.concurrent.CountDownLatch (compiled d))
                         (skip! d)
                         (finish!))))
        submit     (fn submit [ns]
                     (let [i    (long (index ns))
                           task (bound-fn []
                                  (try
                                    (try
                                      (compile-ns (by-ns ns) opts)
                                      (finally
                                        (.countDown ^java.util.concurrent.CountDownLatch (compiled ns))))
                                    (doseq [d (dependents ns)]
                                      (when (zero? (.decrementAndGet ^java.util.concurrent.atomic.AtomicInteger (remaining d)))
                                        (submit d)))
                                    (catch Throwable e
                                      (compare-and-set! failure nil e)
                                      (skip! ns)))
                                  (finish!))]
                       (.execute pool
                         (reify
                           Runnable
                           (run [_] (task))
                           ICompileTask
                           (taskIndex [_] i)
                           Comparable
                           (compareTo [_ other]
                             (Long/compare i (long (task-index other))))))))]
    (try
      (if (seq inputs)
        (do
          ;; the tasks' bound-fns capture it
          (binding [ana/*await-ns* await-ns]
            (doseq [{:keys [ns]} inputs
                    :when (empty? (deps ns))]
              (submit ns)))
          (.await done))
        nil)
      (when-let [e @failure]
        (throw e))
      (finally
        ;; after a failure, the namespaces not requiring the failed one are
        ;; compiled still (i.e. recompiled for a changed api, *api-changed*
        ;; doesn't outlive the build), the ones requiring it are skipped,
        ;; and all of them finish before the build returns, i.e. before the
        ;; watcher's next build
        (doseq [[_ ^java.util.concurrent.CountDownLatch l] compiled] (.countDown l))
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/HOURS)))))

;; Loading a macro namespace (Clojure) holds the analyzer's load-mutex, the
;; namespaces using it wait meanwhile.
(def ^:private ^:dynamic *macro-dir*
  "Under :aot-macros, the per-user cache entry of the macro namespaces'
  classes (or the directory it's being written in): what they are depends
  only on its key, the Clojure sources and classpath."
  nil)

(defn- macro-order-file
  "The file of the order the build's macro namespaces loaded in, in the
  macro classes' cache entry, per build configuration: the namespaces a
  build requires depend on its mains, preloads and profile."
  ^File [opts]
  (when-let [dir *macro-dir*]
    (io/file dir (str "macro-order-"
                      ((requiring-resolve 'cljf.main/sha1-hex)
                       (pr-str [(vec (mains opts)) (:mode opts) (:test-runner opts) (:js-entries opts)]))
                      ".edn"))))

(defn- previous-macro-order
  "The macro namespaces a previous build of the same configuration with the
  same Clojure sources loaded, in its order: a build's own dependency order,
  valid for these sources. nil without one."
  [opts]
  (when-let [f (macro-order-file opts)]
    (when (.exists f)
      (try
        (:nses (edn/read-string (slurp f)))
        (catch Exception _ nil)))))

(defn- store-macro-order! [opts nses]
  (when-let [f (macro-order-file opts)]
    (try
      (let [content (pr-str {:nses (vec nses)})]
        (when-not (and (.exists f) (= content (slurp f)))
          (let [tmp (io/file (str (.getPath f) ".tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime)))]
            (spit tmp content)
            (java.nio.file.Files/move (.toPath tmp) (.toPath f)
              (into-array [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                           java.nio.file.StandardCopyOption/ATOMIC_MOVE])))))
      ;; an optimization
      (catch Exception _))))

(defn- build-macro-order
  "The macro namespaces the inputs require, in dependency order."
  [inputs]
  (into []
    (comp (mapcat (fn [{:keys [ast]}]
                    (concat (vals (:require-macros ast)) (vals (:use-macros ast)))))
          (distinct))
    inputs))

(defn- preload-macro-namespaces
  "Under :parallel-build, starts loading macro namespaces in a thread, one at
  a time: the namespaces compiled after cljs.core would otherwise wait for
  each other's first loads. Loads head first, the order a previous build
  with the same Clojure sources loaded (so while this one's namespaces are
  found), then the namespaces given to the returned fn (the build's own, in
  dependency order, those loaded already are skipped), nil ending the
  queue. Macro namespaces' load order matters (sci.impl.copy-vars resolves
  cljs.analyzer.api at load time), so only a build's order is used. Load
  failures are left to the namespaces requiring them. Returns [add! future]."
  [head]
  (let [queue (LinkedBlockingQueue.)
        end   (Object.)
        pool  (Executors/newSingleThreadExecutor)]
    (doseq [ns head] (.put queue ns))
    (try
      [(fn [nses]
         (if nses
           (doseq [ns nses] (.put queue ns))
           (.put queue end)))
       (.submit pool
         ^Callable
         (bound-fn []
           ;; first what reading or compiling cljs.core needs, which the
           ;; build waits for: its macros, and cljs.spec.alpha's for its
           ;; specs (register-specs). The analyzer's lock isn't fair, so
           ;; cljs.core waiting for it would wait for many of the others
           (try
             (ana/load-core)
             (ana/require-macros-ns 'cljs.spec.alpha nil)
             (catch Throwable _))
           (loop []
             (let [ns (.take queue)]
               (when-not (identical? end ns)
                 (try
                   (ana/require-macros-ns ns nil)
                   (catch Throwable _))
                 (recur))))))]
      (finally
        (.shutdown pool)))))

(defn- ensure-analyzed!
  "Namespaces up to date aren't recompiled, nor analyzed. Reads their
  analysis (from the analysis cache) so that the compiler environment
  describes the whole build, for build hooks and the watcher."
  [compiler-env inputs opts]
  (doseq [{:keys [ns source-file]} inputs]
    (when-not (get-in @compiler-env [::ana/namespaces ns :name])
      (ana/analyze-file source-file opts))))

(defn- run-hooks!
  "Calls the :build-hooks, [fn-sym & args], with the build and args. The
  build is a map of :compiler-env (a value), :namespaces (in dependency
  order), :mode, :options, :trigger and :changed-namespaces. trigger is
  :build, :watch (a recompile) or :repl-eval (a form evaluated at the REPL
  changed the analysis of the changed namespaces)."
  ([compiler-env inputs opts]
   (run-hooks! compiler-env inputs opts :build (map :ns inputs)))
  ([compiler-env inputs opts trigger changed]
   (doseq [[f & args] (:build-hooks opts)]
     (apply (requiring-resolve f)
       {:compiler-env       @compiler-env
        :namespaces         (mapv :ns inputs)
        :mode               (:mode opts)
        :options            opts
        :trigger            trigger
        :changed-namespaces (vec changed)}
       args))))

(defn- cljs-warnings
  "The analyzer's warnings with the :warnings option applied, like
  cljs.closure: a map of warning types to true, false, :warning, :error or
  :off, or one of them for the undeclared var and namespace warnings.
  :warnings-as-errors true makes every enabled warning an error, like
  shadow-cljs. Closure namespace munging doesn't happen under ES module
  output."
  [{:keys [warnings warnings-as-errors] :or {warnings true}}]
  (-> ana/*cljs-warnings*
      (merge (if (map? warnings)
               warnings
               (zipmap [:unprovided :undeclared-var :undeclared-ns :undeclared-ns-form]
                 (repeat (if (keyword? warnings) warnings (boolean warnings))))))
      (assoc :munged-namespace false)
      (cond-> warnings-as-errors
        (as-> ws (reduce-kv (fn [m k v] (assoc m k (if (#{true :warning} v) :error v))) ws ws)))))

(defn compiler-bindings
  "The dynamic bindings compiling with opts, as cljs.closure binds them:
  :warnings, :elide-asserts and :load-tests."
  [opts]
  {#'ana/*cljs-warnings* (cljs-warnings opts)
   #'*assert*            (not= true (:elide-asserts opts))
   #'ana/*load-tests*    (not= false (:load-tests opts))})

(defn- release-defines
  "A :release build is a production build: goog.DEBUG is false unless
  :closure-defines sets it, like shadow-cljs' release mode."
  [{:keys [mode] :as opts}]
  (cond-> opts
    (and (= :release mode) (not (contains? (:closure-defines opts) "goog.DEBUG")))
    (assoc-in [:closure-defines "goog.DEBUG"] false)))

(defn- prepare-opts [opts]
  (-> (merge default-opts opts)
      (update :closure-defines normalize-closure-defines)
      release-defines
      ;; as cljs.closure: keyword and symbol constants are hoisted into a
      ;; constants module (see write-constants!)
      (cond-> (true? (:optimize-constants opts)) (assoc :emit-constants true))
      ;; the generated test runner is a main namespace
      (cond-> (:test-runner opts)
        (update :main #(vec (distinct (conj (if (coll? %) (vec %) (if % [%] [])) (-> opts :test-runner :ns))))))))

(declare watch-dirs source-files)

(defn- write-constants!
  "With :emit-constants (:optimize-constants), writes the constants module the
  compiled namespaces import their keyword and symbol constants from:
  cljs/core/constants.js, an export per constant."
  [compiler-env opts]
  (when (:emit-constants opts)
    (spit-if-changed (io/file (:output-dir opts) (util/ns->relpath ana/constants-ns-sym :js))
      (with-out-str
        (comp/emit-esm-constants-table (::ana/constant-table @compiler-env))))))

(defn- test-namespaces
  "The ClojureScript namespaces in the source directories matching regexp,
  sorted."
  [regexp opts]
  (->> (source-files (watch-dirs opts))
       keys
       (filter #(re-find #"\.clj[sc]$" %))
       (keep #(try (:ns (parse-ns (io/file %))) (catch Throwable _ nil)))
       (filter #(re-find (re-pattern regexp) (str %)))
       distinct
       sort))

(defn- test-runner-source
  "The file the :test-runner namespace is generated into, nil without one."
  ^File [{{:keys [ns]} :test-runner :keys [output-dir]}]
  (when ns
    (io/file output-dir "cljs-esm-gen" (util/ns->relpath ns :cljs))))

(defn- slurp-if-exists [^File f]
  (when (.exists f) (slurp f)))

(defn- generate-test-runner
  "Writes the :test-runner namespace, requiring the test namespaces matching
  :ns-regexp (default -test$) and calling (runner run-tests) with a fn running
  them, given a cljs.test env. Returns {ns File}."
  [{{:keys [ns ns-regexp runner] :or {ns-regexp "-test$"}} :test-runner :as opts}]
  (let [nses (test-namespaces ns-regexp opts)
        file (test-runner-source opts)
        env  (gensym "env")]
    (util/mkdirs file)
    (spit-if-changed file
      (with-out-str
        (prn (list* 'ns ns
               "Generated by cljf.esm (:test-runner), runs the test namespaces."
               [(list* :require ['cljs.test] [(symbol (namespace runner))] (map vector nses))]))
        (prn (list runner
               (list 'fn [env]
                 (list* 'cljs.test/run-tests env (map #(list 'quote %) nses)))))))
    {ns file}))

(defn- check-classpath!
  "Fails when another ClojureScript compiler is on the classpath, usually
  org.clojure/clojurescript brought in by a library: the classpath order
  decides whose namespaces load, and its jar's precompiled classes are loaded
  instead of these sources."
  []
  (let [loader (.getContextClassLoader (Thread/currentThread))
        dupes  (keep (fn [path]
                       (let [urls (distinct (map str (enumeration-seq (.getResources loader path))))]
                         (when (next urls)
                           [path urls])))
                 ["cljs/analyzer.cljc" "cljs/core.cljs"])]
    (when (seq dupes)
      (throw (ex-info (str "More than one ClojureScript compiler is on the classpath:\n"
                        (apply str (for [[path urls] dupes]
                                     (apply str "  " path "\n" (map #(str "    " % "\n") urls))))
                        "Exclude org.clojure/clojurescript from the library that brings it in"
                        " (clojure -X:deps tree shows which one) with :exclusions"
                        " [org.clojure/clojurescript], or from the whole dependency tree with"
                        " :override-deps, replacing it with the empty project in this"
                        " compiler's no-clojurescript directory.")
               {:resources (into {} dupes)})))))

(declare build*)

(defn- classpath-entries []
  (string/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator)))

(defn- inside?
  "Whether canonical path p is directory dir (canonical) or inside it."
  [^String dir ^String p]
  (or (= dir p) (string/starts-with? p (str dir File/separator))))

(defn- macro-cache-key
  "Identifies the Clojure code macro namespaces load: the classpath, the
  Clojure and Java versions, whether asserts are compiled (:elide-asserts
  binds *assert*, which Clojure compiles the macro namespaces with), the
  path, modification time and size of the jars, and the path and content
  of every Clojure source in the classpath directories (except the output
  directory, which has copies of jars' sources): a fresh checkout of the
  same sources (i.e. CI's) has the same key."
  [output-dir asserts?]
  (let [output     (.getCanonicalPath (io/file output-dir))
        md         (java.security.MessageDigest/getInstance "SHA-1")
        add!       (fn [& xs] (.update md (.getBytes (str (string/join "\t" xs) "\n") "UTF-8")))
        entry-id   (requiring-resolve 'cljf.main/classpath-entry-id)
        file-hash  (requiring-resolve 'cljf.main/file-hash)]
    (add! (clojure-version) (System/getProperty "java.version") (util/clojurescript-version)
      (str "assert " (boolean asserts?)))
    (doseq [entry (classpath-entries)
            :let [f (io/file entry)]]
      (apply add! (entry-id entry f))
      ;; a directory inside the output directory has copies only; one
      ;; containing it (i.e. the project's root) is hashed without them
      (when (and (.isDirectory f) (not (inside? output (.getCanonicalPath f))))
        (doseq [^File src (sort (file-seq f))
                :when (and (.isFile src) (re-find #"\.clj[c]?$" (.getName src))
                           (not (inside? output (.getCanonicalPath src))))]
          (add! (.getPath src) (file-hash src)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- with-aot-macros
  "Under :aot-macros, calls f loading Clojure namespaces (the macro
  namespaces) from classes compiled by an earlier build, or compiling them
  to classes (*compile-files*) as they load. Clojure loads a namespace's
  class instead of its source when it's newer. The classes are kept in the
  per-user cache (macro-classes/, see cljf.main) under a key of all Clojure
  sources (macro-cache-key), shared by builds into other output directories;
  any change of them compiles them again. Classes being compiled go to a
  directory of the build's own, renamed to the key's once the build
  succeeds."
  [{:keys [aot-macros output-dir elide-asserts]} f]
  (if-not aot-macros
    (f)
    (let [root   ((requiring-resolve 'cljf.main/cache-dir) "macro-classes")
          key    (macro-cache-key output-dir (not= true elide-asserts))
          dir    (io/file root key)
          done   (io/file dir ".complete")
          ready? (.exists done)
          tmp    (when-not ready?
                   (io/file root (str key ".tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime))))
          thread (Thread/currentThread)
          loader (.getContextClassLoader thread)
          dcl    (or (when ready?
                       ((requiring-resolve 'cljf.main/cache-loader) dir (clojure.lang.RT/baseLoader)))
                     (doto (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))
                       (.addURL (.toURL (.toURI (or tmp dir))))))]
      (if ready?
        ;; used while this process runs (a watch loads more of its classes
        ;; later), see cljf.main/prune!
        ((requiring-resolve 'cljf.main/hold!) dir)
        (.mkdirs tmp))
      ;; threads started by the build inherit it
      (.setContextClassLoader thread dcl)
      (try
        (let [;; and the loader require uses while one is bound, i.e. when
              ;; building from a REPL or a script (clojure -i)
              f   #(binding [*macro-dir* (or tmp dir)]
                     (if (.isBound clojure.lang.Compiler/LOADER)
                       (with-bindings {clojure.lang.Compiler/LOADER dcl} (f))
                       (f)))
              ret (if ready?
                    (f)
                    (binding [*compile-files* true
                              *compile-path*  (.getPath tmp)]
                      (f)))]
          (when tmp
            ((requiring-resolve 'cljf.main/write-shadowed!) tmp (clojure.lang.RT/baseLoader))
            (spit (io/file tmp ".complete") key)
            (try
              (java.nio.file.Files/move (.toPath tmp) (.toPath dir)
                (into-array [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
              ((requiring-resolve 'cljf.main/hold!) dir)
              ;; another build stored them meanwhile
              (catch java.io.IOException _)))
          ret)
        (finally
          (.setContextClassLoader thread loader)
          ;; a failed build's, or one another build stored meanwhile
          (when (and tmp (.exists tmp))
            ((requiring-resolve 'cljf.main/delete-tree!) tmp))
          ((requiring-resolve 'cljf.main/prune!) root))))))

(defn build
  "Compiles the namespaces in :main (a symbol or a collection of symbols) and
  their dependencies to ES modules in :output-dir. Returns the compiled
  namespaces in dependency order.

  :test-runner {:ns my.test-runner :ns-regexp \"-test$\" :runner my.test/start}
  generates (and compiles) namespace my.test-runner, requiring the test
  namespaces of the source directories, calling (my.test/start run-tests)."
  ([opts]
   (check-classpath!)
   (build opts (env/default-compiler-env (merge default-opts opts))))
  ([opts compiler-env]
   (let [opts (prepare-opts opts)]
     (with-aot-macros opts #(build* opts compiler-env)))))

(defn- build* [opts compiler-env]
  (with-bindings (assoc (compiler-bindings opts)
                   #'*generated-sources* (if (:test-runner opts)
                                           (generate-test-runner opts)
                                           *generated-sources*)
                   #'*api-changed* (atom #{})
                   ;; the classpath doesn't change during a build
                   #'util/*ns-source-cache* (java.util.concurrent.ConcurrentHashMap.)
                   #'ana/*parse-ns-cache* (java.util.concurrent.ConcurrentHashMap.))
    (env/with-compiler-env compiler-env
      (swap! compiler-env assoc :options opts)
      (let [[preload-add! preload] (when (:parallel-build opts)
                                   (preload-macro-namespaces (previous-macro-order opts)))]
        (try
          (let [start  (System/nanoTime)
                ;; With cljs.core's output from the per-user cache, reading its
                ;; analysis doesn't depend on the build's other namespaces:
                ;; under :parallel-build it's read while they're found. A
                ;; compile of it stays after (see #60: compiling it alongside
                ;; slows both)
                core-input (when (and (:parallel-build opts) (:core-cache opts true))
                             (first (find-sources ['cljs.core] (dissoc opts :parallel-build))))
                core-early (when (and core-input (instance? File (:source-file core-input))
                                      (restore-core! core-input opts))
                             (let [pool (Executors/newSingleThreadExecutor)]
                               (try
                                 (.submit pool ^Callable (bound-fn [] (compile-ns core-input opts)))
                                 (finally
                                   (.shutdown pool)))))
                inputs (try
                         (find-sources (mains opts) opts)
                         (catch Throwable e
                           (some-> ^Future core-early (.cancel true))
                           (throw e)))]
            ;; lets the compiler know which namespaces are part of the build
            ;; before they're analyzed
            (swap! compiler-env assoc ::namespaces (into #{} (map :ns) inputs))
            (swap! compiler-env update ::ana/data-readers merge (ana/load-data-readers))
            (write-build-info opts)
            (let [order (build-macro-order inputs)]
              (when preload-add!
                (preload-add! order)
                (preload-add! nil))
              (store-macro-order! opts order)
              ;; cljs.core is analyzed first, the compiler expects its analysis
              ;; to be available when compiling anything else
              (if core-early
                (try
                  (.get ^Future core-early)
                  (catch ExecutionException e
                    (throw (or (.getCause e) e))))
                (let [core   (first (filter #(= 'cljs.core (:ns %)) inputs))
                      cache? (and (:core-cache opts true) (instance? File (:source-file core)))]
                  (when cache? (restore-core! core opts))
                  (compile-ns core opts)
                  (when cache? (store-core! core opts))))
              (let [others (remove #(= 'cljs.core (:ns %)) inputs)]
                (if (:parallel-build opts)
                  (compile-parallel others opts)
                  (doseq [input others]
                    (compile-ns input opts))))
              (some-> ^Future preload (.get)))
            (check-js-entries compiler-env opts)
            (write-constants! compiler-env opts)
            (install-goog-shims opts)
            (install-goog-libs compiler-env opts)
            (write-package-json opts)
            (when (seq (:build-hooks opts))
              (ensure-analyzed! compiler-env inputs opts)
              (run-hooks! compiler-env inputs opts))
            (when (:verbose opts)
              (util/debug-prn
                (format "Compiled %d namespaces in %.1fs" (count inputs)
                  (/ (- (System/nanoTime) start) 1e9))))
            (map :ns inputs))
          ;; whatever fails before its last namespaces are added, its thread
          ;; doesn't wait for more (ending it again after is harmless)
          (finally
            (when preload-add! (preload-add! nil))))))))

(def ^:private stdout
  "The process' standard output, events go there from any thread, i.e. the
  REPL's, where *out* is a REPL session's."
  (delay (.getRawRoot #'*out*)))

(def ^:private stderr
  (delay (.getRawRoot #'*err*)))

(defn event!
  "Prints a build event as a JSON line, consumed by the Vite plugin."
  [type m]
  (let [^java.io.Writer out @stdout]
    (locking out
      (.write out (str "[cljf.esm] "
                    ;; i.e. a File in ex-data: printed rather than failing
                    (json/write-str (assoc m :type type)
                      :value-fn (fn [_ v]
                                  (if (or (nil? v) (string? v) (number? v) (boolean? v)
                                          (keyword? v) (symbol? v) (map? v) (coll? v))
                                    v
                                    (str v))))
                    "\n"))
      (.flush out))))

(defn- collecting-warnings
  "The warning handlers, collecting the warnings printed into the atom
  warnings as {:message :file :line :column :output}, :output is the
  namespace's output file, for the watcher's compiled events."
  [warnings opts]
  (conj ana/*cljs-warning-handlers*
    (fn [warning-type env extra]
      (when (#{true :warning} (warning-type ana/*cljs-warnings*))
        (when-let [s (ana/error-message warning-type extra)]
          (swap! warnings conj {:message s
                                :file    (some-> ana/*cljs-file* str)
                                :line    (:line env)
                                :column  (:column env)
                                :output  (when-let [ns ana/*cljs-ns*] (.getPath (output-file ns opts)))}))))))

(defn- source-path
  "The path of an error's source file: a classpath resource's (i.e. a macro
  namespace's CompilerException names it relative to the classpath) is
  resolved to its file."
  [file]
  (let [path (str file)]
    (or (when-not (.isAbsolute (io/file path))
          (when-let [res (io/resource path)]
            (when (= "file" (.getProtocol ^URL res))
              (.getPath (io/file res)))))
        path)))

(defn- error-data
  "The event of a compile error: the innermost cause's message, naming the
  macro whose expansion failed, and the file, line and column of the first
  exception of the cause chain with a line (the analyzer's, or Clojure's
  CompilerException in a macro namespace, which can be another file than
  the one compiled), else the file of the first one with a file
  (compile-file's). Parallel builds wrap them in ExecutionExceptions."
  [^Throwable e]
  (let [chain  (take-while some? (iterate ex-cause e))
        datas  (keep ex-data chain)
        pick   (fn [ds & ks] (some (fn [d] (some #(get d %) ks)) ds))
        ;; from the exception locating the error on: the exceptions around
        ;; it are about the file compiled
        inner  (or (seq (drop-while #(not (pick [%] :line :clojure.error/line)) datas)) datas)
        loc    (take 1 inner)
        file   (or (pick loc :file :clojure.error/source)
                   (pick datas :file :clojure.error/source))
        symbol (pick inner :clojure.error/symbol)
        cause  (last chain)]
    {:message (cond-> (str (or (.getMessage ^Throwable cause) (.getName (class cause))))
                symbol (str " (expanding " symbol ")"))
     :file    (some-> file source-path)
     :line    (pick loc :line :clojure.error/line)
     :column  (pick loc :column :clojure.error/column)}))

(defn- source-files
  "All ClojureScript and Clojure (macro) files in dirs with their
  modification time."
  [dirs]
  (into {}
    (comp
      (mapcat #(file-seq (io/file %)))
      (filter #(.isFile ^File %))
      (filter #(re-find #"\.(cljs|cljc|clj)$" (.getName ^File %)))
      (map (fn [^File f] [(.getCanonicalPath f) (.lastModified f)])))
    dirs))

(defn- dependents
  "Namespaces of the build directly requiring any of nses."
  [compiler-env nses]
  (let [nses (set nses)]
    (->> (vals (::ana/namespaces @compiler-env))
         (filter (fn [{:keys [requires uses]}]
                   (some nses (concat (vals requires) (vals uses)))))
         (map :name))))

(defn- macro-dependents
  "Namespaces of the build using macros of any of macro-nses."
  [compiler-env macro-nses]
  (let [macro-nses (set macro-nses)]
    (->> (vals (::ana/namespaces @compiler-env))
         (filter (fn [{:keys [require-macros use-macros]}]
                   (some macro-nses (concat (vals require-macros) (vals use-macros)))))
         (map :name))))

(defn- file-ns
  "The namespace declared by a Clojure or ClojureScript file."
  [^File f]
  (try
    (:ns (ana/parse-ns f))
    (catch Throwable _ nil)))

(defn- recompile!
  "Recompiles namespaces nses (and their dependents if their API changed),
  returns the namespaces compiled."
  [compiler-env inputs nses opts]
  (let [by-ns (into {} (map (juxt :ns identity)) inputs)]
    (loop [queue (vec nses) done #{}]
      (if-let [ns (first queue)]
        (if (or (contains? done ns) (nil? (by-ns ns)))
          (recur (subvec queue 1) done)
          (let [before (api compiler-env ns)]
            (compile-ns (by-ns ns) (assoc opts :force true))
            (let [changed? (not= before (api compiler-env ns))]
              (recur (cond-> (subvec queue 1)
                       changed? (into (dependents compiler-env [ns])))
                (conj done ns)))))
        done))))

(defn- resource-refs
  "Classpath resources namespaces depend on, {path #{ns}}. Macros record
  them as {path last-modified} under :cljf.esm/resource-refs of the
  namespace being compiled, see watch-resource!."
  [compiler-env]
  (reduce-kv
    (fn [m ns {:keys [::resource-refs]}]
      (reduce (fn [m path] (update m path (fnil conj #{}) ns)) m (keys resource-refs)))
    {}
    (::ana/namespaces @compiler-env)))

(defn- resource-mtimes [refs]
  (into {}
    (keep (fn [path]
            (when-let [res (io/resource path)]
              [path (util/last-modified res)])))
    (keys refs)))

(defn watch-resource!
  "For macros: records that the namespace being compiled depends on the
  classpath resource at path, the watcher recompiles the namespace when the
  resource changes."
  [env path]
  (when env/*compiler*
    (let [res (or (io/resource path)
                  (throw (ana/error env (str "Resource not found: " path))))]
      (swap! env/*compiler* assoc-in
        [::ana/namespaces (-> env :ns :name) ::resource-refs path]
        (util/last-modified res)))))

(defn- resource-root
  "The classpath directory resource path is in, nil if it isn't in one."
  [path]
  (when-let [res (io/resource path)]
    (when (= "file" (.getProtocol ^URL res))
      (let [file (.getCanonicalPath (io/file res))]
        (subs file 0 (- (count file) (count path) 1))))))

(defn- watch-dirs
  "Directories with sources to watch, :watch-dirs or the classpath
  directories, without the output directory and the compiler's own sources:
  a changed compiler needs a new watcher, reloading it in place doesn't
  work."
  [{:keys [output-dir] :as opts}]
  (let [output   (.getCanonicalPath (io/file output-dir))
        compiler (set (keep resource-root ["cljs/compiler.cljc" "cljs/core.cljs"]))]
    (->> (or (:watch-dirs opts)
             (string/split (System/getProperty "java.class.path") #":"))
         (map io/file)
         (filter #(.isDirectory ^File %))
         (map #(.getCanonicalPath ^File %))
         (remove #(or (= output %) (string/starts-with? output (str % File/separator))))
         (remove compiler)
         distinct)))

(defn- start-stdin-reader!
  "Under :exit-with-parent, reads the parent's messages from stdin, puts
  the paths of `changed <path>` lines on queue, passes `repl <json>` lines
  to the REPL (cljf.esm.repl), exits on EOF: the parent closes stdin when it
  exits, the watcher has to stop too."
  [^LinkedBlockingQueue queue]
  (doto (Thread. (fn []
                   (with-open [rdr (io/reader System/in)]
                     (doseq [^String line (line-seq rdr)]
                       (cond
                         (string/starts-with? line "changed ")
                         (.put queue (subs line 8))

                         (string/starts-with? line "repl ")
                         (try
                           ((requiring-resolve 'cljf.esm.repl/handle-message!) (subs line 5))
                           (catch Throwable e
                             (binding [*out* *err*]
                               (println "cljf.esm.repl:" (.getMessage e))))))))
                   (System/exit 0)))
    (.setDaemon true)
    (.start)))

(defn- poll-changes
  "Polls dirs for changed source files, returns [changed files']. Removed
  files are changes too: a generated test runner requires what is left."
  [dirs files]
  (Thread/sleep 100)
  (let [files' (source-files dirs)]
    [(concat
       (keep (fn [[path mtime]]
               (when (not= mtime (get files path)) (io/file path)))
         files')
       (keep (fn [path]
               (when-not (contains? files' path) (io/file path)))
         (keys files)))
     files']))

(defn- next-changes
  "Blocks until the parent reports changed files (sources or resources),
  returns them, batching the changes reported within a moment of each
  other."
  [^LinkedBlockingQueue queue]
  (let [first-path (.take queue)]
    (Thread/sleep 50)
    (let [more (java.util.ArrayList.)]
      (.drainTo queue more)
      (map io/file (distinct (cons first-path more))))))

(defn- locking*
  "Calls f holding lock."
  [^ReentrantLock lock f]
  (.lock lock)
  (try
    (f)
    (finally
      (.unlock lock))))

(defn- canonical-path [^File f]
  (.getCanonicalPath f))

(defn- watch-opts
  "watch's options: :esm-hmr unless disabled, and with it :esm-repl unless
  disabled, the namespaces required at the REPL are mains too."
  [opts]
  (let [opts (prepare-opts (merge {:esm-hmr true} opts))]
    (cond-> opts
      (and (:esm-hmr opts) (not (false? (:esm-repl opts))))
      (assoc :esm-repl true ::repl-mains (atom #{})))))

(defn- reuse-inputs
  "The build's inputs after files changed, if finding them again would find
  the same namespaces in the same order: the mains are the same, and every
  changed file is a Clojure (macro) file, or the source of an input whose
  ns form still names it and requires the same namespaces. The changed
  sources' ns infos are parsed again. nil otherwise, i.e. when a file was
  added, removed or renamed: find-sources finds the inputs then."
  [inputs prev-mains mains changed]
  (when (and (seq inputs) (= prev-mains (vec mains)))
    (let [by-ns (into {} (map (juxt :ns identity)) inputs)
          fresh (reduce
                  (fn [m ^File f]
                    (cond
                      (not (.exists f)) (reduced nil)
                      (re-find #"\.clj$" (.getName f)) m
                      :else
                      (let [ns-info (parse-ns f)
                            old     (by-ns (:ns ns-info))]
                        (if (and old
                                 (= (.getCanonicalPath f)
                                    (.getCanonicalPath ^File (:source-file old)))
                                 (= (set (:requires ns-info)) (set (:requires old))))
                          (assoc m (:ns ns-info) (assoc ns-info :source-file (:source-file old)))
                          (reduced nil)))))
                  {}
                  changed)]
      (when fresh
        (mapv #(or (fresh (:ns %)) %) inputs)))))

(defn watch
  "Builds like build, then recompiles namespaces as their sources change.
  With :watch-events :stdin, the parent process (the Vite plugin) watches
  the directories of the watch-dirs event and writes `changed <path>` lines
  to stdin, otherwise polls :watch-dirs (defaults to the classpath
  directories). Enables :esm-hmr, modules accept their own hot updates when
  served by Vite, and :esm-repl, the REPL evaluates forms in the pages
  running the build (cljf.esm.repl), with an nREPL server under :repl. Until
  a build succeeds, each change builds again: a failed build's output misses
  the namespaces it didn't get to."
  [opts]
  (check-classpath!)
  (let [opts         (watch-opts opts)
        compiler-env (env/default-compiler-env opts)
        dirs         (watch-dirs opts)
        queue        (LinkedBlockingQueue.)
        stdin?       (= :stdin (:watch-events opts))
        ;; recompiles and the REPL's analysis both change the compiler env
        lock         (ReentrantLock.)
        built?       (atom false)
        ;; the build's inputs, for build hooks run by the REPL
        inputs-      (atom [])
        ;; the mains the inputs were found for
        inputs-mains- (atom nil)
        ;; the modification times of the source files compiled: a file the
        ;; REPL compiled (load-file) isn't compiled again when the editor's
        ;; save is reported
        compiled-    (atom {})
        ;; the warnings of the output files, {path [warning]}: like the Vite
        ;; plugin, which holds back hot reloads while there are any
        warnings-    (atom {})
        outstanding! (fn [{:keys [files warnings]}]
                       (swap! warnings-
                         #(reduce (fn [m w] (update m (:output w) (fnil conj []) w))
                            (apply dissoc % files) warnings)))
        build!       (fn []
                       (let [start    (System/nanoTime)
                             warnings (atom [])
                             nses     (binding [ana/*cljs-warning-handlers* (collecting-warnings warnings opts)]
                                        (build opts compiler-env))
                             inputs   (env/with-compiler-env compiler-env
                                        (find-sources (mains opts) opts))]
                         ;; the watcher needs the analysis of the whole build
                         (env/with-compiler-env compiler-env
                           (ensure-analyzed! compiler-env inputs opts))
                         (reset! inputs- inputs)
                         (reset! inputs-mains- (vec (mains opts)))
                         (reset! built? true)
                         (reset! warnings- {})
                         (doto {:type       "compiled"
                                :namespaces (count nses)
                                :warnings   @warnings
                                :ms         (long (/ (- (System/nanoTime) start) 1e6))}
                           outstanding!
                           (->> (event! "compiled")))))
        recompile-changes!
                     (fn [changed res-nses]
                       (let [start  (System/nanoTime)
                             warnings (atom [])
                             ;; the test runner requires the test namespaces there are now
                             runner (test-runner-source opts)
                             before (some-> runner slurp-if-exists)
                             gen    (if (:test-runner opts) (generate-test-runner opts) {})
                             runner-changed (when (and runner (not= before (slurp-if-exists runner)))
                                              [(-> opts :test-runner :ns)])
                             inputs (or (when-not (:test-runner opts)
                                          (env/with-compiler-env compiler-env
                                            (reuse-inputs @inputs- @inputs-mains- (mains opts) changed)))
                                        (binding [*generated-sources* gen]
                                          (env/with-compiler-env compiler-env
                                            (find-sources (mains opts) opts))))
                             _      (reset! inputs-mains- (vec (mains opts)))
                             _      (swap! compiler-env assoc ::namespaces (into #{} (map :ns) inputs))
                             _      (reset! inputs- inputs)
                             macros (keep #(when (re-find #"\.clj[c]?$" (.getName ^File %)) (file-ns %)) changed)
                             _      (doseq [ns macros]
                                      (when (find-ns ns)
                                        (require ns :reload)))
                             cljs   (keep #(when (re-find #"\.clj[sc]$" (.getName ^File %)) (file-ns %)) changed)
                             ;; namespaces newly required by a changed namespace
                             fresh  (remove #(get-in @compiler-env [::ana/namespaces % :name]) (map :ns inputs))
                             done   (env/with-compiler-env compiler-env
                                      (with-bindings (assoc (compiler-bindings opts)
                                                       #'ana/*cljs-warning-handlers* (collecting-warnings warnings opts)
                                                       #'*generated-sources* gen)
                                        (recompile! compiler-env inputs
                                          (distinct (concat fresh cljs res-nses runner-changed
                                                      (macro-dependents compiler-env macros)))
                                          opts)))]
                         (check-js-entries compiler-env opts)
                         (write-constants! compiler-env opts)
                         (install-goog-libs compiler-env opts)
                         (env/with-compiler-env compiler-env
                           (run-hooks! compiler-env inputs opts :watch done))
                         (doto {:type       "compiled"
                                :namespaces (count done)
                                :compiled   (sort done)
                                :files      (map #(.getPath (output-file % opts)) (sort done))
                                :warnings   @warnings
                                :ms         (long (/ (- (System/nanoTime) start) 1e6))}
                           outstanding!
                           (->> (event! "compiled")))))
        compile!     (fn
                       ;; Compiles changed files (sources and macros) and
                       ;; namespaces nses (whose resources changed, or the
                       ;; REPL's), returns the compiled event, or the error
                       ;; event. Files compiled already are skipped, unless
                       ;; :force, nothing to compile is a no-op.
                       [{:keys [files nses force]}]
                       (locking* lock
                         (fn []
                           ;; the watcher's output, also when the REPL compiles
                           (binding [*out* @stdout
                                     *err* @stderr]
                             (let [files (remove #(= (.lastModified ^File %) (get @compiled- (canonical-path %)))
                                           files)]
                               (when (or force (seq files) (seq nses))
                                 (swap! compiled- into
                                   (map (fn [^File f] [(canonical-path f) (.lastModified f)]))
                                   files)
                                 (event! "compiling" {})
                                 (try
                                   (if-not @built?
                                     (build!)
                                     ;; lookups of sources and their ns forms cached
                                     ;; for the recompile, not across them: files
                                     ;; may be added or removed in between
                                     (binding [util/*ns-source-cache* (java.util.concurrent.ConcurrentHashMap.)
                                               ana/*parse-ns-cache*  (java.util.concurrent.ConcurrentHashMap.)]
                                       (recompile-changes! files nses)))
                                   (catch Throwable e
                                     (doto (assoc (error-data e) :type "error")
                                       (->> (event! "error")))))))))))]
    (when (:exit-with-parent opts)
      (start-stdin-reader! queue))
    (event! "watch-dirs" {:dirs dirs})
    (locking* lock
      (fn []
        (try
          (build!)
          (catch Throwable e
            (event! "error" (error-data e))))))
    (when (:esm-repl opts)
      ((requiring-resolve 'cljf.esm.repl/start!)
       {:compiler-env compiler-env
        :options      opts
        :lock         lock
        :compile!     compile!
        :inputs       inputs-
        :warnings     warnings-
        :watch-dirs   dirs
        :run-hooks!   (fn [nses]
                        (locking* lock
                          (fn []
                            (binding [*out* @stdout
                                      *err* @stderr]
                              (env/with-compiler-env compiler-env
                                (run-hooks! compiler-env @inputs- opts :repl-eval nses))))))
        :output-path  (fn [ns] (util/ns->relpath ns :js))}))
    (loop [files     (when-not stdin? (source-files dirs))
           resources (resource-mtimes (resource-refs compiler-env))]
      (let [[changed files'] (if stdin?
                               [(next-changes queue) nil]
                               (poll-changes dirs files))
            changed    (filter #(re-find #"\.(cljs|cljc|clj)$" (.getName ^File %)) changed)
            refs       (resource-refs compiler-env)
            resources' (resource-mtimes refs)
            ;; namespaces depending on changed resources
            res-nses   (mapcat (fn [[path mtime]]
                                 (when (and (contains? resources path) (not= mtime (get resources path)))
                                   (get refs path)))
                         resources')]
        (when (or (seq changed) (seq res-nses))
          (compile! {:files changed :nses res-nses}))
        (recur files' resources')))))

(defn- deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(defn load-options
  "Compiler options from command line arguments: @file.edn options files,
  :profile keywords and EDN maps. Profiles are maps under :profiles of the
  files, deep merged over them in order, EDN maps over the result. The
  profile names the build's :mode. Without a profile, default-profile."
  [args default-profile]
  (let [files    (keep #(when (string/starts-with? % "@") (edn/read-string (slurp (subs % 1)))) args)
        maps     (keep #(when (string/starts-with? % "{") (edn/read-string %)) args)
        profiles (or (seq (keep #(when (string/starts-with? % ":") (keyword (subs % 1))) args))
                     [default-profile])
        opts     (apply deep-merge files)]
    (-> (apply deep-merge
          (dissoc opts :profiles)
          (concat (map #(get-in opts [:profiles %]) profiles) maps))
        (assoc :mode (last profiles)))))

(defn -main
  "Usage: clojure -M -m cljf.esm [build|watch] options...

  Options are EDN maps, @file.edn and :profile keywords, see load-options.
  Watch defaults to the :dev profile, build to :release.

    clojure -M -m cljf.esm build '{:main my.app :output-dir \"out\"}'
    clojure -M -m cljf.esm watch @cljs.edn"
  [& args]
  (let [[command args] (if (#{"build" "watch"} (first args))
                         [(first args) (rest args)]
                         ["build" args])]
    (if (= "watch" command)
      (watch (merge {:verbose false} (load-options args :dev)))
      (do
        (build (merge {:verbose true} (load-options args :release)))
        (shutdown-agents)))))
