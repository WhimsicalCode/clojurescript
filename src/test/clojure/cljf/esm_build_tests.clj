;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm-build-tests
  "cljf.esm builds reusing the output of a previous build."
  (:require [cljs.compiler :as comp]
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
