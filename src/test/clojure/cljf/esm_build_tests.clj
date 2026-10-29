;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm-build-tests
  "cljf.esm builds reusing the output of a previous build."
  (:require [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljf.esm :as esm]
            [cljs.util :as util]
            [cljs.vendor.clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "cljs-esm-build" (make-array FileAttribute 0))))

(defn- write-source!
  "Writes namespace ns' source to dir, modified at time ms. Returns [ns file]."
  [^File dir ns source ms]
  (let [f (io/file dir (str (string/replace (str ns) "." "_") ".cljs"))]
    (spit f source)
    (.setLastModified f ms)
    [ns f]))

(defn- build!
  "Builds namespace main of sources {ns file} into dir/out. Returns the names
  of the source files it compiled."
  [^File dir sources main]
  (let [compiled      (atom #{})
        compile-file* comp/compile-file*]
    (with-redefs [comp/compile-file* (fn [^File src dest opts]
                                       (swap! compiled conj (.getName src))
                                       (compile-file* src dest opts))]
      (binding [esm/*generated-sources* sources]
        (esm/build {:main main :output-dir (str (io/file dir "out"))})))
    @compiled))

(defn- output [^File dir ns]
  (slurp (io/file dir "out" (str (string/replace (str ns) "." "/") ".js"))))

(deftest recompiles-dependents-of-api-changes
  (let [dir    (temp-dir)
        before (- (System/currentTimeMillis) 60000)
        after  (+ (System/currentTimeMillis) 60000)
        b      (write-source! dir 'test.b "(ns test.b (:require [test.a :as a])) (def y (a/f 1))" before)
        build  #(build! dir (into {} [b (write-source! dir 'test.a % %2)]) 'test.b)]
    (build "(ns test.a) (defn f [x] x)" before)
    (testing "a changed without changing its api"
      (is (= #{"test_a.cljs"} (build "(ns test.a) (defn f [x] (inc x))" after))))
    (testing "a changed its api: b calls f's arity directly (:static-fns)"
      (is (= #{"test_a.cljs" "test_b.cljs"} (build "(ns test.a) (defn f ([x] x) ([x y] y))" (+ after 1000))))
      (is (string/includes? (output dir 'test.b) "cljs$core$IFn$_invoke$arity$1(")))))

(deftest watch-recompiles-in-dependency-order
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        a       (write-source! dir 'test.ra "(ns test.ra) (defn f [x] x)" ms)
        b       (write-source! dir 'test.rb "(ns test.rb (:require [test.ra :as a])) (def y (a/f 1))" ms)
        sources (into {} [a b])
        opts    (#'esm/prepare-opts {:main 'test.rb :output-dir (str (io/file dir "out"))})
        env     (env/default-compiler-env opts)]
    (binding [esm/*generated-sources* sources]
      (esm/build opts env)
      (testing "a batch naming a dependent before the namespace whose new var it uses (i.e. after a rebase)"
        (write-source! dir 'test.ra "(ns test.ra) (defn f [x] x) (defn g [x] x)" (+ ms 1000))
        (write-source! dir 'test.rb "(ns test.rb (:require [test.ra :as a])) (def y (a/g 1))" (+ ms 1000))
        (let [warnings (atom [])
              inputs   (env/with-compiler-env env (esm/find-sources ['test.rb] opts))
              done     (env/with-compiler-env env
                         (binding [ana/*cljs-warning-handlers*
                                   [(fn [type _ extra] (swap! warnings conj [type extra]))]]
                           (#'esm/recompile! env inputs '[test.rb test.ra] opts)))]
          (is (= '#{test.ra test.rb} done))
          (is (empty? @warnings)))))))

(deftest writes-closure-libraries-of-cached-namespaces
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.c "(ns test.c (:require [goog.string.format]))"
                            (- (System/currentTimeMillis) 60000))])
        lib     (io/file dir "out" "goog-lib" "ns" "goog.string.format.js")]
    (build! dir sources 'test.c)
    (is (.exists lib))
    (testing "a build compiling none of its users"
      (doseq [^File f (reverse (file-seq (io/file dir "out" "goog-lib")))]
        (.delete f))
      (build! dir sources 'test.c)
      (is (.exists lib)))))

(deftest builds-without-the-closure-compiler
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.d "(ns test.d) (defn f [x] (if (js/isNaN x) 1 2))"
                            (- (System/currentTimeMillis) 60000))])]
    (build! dir sources 'test.d)
    (is (nil? (find-ns 'cljs.closure)))
    (is (nil? (find-ns 'cljs.externs)))
    (testing "the default externs' types, from cljs/externs/default.edn: isNaN returns a boolean"
      (is (string/includes? (output dir 'test.d) "if(isNaN(x))")))))

(deftest fails-with-another-compiler-on-the-classpath
  (let [dir    (temp-dir)
        stock  (io/file dir "stock")
        thread (Thread/currentThread)
        loader (.getContextClassLoader thread)]
    (io/make-parents (io/file stock "cljs" "analyzer.cljc"))
    (spit (io/file stock "cljs" "analyzer.cljc") "(ns cljs.analyzer)")
    (.setContextClassLoader thread (URLClassLoader. (into-array URL [(.toURL (.toURI stock))]) loader))
    (try
      (let [e (try
                (esm/build {:main 'test.e :output-dir (str (io/file dir "out"))})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
        (is (string/starts-with? (ex-message e) "More than one ClojureScript compiler is on the classpath"))
        (is (= ["cljs/analyzer.cljc"] (keys (:resources (ex-data e)))))
        (is (some #(string/includes? % (str stock)) (get-in (ex-data e) [:resources "cljs/analyzer.cljc"]))))
      (finally
        (.setContextClassLoader thread loader)))))

(deftest extra-main-adds-main-namespaces
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.f "(ns test.f)" ms)
                          (write-source! dir 'test.g "(ns test.g)" ms)])]
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.f :extra-main '[test.g test.f] :output-dir (str (io/file dir "out"))}))
    (is (.exists (io/file dir "out" "test" "g.js")))
    (is (= {"test.f" "test/f.js" "test.g" "test/g.js"}
           (get (json/read-str (slurp (io/file dir "out" "cljs-esm.json"))) "main")))))

(deftest extra-main-namespaces-import-the-preloads
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.p "(ns test.p)" ms)
                          (write-source! dir 'test.m "(ns test.m)" ms)
                          (write-source! dir 'test.x "(ns test.x (:require [test.m]))" ms)])
        build   #(binding [esm/*generated-sources* sources]
                   (esm/build (merge {:preloads '[test.p] :output-dir (str (io/file dir "out"))} %)))]
    (build {:main 'test.x})
    (is (not (string/includes? (output dir 'test.m) "import \"./p.js\";")))
    (testing "a namespace compiled before it was a main namespace, i.e. a page's script, is recompiled"
      (build {:main 'test.x :extra-main '[test.m]})
      (is (string/includes? (output dir 'test.m) "import \"./p.js\";")))))

(deftest js-entries-import-the-preloads-and-the-repl-runtime
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.p "(ns test.p)" ms)
                          (write-source! dir 'test.s "(ns test.s) (def app 1)" ms)])
        build   #(binding [esm/*generated-sources* sources]
                   (esm/build (merge {:preloads '[test.p] :output-dir (str (io/file dir "out"))
                                      :js-entries '{server {:exports {default test.s/app}}}}
                                 %)))
        entry   #(slurp (io/file dir "out" "cljs-esm-entries" "server.js"))]
    (build {})
    (is (= (str "import \"../test/p.js\";\n"
                "export { app as default } from \"../test/s.js\";\n")
           (entry)))
    (testing "under :esm-repl the REPL runtime first, the REPL evaluates in a server running the entry"
      (build {:esm-repl true ::esm/repl-mains (atom #{})})
      (is (= (str "import \"../cljf/esm/repl_runtime.js\";\n"
                  "import \"../test/p.js\";\n"
                  "export { app as default } from \"../test/s.js\";\n")
             (entry))))))

(deftest reuses-output-compiled-with-a-released-version
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.h "(ns test.h)" (- (System/currentTimeMillis) 60000))])]
    ;; as script/stamp-version sets it in released compilers
    (binding [util/*clojurescript-version* {:major 1 :minor 12 :qualifier "clojurific-0.12.3"}]
      (is (contains? (build! dir sources 'test.h) "test_h.cljs"))
      (is (string/starts-with? (output dir 'test.h) "// Compiled by ClojureScript 1.12.clojurific-0.12.3 "))
      (testing "a second build compiles nothing"
        (is (= #{} (build! dir sources 'test.h)))))))

(defn- executor-threads
  "The live threads of executors (Executors' default thread names)."
  []
  (into #{} (filter #(re-matches #"pool-\d+-thread-\d+" (.getName ^Thread %))) (keys (Thread/getAllStackTraces))))

(deftest parallel-build-errors-name-their-file-and-location
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        ;; forms analyzed before the failing one, queued for the emitter
        forms   (apply str (for [i (range 200)] (str "(defn f" i " [x] (let [a (inc x)] (if (pos? a) [a " i "] a)))\n")))
        bad     (write-source! dir 'test.bad (str "(ns test.bad)\n" forms "(defn g [x] (let [a] x))") ms)
        sources (into {} (cons bad (for [i (range 4)]
                                     (write-source! dir (symbol (str "test.d" i))
                                       (str "(ns test.d" i " (:require [test.bad]))") ms))))
        main    (write-source! dir 'test.main (str "(ns test.main (:require " (string/join " " (map #(str "[test.d" % "]") (range 4))) "))") ms)
        before  (executor-threads)
        err     (try
                  (binding [esm/*generated-sources* (into sources [main])]
                    (esm/build {:main 'test.main :output-dir (str (io/file dir "out")) :parallel-build true}))
                  nil
                  (catch Throwable e e))
        data    (#'esm/error-data err)]
    (is (some? err))
    (is (= {:file (.getPath ^File (second bad)) :line 202 :column 13} (select-keys data [:file :line :column])))
    (is (string/ends-with? (:message data) "(expanding cljs.core/let)"))
    (testing "events print values JSON lacks, i.e. a File in ex-data"
      (let [out (java.io.StringWriter.)]
        (with-redefs [esm/stdout (delay out)]
          (esm/event! "error" {:file (second bad)}))
        (is (= {"file" (.getPath ^File (second bad)) "type" "error"}
               (json/read-str (subs (str out) (count "[cljf.esm] ")))))))
    (testing "the queued emits don't outlive the build, nor leave its output file behind"
      (is (empty? (remove before (filter #(.isAlive ^Thread %) (executor-threads)))))
      (is (not (.exists (io/file dir "out" "test" "bad.js.tmp")))))))

(deftest failed-parallel-builds-compile-the-namespaces-not-requiring-the-failed-one
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        bad     (write-source! dir 'test.fbad "(ns test.fbad)\n(defn g [x] (let [a] x))" ms)
        dep     (write-source! dir 'test.fdep "(ns test.fdep (:require [test.fbad]))" ms)
        dep2    (write-source! dir 'test.fdep2 "(ns test.fdep2 (:require [test.fdep]))" ms)
        indep   (write-source! dir 'test.findep "(ns test.findep) (defn h [x] x)" ms)
        ;; requires both the failed namespace and one that compiles: never ready
        diamond (write-source! dir 'test.fdia "(ns test.fdia (:require [test.fbad] [test.findep]))" ms)
        main    (write-source! dir 'test.fmain "(ns test.fmain (:require [test.fdep2] [test.findep] [test.fdia]))" ms)
        err     (deref
                  (future
                    (try
                      (binding [esm/*generated-sources* (into {} [bad dep dep2 indep diamond main])]
                        (esm/build {:main 'test.fmain :output-dir (str (io/file dir "out")) :parallel-build true}))
                      ::no-error
                      (catch Throwable e e)))
                  60000 ::timeout)]
    (is (instance? Throwable err) "fails, without waiting forever for the failed namespace's dependents")
    (is (.exists (io/file dir "out" "test" "findep.js")) "a namespace not requiring the failed one is compiled")
    (is (not (.exists (io/file dir "out" "test" "fdep2.js"))) "its dependents, transitively, aren't")
    (is (not (.exists (io/file dir "out" "test" "fdia.js"))) "nor one also requiring a namespace that compiled")))

(deftest ns-first-segments-after-a-removal-and-an-addition
  ;; a removal and an addition leave the count of namespaces unchanged
  (let [cenv (env/default-compiler-env)
        segs @#'comp/ns-first-segments]
    (swap! cenv update ::ana/namespaces assoc 'aaa.x {} 'bbb.y {})
    (is (contains? (segs cenv) "aaa"))
    (ana/remove-namespace! cenv 'aaa.x)
    (swap! cenv update ::ana/namespaces assoc 'ccc.z {})
    (is (not (contains? (segs cenv) "aaa")))
    (is (contains? (segs cenv) "ccc"))))

(deftest compiler-source-root-through-a-symlinked-directory
  ;; the compiler classes' cache key hashes the sources under this root: a
  ;; symlink inside the resource path (cljf/ linked to a differently named
  ;; directory) must not make it a path that isn't the classpath directory
  (let [dir    (temp-dir)
        real   (io/file dir "real" "compiler-src")
        cp     (io/file dir "cp")
        nofa   (make-array java.nio.file.attribute.FileAttribute 0)
        thread (Thread/currentThread)
        before (.getContextClassLoader thread)]
    (.mkdirs real) (.mkdirs cp)
    (spit (io/file real "esm.clj") "(ns cljf.esm)")
    (Files/createSymbolicLink (.toPath (io/file cp "cljf")) (.toPath real) nofa)
    ;; no parent: only cp, not the compiler's own cljf/esm.clj
    (.setContextClassLoader thread (URLClassLoader. (into-array URL [(.toURL (.toURI cp))]) nil))
    (try
      (let [root (@(requiring-resolve 'cljf.main/source-root) "cljf/esm.clj")]
        (is (some? root))
        (is (= (.getCanonicalPath cp) (some-> ^File root .getCanonicalPath)))
        (is (.isDirectory ^File root)))
      (finally
        (.setContextClassLoader thread before)))))

(deftest parse-ns-cache-without-a-compiler-env
  ;; the watcher looks up changed files' namespaces outside any compiler env,
  ;; with the per-recompile cache bound
  (let [dir (temp-dir)
        [_ f] (write-source! dir 'test.pnc "(ns test.pnc)" (System/currentTimeMillis))]
    (binding [env/*compiler* nil
              ana/*parse-ns-cache* (java.util.concurrent.ConcurrentHashMap.)]
      (is (= 'test.pnc (:ns (ana/parse-ns f))))
      (is (= 'test.pnc (:ns (ana/parse-ns f))) "and from the cache"))))

(deftest errors-in-macro-namespaces-name-their-file-and-line
  (let [dir    (temp-dir)
        ms     (- (System/currentTimeMillis) 60000)
        thread (Thread/currentThread)
        loader (.getContextClassLoader thread)
        ;; macro namespaces are required with Compiler/LOADER's class loader,
        ;; their errors' files are resources
        cl     (doto (clojure.lang.DynamicClassLoader. loader) (.addURL (.toURL (.toURI dir))))]
    (push-thread-bindings {clojure.lang.Compiler/LOADER cl})
    (.setContextClassLoader thread cl)
    (try
      (doseq [parallel? [false true]]
        (testing (str ":parallel-build " parallel?)
          ;; a namespace per build: a macro namespace is loaded once
          (let [mac    (str "test.mac" parallel?)
                macros (io/file dir "test" (str "mac" parallel? ".clj"))
                _      (io/make-parents macros)
                _      (spit macros (str "(ns " mac ")\n\n(defmacro m [] (no-such-fn))\n"))
                uses   (write-source! dir 'test.uses (str "(ns test.uses (:require-macros [" mac "]))\n(def x (" mac "/m))") ms)
                main   (write-source! dir 'test.umain "(ns test.umain (:require [test.uses]))" ms)
                err    (try
                         (binding [esm/*generated-sources* (into {} [uses main])]
                           (esm/build {:main 'test.umain :output-dir (str (io/file dir (str "out" parallel?))) :parallel-build parallel?}))
                         nil
                         (catch Throwable e e))
                data   (#'esm/error-data err)]
            (is (= {:file (.getPath macros) :line 3} (select-keys data [:file :line])))
            (is (not (string/includes? (:message data) "(expanding"))))))
      (finally
        (.setContextClassLoader thread loader)
        (pop-thread-bindings)))))

(deftest release-builds-define-goog-debug-false
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.dbg "(ns test.dbg) (def debug? goog.DEBUG)" ms)])
        debug?  (fn [opts]
                  (binding [esm/*generated-sources* sources]
                    (esm/build (merge {:main 'test.dbg :output-dir (str (io/file dir "out"))} opts)))
                  (second (re-find #"test\$dbg\$debug_QMARK_ = (\w+)" (output dir 'test.dbg))))]
    (is (= "true" (debug? {})))
    (is (= "true" (debug? {:mode :dev})))
    (testing "a :release build is a production build, as in shadow-cljs' release mode"
      (is (= "false" (debug? {:mode :release}))))
    (testing ":closure-defines sets it in a :release build too"
      (is (= "true" (debug? {:mode :release :closure-defines '{goog.DEBUG true}}))))
    (testing "a nil define doesn't set it (the Vite plugin's compilerOptions write null as nil)"
      (is (= "false" (debug? {:mode :release :closure-defines '{goog.DEBUG nil}})))
      (is (= "true" (debug? {:closure-defines '{goog.DEBUG nil}}))))))

(deftest a-test-runner-without-its-runner-fails-clearly
  (let [dir   (temp-dir)
        build (fn [test-runner]
                (try
                  (esm/build {:output-dir (str (io/file dir "out")) :test-runner test-runner})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (doseq [test-runner [{:ns 'my.test-runner} {:ns 'my.test-runner :runner nil}
                         {:ns 'my.test-runner :runner 'start} {:runner 'my.test/start}]]
      (is (some-> (build test-runner) (string/starts-with? ":test-runner needs :ns"))
          (pr-str test-runner)))))

(deftest compiled-events-report-the-build-s-outstanding-warnings
  (let [outstanding #'esm/outstanding-warnings
        w           (fn [output message] {:output output :message message})
        a1          (w "out/a.js" "a1")
        b1          (w "out/b.js" "b1")]
    (testing "a compile's warnings replace those of the files it compiled"
      (is (= [{"out/a.js" [a1]} [a1]]
             (outstanding {} {:files ["out/a.js"] :warnings [a1]} #{"out/a.js" "out/b.js"})))
      (is (= [{"out/a.js" [a1] "out/b.js" [b1]} [a1 b1]]
             (outstanding {"out/a.js" [a1]} {:files ["out/b.js"] :warnings [b1]} #{"out/a.js" "out/b.js"})))
      (is (= [{} []]
             (outstanding {"out/a.js" [a1]} {:files ["out/a.js"] :warnings []} #{"out/a.js"}))))
    (testing "a namespace removed from the build has no warnings, though it isn't compiled again"
      (is (= [{} []]
             (outstanding {"out/a.js" [a1]} {:files ["out/main.js"] :warnings []} #{"out/main.js"}))))
    (testing "the warnings of no namespace in the build (i.e. raised in cljs.user) are reported once"
      (let [user (w "out/cljs/user.js" "user")]
        (is (= [{"out/a.js" [a1]} [a1 user]]
               (outstanding {} {:files ["out/a.js"] :warnings [a1 user]} #{"out/a.js"})))
        (is (= [{"out/a.js" [a1]} [a1]]
               (outstanding {"out/a.js" [a1]} {:files [] :warnings []} #{"out/a.js"})))))))

(deftest jsx-literals-require-cljf-jsx-only-when-there-are-any
  (let [dir (temp-dir)
        ns-requires (fn [source]
                      (let [f (io/file dir "t.cljs")]
                        (spit f source)
                        (set (:requires (ana/parse-ns f)))))]
    (is (contains? (ns-requires "(ns t) (defn f [] #x [:p])") 'cljf.jsx))
    (is (contains? (ns-requires "(ns t (:require [clojure.string :as s])) (def a #jsx [:p (s/trim \" \")])") 'cljf.jsx))
    (testing "not for the tags in strings and comments, nor other tags"
      (is (not (contains? (ns-requires "(ns t) ;; #x [:p]\n(def s \"#x [:p]\") (def u #uuid \"00000000-0000-0000-0000-000000000000\")") 'cljf.jsx))))))

(deftest jsx-literals-call-the-jsx-runtime
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        runtime (io/file dir "node_modules" "react" "jsx-runtime.js")
        sources (into {} [(write-source! dir 'test.jsx
                            "(ns test.jsx)
                             (defn Item [^js p] #x [:li (.-label p)])
                             (defn view [{:keys [items extra style n]}]
                               #jsx [:<>
                                     [:div#main.a {:class \"b\" :on-click identity :data-x 1
                                                   :style {:font-size 12 :--gap \"1px\"} :key \"k\"}
                                      (if (seq items) #x [:ul (for [i items] #x [Item {:label i :key i}])] \"none\")
                                      n]
                                     [:p {:& extra :title \"t\" :style style}]])
                             (js/console.log (js/JSON.stringify
                               (view {:items [1 2] :n 3 :extra {:tab-index 2 :aria-label \"l\"} :style {:z-index 1}})))"
                            ms)])]
    (io/make-parents runtime)
    (spit runtime "const el = (type, props, key) => ({type: typeof type === 'function' ? type.name : type, key: key ?? null, props});
                   export const jsx = el, jsxs = el, Fragment = 'Fragment';")
    (spit (io/file dir "node_modules" "react" "package.json")
      "{\"name\": \"react\", \"type\": \"module\", \"exports\": {\"./jsx-runtime\": \"./jsx-runtime.js\"}}")
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.jsx :output-dir (str (io/file dir "out"))}))
    (let [out (output dir 'test.jsx)]
      (testing "a namespace with #x literals requires cljf.jsx implicitly"
        (is (string/includes? out "import * as cljf$jsx from \"../cljf/jsx.js\";")))
      (testing "literal props are JavaScript objects, with JavaScript names"
        (is (string/includes? out "{\"className\": \"a b\", \"onClick\": cljs$core.identity, \"data-x\": (1), \"style\": {\"fontSize\": (12), \"--gap\": \"1px\"}, \"id\": \"main\""))
        (is (string/includes? out "}, \"k\")")))
      (testing "only the values the compiler can't see into are converted"
        (is (string/includes? out "{...cljf$jsx.props(extra), \"title\": \"t\", \"style\": cljf$jsx.style(style)}"))
        (is (string/includes? out "jsx(test$jsx$Item, {\"label\": i}, (i ?? undefined))"))
        (is (string/includes? out "cljf$jsx.child(n)"))))
    (let [proc (.start (doto (ProcessBuilder. ["node" (str (io/file dir "out" "test" "jsx.js"))])
                         (.redirectErrorStream true)))
          res  (json/read-str (string/trim (slurp (.getInputStream proc))))]
      (.waitFor proc)
      (is (= {"type" "Fragment" "key" nil
              "props" {"children"
                       [{"type" "div" "key" "k"
                         "props" {"className" "a b" "data-x" 1 "style" {"fontSize" 12 "--gap" "1px"} "id" "main"
                                  "children" [{"type" "ul" "key" nil
                                               "props" {"children" [{"type" "test$jsx$Item" "key" 1 "props" {"label" 1}}
                                                                    {"type" "test$jsx$Item" "key" 2 "props" {"label" 2}}]}}
                                              3]}}
                        {"type" "p" "key" nil
                         "props" {"tabIndex" 2 "aria-label" "l" "title" "t" "style" {"zIndex" 1}}}]}}
             res)))))

(defn- stub-jsx-runtime!
  "A JSX runtime (src/jsx-runtime, src an import source: a package or a
  package's subpath) in dir's node_modules, its elements naming src, keys
  as React's: none when undefined, else strings (null as \"null\")."
  [^File dir src]
  (let [[pkg-name sub] (string/split src #"/" 2)
        pkg  (io/file dir "node_modules" pkg-name)
        path (str (when sub (str sub "/")) "jsx-runtime.js")]
    (io/make-parents (io/file pkg path))
    (spit (io/file pkg path)
      (str "const el = (type, props, key) => ({runtime: '" src "', type: typeof type === 'function' ? type.name : type, key: key === undefined ? null : '' + key, props});
            export const jsx = el, jsxs = el, Fragment = '" src "-Fragment';"))
    (spit (io/file pkg "package.json")
      (str "{\"name\": \"" pkg-name "\", \"type\": \"module\", \"exports\": {\"./" (str (when sub (str sub "/")) "jsx-runtime") "\": \"./" path "\"}}"))))

(defn- run-node [^File f]
  (let [proc (.start (doto (ProcessBuilder. ["node" (str f)]) (.redirectErrorStream true)))
        out  (slurp (.getInputStream proc))]
    (.waitFor proc)
    out))

(deftest jsx-import-source-picks-the-jsx-runtime
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.jsxsrc
                            "(ns test.jsxsrc (:require [test.jsxsrv :as srv]))
                             (js/console.log (js/JSON.stringify #js [#x [:<> [:p]] (srv/page)]))"
                            ms)
                          (write-source! dir 'test.jsxsrv
                            "(ns ^{:jsx-import-source \"hono/jsx\"} test.jsxsrv)
                             (defn page [] #x [:<> [:h1 {:class \"t\"} \"Hi\"]])"
                            ms)])]
    (stub-jsx-runtime! dir "preact")
    (stub-jsx-runtime! dir "hono/jsx")
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.jsxsrc :output-dir (str (io/file dir "out")) :jsx-import-source "preact"}))
    (testing "the build's :jsx-import-source is cljf.jsx's runtime"
      (is (string/includes? (slurp (io/file dir "out" "cljf" "jsx.js")) "from \"preact/jsx-runtime\"")))
    (testing "a namespace's :jsx-import-source metadata imports its own"
      (is (string/includes? (output dir 'test.jsxsrv) "from \"hono/jsx/jsx-runtime\"")))
    (is (= [{"runtime" "preact" "type" "preact-Fragment" "key" nil
             "props" {"children" {"runtime" "preact" "type" "p" "key" nil "props" {}}}}
            {"runtime" "hono/jsx" "type" "hono/jsx-Fragment" "key" nil
             "props" {"children" {"runtime" "hono/jsx" "type" "h1" "key" nil
                                  "props" {"className" "t" "children" "Hi"}}}}]
           (json/read-str (string/trim (run-node (io/file dir "out" "test" "jsxsrc.js"))))))))

(deftest a-changed-jsx-import-source-recompiles
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.jsxchg "(ns test.jsxchg) (def e #x [:p])"
                            (- (System/currentTimeMillis) 60000))])
        build   #(binding [esm/*generated-sources* sources]
                   (esm/build (merge {:main 'test.jsxchg :output-dir (str (io/file dir "out"))} %)))]
    (build {})
    (is (string/includes? (slurp (io/file dir "out" "cljf" "jsx.js")) "from \"react/jsx-runtime\""))
    (build {:jsx-import-source "preact"})
    (is (string/includes? (slurp (io/file dir "out" "cljf" "jsx.js")) "from \"preact/jsx-runtime\""))))

(deftest jsx-literals-keys-classes-and-children
  (let [dir     (temp-dir)
        ms      (- (System/currentTimeMillis) 60000)
        sources (into {} [(write-source! dir 'test.jsxrev
                            "(ns test.jsxrev)
                             (defn k [e] (.-key e))
                             (defn props [e] (.-props e))
                             (defn run [^js items m on? attrs]
                               (js/console.log (js/JSON.stringify
                                 #js {:nil-key        (k #x [:li {:key (:id {})}])
                                      :literal-key    (k #x [:li {:key :a}])
                                      :nested-key     (k #x [:li {:& {:key \"n\" :title \"t\"}}])
                                      :spread-key     (k #x [:li {:& m}])
                                      :spread-nil     (k #x [:li {:& (:attrs {})}])
                                      :spread-js      (k #x [:li {:& (js-obj \"title\" \"t\")}])
                                      :spread-props   (props #x [:li {:& m}])
                                      :outer-key-wins (k #x [:li {:& m :key \"o\"}])
                                      :class-vector   (.-className (props #x [:div.a {:class [\"b\" (when on? \"c\") nil]}]))
                                      :class-runtime  (.-className (props #x [:div {:class (if on? [:x [\"y\"]] \"z\")}]))
                                      :keyword-value  (.-type (props #x [:button {:type :submit}]))
                                      :vector-child   (.-children (props #x [:ul (mapv (fn [i] #x [:li i]) [1 2])]))
                                      :shadowed-local (.-children (props #x [:ul (let [items (for [i items] i)] items)]))
                                      :hiccup-child   (try (props #x [:ul [:li] (identity [:li])]) nil (catch :default e (.-message e)))
                                      :map-child      (try (props #x [:div attrs]) nil (catch :default e (.-message e)))})))
                             (run #js [1 2] {:key \"m\" :tab-index 1 :class [:p :q]} true {:class \"x\"})"
                            ms)])]
    (stub-jsx-runtime! dir "react")
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.jsxrev :output-dir (str (io/file dir "out"))}))
    (let [r (json/read-str (string/trim (run-node (io/file dir "out" "test" "jsxrev.js"))))]
      (testing "a nil :key is no key, as in Reagent"
        (is (nil? (get r "nil-key"))))
      (is (= "a" (get r "literal-key")))
      (testing "the :key of maps spread, literal or at runtime, is the key argument, not a prop"
        (is (= "n" (get r "nested-key")))
        (is (= "m" (get r "spread-key")))
        (testing "(nil or a JavaScript object spread is no key, not React's \"null\")"
          (is (nil? (get r "spread-nil")))
          (is (nil? (get r "spread-js"))))
        (is (= {"tabIndex" 1 "className" "p q"} (get r "spread-props")))
        (is (= "o" (get r "outer-key-wins"))))
      (testing ":class collections are joined, keyword values are their names"
        (is (= "a b c" (get r "class-vector")))
        (is (= "x y" (get r "class-runtime")))
        (is (= "submit" (get r "keyword-value"))))
      (testing "vectors of elements are children, the locals let binds aren't the outer ones"
        (is (= 2 (count (get r "vector-child"))))
        (is (= [1 2] (get r "shadowed-local"))))
      (testing "hiccup and maps outside #x fail clearly"
        (is (string/starts-with? (get r "hiccup-child") "A hiccup vector isn't a #x child"))
        (is (string/starts-with? (get r "map-child") "A map isn't a #x child"))))))
(deftest functions-declare-their-locals-with-const-and-let
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.decl
                            "(ns test.decl)
                             (deftype T [a] Object (get [this] a))
                             (defn f [x & more]
                               (let [y (inc x)
                                     z (case y 1 :one 2 (let [s \"two\"] s) :other)
                                     n (loop [i 0 acc 0] (if (< i y) (recur (inc i) (+ acc i)) acc))
                                     xs (for [i (range n)] (* i y))]
                                 (letfn [(g [v] (if (pos? v) (g (dec v)) z))]
                                   [y (g 2) n (vec xs) (count more) (.get (T. 1))])))
                             (defn h ([a] a) ([a b & cs] (apply + a b cs)))
                             (js/console.log (pr-str (f 2 :a :b) (h 1) (h 1 2 3)))"
                            (- (System/currentTimeMillis) 60000))])]
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.decl :output-dir (str (io/file dir "out"))}))
    (let [out    (output dir 'test.decl)
          ;; the module's own bindings are module scoped vars: hot reloads and
          ;; defonce assign them
          in-fns (remove #(or (re-find #"^var test\$decl\$" %) (re-find #"No var test" %))
                         (string/split-lines out))]
      (testing "no var in functions"
        (is (not-any? #(re-find #"\bvar " %) in-fns) (string/join "\n" (filter #(re-find #"\bvar " %) in-fns))))
      (testing "loop locals and the locals hoisted from expressions are let, the rest const"
        (is (re-find #"\blet i_\d+ = " out))
        (is (re-find #"\bconst y = " out))
        (is (re-find #"\(function \(x,more\)\{let [^;]+;" out)))
      (testing "case clauses are blocks"
        (is (re-find #"case \(2\):\n\{" out))))
    (is (= "[3 :other 3 [0 3 6] 2 1] 1 6\n"
           (run-node (io/file dir "out" "test" "decl.js"))))))

(deftest letfn-and-var-args-locals-are-declared-once
  (let [dir     (temp-dir)
        sources (into {} [(write-source! dir 'test.decl2
                            "(ns test.decl2)
                             (defn f1 [x] (letfn [(h [] 1)] (h)) (letfn [(h [] (if (pos? x) 2 (h)))] (h)))
                             (letfn [(top [] 1)] (top))
                             (letfn [(top [] 2)] (def t (top)))
                             (defn f3 [] (let [a (letfn [(x [] 1)] (x)) x 2] [a x]))
                             (defn f4 [n] (letfn [(ev? [n] (if (zero? n) true (od? (dec n))))
                                                  (od? [n] (if (zero? n) false (ev? (dec n))))]
                                            (ev? n)))
                             (def f (fn [a & var_args] var_args))
                             (def g (fn ([a] a) ([a & var_args] var_args)))
                             (js/console.log (pr-str (f1 1) t (f3) (f4 4) (f 1 2 3) (g 1 2 3) (g 1)))"
                            (- (System/currentTimeMillis) 60000))])]
    (binding [esm/*generated-sources* sources]
      (esm/build {:main 'test.decl2 :output-dir (str (io/file dir "out"))}))
    (is (= "2 2 [1 2] true (2 3) (2 3) 1\n" (run-node (io/file dir "out" "test" "decl2.js"))))))
