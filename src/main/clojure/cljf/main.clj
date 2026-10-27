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
  %LOCALAPPDATA%\\cljf), under a key of the classpath (its jars' paths,
  modification times and sizes), the Clojure and Java versions, and the
  compiler's Clojure sources (their paths and contents, so a fresh checkout
  of the same sources has the same key): a run with another key compiles
  them again.
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

(defn sha1-hex
  "The SHA-1 of string s, in hex."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes s "UTF-8"))))))

(defn file-hash
  "The SHA-1 of file f's content, in hex: cache keys identify sources by
  content, which a checkout (i.e. CI's) keeps and their modification times
  don't."
  [^File f]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (apply str (map #(format "%02x" %) (.digest md (Files/readAllBytes (.toPath f)))))))

(defn classpath-entry-id
  "What identifies classpath entry f in a cache key: a jar by its path,
  modification time and size, a directory by its path (its sources are
  keyed by content)."
  [^String entry ^File f]
  (if (.isDirectory f)
    [entry "dir"]
    [entry (.lastModified f) (.length f)]))

(defn- cache-key
  "The key of the compiler's classes, see the namespace doc."
  []
  (let [md   (MessageDigest/getInstance "SHA-1")
        add! (fn [& xs] (.update md (.getBytes (str (string/join "\t" xs) "\n") "UTF-8")))]
    (add! (clojure-version) (System/getProperty "java.version"))
    (doseq [entry (string/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))]
      (apply add! (classpath-entry-id entry (io/file entry))))
    ;; the compiler's and its macros' sources
    (doseq [^File root (distinct (keep source-root ["cljf/esm.clj" "cljs/core.cljs"]))]
      (if (.isDirectory root)
        (doseq [^File f (sort (file-seq root))
                :when (and (.isFile f) (re-find #"\.clj[c]?$" (.getName f)))]
          (add! (.getPath f) (file-hash f)))
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

(declare write-pack!)

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
    (spit (io/file dir ".shadowed") (pr-str shadowed))
    (write-pack! dir)))

(defn write-pack!
  "Writes the classes of cache entry dir into one file, .pack: reading
  thousands of small class files one by one is a good part of loading
  them. Each class is its name and its bytes. Written to a temporary file,
  then renamed: another process may read it meanwhile."
  [^File dir]
  (let [root  (count (.getPath dir))
        files (->> (file-seq dir)
                   (filter #(and (.isFile ^File %) (.endsWith (.getName ^File %) ".class"))))
        pack  (io/file dir ".pack")
        tmp   (io/file dir (str ".pack.tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime)))]
    (with-open [out (java.io.DataOutputStream. (java.io.BufferedOutputStream. (java.io.FileOutputStream. tmp) 65536))]
      (doseq [^File f files
              :let [path  (.getPath f)
                    name  (-> path (subs (inc root)) (subs 0 (- (count path) root 7)) (.replace File/separator "."))
                    bytes (Files/readAllBytes (.toPath f))]]
        (.writeUTF out name)
        (.writeInt out (alength bytes))
        (.write out bytes)))
    (Files/move (.toPath tmp) (.toPath pack)
      (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn- read-pack
  "The classes of cache entry dir's .pack, {name bytes} in a mutable map
  (each removed once defined), nil without a readable one."
  ^java.util.concurrent.ConcurrentHashMap [^File dir]
  (let [pack (io/file dir ".pack")]
    (when (.exists pack)
      (try
        (let [m (java.util.concurrent.ConcurrentHashMap.)]
          (with-open [in (java.io.DataInputStream. (java.io.BufferedInputStream. (java.io.FileInputStream. pack) 65536))]
            (loop []
              (when (pos? (.available in))
                (let [name  (.readUTF in)
                      bytes (byte-array (.readInt in))]
                  (.readFully in bytes)
                  (.put m name bytes)
                  (recur)))))
          m)
        ;; a damaged pack: the class files are there
        (catch java.io.IOException _ nil)))))

(def ^:private ^java.lang.reflect.Method in-memory-class
  "DynamicClassLoader's (package private) lookup of the classes the Clojure
  compiler defined in memory, which its loadClass consults first."
  (doto (.getDeclaredMethod DynamicClassLoader "findInMemoryClass" (into-array Class [String]))
    (.setAccessible true)))

(defn- current-class-url
  "A URL of class file f whose modification time is at least as of: Clojure
  loads a namespace's class instead of its source only when the class is
  newer. An entry's key covers its sources' content, so its classes are
  current for every source not modified since the key was computed, however
  old their files are (i.e. restored from a CI cache, or sources checked out
  again). Sources edited later (in a watch) are newer still, and load."
  ^URL [^File f ^long as-of]
  (let [file-url    (.toURL (.toURI f))
        ^URL context nil]
    (URL. context (str file-url)
      (proxy [java.net.URLStreamHandler] []
        (openConnection [^URL _]
          (let [c (.openConnection file-url)]
            (proxy [java.net.URLConnection] [file-url]
              (connect [] (.connect c))
              (getInputStream [] (.getInputStream c))
              (getContentLengthLong [] (.getContentLengthLong c))
              (getLastModified [] (max as-of (.getLastModified c))))))))))

(defn cache-loader
  "A class loader of the classes in cache entry dir, as a DynamicClassLoader
  with dir on its classpath but loading the entry's classes, and finding its
  resources, from dir before asking parent: parent's classpath doesn't have
  them, and searching every jar and directory of a large classpath for each
  of thousands of classes is a big part of a start. Classes parent has too
  (see write-shadowed!) load parent first. Its classes count as current
  for the sources as they are now (see current-class-url). nil if dir lacks
  .shadowed."
  ^DynamicClassLoader [^File dir ^ClassLoader parent]
  (let [as-of         (System/currentTimeMillis)
        shadowed-file (io/file dir ".shadowed")]
    (when (.exists shadowed-file)
      (let [shadowed (edn/read-string (slurp shadowed-file))
            ;; entries written before packs existed get one
            _        (when-not (.exists (io/file dir ".pack"))
                       (try (write-pack! dir) (catch Exception _)))
            pack     (read-pack dir)
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
                                             (or (some-> pack (.remove name))
                                                 (Files/readAllBytes (.toPath f)))
                                             nil)]
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
                           (cond
                             (not (and f (.isFile f)))
                             (.getResource parent name)

                             (not (.endsWith name ".class"))
                             (.toURL (.toURI f))

                             (contains? shadowed (-> name (subs 0 (- (count name) 6)) (.replace "/" ".")))
                             (.getResource parent name)

                             :else
                             (current-class-url f as-of)))))]
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
  (let [done  (io/file dir ".complete")
        touch #(when (.exists done) (.setLastModified done (System/currentTimeMillis)))]
    ;; now, not only from the holder's thread: a short build may exit first
    (touch)
    (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService @holder
      ^Runnable touch 10 10 java.util.concurrent.TimeUnit/MINUTES)))

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
