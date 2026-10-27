;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.main
  "The compiler's command line, as cljf.esm's -main (clojure -M -m cljf.main
  build|watch options...), loading the compiler from classes compiled by an
  earlier run: loading its Clojure sources is most of a start's time.

  The classes are kept in the per-user cache (CLJF_CACHE_DIR, else
  ~/Library/Caches/cljf, $XDG_CACHE_HOME/cljf or ~/.cache/cljf,
  %LOCALAPPDATA%\\cljf), under a key of the classpath, the Clojure and Java
  versions, and the compiler's Clojure sources (their paths, modification
  times and sizes): a run with another key compiles them again.
  CLJF_COMPILER_CLASSES=false loads the sources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [clojure.lang DynamicClassLoader]
           [java.io File]
           [java.net URL]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(defn cache-dir
  "Directory name of the per-user cache, see the namespace doc."
  ^File [name]
  (let [env  #(not-empty (System/getenv %))
        home (System/getProperty "user.home")
        os   (string/lower-case (System/getProperty "os.name"))]
    (io/file
      (cond
        (env "CLJF_CACHE_DIR") (env "CLJF_CACHE_DIR")
        (string/includes? os "mac") (io/file home "Library" "Caches" "cljf")
        (string/includes? os "windows") (io/file (or (env "LOCALAPPDATA") home) "cljf")
        :else (io/file (or (env "XDG_CACHE_HOME") (io/file home ".cache")) "cljf"))
      name)))

(defn- cache-root ^File []
  (cache-dir "compiler-classes"))

(defn- source-root
  "The classpath root of resource path: a directory, or a jar file."
  ^File [path]
  (when-let [^URL url (io/resource path)]
    (case (.getProtocol url)
      "file" (let [f (.getCanonicalPath (io/file url))]
               (io/file (subs f 0 (- (count f) (count path) 1))))
      "jar"  (io/file (.toURI (URL. (first (string/split (.getPath url) #"!/")))))
      nil)))

(defn- cache-key
  "The key of the compiler's classes, see the namespace doc."
  []
  (let [md   (MessageDigest/getInstance "SHA-1")
        add! (fn [& xs] (.update md (.getBytes (str (string/join "\t" xs) "\n") "UTF-8")))]
    (add! (clojure-version) (System/getProperty "java.version"))
    (doseq [entry (string/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
            :let [f (io/file entry)]]
      (add! entry (.lastModified f) (.length f)))
    ;; the compiler's and its macros' sources
    (doseq [^File root (distinct (keep source-root ["cljf/esm.clj" "cljs/core.cljs"]))]
      (if (.isDirectory root)
        (doseq [^File f (sort (file-seq root))
                :when (and (.isFile f) (re-find #"\.clj[c]?$" (.getName f)))]
          (add! (.getPath f) (.lastModified f) (.length f)))
        (add! (.getPath root) (.lastModified root) (.length root))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- store-goog-dependencies!
  "Writes the Closure Library's dependency index (cljs.js-deps/
  goog-dependencies, read from goog/deps.js and the library's files) to dir:
  it depends on the classpath only, which the key covers."
  [dir]
  (spit (io/file dir "goog-dependencies.edn")
    (pr-str ((requiring-resolve 'cljs.js-deps/goog-dependencies*)))))

(defn- load-goog-dependencies!
  "Makes cljs.js-deps/goog-dependencies return the index stored in dir, if
  any, instead of reading the Closure Library's files again."
  [dir]
  (let [f (io/file dir "goog-dependencies.edn")]
    (when (.exists f)
      (let [deps (edn/read-string (slurp f))]
        (alter-var-root (requiring-resolve 'cljs.js-deps/goog-dependencies)
          (constantly (fn [] deps)))))))

(defn- class-file [^String name]
  (str (.replace name \. \/) ".class"))

(defn write-shadowed!
  "Records in cache entry dir (being written) the names of its classes that
  parent has too, which cache-loader loads parent first."
  [^File dir ^ClassLoader parent]
  (let [root (count (.getPath dir))
        shadowed (->> (file-seq dir)
                      (map #(.getPath ^File %))
                      (filter #(.endsWith ^String % ".class"))
                      (map #(-> ^String % (subs (inc root)) (subs 0 (- (count %) root 7)) (.replace File/separator ".")))
                      (filter #(.getResource parent (class-file %)))
                      (into (sorted-set)))]
    (spit (io/file dir ".shadowed") (pr-str shadowed))))

(def ^:private ^java.lang.reflect.Method in-memory-class
  "DynamicClassLoader's (package private) lookup of the classes the Clojure
  compiler defined in memory, which its loadClass consults first."
  (doto (.getDeclaredMethod DynamicClassLoader "findInMemoryClass" (into-array Class [String]))
    (.setAccessible true)))

(defn cache-loader
  "A class loader of the classes in cache entry dir, as a DynamicClassLoader
  with dir on its classpath but loading the entry's classes, and finding its
  resources, from dir before asking parent: parent's classpath doesn't have
  them, and searching every jar and directory of a large classpath for each
  of thousands of classes is a big part of a start. Classes parent has too
  (see write-shadowed!) load parent first. nil if dir lacks .shadowed."
  ^DynamicClassLoader [^File dir ^ClassLoader parent]
  (let [shadowed-file (io/file dir ".shadowed")]
    (when (.exists shadowed-file)
      (let [shadowed (edn/read-string (slurp shadowed-file))
            defined  (java.util.concurrent.ConcurrentHashMap.)
            locks    (java.util.concurrent.ConcurrentHashMap.)
            own      (fn [^String name] (when-not (contains? shadowed name)
                                          (let [f (io/file dir (class-file name))]
                                            (when (.isFile f) f))))
            loader   (promise)
            load     (fn [^String name]
                       (or (.invoke ^java.lang.reflect.Method in-memory-class nil (object-array [name]))
                           (.get defined name)
                           (if-let [^File f (own name)]
                             (locking (.computeIfAbsent locks name (reify java.util.function.Function (apply [_ _] (Object.))))
                               (or (.get defined name)
                                   (let [c (.defineClass ^DynamicClassLoader @loader name
                                             (Files/readAllBytes (.toPath f)) nil)]
                                     (.put defined name c)
                                     c)))
                             (.loadClass parent name))))
            dcl      (proxy [DynamicClassLoader] [parent]
                       (loadClass
                         ([name] (load name))
                         ([name _resolve] (load name)))
                       (getResource [^String name]
                         ;; only plain relative names can be the entry's files
                         (let [f (when-not (or (.startsWith name "/") (.contains name ".."))
                                   (File. dir name))]
                           (if (and f (.isFile f)
                                    (not (and (.endsWith name ".class")
                                              (contains? shadowed (-> name (subs 0 (- (count name) 6)) (.replace "/" "."))))))
                             (.toURL (.toURI f))
                             (.getResource parent name)))))]
        (deliver loader dcl)
        (.addURL dcl (.toURL (.toURI dir)))
        dcl))))

(declare hold!)

(defn- load-compiler!
  "Loads the compiler (cljf.esm and cljs.core's macros) from its classes in
  the cache, compiling them there first if they aren't."
  []
  (let [dir    (io/file (cache-root) (cache-key))
        done   (io/file dir ".complete")
        thread (Thread/currentThread)
        dcl    (DynamicClassLoader. (.getContextClassLoader thread))
        ;; cljs.core's macros, which every build loads
        core   #(with-bindings {(requiring-resolve 'cljs.env/*compiler*)
                                ((requiring-resolve 'cljs.env/default-compiler-env))}
                  ((requiring-resolve 'cljs.analyzer/load-core)))]
    (if (.exists done)
      (let [dcl (or (cache-loader dir (.getContextClassLoader thread))
                    (doto dcl (.addURL (.toURL (.toURI dir)))))]
        ;; when it was last used, see prune!
        (.setLastModified done (System/currentTimeMillis))
        ;; threads started from now on inherit it
        (.setContextClassLoader thread dcl)
        (hold! dir)
        (require 'cljf.esm)
        (load-goog-dependencies! dir))
      (let [tmp (io/file (str dir ".tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime)))]
        (.mkdirs tmp)
        (binding [*compile-files* true
                  *compile-path*  (.getPath tmp)]
          (require 'cljf.esm)
          (core))
        (store-goog-dependencies! tmp)
        (write-shadowed! tmp (.getContextClassLoader thread))
        (spit (io/file tmp ".complete") "")
        (try
          (Files/move (.toPath tmp) (.toPath dir) (into-array [StandardCopyOption/ATOMIC_MOVE]))
          (hold! dir)
          ;; another run compiled them meanwhile
          (catch java.io.IOException _
            (doseq [^File f (reverse (file-seq tmp))] (.delete f))))))))

(def ^:private holder
  (delay
    (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
      (reify java.util.concurrent.ThreadFactory
        (newThread [_ r]
          (doto (Thread. ^Runnable r "cljf-cache-hold") (.setDaemon true)))))))

(defn hold!
  "Marks cache entry dir as used every ten minutes while this process runs:
  classes it loaded load more classes from it later (i.e. a watch's), so
  prune! mustn't delete it meanwhile."
  [^File dir]
  (let [done (io/file dir ".complete")]
    (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService @holder
      ^Runnable #(when (.exists done) (.setLastModified done (System/currentTimeMillis)))
      0 10 java.util.concurrent.TimeUnit/MINUTES)))

(def ^:private keep-entries
  "How many of a cache's most recently used entries prune! keeps however
  old: each key (i.e. every edit of a Clojure source on the classpath, for
  macro classes) is a new entry of tens of MB."
  8)

(defn prune!
  "Deletes the entries (directories) of root, the compiler's classes by
  default, not used for a week, and beyond the keep-entries most recently
  used ones those not used for an hour: a running compiler may still load
  classes from an entry it started with. Entries being written (.tmp-) are
  deleted after an hour, a build that crashed left them."
  ([] (prune! (cache-root)))
  ([^File root]
   (let [hour    (* 3600 1000)
         week    (* 7 24 hour)
         now     (System/currentTimeMillis)
         used    (fn [^File dir]
                   (let [done (io/file dir ".complete")]
                     (if (.exists done) (.lastModified done) (.lastModified dir))))
         dirs    (filter #(.isDirectory ^File %) (.listFiles root))
         tmp?    #(string/includes? (.getName ^File %) ".tmp-")
         entries (sort-by (comp - used) (remove tmp? dirs))
         stale   (concat
                   (filter #(< (used %) (- now hour)) (filter tmp? dirs))
                   (filter #(< (used %) (- now week)) (take keep-entries entries))
                   (filter #(< (used %) (- now hour)) (drop keep-entries entries)))]
     (doseq [^File dir stale
             ^File f (reverse (file-seq dir))]
       (.delete f)))))

(defn -main
  "Usage: clojure -M -m cljf.main [build|watch] options..., see cljf.esm/-main."
  [& args]
  (if (= "false" (System/getenv "CLJF_COMPILER_CLASSES"))
    (require 'cljf.esm)
    (try
      (load-compiler!)
      (prune!)
      (catch Throwable e
        (binding [*out* *err*]
          (println "cljf: loading the compiler from its sources:" (.getMessage e)))
        (require 'cljf.esm))))
  (apply (resolve 'cljf.esm/-main) args))
