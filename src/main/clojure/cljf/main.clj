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

(defn- cache-root ^File []
  (let [env  #(not-empty (System/getenv %))
        home (System/getProperty "user.home")
        os   (string/lower-case (System/getProperty "os.name"))]
    (io/file
      (cond
        (env "CLJF_CACHE_DIR") (env "CLJF_CACHE_DIR")
        (string/includes? os "mac") (io/file home "Library" "Caches" "cljf")
        (string/includes? os "windows") (io/file (or (env "LOCALAPPDATA") home) "cljf")
        :else (io/file (or (env "XDG_CACHE_HOME") (io/file home ".cache")) "cljf"))
      "compiler-classes")))

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
      (do
        ;; when it was last used, see prune!
        (.setLastModified done (System/currentTimeMillis))
        (.addURL dcl (.toURL (.toURI dir)))
        ;; threads started from now on inherit it
        (.setContextClassLoader thread dcl)
        (require 'cljf.esm)
        (load-goog-dependencies! dir))
      (let [tmp (io/file (str dir ".tmp-" (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime)))]
        (.mkdirs tmp)
        (binding [*compile-files* true
                  *compile-path*  (.getPath tmp)]
          (require 'cljf.esm)
          (core))
        (store-goog-dependencies! tmp)
        (spit (io/file tmp ".complete") "")
        (try
          (Files/move (.toPath tmp) (.toPath dir) (into-array [StandardCopyOption/ATOMIC_MOVE]))
          ;; another run compiled them meanwhile
          (catch java.io.IOException _
            (doseq [^File f (reverse (file-seq tmp))] (.delete f))))))))

(defn- prune!
  "Deletes the classes of other keys not used for a week (a running compiler
  may still load classes from them)."
  []
  (let [week (* 7 24 3600 1000)
        now  (System/currentTimeMillis)]
    (doseq [^File dir (.listFiles (cache-root))
            :let [done (io/file dir ".complete")]
            :when (and (.isDirectory dir)
                       (< (if (.exists done) (.lastModified done) (.lastModified dir)) (- now week)))]
      (doseq [^File f (reverse (file-seq dir))] (.delete f)))))

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
