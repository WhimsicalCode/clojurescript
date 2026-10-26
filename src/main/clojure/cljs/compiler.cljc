;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.compiler
  #?(:clj (:refer-clojure :exclude [ensure macroexpand-1 munge])
     :cljs (:refer-clojure :exclude [ensure js-reserved macroexpand-1 munge]))
  #?(:cljs (:require-macros [cljs.compiler.macros :refer [emit-wrap]]
                            [cljs.env.macros :refer [ensure]]))
  #?(:clj (:require [cljs.analyzer :as ana]
                    [cljs.env :as env :refer [ensure]]
                    [cljs.js-deps :as deps]
                    [cljs.source-map :as sm]
                    [cljs.tagged-literals :as tags]
                    [cljs.util :as util]
                    [cljs.vendor.clojure.data.json :as json]
                    [clojure.java.io :as io]
                    [clojure.set :as set]
                    [clojure.string :as string]
                    [cljs.vendor.clojure.tools.reader :as reader])
     :cljs (:require [cljs.analyzer :as ana]
                     [cljs.analyzer.impl :as ana.impl]
                     [cljs.env :as env]
                     [cljs.source-map :as sm]
                     [cljs.tools.reader :as reader]
                     [clojure.set :as set]
                     [clojure.string :as string]
                     [goog.string :as gstring]))
  #?(:clj (:import [cljs.tagged_literals JSValue]
                   java.lang.StringBuilder
                   [java.io File Writer]
                   [java.time Instant]
                   [java.util.concurrent Executors ExecutorService TimeUnit]
                   [java.util.concurrent.atomic AtomicLong])
     :cljs (:import [goog.string StringBuffer])))

#?(:clj (set! *warn-on-reflection* true))

(def js-reserved ana/js-reserved)

(def ^:private es5>=
  (into #{}
    (comp
      (mapcat (fn [lang]
                [lang (keyword (string/replace (name lang) #"^ecmascript" "es"))])))
    [:ecmascript5 :ecmascript5-strict :ecmascript6 :ecmascript6-strict
     :ecmascript-2015 :ecmascript6-typed :ecmascript-2016 :ecmascript-2017
     :ecmascript-2018 :ecmascript-2019 :ecmascript-2020 :ecmascript-2021
     :ecmascript-next]))

(def ^:dynamic *recompiled* nil)
(def ^:dynamic *inputs* nil)
(def ^:dynamic *source-map-data* nil)
(def ^:dynamic *source-map-data-gen-col* nil)
(def ^:dynamic *lexical-renames* {})
;; In a fn body, an atom of the locals declared at its end: a let in
;; expression position assigns them in a comma expression instead of binding
;; them in an IIFE, see emit-let.
(def ^:dynamic *hoisted-locals* nil)

;; ES module output (:module-format :esm)
;;
;; Every namespace becomes an ES module. Vars of the namespace being compiled
;; are module-local bindings named my$ns$var, exported as var. Vars of other
;; namespaces are referenced through namespace imports, i.e. my$ns.var, which
;; bundlers can statically resolve, tree-shake and rename.

(def ^:dynamic *esm*
  "Per-file ESM emission state, a map of atoms. Bound by emit-source when
  compiling with :module-format :esm."
  nil)

(def ^:dynamic *esm-emitting*
  "True while emitting (not analyzing) an ESM file. Var munging only switches
  to ESM names during emission, analysis time munging is unaffected."
  false)

(def ^:dynamic *esm-in-fn*
  "True while emitting code nested in a JS function, where a top-level var
  declaration can't be emitted."
  false)

(def ^:dynamic *esm-repl*
  "True while emitting a form evaluated at the REPL (cljf.esm.repl), the body
  of a function the REPL runtime calls with the namespaces it references.
  The namespace being compiled is one of them: its vars are module bindings
  of a module already running, reached through the runtime's view of the
  namespace, my$ns.var, and def assigns the view."
  false)

(def esm-repl-runtime
  "The namespace evaluating REPL forms in a running build, imported first by
  the main namespaces under :esm-repl."
  'cljf.esm.repl-runtime)

(defn esm-mode?
  "Whether compiling to ES modules, :module-format :esm."
  ([] (esm-mode? (when env/*compiler* (:options @env/*compiler*))))
  ([opts] (= :esm (:module-format opts))))

(def cljs-reserved-file-names #{"deps.cljs"})

(defn get-first-ns-segment
  "Gets the part up to the first `.` of a namespace.
   Returns the empty string for nil.
   Returns the entire string if no `.` in namespace"
  [ns]
  (let [ns (str ns)
        idx (.indexOf ns ".")]
    (if (== -1 idx)
      ns
      (subs ns 0 idx))))

(defn ^:dynamic find-ns-starts-with [needle]
  (reduce-kv
    (fn [xs ns _]
      (when (= needle (get-first-ns-segment ns))
        (reduced needle)))
    nil
    (::ana/namespaces @env/*compiler*)))

; Helper fn
(defn shadow-depth [s]
  (let [{:keys [name info]} s]
    (loop [d 0, {:keys [shadow]} info]
      (cond
        shadow (recur (inc d) shadow)
        (find-ns-starts-with (str name)) (inc d)
        :else d))))

(defn hash-scope [s]
  (hash-combine #?(:clj  (hash (:name s))
                   :cljs (-hash ^not-native (:name s)))
    (shadow-depth s)))

(declare munge emits)

(defn esm-ns-alias
  "Returns the identifier a namespace is imported as, i.e. my$ns."
  [ns]
  (if (= "goog" (str ns))
    ;; not goog, the Closure Library compatibility layer defines a global goog
    "goog$"
    (string/join "$" (map #(str (munge % #{})) (string/split (str ns) #"\.")))))

(defn esm-module-ns?
  "Whether references into namespace ns (a string) should go through an ES
  module import. Everything else, i.e. Math or unknown namespaces, keeps
  being referenced as a global."
  [ns]
  (let [cenv @env/*compiler*]
    (or (= ns (str ana/*cljs-ns*))
        (= "goog" ns)
        ;; Closure Library namespaces, i.e. goog.string but not goog.DEBUG
        (and (string/starts-with? ns "goog.")
             (contains? (:js-dependency-index cenv) ns))
        (contains? (::ana/namespaces cenv) (symbol ns))
        ;; namespaces of the build not analyzed yet, i.e. under :parallel-build
        (contains? (:cljf.esm/namespaces cenv) (symbol ns)))))

(defn esm-var-name
  "Munges the name of a var for ESM output, reserved words are munged like
  they are for global namespace paths so that munging is idempotent."
  [nm]
  (str (munge nm js-reserved)))

(declare esm-goog-shim-exports)

(defn- munge-esm-var
  ([ns nm] (munge-esm-var ns nm js-reserved))
  ([ns nm reserved]
   (let [local? (and (not *esm-repl*) (= ns (str ana/*cljs-ns*)))
         idx    (.indexOf ^String nm ".")
         member (esm-var-name (if (neg? idx) nm (subs nm 0 idx)))
         ;; nm may be a property path, i.e. Foo.prototype.bar, reserved
         ;; words are valid property names, i.e. an npm module's default
         nm'    (if (neg? idx)
                  member
                  (str member "." (munge (subs nm (inc idx)) #{})))
         ;; Closure Library members are taken from ES module shims if they
         ;; provide them, otherwise from the Closure Library itself run by
         ;; the compatibility layer, see cljf.esm/install-goog-libs
         lib?   (and (or (= "goog" ns) (string/starts-with? ns "goog."))
                     (not (contains? #?(:clj (esm-goog-shim-exports ns ana/*cljs-ns*) :cljs #{}) member)))]
     (cond
       local? nil
       lib?   (swap! (:goog-lib-refs *esm*) conj ns)
       :else  (swap! (:refs *esm*) conj ns))
     (str (esm-ns-alias ns) (when lib? "$") (if local? "$" ".") nm'))))

(defn- esm-split-dotted
  "Fully qualified dotted references like cljs.core.PersistentVector.EMPTY
  resolve to namespace cljs.core.PersistentVector, returns the longest
  namespace prefix and the rest, if any."
  [ns nm]
  (let [segs (string/split ns #"\.")]
    (loop [n (dec (count segs))]
      (when (pos? n)
        (let [prefix (string/join "." (take n segs))]
          (if (esm-module-ns? prefix)
            [prefix (string/join "." (concat (drop n segs) [nm]))]
            (recur (dec n))))))))

(defn- esm-var-ref
  "While emitting ES modules, returns [ns name] if symbol s references a var
  of a namespace compiled to an ES module."
  [s]
  (when (and *esm-emitting* (symbol? s) (some? (namespace s)))
    (if (esm-module-ns? (namespace s))
      [(namespace s) (name s)]
      (esm-split-dotted (namespace s) (name s)))))

(defn fn-self-name [{:keys [name info] :as name-var}]
  (let [name (string/replace (str name) ".." "_DOT__DOT_")
        {:keys [ns fn-scope]} info
        scoped-name (apply str
                      (interpose "_$_"
                        (concat (map (comp str :name) fn-scope) [name])))]
    (symbol
      (munge
        (str (string/replace (str ns) "." "$") "$" scoped-name)))))

(defn munge-reserved [reserved]
  (fn [s]
    (if-not (nil? (get reserved s))
      (str s "$")
      s)))

(defn munge
  ([s] (munge s js-reserved))
  ([s reserved]
   (cond
     #?(:clj  (map? s)
        :cljs (ana.impl/cljs-map? s))
     (let [name-var s
           name     (:name name-var)
           field    (:field name-var)
           info     (:info name-var)]
       (if-not (nil? (:fn-self-name info))
         (fn-self-name s)
         ;; Unshadowing
         (let [depth       (shadow-depth s)
               code        (hash-scope s)
               renamed     (get *lexical-renames* code)
               name        (cond
                             (true? field) (str "self__." name)
                             (not (nil? renamed)) renamed
                             :else name)
               munged-name (munge name reserved)]
           (if (or (true? field) (zero? depth))
             munged-name
             (symbol (str munged-name "__$" depth))))))
     ;; vars of namespaces compiled to ES modules
     (some? (esm-var-ref s))
     (let [[ns nm] (esm-var-ref s)]
       (symbol (munge-esm-var ns nm reserved)))

     ;; String munging
     :else
     (let [ss (string/replace (str s) ".." "_DOT__DOT_")
           ss (string/replace ss
                #?(:clj #"\/(.)" :cljs (js/RegExp. "\\/(.)")) ".$1") ; Division is special
           rf (munge-reserved reserved)
           ss (map rf (string/split ss #"\."))
           ss (string/join "." ss)
           ms #?(:clj (clojure.lang.Compiler/munge ss)
                 :cljs (munge-str ss))]
       (if (symbol? s)
         (symbol ms)
         ms)))))

(defn esm-rewrite-core-refs
  "Under ESM, rewrite cljs.core.foo references in emitted JS strings to
  ESM references."
  [s]
  (if (and *esm-emitting* (string/includes? s "cljs.core."))
    (string/replace s #"(?<![\w$.])cljs\.core\.([A-Za-z_$][\w$]*)"
      (fn [[_ n]]
        (munge-esm-var "cljs.core" n)))
    s))

(defn- cc
  "Emitted JS that references cljs.core vars as cljs.core.foo goes through
  here to support ESM output."
  [s]
  (esm-rewrite-core-refs s))

(defn- esm-js-ns-ref
  "Under ESM, returns the reference a js/my.ns or js/my.ns.foo global
  reference of a namespace of the build is emitted as."
  [js-name]
  (when (string/includes? js-name ".")
    (let [segs (string/split js-name #"\.")]
      (loop [n (count segs)]
        (when (pos? n)
          (let [ns (string/join "." (take n segs))]
            (if (esm-module-ns? ns)
              (do (if (and (= ns (str ana/*cljs-ns*)) (not *esm-repl*))
                    ;; the module's own namespace object
                    (reset! (:self-import *esm*) true)
                    (swap! (:refs *esm*) conj ns))
                  (string/join "." (cons (esm-ns-alias ns) (drop n segs))))
              (recur (dec n)))))))))

(defn- esm-def!
  "Records a var defined by the namespace being compiled as an export of the
  ES module. Returns the module-local name of the var."
  [sym]
  (let [local  (str (munge sym))
        export (esm-var-name (name sym))]
    (swap! (:exports *esm*) assoc export local)
    (swap! (:export-syms *esm*) assoc export sym)
    (when (get-in @env/*compiler*
            [::ana/namespaces (symbol (namespace sym)) :defs (symbol (name sym)) :dynamic])
      (swap! (:dynamic *esm*) conj export))
    local))

(defn- emit-esm-type-binding
  "Emits the binding a deftype / defrecord constructor is assigned to."
  [t]
  (if (and *esm-emitting* (not *esm-repl*))
    (let [local (esm-def! t)]
      (if *esm-in-fn*
        (do (swap! (:hoist *esm*) conj local)
            (emits local))
        (emits "var " local)))
    (emits (munge t))))

(defn- comma-sep [xs]
  (interpose "," xs))

(defn- escape-char [^Character c]
  (let [cp #?(:clj (.hashCode c)
              :cljs (gstring/hashCode c))]
    (case cp
      ; Handle printable escapes before ASCII
      34 "\\\""
      92 "\\\\"
      ; Handle non-printable escapes
      8 "\\b"
      12 "\\f"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      (if (< 31 cp 127)
        c ; Print simple ASCII characters
        #?(:clj (format "\\u%04X" cp)                       ; Any other character is Unicode
           :cljs (let [unpadded (.toString cp 16)
                       pad      (subs "0000" (.-length unpadded))]
                   (str "\\u" pad unpadded)))))))

(defn- escape-string [^CharSequence s]
  (let [sb #?(:clj (StringBuilder. (count s))
              :cljs (StringBuffer.))]
    (doseq [c s]
      (.append sb (escape-char c)))
    (.toString sb)))

(defn- wrap-in-double-quotes [x]
  (str \" x \"))

(defmulti emit* :op)

(defn emit [ast]
  (when *source-map-data*
    (let [{:keys [env]} ast]
      (when (:line env)
        (let [{:keys [line column]} env]
          (swap! *source-map-data*
            (fn [m]
              (let [minfo (cond-> {:gcol  #?(:clj  (.get ^AtomicLong *source-map-data-gen-col*)
                                             :cljs (:gen-col m))
                                   :gline (:gen-line m)}
                            (#{:var :local :js-var :binding} (:op ast))
                            (assoc :name (str (-> ast :info :name))))]
                ; Dec the line/column numbers for 0-indexing.
                ; tools.reader uses 1-indexed sources, chrome
                ; expects 0-indexed source maps.
                (update-in m [:source-map (dec line)]
                  (fnil (fn [line]
                          (update-in line [(if column (dec column) 0)]
                            (fnil (fn [column] (conj column minfo)) [])))
                    (sorted-map))))))))))
  (if (and *esm-emitting*
           (not *esm-in-fn*)
           (or (= :fn (:op ast))
               ;; these emit an IIFE in expression context
               (and (= :expr (-> ast :env :context))
                    (contains? #{:case :throw :do :try :let :loop :letfn} (:op ast)))))
    (binding [*esm-in-fn* true]
      (emit* ast))
    (emit* ast)))

(defn emits
  ([])
  ([^Object a]
   (cond
     (nil? a) nil
     #?(:clj (map? a) :cljs (ana.impl/cljs-map? a)) (emit a)
     #?(:clj (seq? a) :cljs (ana.impl/cljs-seq? a)) (apply emits a)
     #?(:clj (fn? a) :cljs (js-fn? a)) (a)
     :else (let [^String s (cond-> a (not (string? a)) .toString)]
             #?(:clj  (when-some [^AtomicLong gen-col *source-map-data-gen-col*]
                        (.addAndGet gen-col (.length s)))
                :cljs (when-some [sm-data *source-map-data*]
                        (swap! sm-data update :gen-col #(+ % (.-length s)))))
             #?(:clj  (.write ^Writer *out* s)
                :cljs (print s))))
    nil)
  ([a b]
   (emits a) (emits b))
  ([a b c]
   (emits a) (emits b) (emits c))
  ([a b c d]
   (emits a) (emits b) (emits c) (emits d))
  ([a b c d e]
   (emits a) (emits b) (emits c) (emits d) (emits e))
  ([a b c d e & xs]
   (emits a) (emits b) (emits c) (emits d) (emits e)
   (doseq [x xs] (emits x))))

(defn ^:private _emitln []
  (newline)
  (when *source-map-data*
    #?(:clj (.set ^AtomicLong *source-map-data-gen-col* 0))
    (swap! *source-map-data*
      (fn [{:keys [gen-line] :as m}]
        (assoc m
          :gen-line (inc gen-line)
          #?@(:cljs [:gen-col 0])))))
  nil)

(defn emitln
  ([] (_emitln))
  ([a]
   (emits a) (_emitln))
  ([a b]
   (emits a) (emits b) (_emitln))
  ([a b c]
   (emits a) (emits b) (emits c) (_emitln))
  ([a b c d]
   (emits a) (emits b) (emits c) (emits d) (_emitln))
  ([a b c d e]
   (emits a) (emits b) (emits c) (emits d) (emits e) (_emitln))
  ([a b c d e & xs]
   (emits a) (emits b) (emits c) (emits d) (emits e)
   (doseq [x xs] (emits x))
   (_emitln)))

(defn ^String emit-str [expr]
  (with-out-str (emit expr)))

#?(:clj
   (defmulti emit-constant* class)
   :cljs
   (defmulti emit-constant* type))

(declare emit-map emit-list emit-vector emit-set emit-js-object emit-js-array
         emit-with-meta emit-constants-comma-sep emit-constant emit-record-value)

(defn all-distinct? [xs]
  (apply distinct? xs))

#?(:clj
   (defn emit-constant-no-meta [x]
     (cond
       (seq? x) (emit-list x emit-constants-comma-sep)
       (record? x) (let [[ns name] (ana/record-ns+name x)]
                     (emit-record-value ns name #(emit-constant (into {} x))))
       (map? x) (emit-map (keys x) (vals x) emit-constants-comma-sep all-distinct?)
       (vector? x) (emit-vector x emit-constants-comma-sep)
       (set? x) (emit-set x emit-constants-comma-sep all-distinct?)
       :else (emit-constant* x)))
   :cljs
   (defn emit-constant-no-meta [x]
     (cond
       (ana.impl/cljs-seq? x) (emit-list x emit-constants-comma-sep)
       (record? x) (let [[ns name] (ana/record-ns+name x)]
                     (emit-record-value ns name #(emit-constant (into {} x))))
       (ana.impl/cljs-map? x) (emit-map (keys x) (vals x) emit-constants-comma-sep all-distinct?)
       (ana.impl/cljs-vector? x) (emit-vector x emit-constants-comma-sep)
       (ana.impl/cljs-set? x) (emit-set x emit-constants-comma-sep all-distinct?)
       :else (emit-constant* x))))

(defn emit-constant [v]
  (let [m (ana/elide-irrelevant-meta (meta v))]
    (if (some? (seq m))
      (emit-with-meta #(emit-constant-no-meta v) #(emit-constant-no-meta m))
      (emit-constant-no-meta v))))

(defmethod emit-constant* :default
  [x]
  (throw
    (ex-info (str "failed compiling constant: " x "; "
               (pr-str (type x)) " is not a valid ClojureScript constant.")
      {:constant x
       :type (type x)
       :clojure.error/phase :compilation})))

(defmethod emit-constant* nil [x] (emits "null"))

#?(:clj
   (defmethod emit-constant* Long [x] (emits "(" x ")")))

#?(:clj
   (defmethod emit-constant* Integer [x] (emits x))) ; reader puts Integers in metadata

#?(:clj
   (defmethod emit-constant* Double [x]
     (let [x (double x)]
       (cond (Double/isNaN x)
             (emits "NaN")

             (Double/isInfinite x)
             (emits (if (pos? x) "Infinity" "-Infinity"))

             :else (emits x))))
   :cljs
   (defmethod emit-constant* js/Number [x]
     (cond (js/isNaN x)
           (emits "NaN")

           (not (js/isFinite x))
           (emits (if (pos? x) "Infinity" "-Infinity"))

           (and (zero? x) (neg? (/ x)))
           (emits "(-0)")

           :else (emits "(" x ")"))))

#?(:clj
   (defmethod emit-constant* BigDecimal [x] (emits (.doubleValue ^BigDecimal x))))

#?(:clj
   (defmethod emit-constant* clojure.lang.BigInt [x] (emits (.doubleValue ^clojure.lang.BigInt x))))

(defmethod emit-constant* #?(:clj String :cljs js/String) [x]
  (emits (wrap-in-double-quotes (escape-string x))))

(defmethod emit-constant* #?(:clj Boolean :cljs js/Boolean) [x] (emits (if x "true" "false")))

#?(:clj
   (defmethod emit-constant* Character [x]
     (emits (wrap-in-double-quotes (escape-char x)))))

(defmethod emit-constant* #?(:clj java.util.regex.Pattern :cljs js/RegExp) [x]
  (if (= "" (str x))
    (emits "(new RegExp(\"\"))")
    (let [[_ flags pattern] (re-find #"^(?:\(\?([idmsux]*)\))?(.*)" (str x))]
      #?(:clj  (emits \/
                 (.replaceAll (re-matcher #"/" pattern) "\\\\/")
                 \/ flags)
         :cljs (emits pattern)))))

(defn emits-keyword [kw]
  (let [ns   (namespace kw)
        name (name kw)]
    (emits (cc "new cljs.core.Keyword("))
    (emit-constant ns)
    (emits ",")
    (emit-constant name)
    (emits ",")
    (emit-constant (if ns
                     (str ns "/" name)
                     name))
    (emits ",")
    (emit-constant (hash kw))
    ;; _idx, see cljs.core/array-map-index-of: initialized in ES module output,
    ;; classic builds' keywords start without (undefined, as before)
    (emits (if *esm-emitting* ",-1)" ")"))))

(defn emits-symbol [sym]
  (let [ns     (namespace sym)
        name   (name sym)
        symstr (if-not (nil? ns)
                 (str ns "/" name)
                 name)]
    (emits (cc "new cljs.core.Symbol("))
    (emit-constant ns)
    (emits ",")
    (emit-constant name)
    (emits ",")
    (emit-constant symstr)
    (emits ",")
    (emit-constant (hash sym))
    (emits ",")
    (emit-constant nil)
    (emits ")")))

(defn- esm-constants?
  "Whether the module being emitted references keyword and symbol constants
  of the constants module (:emit-constants). cljs.core, which the constants
  module imports, keeps its own."
  []
  (and *esm-emitting*
       (-> @env/*compiler* :options :emit-constants)
       (not= 'cljs.core ana/*cljs-ns*)
       ;; the constants module has the constants of the compiled namespaces
       (not *esm-repl*)))

(defn- emit-constant-ref
  "Emits the reference to constant x of the constants table, true if it has
  one."
  [x]
  (when-let [value (and (-> @env/*compiler* :options :emit-constants)
                        (-> @env/*compiler* ::ana/constant-table x))]
    (if *esm-emitting*
      (when (esm-constants?)
        (emits (esm-ns-alias ana/constants-ns-sym) "." value)
        true)
      (do (emits "cljs.core." value)
          true))))

(defmethod emit-constant* #?(:clj clojure.lang.Keyword :cljs Keyword) [x]
  (when-not (emit-constant-ref x)
    (emits-keyword x)))

(defmethod emit-constant* #?(:clj clojure.lang.Symbol :cljs Symbol) [x]
  (when-not (emit-constant-ref x)
    (emits-symbol x)))

(defn emit-constants-comma-sep [cs]
  (fn []
    (doall
      (map-indexed (fn [i m]
                     (if (even? i)
                       (emit-constant m)
                       (emits m)))
                   (comma-sep cs)))))

(def ^:private array-map-threshold 8)

;; tagged literal support

(defn- emit-inst [inst-ms]
  (emits "new Date(" inst-ms ")"))

(defmethod emit-constant* #?(:clj java.util.Date :cljs js/Date) [^java.util.Date date]
  (emit-inst (.getTime date)))

#?(:clj
   (defmethod emit-constant* java.time.Instant [^java.time.Instant inst]
     (emit-inst (.toEpochMilli inst))))

(defmethod emit-constant* #?(:clj java.util.UUID :cljs UUID) [^java.util.UUID uuid]
  (let [uuid-str (.toString uuid)]
    (emits (cc "new cljs.core.UUID(\"") uuid-str "\", " (hash uuid-str) ")")))

(defmethod emit-constant* #?(:clj JSValue :cljs cljs.tagged-literals/JSValue) [^JSValue v]
  (let [items (.-val v)]
    (if (map? items)
      (emit-js-object items #(fn [] (emit-constant %)))
      (emit-js-array items emit-constants-comma-sep))))

#?(:clj
   (defmacro emit-wrap [env & body]
     `(let [env# ~env]
        (when (= :return (:context env#)) (emits "return "))
        ~@body
        (when-not (= :expr (:context env#)) (emitln ";")))))

(defmethod emit* :no-op [m])

(defn emit-var
  [{:keys [info env form] :as ast}]
  (if-let [const-expr (:const-expr ast)]
    (emit (assoc const-expr :env env))
    (let [{:keys [options] :as cenv} @env/*compiler*
          var-name (:name info)
          info (if (= (namespace var-name) "js")
                 (let [js-module-name (get-in cenv [:js-module-index (name var-name) :name])]
                   (or js-module-name (name var-name)))
                 info)]
      ;; We need a way to write bindings out to source maps and javascript
      ;; without getting wrapped in an emit-wrap calls, otherwise we get
      ;; e.g. (function greet(return x, return y) {}).
      (cond
        (:binding-form? ast)
        ;; Emit the arg map so shadowing is properly handled when munging
        ;; (prevents duplicate fn-param-names)
        (emits (munge ast))

        ;; ES modules are never loaded by the goog debug loader
        (and *esm-emitting* (= 'js/COMPILED var-name))
        (emit-wrap env (emits "true"))

        ;; a compile time constant, bundlers eliminate dead code depending on
        ;; it, set with :closure-defines {goog.DEBUG false}
        (and *esm-emitting* (contains? '#{goog/DEBUG js/goog.DEBUG} var-name))
        (when-not (= :statement (:context env))
          (emit-wrap env
            (emits (pr-str (get-in options [:closure-defines "goog.DEBUG"] true)))))

        ;; a direct eval in a module defeats tree shaking and renaming of all
        ;; of the module's bindings
        (and *esm-emitting* (= 'js/eval var-name))
        (when-not (= :statement (:context env))
          (emit-wrap env (emits "(0,eval)")))

        ;; namespaces accessed as JavaScript globals, i.e. js/my.ns.foo
        (and *esm-emitting* (= "js" (namespace var-name)) (esm-js-ns-ref (name var-name)))
        (when-not (= :statement (:context env))
          (emit-wrap env (emits (esm-js-ns-ref (name var-name)))))

        :else
        (when-not (= :statement (:context env))
          (let [reserved (cond-> js-reserved
                           (and (es5>= (:language-out options))
                                ;; we can skip munging things like `my.ns.default`
                                ;; but not standalone `default` variable names
                                ;; as they're not valid ES5 - Antonio
                                (some? (namespace var-name)))
                           (set/difference ana/es5-allowed))
                js-module (get-in cenv [:js-namespaces (or (namespace var-name) (name var-name))])
                info (cond-> info
                       (not= form 'js/-Infinity) (munge reserved))]
            (emit-wrap env
              (case (:module-type js-module)
                ;; Closure exports CJS exports through default property
                :commonjs
                (if (namespace var-name)
                  (emits (munge (namespace var-name) reserved) "[\"default\"]." (munge (name var-name) reserved))
                  (emits (munge (name var-name) reserved) "[\"default\"]"))

                ;; Emit bracket notation for default prop access instead of dot notation
                :es6
                (if (and (namespace var-name) (= "default" (name var-name)))
                  (emits (munge (namespace var-name) reserved) "[\"default\"]")
                  (emits info))

                (emits info)))))))))

(defmethod emit* :var [expr] (emit-var expr))
(defmethod emit* :binding [expr] (emit-var expr))
(defmethod emit* :js-var [expr] (emit-var expr))
(defmethod emit* :local [expr] (emit-var expr))

(defmethod emit* :the-var
  [{:keys [env var sym meta] :as arg}]
  {:pre [(ana/ast? sym) (ana/ast? meta)]}
  (let [{:keys [name]} (:info var)]
    (emit-wrap env
      (emits (cc "new cljs.core.Var(function(){return ") (munge name) ";},"
        sym "," meta ")"))))

(defn emit-with-meta [expr meta]
  (emits (cc "cljs.core.with_meta(") expr "," meta ")"))

(defmethod emit* :with-meta
  [{:keys [expr meta env]}]
  (emit-wrap env
    (emit-with-meta expr meta)))

(defn distinct-keys? [keys]
  (let [keys (map ana/unwrap-quote keys)]
    (and (every? #(= (:op %) :const) keys)
         (= (count (into #{} keys)) (count keys)))))

(defn obj-map-key [x]
  (if (keyword? x)
    (str \" "\\uFDD0" \'
      (if (namespace x)
        (str (namespace x) "/") "")
      (name x)
      \")
    (str \" x \")))

(defn emit-obj-map [str-keys vals comma-sep distinct-keys?]
  (if (zero? (count str-keys))
    (emits (cc "cljs.core.ObjMap.EMPTY"))
    (emits (cc "cljs.core.ObjMap.fromObject([") (comma-sep str-keys) "], {"
      (comma-sep (map (fn [k v] (str k ":" (emit-str v))) str-keys vals))
      "})")))

(defn emit-lite-map [keys vals comma-sep distinct-keys?]
  (if (zero? (count keys))
    (emits (cc "cljs.core.HashMapLite.EMPTY"))
    (emits (cc "cljs.core.HashMapLite.fromArrays([") (comma-sep keys) "], [" (comma-sep vals) "])")))

(defn emit-map [keys vals comma-sep distinct-keys?]
  (cond
    (zero? (count keys))
    (emits (cc "cljs.core.PersistentArrayMap.EMPTY"))

    (<= (count keys) array-map-threshold)
    (if (distinct-keys? keys)
      (emits (cc "new cljs.core.PersistentArrayMap(null, ") (count keys) ", ["
        (comma-sep (interleave keys vals))
        "], null)")
      (emits (cc "cljs.core.PersistentArrayMap.createAsIfByAssoc([")
        (comma-sep (interleave keys vals))
        "])"))

    :else
    (emits (cc "cljs.core.PersistentHashMap.fromArrays([")
      (comma-sep keys)
      "],["
      (comma-sep vals)
      "])")))

(defmethod emit* :map
  [{:keys [env form keys vals]}]
  (emit-wrap env
    (if (ana/lite-mode?)
      (let [form-keys (clojure.core/keys form)]
        (if (every? #(or (string? %) (keyword? %)) form-keys)
          (emit-obj-map (map obj-map-key form-keys) vals comma-sep distinct-keys?)
          (emit-lite-map keys vals comma-sep distinct-keys?)))
      (emit-map keys vals comma-sep distinct-keys?))))

(defn emit-list [items comma-sep]
  (if (empty? items)
    (emits (cc "cljs.core.List.EMPTY"))
    (emits (cc "cljs.core.list(") (comma-sep items) ")")))

(defn emit-vector [items comma-sep]
  (if (empty? items)
    (emits (cc "cljs.core.PersistentVector.EMPTY"))
    (let [cnt (count items)]
      (if (< cnt 32)
        (emits (cc "new cljs.core.PersistentVector(null, ") cnt
          (cc ", 5, cljs.core.PersistentVector.EMPTY_NODE, [")  (comma-sep items) "], null)")
        (emits (cc "cljs.core.PersistentVector.fromArray([") (comma-sep items) "], true)")))))

(defn emit-lite-vector [items comma-sep]
  (if (empty? items)
    (emits (cc "cljs.core.VectorLite.EMPTY"))
    (emits (cc "new cljs.core.VectorLite(null, [") (comma-sep items) "], null)")))

(defmethod emit* :vector
  [{:keys [items env]}]
  (emit-wrap env
    (if (ana/lite-mode?)
      (emit-lite-vector items comma-sep)
      (emit-vector items comma-sep))))

(defn distinct-constants? [items]
  (let [items (map ana/unwrap-quote items)]
    (and (every? #(= (:op %) :const) items)
         (= (count (into #{} items)) (count items)))))

(defn- checked-set-literals?
  "Whether set literals of non-constant items throw on duplicates (CLJS-3415),
  :checked-set-literals false keeps the earlier behavior: duplicates collapse."
  []
  (not (false? (get-in @env/*compiler* [:options :checked-set-literals]))))

(defn emit-set [items comma-sep distinct-constants?]
  (cond
    (empty? items)
    (emits (cc "cljs.core.PersistentHashSet.EMPTY"))

    (distinct-constants? items)
    (emits (cc "new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, ") (count items) ", ["
      (comma-sep (interleave items (repeat "null"))) "], null), null)")

    (checked-set-literals?)
    (emits (cc "cljs.core.PersistentHashSet.createWithCheck([") (comma-sep items) "])")

    :else (emits (cc "cljs.core.PersistentHashSet.createAsIfByAssoc([") (comma-sep items) "])")))

(defn emit-lite-set [items comma-sep distinct-constants?]
  (cond
    (empty? items)
    (emits (cc "cljs.core.SetLite.EMPTY"))

    (distinct-constants? items)
    (emits (cc "cljs.core.set_lite([") (comma-sep items) "])")

    (checked-set-literals?)
    (emits (cc "cljs.core.set_lite_check([") (comma-sep items) "])")

    :else (emits (cc "cljs.core.set_lite([") (comma-sep items) "])")))

(defmethod emit* :set
  [{:keys [items env]}]
  (emit-wrap env
    (if (ana/lite-mode?)
      (emit-lite-set items comma-sep distinct-constants?)
      (emit-set items comma-sep distinct-constants?))))

(defn emit-js-object [items emit-js-object-val]
  (emits "({")
  (when-let [items (seq items)]
    (let [[[k v] & r] items]
      (emits "\"" (name k) "\": " (emit-js-object-val v))
      (doseq [[k v] r]
        (emits ", \"" (name k) "\": " (emit-js-object-val v)))))
  (emits "})"))

(defn emit-js-array [items comma-sep]
  (emits "[" (comma-sep items) "]"))

(defmethod emit* :js-object
  [{:keys [keys vals env]}]
  (emit-wrap env
    (emit-js-object (map vector keys vals) identity)))

(defmethod emit* :js-array
  [{:keys [items env]}]
  (emit-wrap env
    (emit-js-array items comma-sep)))

(defn emit-record-value
  [ns name items]
  (emits ns ".map__GT_" name "(" items ")"))

(defmethod emit* :quote
  [{:keys [expr]}]
  (emit expr))

(defmethod emit* :const
  [{:keys [form env]}]
  (when-not (= :statement (:context env))
    (emit-wrap env (emit-constant form))))

(defn truthy-constant? [expr]
  (let [{:keys [op form const-expr]} (ana/unwrap-quote expr)]
    (or (and (= op :const)
             form
             (not (or (and (string? form) (= form ""))
                      (and (number? form) (zero? form)))))
        (and (some? const-expr)
             (truthy-constant? const-expr)))))

(defn falsey-constant? [expr]
  (let [{:keys [op form const-expr]} (ana/unwrap-quote expr)]
    (or (and (= op :const)
             (or (false? form) (nil? form)))
        (and (some? const-expr)
             (falsey-constant? const-expr)))))

(defn safe-test? [env e]
  (let [tag (ana/infer-tag env e)]
    (or ('#{boolean seq} (ana/js-prim-ctor->tag tag tag))
        (truthy-constant? e))))

(defmethod emit* :if
  [{:keys [test then else env unchecked]}]
  (let [context (:context env)
        checked (not (or unchecked (safe-test? env test)))]
    (cond
      (truthy-constant? test) (emitln then)
      (falsey-constant? test) (emitln else)
      :else
      (if (= :expr context)
        (emits "(" (when checked (cc "cljs.core.truth_")) "(" test ")?" then ":" else ")")
        (do
          (if checked
            (emitln (cc "if(cljs.core.truth_(") test ")){")
            (emitln "if(" test "){"))
          (emitln then "} else {")
          (emitln else "}"))))))

(defn iife-open [{:keys [async]}]
  (str (when async "(await ") "(" (when async "async ") "function (){"))

(defn iife-close [{:keys [async]}]
  (str "})()" (when async ")")))

(defmethod emit* :case
  [{v :test :keys [nodes default env]}]
  (when (= (:context env) :expr)
    (emitln (iife-open env)))
  (let [gs (gensym "caseval__")]
    (when (= :expr (:context env))
      (emitln "var " gs ";"))
    (emitln "switch (" v ") {")
    (doseq [{ts :tests {:keys [then]} :then} nodes]
      (doseq [test (map :test ts)]
        (emitln "case " test ":"))
      (if (= :expr (:context env))
        (emitln gs "=" then)
        (emitln then))
      (emitln "break;"))
    (when default
      (emitln "default:")
      (if (= :expr (:context env))
        (emitln gs "=" default)
        (emitln default)))
    (emitln "}")
    (when (= :expr (:context env))
      (emitln "return " gs ";"
              (iife-close env)))))

(defmethod emit* :throw
  [{throw :exception :keys [env]}]
  (if (= :expr (:context env))
    (emits (iife-open env) "throw " throw (iife-close env))
    (emitln "throw " throw ";")))

(def base-types
  #{"null" "*" "...*"
    "boolean" "Boolean"
    "string" "String"
    "number" "Number"
    "array" "Array"
    "object" "Object"
    "RegExp"
    "Date"})

(def mapped-types
  {"nil" "null"})

(defn resolve-type [env ^String t]
  (cond
    (get base-types t) t

    (get mapped-types t) (get mapped-types t)

    #?(:clj  (.startsWith t "!")
       :cljs (gstring/startsWith t "!"))
    (str "!" (resolve-type env (subs t 1)))

    #?(:clj  (.startsWith t "{")
       :cljs (gstring/startsWith t "{")) t

    #?(:clj  (.startsWith t "function")
       :cljs (gstring/startsWith t "function"))
    (let [idx         (.lastIndexOf t ":")
          [fstr rstr] (if-not (== -1 idx)
                        [(subs t 0 idx) (subs t (inc idx) (count t))]
                        [t nil])
          ret-t       (when rstr (resolve-type env rstr))
          axstr       (subs fstr 9 (dec (count fstr)))
          args-ts     (when-not (string/blank? axstr)
                        (map (comp #(resolve-type env %) string/trim)
                          (string/split axstr #",")))]
      (cond-> (str "function(" (string/join "," args-ts) ")")
        ret-t (str ":" ret-t)))

    #?(:clj  (.endsWith t "=")
       :cljs (gstring/endsWith t "="))
    (str (resolve-type env (subs t 0 (dec (count t)))) "=")

    :else
    (munge (str (:name (ana/resolve-var env (symbol t)))))))

(defn resolve-types [env ts]
  (let [ts (-> ts string/trim (subs 1 (dec (count ts))))
        xs (string/split ts #"\|")]
    (str "{" (string/join "|" (map #(resolve-type env %) xs)) "}")))

(defn munge-param-return [env line]
  (cond
    (re-find #"@param" line)
    (let [[p ts n & xs] (map string/trim
                          (string/split (string/trim line) #" "))]
      (if (and (= "@param" p)
               ts #?(:clj  (.startsWith ^String ts "{")
                     :cljs (gstring/startsWith ts "{")))
        (string/join " " (concat [p (resolve-types env ts) (munge n)] xs))
        line))

    (re-find #"@return" line)
    (let [[p ts & xs] (map string/trim
                        (string/split (string/trim line) #" "))]
      (if (and (= "@return" p)
               ts #?(:clj  (.startsWith ^String ts "{")
                     :cljs (gstring/startsWith ts "{")))
        (string/join " " (concat [p (resolve-types env ts)] xs))
        line))

    :else line))

(defn checking-types? []
  (#{:error :warning}
    (get-in @env/*compiler*
      [:options :closure-warnings :check-types])))

(defn emit-comment
  "Emit a nicely formatted comment string."
  ([doc jsdoc]
    (emit-comment nil doc jsdoc))
  ([env doc jsdoc]
   (let [docs (when doc [doc])
         docs (if jsdoc (concat docs jsdoc) docs)
         docs (remove nil? docs)]
     (letfn [(print-comment-lines [e]
               (let [[x & ys]
                     (map #(if (checking-types?) (munge-param-return env %) %)
                       (string/split-lines e))]
                 (emitln " * " (string/replace x "*/" "* /"))
                 (doseq [next-line ys]
                   (emitln " * "
                     (-> next-line
                       (string/replace #"^   " "")
                       (string/replace "*/" "* /"))))))]
       (when (seq docs)
         (emitln "/**")
         (doseq [e docs]
           (when e
             (print-comment-lines e)))
         (emitln " */"))))))

(defn valid-define-value? [x]
  (or (string? x)
      (true? x)
      (false? x)
      (number? x)))

(defn get-define [mname jsdoc]
  (let [opts (get @env/*compiler* :options)]
    (and (some #?(:clj #(.startsWith ^String % "@define")
                  :cljs #(gstring/startsWith % "@define"))
           jsdoc)
         opts
         (or (= (:optimizations opts) :none)
             (esm-mode? opts))
         (let [define (get-in opts [:closure-defines (str mname)])]
           (when (valid-define-value? define)
             (pr-str define))))))

(defmethod emit* :def
  [{:keys [name var init env doc goog-define jsdoc export test var-ast]}]
  ;; Under ESM a var without init, i.e. (declare ^:dynamic *x*), still needs
  ;; a binding other namespaces can import and set! with binding
  (when (and *esm-emitting* (not *esm-repl*) (nil? init) (not (:def-emits-var env)))
    (swap! (:hoist *esm*) conj (esm-def! name)))
  ;; We only want to emit if an init is supplied, this is to avoid dead code
  ;; elimination issues. The REPL is the exception to this rule.
  (when (or init (:def-emits-var env))
    (let [mname (binding [*esm-emitting* false] (munge name))
          ;; at the REPL var is the namespace view's property, see *esm-repl*
          esm-local (when (and *esm-emitting* (not *esm-repl*)) (esm-def! name))
          esm-var-decl? (and esm-local
                             (not *esm-in-fn*)
                             (= :statement (:context env))
                             (not (:def-emits-var env)))]
      (when (and esm-local (not esm-var-decl?))
        (swap! (:hoist *esm*) conj esm-local))
      (emit-comment env doc (concat
                              (when goog-define
                                [(str "@define {" goog-define "}")])
                              jsdoc (:jsdoc init)))
      (when (= :return (:context env))
        (emitln "return ("))
      (when (:def-emits-var env)
        (emitln (iife-open env)))
      (when esm-var-decl?
        (emits "var "))
      (emits var)
      (when init
        (emits " = "
          (if-let [define (get-define mname jsdoc)]
            define
            (if (and goog-define *esm-emitting* (= :invoke (:op init)))
              ;; no override, the default value of (goog/define name default)
              (second (:args init))
              init))))
      (when (:def-emits-var env)
        (emitln "; return (")
        (emits (merge
                 {:op  :the-var
                  :env (assoc env :context :expr)}
                 var-ast))
        (emitln ");"
                (iife-close env)))
      (when (= :return (:context env))
        (emitln ")"))
      ;; NOTE: JavaScriptCore does not like this under advanced compilation
      ;; this change was primarily for REPL interactions - David
      ;(emits " = (typeof " mname " != 'undefined') ? " mname " : undefined")
      (when-not (= :expr (:context env)) (emitln ";"))
      (when export
        (if *esm-emitting*
          ;; also a global for scripts calling into the build, i.e. my.app.init()
          (emitln (munge 'goog/exportSymbol) "('" (binding [*esm-emitting* false] (munge export)) "', " var ");")
          (emitln "goog.exportSymbol('" (munge export) "', " mname ");")))
      (when (and ana/*load-tests* test)
        (when (= :expr (:context env))
          (emitln ";"))
        (emitln var ".cljs$lang$test = " test ";")))))

(defn emit-apply-to
  [{:keys [name params env]}]
  (let [arglist (gensym "arglist__")
        delegate-name (str (munge name) "__delegate")]
    (emitln "(function (" arglist "){")
    (doseq [[i param] (map-indexed vector (drop-last 2 params))]
      (emits "var ")
      (emit param)
      (emits (cc " = cljs.core.first("))
      (emitln arglist ");")
      (emitln arglist (cc " = cljs.core.next(") arglist ");"))
    (if (< 1 (count params))
      (do
        (emits "var ")
        (emit (last (butlast params)))
        (emitln (cc " = cljs.core.first(") arglist ");")
        (emits "var ")
        (emit (last params))
        (emitln (cc " = cljs.core.rest(") arglist ");")
        (emits "return " delegate-name "(")
        (doseq [param params]
          (emit param)
          (when-not (= param (last params)) (emits ",")))
        (emitln ");"))
      (do
        (emits "var ")
        (emit (last params))
        (emitln (cc " = cljs.core.seq(") arglist ");")
        (emits "return " delegate-name "(")
        (doseq [param params]
          (emit param)
          (when-not (= param (last params)) (emits ",")))
        (emitln ");")))
    (emits "})")))

(defn emit-fn-params [params]
  (doseq [param params]
    (emit param)
    ; Avoid extraneous comma (function greet(x, y, z,)
    (when-not (= param (last params))
      (emits ","))))

(defn emit-fn-body
  "Emits the body of a fn method, declaring the locals its lets in expression
  position hoisted (see emit-let) at its end."
  [expr recurs]
  (binding [*hoisted-locals* (atom [])]
    (when recurs (emitln "while(true){"))
    (emits expr)
    (when recurs
      (emitln "break;")
      (emitln "}"))
    (when-let [locals (seq @*hoisted-locals*)]
      (emitln "var " (comma-sep locals) ";"))))

(defn emit-fn-method
  [{expr :body :keys [type name params env recurs]}]
  (let [async (:async env)]
    (emit-wrap env
               (emits "(" (when async "async ") "function " (munge name) "(")
               (emit-fn-params params)
               (emitln "){")
               (when type
                 (emitln "var self__ = this;"))
               (emit-fn-body expr recurs)
               (emits "})"))))

(defn emit-arguments-to-array
  "Emit code that copies function arguments into an array starting at an index.
  Returns name of var holding the array."
  [startslice]
  (assert (and (>= startslice 0) (integer? startslice)))
  (let [mname (munge (gensym))
        i (str mname "__i")
        a (str mname "__a")]
    (emitln "var " i " = 0, "
                   a " = new Array(arguments.length -  " startslice ");")
    (emitln "while (" i " < " a ".length) {"
      a "[" i "] = arguments[" i " + " startslice "]; ++" i ";}")
    a))

(defn emit-variadic-fn-method
  [{expr :body max-fixed-arity :fixed-arity variadic :variadic? :keys [type name params env recurs] :as f}]
  (emit-wrap env
    (let [name (or name (gensym))
          mname (munge name)
          delegate-name (str mname "__delegate")
          async (:async env)]
      (emitln (when *esm-emitting* "/*@__PURE__*/") "(function() { ")
      (emits "var " delegate-name " = " (when async "async ") "function (")
      (doseq [param params]
        (emit param)
        (when-not (= param (last params)) (emits ",")))
      (emitln "){")
      (when type
        (emitln "var self__ = this;"))
      (emit-fn-body expr recurs)
      (emitln "};")

      (emitln "var " mname " = " (when async "async ")  "function ("
              (comma-sep
               (if variadic
                 (concat (butlast params) ['var_args])
                 params)) "){")
      (when type
        (emitln "var self__ = this;"))
      (when variadic
        (emits "var ")
        (emit (last params))
        (emitln " = null;")
        (emitln "if (arguments.length > " (dec (count params)) ") {")
        (let [a (emit-arguments-to-array (dec (count params)))]
          (emitln "  " (last params) (cc " = new cljs.core.IndexedSeq(") a ",0,null);"))
        (emitln "} "))
      (emits "return " delegate-name ".call(this,")
      (doseq [param params]
        (emit param)
        (when-not (= param (last params)) (emits ",")))
      (emits ");")
      (emitln "};")

      (emitln mname ".cljs$lang$maxFixedArity = " max-fixed-arity ";")
      (emits mname ".cljs$lang$applyTo = ")
      (emit-apply-to (assoc f :name name))
      (emitln ";")
      (emitln mname ".cljs$core$IFn$_invoke$arity$variadic = " delegate-name ";")
      (emitln "return " mname ";")
      (emitln "})()"))))

(declare ast-children)

(defn- captured-locals
  "The locals of bindings (loop locals, which a fn created in a loop captures
  by value) that fn-ast may refer to: by name, over-approximated with every
  symbol of its form and the js* code it contains."
  [fn-ast bindings]
  (when (seq bindings)
    (let [codes (volatile! [])
          names (loop [names (into #{} (filter symbol?) (tree-seq coll? seq (:form fn-ast)))
                       nodes [fn-ast]]
                  (if-let [node (first nodes)]
                    (recur (case (:op node)
                             :local (conj names (:name node))
                             :js (do (vswap! codes conj (str (:code node) (apply str (:segs node))))
                                     names)
                             names)
                           (into (rest nodes) (ast-children node)))
                    names))]
      (filter (fn [binding]
                (or (contains? names (:name binding))
                    (let [munged (str (munge binding))]
                      (some #(string/includes? % munged) @codes))))
              bindings))))

(defmethod emit* :fn
  [{variadic :variadic? :keys [name named? env methods max-fixed-arity recur-frames in-loop loop-lets] :as ast}]
  ;;fn statements get erased, serve no purpose and can pollute scope if named
  (when-not (= :statement (:context env))
    (let [;; Under ESM the name def gives a fn is the module binding of its var:
          ;; the function expression's name would shadow it in the fn's body,
          ;; where calls (as the dispatcher of a variadic defn) must see the
          ;; value set! (and with-redefs) assign. The engine names the fn
          ;; after the binding it's assigned to.
          name (when-not (and *esm-emitting* (not named?)) name)
          recur-params (mapcat :params (filter #(and % @(:flag %)) recur-frames))
          loop-locals
          (->> (concat recur-params
                 ;; need to capture locals only if in recur fn or loop
                 (when (or in-loop (seq recur-params))
                   (mapcat :params loop-lets)))
               (captured-locals ast)
               (map munge)
               seq)
          async (:async env)]
      (when loop-locals
        (when (= :return (:context env))
            (emits "return "))
        (emitln "((function (" (comma-sep (map munge loop-locals)) "){")
        (when-not (= :return (:context env))
          (emits "return ")))
      (if (= 1 (count methods))
        (if variadic
          (emit-variadic-fn-method (assoc (first methods) :name name))
          (emit-fn-method (assoc (first methods) :name name)))
        (let [name (or name (gensym))
              mname (munge name)
              maxparams (apply max-key count (map :params methods))
              mmap (into {}
                     (map (fn [method]
                            [(munge (symbol (str mname "__" (count (:params method)))))
                             method])
                          methods))
              ms (sort-by #(-> % second :params count) (seq mmap))]
          (when (= :return (:context env))
            (emits "return "))
          ;; only defines and returns the fn, marked pure for bundlers
          (emitln (when *esm-emitting* "/*@__PURE__*/") "(function() {")
          (emitln "var " mname " = null;")
          (doseq [[n meth] ms]
            (emits "var " n " = ")
            (if (:variadic? meth)
              (emit-variadic-fn-method meth)
              (emit-fn-method meth))
            (emitln ";"))
          (emitln mname " = " (when async "async ") "function("
                  (comma-sep (if variadic
                               (concat (butlast maxparams) ['var_args])
                               maxparams)) "){")
          (when variadic
            (emits "var ")
            (emit (last maxparams))
            (emitln " = var_args;"))
          (emitln "switch(arguments.length){")
          (doseq [[n meth] ms]
            (if (:variadic? meth)
              (do (emitln "default:")
                  (let [restarg (munge (gensym))]
                    (emitln "var " restarg " = null;")
                    (emitln "if (arguments.length > " max-fixed-arity ") {")
                    (let [a (emit-arguments-to-array max-fixed-arity)]
                      (emitln restarg (cc " = new cljs.core.IndexedSeq(") a ",0,null);"))
                    (emitln "}")
                    (emitln "return " n ".cljs$core$IFn$_invoke$arity$variadic("
                            (comma-sep (butlast maxparams))
                            (when (> (count maxparams) 1) ", ")
                            restarg ");")))
              (let [pcnt (count (:params meth))]
                (emitln "case " pcnt ":")
                (emitln "return " n ".call(this" (if (zero? pcnt) nil
                                                     (list "," (comma-sep (take pcnt maxparams)))) ");"))))
          (emitln "}")
          (let [arg-count-js (if (= 'self__ (-> ms first val :params first :name))
                               "(arguments.length - 1)"
                               "arguments.length")]
            (emitln "throw(new Error('Invalid arity: ' + " arg-count-js "));"))
          (emitln "};")
          (when variadic
            (emitln mname ".cljs$lang$maxFixedArity = " max-fixed-arity ";")
            (emitln mname ".cljs$lang$applyTo = " (some #(let [[n m] %] (when (:variadic? m) n)) ms) ".cljs$lang$applyTo;"))
          (doseq [[n meth] ms]
            (let [c (count (:params meth))]
              (if (:variadic? meth)
                (emitln mname ".cljs$core$IFn$_invoke$arity$variadic = " n ".cljs$core$IFn$_invoke$arity$variadic;")
                (emitln mname ".cljs$core$IFn$_invoke$arity$" c " = " n ";"))))
          (emitln "return " mname ";")
          (emitln "})()")))
      (when loop-locals
        (emitln ";})(" (comma-sep loop-locals) "))")))))

(def ^:private expr-ops
  "Ops analyzing their children in expression context whatever their own, they
  emit as JavaScript expressions in any context."
  #{:var :js-var :local :the-var :with-meta :map :vector :set :js-object
    :js-array :const :invoke :new :set! :host-field :host-call})

(defn- js-code [{:keys [code segs]}]
  (or code (apply str segs)))

(defn- js-expression?
  "Whether the code of a js* form is an expression in a statement position too,
  conservatively."
  [ast]
  (let [code (js-code ast)]
    (and (not (string/blank? code))
         (not (re-find #"[;{}]|/\*|//" code))
         (not (re-find #"^\s*(?:debugger|if|for|while|do|var|let|const|return|throw|try|switch|break|continue|function|class|import|export)\b" code)))))

(defn- ast-children [ast]
  (mapcat #(let [c (get ast %)] (if (vector? c) c (when c [c]))) (:children ast)))

(defn- uses-fn-scope?
  "Whether ast reads this or arguments outside of the fns it contains: moving it
  out of an IIFE changes what they refer to."
  [ast]
  (case (:op ast)
    :fn false
    :js (boolean (re-find #"\bthis\b|\barguments\b" (js-code ast)))
    (:var :js-var) (contains? #{"this" "arguments"} (some-> ast :info :name name))
    (boolean (some uses-fn-scope? (ast-children ast)))))

(defn- ->expr
  "Returns ast, analyzed in any context, as an AST emitting a JavaScript
  expression of its value without wrapping it in an IIFE at its top, nil if it
  can't. Lets keep emitting IIFEs where emit-let can't hoist their locals."
  [ast]
  (let [env (assoc (:env ast) :context :expr)]
    (case (:op ast)
      :if (let [then (->expr (:then ast))
                else (->expr (:else ast))]
            (when (and then else)
              (assoc ast :env env :then then :else else)))
      :do (let [statements (mapv ->expr (:statements ast))
                ret        (->expr (:ret ast))]
            (when (and ret (every? some? statements))
              (assoc ast :env env :statements statements :ret ret ::expr true)))
      (:let :throw) (assoc ast :env env)
      ;; the quoted constant has the quote's context
      :quote (assoc ast :env env :expr (assoc-in (:expr ast) [:env :context] :expr))
      ;; the method of a single arity fn has the fn's context
      :fn (assoc ast :env env :methods (mapv #(assoc-in % [:env :context] :expr) (:methods ast)))
      :js (when (and (js-expression? ast)
                     (not (re-find #"^\s*/\*" (js-code ast))))
            (assoc ast :env env))
      (when (contains? expr-ops (:op ast))
        (assoc ast :env env)))))

(defn- statement-tail?
  "Whether ast, in expression position, needs statements: a loop, case, try or
  letfn, which emit an IIFE there, in a tail position of it."
  [ast]
  (case (:op ast)
    (:loop :case :try :letfn) true
    :if (or (statement-tail? (:then ast)) (statement-tail? (:else ast)))
    :do (statement-tail? (:ret ast))
    :let (statement-tail? (:body ast))
    false))

(defn- assign-tails
  "Returns ast, analyzed in any context, as an AST of statements assigning its
  value to the local named target (a munged name) in each of its tail
  positions, nil if it can't."
  [ast target]
  (let [env  (assoc (:env ast) :context :statement)
        tail #(assign-tails % target)]
    (case (:op ast)
      :if (let [then (tail (:then ast))
                else (tail (:else ast))]
            (when (and then else)
              (assoc ast :env env :then then :else else)))
      :do (when-let [ret (tail (:ret ast))]
            (assoc ast :env env :ret ret))
      (:let :loop :letfn) (when-let [body (tail (:body ast))]
                            (assoc ast :env env :body body))
      :case (let [nodes   (mapv #(when-let [then (tail (-> % :then :then))]
                                   (assoc-in % [:then :then] then))
                                (:nodes ast))
                  default (some-> (:default ast) tail)]
              (when (and (every? some? nodes)
                         (or default (nil? (:default ast))))
                (assoc ast :env env :nodes nodes :default default)))
      :try (let [body  (tail (:body ast))
                 catch (some-> (:catch ast) tail)]
             (when (and body (or catch (nil? (:catch ast))))
               (assoc ast :env env :body body :catch catch)))
      :throw (assoc ast :env env)
      :recur ast
      (when-let [val (->expr ast)]
        {:op ::assign :env env :target target :val val :children [:val]}))))

(defmethod emit* ::assign
  [{:keys [target val]}]
  (emitln target " = " val ";"))

(defmethod emit* :do
  [{:keys [statements ret env] :as ast}]
  (let [context (:context env)
        expr    (when (and (seq statements) (= :expr context))
                  (if (::expr ast) ast (->expr ast)))]
    (if expr
      ;; a comma expression
      (do (emits "(")
          (doseq [s (:statements expr)]
            (emits s ", "))
          (emits (:ret expr) ")"))
      (do
        (when (and (seq statements) (= :expr context)) (emitln (iife-open env)))
        (doseq [s statements] (emitln s))
        (emit ret)
        (when (and (seq statements) (= :expr context)) (emitln (iife-close env)))))))

(defmethod emit* :try
  [{try :body :keys [env catch name finally]}]
  (let [context (:context env)]
    (if (or name finally)
      (do
        (when (= :expr context)
          (emits (iife-open env)))
        (emits "try{" try "}")
        (when name
          (emits "catch (" (munge name) "){" catch "}"))
        (when finally
          (assert (not= :const (:op (ana/unwrap-quote finally))) "finally block cannot contain constant")
          (emits "finally {" finally "}"))
        (when (= :expr context)
          (emits (iife-close env))))
      (emits try))))

(defn- emit-hoisted-let
  "Emits a let in expression position as a comma expression assigning its
  locals, which the enclosing fn body declares. Like those of lets in statement
  position, they're renamed apart from the fn's other locals; fns created in
  loops capture them by value (loop-lets), as they do the locals of lets in
  statement position."
  [bindings expr]
  (binding [*lexical-renames*
            (into *lexical-renames*
              (map (fn [binding]
                     [(hash-scope binding) (gensym (str (:name binding) "-"))])
                   bindings))]
    (emits "(")
    (doseq [{:keys [init] :as binding} bindings]
      (swap! *hoisted-locals* conj (munge binding))
      (emit binding)
      (emits " = " init ", "))
    (emits expr ")")))

(defn- emit-let-scope
  [{expr :body :keys [bindings env]} is-loop]
  (let [context (:context env)]
    (when (= :expr context)
      (emits (iife-open env)))
    (binding [*lexical-renames*
              (into *lexical-renames*
                (when (= :statement context)
                  (map
                    (fn [binding]
                      (let [name (:name binding)]
                        (vector (hash-scope binding)
                          (gensym (str name "-")))))
                    bindings)))]
      (doseq [{:keys [init] :as binding} bindings]
        (emits "var ")
        (emit binding) ; Binding will be treated as a var
        ;; an init needing statements assigns the binding from them, rather
        ;; than from an IIFE, in a fn body: at a namespace's top level the
        ;; statements would run in module scope, its locals module variables
        ;; (a loop over them is much slower than over an IIFE's locals)
        (if-let [assign (when (and *hoisted-locals*
                                   (statement-tail? init)
                                   (not (uses-fn-scope? init)))
                          (assign-tails init (munge binding)))]
          (do (emitln ";")
              (emit assign))
          (emitln " = " init ";")))
      (when is-loop (emitln "while(true){"))
      (emits expr)
      (when is-loop
        (emitln "break;")
        (emitln "}")))
    (when (= :expr context) (emits (iife-close env)))))

(defn emit-let
  [{expr :body :keys [bindings env] :as ast} is-loop]
  (let [context (:context env)
        hoisted (when (and (= :expr context) (not is-loop) *hoisted-locals*
                           (not (uses-fn-scope? ast)))
                  (->expr expr))]
    (if hoisted
      (emit-hoisted-let bindings hoisted)
      (emit-let-scope ast is-loop))))

(defmethod emit* :let [ast]
  (emit-let ast false))

(defmethod emit* :loop [ast]
  (emit-let ast true))

(defmethod emit* :recur
  [{:keys [frame exprs env]}]
  (let [temps (vec (take (count exprs) (repeatedly gensym)))
        params (:params frame)]
    (dotimes [i (count exprs)]
      (emitln "var " (temps i) " = " (exprs i) ";"))
    (dotimes [i (count exprs)]
      (emitln (munge (params i)) " = " (temps i) ";"))
    (emitln "continue;")))

(defmethod emit* :letfn
  [{expr :body :keys [bindings env]}]
  (let [context (:context env)]
    (when (= :expr context) (emits (iife-open env)))
    (doseq [{:keys [init] :as binding} bindings]
      (emitln "var " (munge binding) " = " init ";"))
    (emits expr)
    (when (= :expr context) (emits (iife-close env)))))

(defn protocol-prefix [psym]
  (symbol (str (-> (str psym)
                 (.replace #?(:clj \. :cljs (js/RegExp. "\\." "g")) \$)
                 (.replace \/ \$))
            "$")))

(defn- emit-lookup-test
  "Emits the test whether local m has the -lookup method lookup (its property),
  shorter with optional chaining in ES module output (ES2020): methods are
  functions, so it's truthy exactly when m != null and the method != null."
  [m lookup]
  (if *esm-emitting*
    (emits "(" m "?" lookup ")")
    (emits "((" m " != null) && (" m lookup " != null))")))

(defmethod emit* :invoke
  [{f :fn :keys [args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 (not (:dynamic info))
                 (:fn-var info))
        protocol (:protocol info)
        tag      (ana/infer-tag env (first (:args expr)))
        proto? (and protocol tag
                 (or (and ana/*cljs-static-fns* protocol (= tag 'not-native))
                     (and
                       (or ana/*cljs-static-fns*
                           (:protocol-inline env))
                       (or (= protocol tag)
                           ;; ignore new type hints for now - David
                           (and (not (set? tag))
                                (not ('#{any clj clj-or-nil clj-nil number string boolean function object array js} tag))
                                (when-let [ps (:protocols
                                                (ana/resolve-existing-var env
                                                  ;; we're just checking for protocol methods,
                                                  ;; an internal optimization, don't emit warnings
                                                  (vary-meta tag assoc ::ana/no-resolve true)))]
                                  (ps protocol)))))))
        first-arg-tag (ana/infer-tag env (first (:args expr)))
        opt-not? (and (= (:name info) 'cljs.core/not)
                      (= first-arg-tag 'boolean))
        ;; the call site dispatch below (protocol methods called at the call site
        ;; rather than through the shared fn) in ES module output only: it adds
        ;; code, classic builds keep their size
        site? (and fn? *esm-emitting*)
        ;; (get m k) of a local map and a constant or local key: m's -lookup at the
        ;; call site when it has one, see the keyword? case below
        get-lookup? (and site?
                         (= (:name info) 'cljs.core/get)
                         (<= 2 (count args) 3)
                         (= :local (:op (first args)))
                         (#{:const :local} (:op (ana/unwrap-quote (second args)))))
        ;; (nth v i) and (nth v i not-found) of a local and a constant index, as
        ;; sequential destructuring compiles to: v's -nth at the call site when it
        ;; has one, as get-lookup? (a number index, nth's own check, holds)
        ;; a local index is tested to be a number at the call site
        nth-lookup? (and site?
                         (= (:name info) 'cljs.core/nth)
                         (<= 2 (count args) 3)
                         (= :local (:op (first args)))
                         (let [i (ana/unwrap-quote (second args))]
                           (or (and (= :const (:op i)) (number? (:form i)))
                               (= :local (:op i)))))
        ;; (seq x), (first x), (next x), (count x) of a local: the protocol method these call
        ;; when x implements the protocol, at the call site when x has it, as
        ;; get-lookup?, anything else through the fn as before
        site-method (when (and site?
                               (== 1 (count args))
                               (= :local (:op (first args))))
                      ('{cljs.core/seq   ".cljs$core$ISeqable$_seq$arity$1"
                         cljs.core/first ".cljs$core$ISeq$_first$arity$1"
                         cljs.core/next  ".cljs$core$INext$_next$arity$1"
                         cljs.core/count ".cljs$core$ICounted$_count$arity$1"}
                       (:name info)))
        ;; (= x y) of a local x and a local or constant y: identical, else x's
        ;; -equiv at the call site when it has one (as = calls it), else = as before
        eq-site? (and site?
                      (= (:name info) 'cljs.core/=)
                      (== 2 (count args))
                      (= :local (:op (first args)))
                      (#{:const :local} (:op (ana/unwrap-quote (second args)))))
        ;; (--destructure-map m) of a local, as map destructuring compiles to:
        ;; anything but a seq is m itself, only seqs (keyword arguments) call it
        destructure-map? (and site?
                              (= (:name info) 'cljs.core/--destructure-map)
                              (== 1 (count args))
                              (= :local (:op (first args))))
        ;; predicates of a local through the protocol method their checks find, at
        ;; the call site: (empty? x) as ICounted's (zero? (-count x)), (not-empty x)
        ;; as (when (-seq x) x), (vector? x) and (map? x) true when x has the
        ;; protocol's method; anything else (natively extended, nil) as before
        site-pred (when (and site?
                             (== 1 (count args))
                             (= :local (:op (first args))))
                    ('{cljs.core/empty?    [".cljs$core$ICounted$_count$arity$1" :zero]
                       cljs.core/not-empty [".cljs$core$ISeqable$_seq$arity$1" :seq]
                       cljs.core/vector?   [".cljs$core$IVector$_assoc_n$arity$3" :true]
                       cljs.core/map?      [".cljs$core$IMap$_dissoc$arity$2" :true]}
                     (:name info)))
        opt-count? (and (= (:name info) 'cljs.core/count)
                        (boolean ('#{string array} first-arg-tag)))
        ns (:ns info)
        ftag (ana/infer-tag env f)
        js? (or (= ns 'js) (= ns 'Math) (:foreign info)) ;; foreign - i.e. global / Node.js library
        goog? (when ns
                (or (= ns 'goog)
                    (when-let [ns-str (str ns)]
                      (= (get (string/split ns-str #"\.") 0 nil) "goog"))
                    (not (contains? (::ana/namespaces @env/*compiler*) ns))))

        keyword? (or (= 'cljs.core/Keyword ftag)
                     (let [f (ana/unwrap-quote f)]
                       (and (= (-> f :op) :const)
                            (keyword? (-> f :form)))))
        [f variadic-invoke]
        (if fn?
          (let [arity (count args)
                variadic? (:variadic? info)
                mps (:method-params info)
                mfa (:max-fixed-arity info)]
            (cond
             ;; if only one method, no renaming needed
             (and (not variadic?)
                  (= (count mps) 1))
             [f nil]

             ;; direct dispatch to variadic case
             (and variadic? (> arity mfa))
             [(update-in f [:info]
                (fn [info]
                  (-> info
                    (assoc :name (symbol (str (munge info) ".cljs$core$IFn$_invoke$arity$variadic")))
                    ;; bypass local fn-self-name munging, we're emitting direct
                    ;; shadowing already applied
                    (update-in [:info]
                      #(-> % (dissoc :shadow) (dissoc :fn-self-name))))))
              {:max-fixed-arity mfa}]

             ;; direct dispatch to specific arity case
             :else
             (let [arities (map count mps)]
               (if (some #{arity} arities)
                 [(update-in f [:info]
                    (fn [info]
                      (-> info
                        (assoc :name (symbol (str (munge info) ".cljs$core$IFn$_invoke$arity$" arity)))
                        ;; bypass local fn-self-name munging, we're emitting direct
                        ;; shadowing already applied
                        (update-in [:info]
                          #(-> % (dissoc :shadow) (dissoc :fn-self-name)))))) nil]
                 [f nil]))))
          [f nil])]
    (emit-wrap env
      (cond
       opt-not?
       (emits "(!(" (first args) "))")

       opt-count?
       (emits "((" (first args) ").length)")

       proto?
       (let [pimpl (str (munge (protocol-prefix protocol))
                        (munge (name (:name info))) "$arity$" (count args))]
         (emits (first args) "." pimpl "(" (comma-sep (cons "null" (rest args))) ")"))

       keyword?
       (let [[m nf] args
             lookup (str ".cljs$core$ILookup$_lookup$arity$" (inc (count args)))]
         (if (and *esm-emitting*
                  (= :const (:op (ana/unwrap-quote f)))
                  (<= 1 (count args) 2)
                  (= :local (:op m)))
           ;; (:k m) of a local: its -lookup at this call site, as get would call
           ;; it, so each site's lookups are as polymorphic as its maps rather
           ;; than as all maps of the program; anything else as before
           (do (emits "(")
               (emit-lookup-test m lookup)
               (emits "?" m lookup "(null," f (when nf (list "," nf)) "):"
                      f ".cljs$core$IFn$_invoke$arity$" (count args) "(" (comma-sep args) "))"))
           (emits f ".cljs$core$IFn$_invoke$arity$" (count args) "(" (comma-sep args) ")")))

       get-lookup?
       (let [[m] args
             lookup (str ".cljs$core$ILookup$_lookup$arity$" (count args))]
         (emits "(")
         (emit-lookup-test m lookup)
         (emits "?" m lookup "(" (comma-sep (cons "null" (rest args))) "):"
                f "(" (comma-sep args) "))"))

       eq-site?
       (let [[x y] args
             equiv ".cljs$core$IEquiv$_equiv$arity$2"]
         (emits "((" x " === " y ") || (")
         (emit-lookup-test x equiv)
         (emits "?" x equiv "(null," y "):" f "(" x "," y ")))"))

       destructure-map?
       (let [[m] args
             first ".cljs$core$ISeq$_first$arity$1"]
         (emits "(")
         (emit-lookup-test m first)
         (emits "?" f "(" m "):" m ")"))

       site-pred
       (let [[x] args
             [method kind] site-pred]
         (emits "(")
         (emit-lookup-test x method)
         (emits "?")
         (case kind
           :zero (emits "(" x method "(null) === 0)")
           :seq  (emits "(" x method "(null) != null ? " x " : null)")
           :true (emits "true"))
         (emits ":" f "(" x "))"))

       site-method
       (let [[x] args]
         (emits "(")
         (emit-lookup-test x site-method)
         (emits "?" x site-method "(null):" f "(" x "))"))

       nth-lookup?
       (let [[v i] args
             nth (str ".cljs$core$IIndexed$_nth$arity$" (count args))]
         (emits "(")
         (when (= :local (:op i))
           (emits "typeof " i " === \"number\" && "))
         (emit-lookup-test v nth)
         (emits "?" v nth "(" (comma-sep (cons "null" (rest args))) "):"
                f "(" (comma-sep args) "))"))

       variadic-invoke
       (let [mfa (:max-fixed-arity variadic-invoke)]
        (emits f "(" (comma-sep (take mfa args))
               (when-not (zero? mfa) ",")
               (cc "cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([")
               (comma-sep (drop mfa args)) "], 0))"))

       (or fn? js? goog?)
       (emits f "(" (comma-sep args)  ")")

       :else
       (if (and ana/*cljs-static-fns* (#{:var :local :js-var} (:op f)))
         ;; higher order case, static information missing
         (let [fprop (str ".cljs$core$IFn$_invoke$arity$" (count args))]
           (if ana/*fn-invoke-direct*
             (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : "
                    f "(" (comma-sep args) "))")
             (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : "
                    f ".call(" (comma-sep (cons "null" args)) "))")))
         (emits f ".call(" (comma-sep (cons "null" args)) ")"))))))

(defmethod emit* :new
  [{ctor :class :keys [args env]}]
  (emit-wrap env
             (emits "(new " ctor "("
                    (comma-sep args)
                    "))")))

(defmethod emit* :qualified-method
  [{ctor :class :keys [args env kind name]}]
  (if (= :new kind)
    (emit-wrap env
      (emits "(function (...args) { return Reflect.construct(" ctor ", args) })"))
    (emit-wrap env
      (emits "(function (x, ...args) { return Reflect.apply(" ctor ".prototype." name ", x, args) })"))))

(defn- esm-foreign-var
  "If ast references a var of another namespace compiled to an ES module,
  returns [ns name]. Closure Library namespaces run by the compatibility
  layer are objects, set! assigns their properties; shims are modules."
  [{:keys [op info]}]
  (when (and *esm-emitting* (= :var op))
    (let [sym (:name info)
          ns  (namespace sym)]
      (when (and ns
                 (or *esm-repl* (not= ns (str ana/*cljs-ns*)))
                 (not (string/includes? (name sym) "."))
                 (or (not (or (= "goog" ns) (string/starts-with? ns "goog.")))
                     #?(:clj (contains? (esm-goog-shim-exports ns ana/*cljs-ns*) (esm-var-name (name sym)))
                        :cljs false))
                 (esm-module-ns? ns))
        [ns (name sym)]))))

(defmethod emit* :set!
  [{:keys [target val env]}]
  ;; Imported ES module bindings are read-only, set! goes through a setter
  ;; exported by the module owning the var.
  (if-let [[ns nm] (esm-foreign-var target)]
    (let [export (esm-var-name nm)]
      (swap! (:refs *esm*) conj ns)
      (emit-wrap env
        (if (:dynamic (:info target))
          (emits (esm-ns-alias ns) ".$set$" export "(" val ")")
          (emits (esm-ns-alias ns) ".$$set(\"" export "\", " val ")"))))
    (emit-wrap env (emits "(" target " = " val ")"))))

(defn sublib-select
  [sublib]
  (when sublib
    (let [xs (string/split sublib #"\.")]
      (apply str
        (map #(str "['" % "']") xs)))))

(defn emit-global-export [ns-name global-exports lib opts]
  (let [[lib' sublib] (ana/lib&sublib lib)
        ref (str "goog.global"
              ;; Convert object dot access to bracket access
              (->> (string/split (name (or (get global-exports (symbol lib'))
                                           (get global-exports (name lib'))))
                     #"\.")
                (map (fn [prop] (str "[\"" prop "\"]")))
                (apply str)))]
    (when (and (ana/external-dep? lib')
               (= :none (:optimizations opts)))
      (emitln
        "if(!" ref ") throw new Error(\"External library, " lib' ", never provided\");"))
    (emitln
      (munge ns-name) "."
      (ana/munge-global-export lib)
      " = "
      ref
      (sublib-select sublib)
      ";")))

(defn load-libs
  [libs seen reloads deps ns-name]
  (let [{:keys [options js-dependency-index]} @env/*compiler*
        {:keys [target nodejs-rt optimizations]} options
        loaded-libs (munge 'cljs.core.*loaded-libs*)
        loaded-libs-temp (munge (gensym 'cljs.core.*loaded-libs*))
        [node-libs libs-to-load] (let [libs (remove (set (vals seen)) (filter (set (vals libs)) deps))]
                                   (if (= :nodejs target)
                                     (let [{node-libs true libs-to-load false} (group-by ana/node-module-dep? libs)]
                                       [node-libs libs-to-load])
                                     [nil libs]))
        [goog-modules libs-to-load] (let [{goog-modules true libs-to-load false}
                                          (group-by ana/goog-module-dep? libs-to-load)]
                                      [goog-modules libs-to-load])
        global-exports-libs (filter ana/dep-has-global-exports? libs-to-load)]
    (when (-> libs meta :reload-all)
      (emitln "if(!COMPILED) " loaded-libs-temp " = " loaded-libs " || cljs.core.set([\"cljs.core\"]);")
      (emitln "if(!COMPILED) " loaded-libs " = cljs.core.set([\"cljs.core\"]);"))
    (doseq [lib libs-to-load]
      (cond
        #?@(:clj
            [(ana/foreign-dep? lib)
             ;; we only load foreign libraries under optimizations :none
             ;; under :modules we also elide loads, as the module loader will
             ;; have handled it - David
             (when (and (= :none optimizations)
                        (not (contains? options :modules)))
               (let [[lib _] (ana/lib&sublib lib)]
                 (if nodejs-rt
                   ;; under node.js we load foreign libs globally
                   (let [ijs (get js-dependency-index (name lib))]
                     (emitln "cljs.core.load_file("
                       (-> (io/file (util/output-directory options)
                             (or (deps/-relative-path ijs)
                                 (util/relative-name (:url ijs))))
                         str
                         escape-string
                         wrap-in-double-quotes)
                       ");"))
                   (if-not (ana/external-dep? lib)
                     (emitln "goog.require('" (munge lib) "');")
                     ;; TODO: validate the lib exists
                     ))))]
            :cljs
            [(and (ana/foreign-dep? lib)
                  (not (keyword-identical? optimizations :none)))
             nil])

        (or (-> libs meta :reload)
            (= (get reloads lib) :reload))
        (emitln "goog.require('" (munge lib) "', 'reload');")

        (or (-> libs meta :reload-all)
            (= (get reloads lib) :reload-all))
        (emitln "goog.require('" (munge lib) "', 'reload-all');")

        :else
        (when-not (= lib 'goog)
          (emitln "goog.require('" (munge lib) "');"))))
    ;; Node Libraries
    (doseq [lib node-libs]
      (let [[lib' sublib] (ana/lib&sublib lib)]
        (emitln (munge ns-name) "."
          (ana/munge-node-lib lib)
          " = require('" lib' "')" (sublib-select sublib) ";")))
    ;; Google Closure Library Modules (i.e. goog.module(...))
    ;; these must be assigned to vars
    (doseq [lib goog-modules]
      (let [[lib' sublib] (ana/lib&sublib lib)]
        (emitln "goog.require('" lib' "');")
        ;; we emit goog.scope here to suppress a Closure error about
        ;; goog.module.get when compiling - meant to discourage incorrect
        ;; usage by hand written code - not applicable here
        (emitln "goog.scope(function(){")
        (emitln (munge ns-name) "."
          (ana/munge-goog-module-lib lib)
          " = goog.module.get('" lib' "')" (sublib-select sublib) ";")
        (emitln "});")))
    ;; Global Exports
    (doseq [lib global-exports-libs]
      (let [{:keys [global-exports]} (get js-dependency-index (name (-> lib ana/lib&sublib first)))]
        (emit-global-export ns-name global-exports lib options)))
    (when (-> libs meta :reload-all)
      (emitln "if(!COMPILED) " loaded-libs " = cljs.core.into(" loaded-libs-temp ", " loaded-libs ");"))))

#?(:clj
   (def esm-goog-shim-exports
     "The names exported by the hand written ES module shim of a Closure
     Library namespace, nil if there's none. Shims implement what cljs.core and
     the standard library use."
     (let [exports
           (memoize
             (fn [ns]
               (when-let [res (io/resource (str "cljf/esm/" (util/ns->relpath ns :js)))]
                 (let [src  (slurp res)
                       decl #"export\s+(/\*\s*stub\s*\*/\s*)?(?:async\s+)?(?:function\*?|class|const|let|var)\s+([\w$]+)"]
                   {:all   (into #{}
                             (concat
                               (map #(nth % 2) (re-seq decl src))
                               (mapcat (fn [[_ names]]
                                         (map #(string/trim (last (string/split % #"\s+as\s+")))
                                           (string/split names #",")))
                                 (re-seq #"export\s*\{([^}]*)\}" src))))
                    ;; export /* stub */ ..., only good enough for cljs.core
                    :stubs (into #{} (keep (fn [[_ stub? name]] (when stub? name))) (re-seq decl src))}))))]
       (fn
         ([ns] (:all (exports ns)))
         ([ns from-ns]
          (when-let [{:keys [all stubs]} (exports ns)]
            (if (= 'cljs.core from-ns) all (reduce disj all stubs))))))))

#?(:clj
   (defn esm-goog-lib-path
     "The output path of the module exporting a Closure Library namespace run
     by the compatibility layer, see cljf.esm/install-goog-libs."
     [ns]
     (str "goog-lib/ns/" ns ".js")))

#?(:clj
   (defn- esm-path
     "Import path of path (relative to the output directory) from namespace
     from."
     [from path]
     (let [from-dir (.getParentFile (io/file (util/ns->relpath from :js)))
           rel      (if from-dir
                      (str (.relativize (.toPath from-dir) (.toPath (io/file path))))
                      path)
           rel      (string/replace rel File/separator "/")]
       (if (string/starts-with? rel ".")
         rel
         (str "./" rel)))))

#?(:clj
   (defn- esm-goog-lib!
     "Records a Closure Library namespace used without a shim, returns the
     import path of its module. Recorded in the analysis of namespace ns-name,
     which the analysis cache keeps: a build compiling none of its users still
     writes its module."
     [ns-name ns]
     (when-not (deps/closure-lib (:js-dependency-index @env/*compiler*) ns)
       (ana/warning :esm-unsupported-goog-ns (ana/empty-env) {:ns ns}))
     (swap! env/*compiler* update-in [::ana/namespaces ns-name :cljf.esm/goog-libs]
       (fnil conj #{}) (str ns))
     (esm-path ns-name (esm-goog-lib-path ns))))

#?(:clj
   (defn esm-ns-path
     "Returns the import path of namespace to relative to namespace from."
     [from to]
     (esm-path from (util/ns->relpath to :js))))

#?(:clj
   (defn- esm-import-specifier
     "Returns the import specifier of a JavaScript library. Package names are
     left as is for the bundler to resolve, paths relative to the source file
     are made relative to the output file."
     [lib]
     (let [lib (str lib)
           {:keys [^File src ^File dest]} *esm*
           ;; canonical paths, the output or source directory may be a symlink
           canonical (fn ^java.nio.file.Path [^File f] (.toPath (.getCanonicalFile f)))
           relative (fn [^java.nio.file.Path target]
                      (let [^java.nio.file.Path target (canonical (.toFile target))
                            ^java.nio.file.Path from   (.getParent ^java.nio.file.Path (canonical dest))
                            rel (str (.relativize from target))
                            rel (string/replace rel File/separator "/")]
                        (if (string/starts-with? rel ".")
                          rel
                          (str "./" rel))))]
       (cond
         (and (or (string/starts-with? lib "./") (string/starts-with? lib "../"))
              src dest)
         (relative (.normalize (.resolve (.getParent (.toAbsolutePath (.toPath src))) lib)))

         ;; classpath relative, i.e. "/my/lib.js"
         (and (string/starts-with? lib "/") dest)
         (let [path (subs lib 1)
               path (if (re-find #"\.[cm]?[jt]sx?$" path) path (str path ".js"))
               res  (io/resource path)]
           (cond
             (nil? res)
             (throw (ex-info (str "Classpath JavaScript file " lib " not found")
                      {:lib lib :clojure.error/phase :compilation}))

             (= "file" (.getProtocol ^java.net.URL res))
             (relative (.toAbsolutePath (.toPath (io/file res))))

             ;; in a jar, copied to the output directory
             :else
             (let [out (io/file (util/output-directory (:options @env/*compiler*)) path)]
               (util/mkdirs out)
               (with-open [in (io/input-stream res)]
                 (io/copy in out))
               (relative (.toAbsolutePath (.toPath out))))))

         :else lib))))

#?(:clj
   (defn- emit-esm-import [ns-name dep]
     (let [dep-str (str dep)]
       (cond
         (ana/node-module-dep? dep)
         (let [[lib sublib] (ana/lib&sublib dep)
               alias (str (munge (symbol (str ns-name) (ana/munge-node-lib dep))))
               spec  (esm-import-specifier lib)]
           ;; REPL forms reach the binding through the namespace's
           ;; registration, see emit-esm-repl-footer
           (some-> (:libs *esm*) (swap! assoc (esm-var-name (ana/munge-node-lib dep)) alias))
           (cond
             ;; like shadow-cljs, :as binds CommonJS modules' module.exports and
             ;; ES modules' namespace, decided by the bundler plugin
             (and (nil? sublib) (= :shadow (:npm-interop (:options @env/*compiler*))))
             (emitln "import " alias " from \"cljs-npm-as:" spec "\";")

             (nil? sublib)
             (emitln "import * as " alias " from \"" spec "\";")

             (= "default" sublib)
             (emitln "import " alias " from \"" spec "\";")

             :else
             (do
               (emitln "import * as " alias "$module from \"" spec "\";")
               (emitln "const " alias " = " alias "$module" (sublib-select sublib) ";"))))

         ;; Closure Library shims are imported when referenced, Closure
         ;; Library namespaces without one are loaded for their side effects
         (or (= "goog" dep-str) (string/starts-with? dep-str "goog."))
         (when-not (or (= "goog" dep-str) (esm-goog-shim-exports dep-str))
           (emitln "import \"" (esm-goog-lib! ns-name dep-str) "\";"))

         ;; Closure style libraries on the classpath, run by the Closure
         ;; Library compatibility layer, i.e. transit-js
         (and (not (contains? (::ana/namespaces @env/*compiler*) (symbol dep-str)))
              (deps/closure-lib (:js-dependency-index @env/*compiler*) dep-str))
         (emitln "import \"" (esm-goog-lib! ns-name dep-str) "\";")

         (contains? (::ana/namespaces @env/*compiler*) (symbol dep-str))
         (when-not (contains? @(:imported *esm*) dep-str)
           (swap! (:imported *esm*) conj dep-str)
           (emitln "import * as " (esm-ns-alias dep-str) " from \"" (esm-ns-path ns-name dep-str) "\";"))

         :else
         (throw
           (ex-info (str "Unsupported dependency " dep-str " for ES module output")
             {:ns ns-name :dep dep :clojure.error/phase :compilation}))))))

#?(:clj
   (defn esm-preloads
     "The namespaces main namespace ns-name imports first: the :preloads, and
     under :esm-repl the REPL runtime. Main namespaces are :main's and
     :extra-main's."
     [ns-name {:keys [main extra-main preloads esm-repl]}]
     (let [mains (set (map symbol (concat (cond (coll? main) main main [main]) extra-main)))]
       (when (and (contains? mains ns-name)
                  (not (some #{ns-name} (map symbol preloads))))
         (cond->> preloads esm-repl (cons esm-repl-runtime))))))

#?(:clj
   (defn- emit-esm-preloads
     "Main namespaces import the :preloads first, ES modules evaluate their
     imports in order."
     [ns-name]
     (doseq [preload (esm-preloads ns-name (:options @env/*compiler*))]
       (emitln "import \"" (esm-ns-path ns-name preload) "\";"))))

#?(:clj (declare emit-esm-ns-import))

#?(:clj
   (defn- emit-esm-imports [ns-name deps]
     (emit-esm-preloads ns-name)
     (when-not (= 'cljs.core ns-name)
       (emit-esm-import ns-name 'cljs.core)
       (when (-> @env/*compiler* :options :emit-constants)
         (emit-esm-ns-import ns-name ana/constants-ns-sym)))
     (doseq [dep deps]
       (emit-esm-import ns-name dep))))

#?(:clj
   (defn- emit-esm-ns-import
     "Emits the import of a namespace referenced by ns-name."
     [ns-name ns]
     (emitln "import * as " (esm-ns-alias ns) " from \"" (esm-ns-path ns-name ns) "\";")))

#?(:clj
   (defn emit-esm-hmr-header
     "Under :esm-hmr, a reloaded module first restores the values of the
     instance it replaces, so that defonce keeps its value."
     []
     (emitln "if (import.meta.hot && import.meta.hot.data.$$vals) { "
       "const vals = import.meta.hot.data.$$vals; "
       "for (const k in vals) { try { $$set(k, vals[k]); } catch (e) {} } }")))

#?(:clj
   (defn- emit-esm-hmr-footer
     "Under :esm-hmr modules accept their own updates. Importers keep the
     bindings of the module instance they imported, the new instance updates
     the bindings of all previous ones through their $$set. Runs
     ^:dev/before-load and ^:dev/after-load hooks around updates."
     [ns-name exports]
     (let [opts  (:options @env/*compiler*)
           ;; ^:dev/before-load / ^:dev/after-load fns, and fns of this
           ;; namespace given as :esm-before-load / :esm-after-load
           hooks (fn [k]
                   (->> (concat
                          (->> (get-in @env/*compiler* [::ana/namespaces ns-name :defs])
                               (filter (fn [[_ v]] (get-in v [:meta k])))
                               (map (fn [[sym _]] (symbol (str ns-name) (str sym)))))
                          (->> (get opts ({:dev/before-load :esm-before-load
                                           :dev/after-load :esm-after-load} k))
                               (filter #(= (str ns-name) (namespace %)))))
                        distinct
                        (map #(str (munge %)))
                        sort))
           vals  (str "{" (string/join ", " (map (fn [[export local]] (str "\"" export "\": " local)) exports)) "}")]
       (emitln "if (import.meta.hot) {")
       (emitln "const data = import.meta.hot.data;")
       (emitln "const vals = " vals ";")
       (emitln "for (const set of (data.$$sets || [])) for (const k in vals) { try { set(k, vals[k]); } catch (e) {} }")
       (emitln "(data.$$sets || (data.$$sets = [])).push($$set);")
       (emitln "import.meta.hot.dispose((data) => { data.$$vals = " vals "; });")
       (doseq [[k hook event] [[:dev/before-load "before_load" "vite:beforeUpdate"]
                               [:dev/after-load "after_load" "vite:afterUpdate"]]]
         (when-let [fns (seq (hooks k))]
           (emitln "data.$$" hook " = () => { " (string/join " " (map #(str % "();") fns)) " };")
           (emitln "if (!data.$$" hook "_on) { data.$$" hook "_on = true; "
             "import.meta.hot.on(\"" event "\", () => data.$$" hook "()); }")))
       (if (= 'cljs.core ns-name)
         ;; values of a previous cljs.core aren't compatible with a new one
         (emitln "import.meta.hot.accept(() => { if (typeof location !== \"undefined\") location.reload(); });")
         (emitln "import.meta.hot.accept();"))
       (emitln "}"))))

#?(:clj (def ^:private esm-repl-stamps (java.util.concurrent.atomic.AtomicLong.)))

#?(:clj
   (defn- emit-esm-repl-footer
     "Under :esm-repl modules register their namespace object, the setters
     of all their instances (see emit-esm-hmr-footer) and the npm modules
     they import, for the forms evaluated at the REPL, see
     cljf.esm.repl-runtime. gen counts the instances, it changes once a
     hot reload ran the module again. stamp identifies the compile, also
     recorded as :cljf.esm/repl-stamp of the namespace's analysis (which the
     analysis cache keeps): the REPL waits for a page to run the module of a
     compile."
     [ns-name libs]
     (let [ns    (pr-str (str ns-name))
           stamp (str (System/currentTimeMillis) "-" (.incrementAndGet ^java.util.concurrent.atomic.AtomicLong esm-repl-stamps))]
       (swap! env/*compiler* assoc-in [::ana/namespaces ns-name :cljf.esm/repl-stamp] stamp)
       (emitln "{ const $$r = globalThis.$CLJS_ESM || (globalThis.$CLJS_ESM = { nses: new Map() }), $$e = $$r.nses.get(" ns ");")
       (emitln "$$r.nses.set(" ns ", { mod: " (esm-ns-alias ns-name) ", "
         "sets: import.meta.hot ? import.meta.hot.data.$$sets : [$$set], "
         "libs: {" (string/join ", " (map (fn [[k v]] (str "\"" k "\": " v)) (sort libs))) "}, "
         "gen: $$e ? $$e.gen + 1 : 1, stamp: \"" stamp "\" });")
       (emitln "if ($$r.registered) $$r.registered(" ns "); }"))))

#?(:clj
   (defn- dts-param
     "A TypeScript parameter name for a ClojureScript param, destructuring
     forms and gensyms become argN."
     [param i]
     (let [n (when (symbol? param) (str (munge param)))]
       (if (and n
                (re-matches #"[A-Za-z_][\w]*" n)
                (not (string/starts-with? n "p__"))
                (not= "_" n))
         n
         (str "arg" i)))))

#?(:clj
   (defn esm-dts
     "TypeScript declarations of the exports of namespace ns-name, so that
     TypeScript code can import ClojureScript modules."
     [ns-name export-syms]
     (let [defs (get-in @env/*compiler* [::ana/namespaces ns-name :defs])
           doc  (fn [{:keys [doc]}]
                  (when (and (string? doc) (not (string/blank? doc)))
                    (str "/**\n"
                         (apply str (map #(str " * " (string/replace % "*/" "* /") "\n")
                                      (string/split-lines doc)))
                         " */\n")))]
       (apply str
         "// TypeScript declarations of ClojureScript namespace " ns-name "\n"
         (for [[export sym] (sort export-syms)
               :let [info (get defs (symbol (name sym)))]]
           (str (doc info)
             (if (and (:fn-var info) (seq (:method-params info)))
               (apply str
                 (for [params (:method-params info)
                       :let [variadic? (and (:variadic? info)
                                            (> (count params) (or (:max-fixed-arity info) 0)))
                             fixed     (if variadic? (butlast params) params)]]
                   (str "export declare function " export "("
                        (string/join ", "
                          (concat
                            (map-indexed (fn [i p] (str (dts-param p i) ": any")) fixed)
                            (when variadic? ["...rest: any[]"])))
                        "): any;\n")))
               (str "export declare const " export ": any;\n"))))))))

#?(:clj
   (defn emit-esm-footer
     "Emits the imports of namespaces only referenced by fully qualified
     names, the declarations of vars only defined in functions, setters and
     exports."
     [ns-name]
     (let [{:keys [refs imported hoist exports dynamic self-import goog-lib-refs libs]} *esm*
           exports (sort @exports)
           repl?   (:esm-repl (:options @env/*compiler*))]
       (when repl?
         (reset! self-import true))
       (emitln)
       ;; Closure Library namespaces run by the compatibility layer, default
       ;; exports are the namespace objects
       (doseq [ns (sort @goog-lib-refs)]
         (emitln "import " (esm-ns-alias ns) "$ from \"" (esm-goog-lib! ns-name ns) "\";"))
       (when @self-import
         (emitln "import * as " (esm-ns-alias ns-name) " from \"./"
           (.getName (io/file (util/ns->relpath ns-name :js))) "\";"))
       (doseq [ns (sort (remove (conj @imported (str ns-name)) @refs))]
         (emit-esm-ns-import ns-name ns))
       (when (seq @hoist)
         (emitln "var " (string/join ", " (sort @hoist)) ";"))
       ;; imported bindings can't be assigned, set! of another namespace's var
       ;; is emitted as a call of one of these
       (doseq [export (sort @dynamic)]
         (emitln "export function $set$" export "(v) { return ("
           (get (into {} exports) export) " = v); }"))
       (emitln "export function $$set(name, v) {")
       (emitln "switch (name) {")
       (doseq [[export local] exports]
         (emitln "case \"" export "\": return (" local " = v);"))
       (emitln "}")
       (emitln "throw new Error(\"No var " ns-name "/\" + name);")
       (emitln "}")
       (emitln "export { "
         (string/join ", " (map (fn [[export local]] (str local " as " export)) exports))
         " };")
       (when (:esm-hmr (:options @env/*compiler*))
         (emit-esm-hmr-footer ns-name exports))
       (when repl?
         (emit-esm-repl-footer ns-name (some-> libs deref))))))

#?(:clj
   (defn emit-esm-repl
     "Emits ast, a form analyzed for the REPL in :return context, as the body
     of a function, see *esm-repl*. Returns {:js :refs :goog-lib-refs}, refs
     the namespaces it references as esm-ns-alias, goog-lib-refs the Closure
     Library namespaces run by the compatibility layer as esm-ns-alias plus
     $: the function's parameters."
     [ast]
     (let [state {:refs          (atom #{})
                  :imported      (atom #{})
                  :hoist         (atom #{})
                  :exports       (atom {})
                  :dynamic       (atom #{})
                  :self-import   (atom false)
                  :goog-lib-refs (atom #{})
                  :libs          (atom {})
                  :export-syms   (atom {})}
           js    (binding [*esm*          state
                           *esm-emitting* true
                           *esm-repl*     true]
                   (emit-str ast))]
       {:js            js
        :refs          @(:refs state)
        :goog-lib-refs @(:goog-lib-refs state)})))

(defmethod emit* :ns*
  [{:keys [name requires uses require-macros reloads env deps]}]
  (cond
    ;; cljf.esm.repl loads the namespaces
    *esm-repl*
    (emit-wrap env (emits "null"))

    *esm-emitting*
    #?(:clj (emit-esm-imports ana/*cljs-ns* deps)
       :cljs nil)

    :else
    (do
      (load-libs requires nil (:require reloads) deps name)
      (load-libs uses requires (:use reloads) deps name)))
  (when (:repl-env env)
    (emitln "'nil';")))

(defmethod emit* :ns
  [{:keys [name requires uses require-macros reloads env deps]}]
  (cond
    *esm-repl*
    (emit-wrap env (emits "null"))

    *esm-emitting*
    #?(:clj (emit-esm-imports name deps)
       :cljs nil)

    :else
    (do
      (emitln "goog.provide('" (munge name) "');")
      (when-not (= name 'cljs.core)
        (emitln "goog.require('cljs.core');")
        (when (-> @env/*compiler* :options :emit-constants)
          (emitln "goog.require('" (munge ana/constants-ns-sym) "');")))
      (load-libs requires nil (:require reloads) deps name)
      (load-libs uses requires (:use reloads) deps name))))

(defmethod emit* :deftype
  [{:keys [t fields pmasks body protocols]}]
  (let [fields (map munge fields)]
    (emitln "")
    (emitln "/**")
    (emitln "* @constructor")
    (doseq [protocol protocols]
      (emitln " * @implements {" (munge (str protocol)) "}"))
    (emitln "*/")
    (emit-esm-type-binding t)
    (emitln " = (function (" (comma-sep fields) "){")
    (doseq [fld fields]
      (emitln "this." fld " = " fld ";"))
    (doseq [[pno pmask] pmasks]
      (emitln "this.cljs$lang$protocol_mask$partition" pno "$ = " pmask ";"))
    (emitln "});")
    (emit body)))

(defmethod emit* :class
  [{:keys [env name base params body]}]
  (emit-wrap env
    (emits "(class " (munge name))
    (when base
      (emits " extends " base))
    (emitln " {")
    (when body
      (emitln "constructor(" (comma-sep (map munge params)) ") {")
      ;; this is accessible once the base class' constructor returned
      (emitln (if base "var self__;" "var self__ = this;"))
      (emit body)
      (emitln "}"))
    (emits "})")))

(defmethod emit* :super
  [{:keys [env args]}]
  ;; super() evaluates to this
  (emit-wrap env
    (emits "(self__ = super(" (comma-sep args) "))")))

(defmethod emit* :defrecord
  [{:keys [t fields pmasks body protocols]}]
  (let [fields (concat (map munge fields) '[__meta __extmap __hash])]
    (emitln "")
    (emitln "/**")
    (emitln "* @constructor")
    (doseq [protocol protocols]
      (emitln " * @implements {" (munge (str protocol)) "}"))
    (emitln "*/")
    (emit-esm-type-binding t)
    (emitln " = (function (" (comma-sep fields) "){")
    (doseq [fld fields]
      (emitln "this." fld " = " fld ";"))
    (doseq [[pno pmask] pmasks]
      (emitln "this.cljs$lang$protocol_mask$partition" pno "$ = " pmask ";"))
    (emitln "});")
    (emit body)))

(defn- esm-js-path
  "The JavaScript path of a js/ global, or of fields read from one:
  (.. js/my -ns -foo) is my.ns.foo. nil for anything else."
  [{:keys [op field target] var-name :name}]
  (case op
    :js-var     (when (= "js" (namespace var-name)) (name var-name))
    :host-field (some-> (esm-js-path target) (str "." (munge field #{})))
    nil))

(defn emit-dot
  [{:keys [target field method args env] :as ast}]
  (emit-wrap env
    (if field
      ;; namespaces read as JavaScript globals' fields, i.e.
      ;; (.. js/cljs -core -PersistentArrayMap -EMPTY), as js/my.ns.foo
      (if-some [ref (when *esm-emitting* (some-> (esm-js-path ast) esm-js-ns-ref))]
        (emits ref)
        (emits target "." (munge field #{})))
      (emits target "." (munge method #{}) "("
        (comma-sep args)
        ")"))))

(defmethod emit* :host-field [ast] (emit-dot ast))
(defmethod emit* :host-call [ast] (emit-dot ast))

(defmethod emit* :js
  [{:keys [op env code segs args]}]
  (if (and code #?(:clj  (.startsWith ^String (string/trim code) "/*")
                   :cljs (gstring/startsWith (string/trim code) "/*")))
    (emits code)
    (emit-wrap env
      (if code
        (emits (esm-rewrite-core-refs code))
        (emits (interleave (concat (map esm-rewrite-core-refs segs) (repeat nil))
                           (concat args [nil])))))))

;; TODO: unify renaming helpers - this one was hard to find - David

#?(:clj
   (defn rename-to-js
     "Change the file extension from .cljs to .js. Takes a File or a
     String. Always returns a String."
     [^String file-str]
     (cond
       (.endsWith file-str ".cljs")
       (clojure.string/replace file-str #"\.cljs$" ".js")

       (.endsWith file-str ".cljc")
       (if (= "cljs/core.cljc" file-str)
         "cljs/core$macros.js"
         (clojure.string/replace file-str #"\.cljc$" ".js"))

       :else
       (throw (util/compilation-error (IllegalArgumentException.
                                        (str "Invalid source file extension " file-str)))))))

#?(:clj
   (defn with-core-cljs
     "Ensure that core.cljs has been loaded."
     ([] (with-core-cljs
           (when env/*compiler*
             (:options @env/*compiler*))))
     ([opts] (with-core-cljs opts (fn [])))
     ([opts body]
      {:pre [(or (nil? opts) (map? opts))
             (fn? body)]}
      (when-not (get-in @env/*compiler* [::ana/namespaces 'cljs.core :defs])
        (ana/analyze-file "cljs/core.cljs" opts))
      (body))))

#?(:clj
   (defn url-path [^File f]
     (.getPath (.toURL (.toURI f)))))

#?(:clj
   (defn compiled-by-string
     ([]
      (compiled-by-string
        (when env/*compiler*
          (:options @env/*compiler*))))
     ([opts]
      (str "// Compiled by ClojureScript "
        (util/clojurescript-version)
        (when opts
          (str " " (pr-str (ana/build-affecting-options opts))))))))

#?(:clj
   (defn cached-core [ns ext opts]
     (and (= :none (:optimizations opts))
          (not (esm-mode? opts))
          (not= "cljc" ext)
          (= 'cljs.core ns)
          (io/resource "cljs/core.aot.js"))))

#?(:clj
   (defn macro-ns? [ns ext opts]
     (or (= "clj" ext)
         (= 'cljs.core$macros ns)
         (and (= ns 'cljs.core) (= "cljc" ext))
         (:macros-ns opts))))

#?(:clj
   (defn emit-cached-core [src dest cached opts]
     ;; no need to bother with analysis cache reading, handled by
     ;; with-core-cljs
     (when (or ana/*verbose* (:verbose opts))
       (util/debug-prn "Using cached cljs.core" (str src)))
     (spit dest (slurp cached))
     (.setLastModified ^File dest (util/last-modified src))
     (when (true? (:source-map opts))
       (spit (io/file (str dest ".map"))
         (json/write-str
           (assoc
             (json/read-str (slurp (io/resource "cljs/core.aot.js.map")))
             "file"
             (str (io/file (util/output-directory opts) "cljs" "core.js"))))))
     (merge
       (ana/parse-ns src dest nil)
       {:out-file dest})))

#?(:clj
   (defn emit-source-map [src dest sm-data opts]
     (let [sm-file (io/file (str (.getPath ^File dest) ".map"))]
       (if-let [smap (:source-map-asset-path opts)]
         (emitln "\n//# sourceMappingURL=" smap
           (string/replace (util/path sm-file)
             (str (util/path (io/file (:output-dir opts))))
             "")
           (if (true? (:source-map-timestamp opts))
             (str
               (if-not (string/index-of smap "?") "?" "&")
               "rel=" (System/currentTimeMillis))
             ""))
         (emitln "\n//# sourceMappingURL="
           (or (:source-map-url opts) (.getName sm-file))
           (if (true? (:source-map-timestamp opts))
             (str "?rel=" (System/currentTimeMillis))
             "")))
       (spit sm-file
         (sm/encode {(url-path src) (:source-map sm-data)}
           {:lines (+ (:gen-line sm-data) 2)
            :file (url-path dest)
            :source-map-path (:source-map-path opts)
            :source-map-timestamp (:source-map-timestamp opts)
            :source-map-pretty-print (:source-map-pretty-print opts)
            ;; ES modules are bundled from the output directory, which
            ;; doesn't have the sources (jar ones aren't files)
            :sources-content (when (esm-mode? opts) [(slurp src)])
            :relpaths {(util/path src)
                       (util/ns->relpath (first (:provides opts)) (:ext opts))}})))))

#?(:clj
   (defn- emit-source* [src dest out-file ext opts]
     (with-open [out ^java.io.Writer (io/make-writer out-file {})]
       (binding [*out*                 out
                 ana/*cljs-ns*         'cljs.user
                 ana/*cljs-file*       (.getPath ^File src)
                 reader/*alias-map*    (or (ana/get-bridged-alias-map) reader/*alias-map* {})
                 ana/*checked-arrays*  (or ana/*checked-arrays* (:checked-arrays opts))
                 ana/*cljs-static-fns* (or ana/*cljs-static-fns* (:static-fns opts))
                 *source-map-data*     (when (:source-map opts)
                                         (atom
                                           {:source-map (sorted-map)
                                            :gen-line 0}))
                 *source-map-data-gen-col* (AtomicLong.)
                 find-ns-starts-with   (memoize find-ns-starts-with)
                 *esm*                 (when (esm-mode? opts)
                                         {:src      src
                                          :dest     dest
                                          :refs     (atom #{})
                                          :imported (atom #{})
                                          :hoist    (atom #{})
                                          :exports  (atom {})
                                          :dynamic  (atom #{})
                                          :self-import (atom false)
                                          :goog-lib-refs (atom #{})
                                          :libs     (atom {})
                                          :export-syms (atom {})})]
         (emitln (compiled-by-string opts))
         (when (and *esm* (:esm-hmr opts))
           (emit-esm-hmr-header))
         (with-open [rdr (io/reader src)]
           (let [env (ana/empty-env)
                 emitter (when (:parallel-build opts)
                           (Executors/newSingleThreadExecutor))
                 emit (if *esm*
                        #(binding [*esm-emitting* true] (emit %))
                        emit)
                 emit (if emitter
                        #(.execute emitter ^Runnable (bound-fn [] (emit %)))
                        emit)]
             (try
               (loop [forms       (ana/forms-seq* rdr (util/path src))
                      ns-name     nil
                      deps        []]
                 (if (seq forms)
                   (let [env (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                         {:keys [op] :as ast} (ana/analyze env (first forms) nil opts)]
                     (cond
                       (= op :ns)
                       (let [ns-name (:name ast)
                             ns-name (if (and (= 'cljs.core ns-name)
                                           (= "cljc" ext))
                                       'cljs.core$macros
                                       ns-name)]
                         (emit ast)
                         (recur (rest forms) ns-name (into deps (:deps ast))))

                       (= :ns* (:op ast))
                       (let [ns-emitted? (some? ns-name)
                             ns-name (if-not ns-emitted?
                                       (ana/gen-user-ns src)
                                       ns-name)]
                         (if-not ns-emitted?
                           (emit (assoc ast :name ns-name :op :ns))
                           (emit ast))
                         (recur (rest forms) ns-name (into deps (:deps ast))))

                       :else
                       (let [ns-emitted? (some? ns-name)
                             ns-name (if-not ns-emitted?
                                       (ana/gen-user-ns src)
                                       ns-name)]
                         (when-not ns-emitted?
                           (emit {:op :ns
                                  :name ns-name}))
                         (emit ast)
                         (recur (rest forms) ns-name deps))))
                   (let [_ (when emitter
                             (.shutdown emitter)
                             (.awaitTermination emitter 1000 TimeUnit/HOURS))
                         _ (when *esm*
                             (binding [*esm-emitting* true]
                               (emit-esm-footer (or ns-name 'cljs.user))))
                         dts (when *esm*
                               (esm-dts (or ns-name 'cljs.user) @(:export-syms *esm*)))
                         sm-data (when *source-map-data* (assoc @*source-map-data*
                                                           :gen-col (.get ^AtomicLong *source-map-data-gen-col*)))
                         ret (merge
                               {:ns         (or ns-name 'cljs.user)
                                :macros-ns  (:macros-ns opts)
                                :provides   [ns-name]
                                :requires   (if (= ns-name 'cljs.core)
                                              (vec (distinct deps))
                                              (cond-> (conj (vec (distinct deps)) 'cljs.core)
                                                (get-in @env/*compiler* [:options :emit-constants])
                                                (conj ana/constants-ns-sym)))
                                :file        dest
                                :out-file    (.toString ^File dest)
                                :source-file src}
                               (when sm-data
                                 {:source-map (:source-map sm-data)})
                               (when dts
                                 {:esm-dts dts}))]
                     (when (and sm-data (= :none (:optimizations opts)))
                       (emit-source-map src dest sm-data
                         (merge opts {:ext ext :provides [ns-name]})))
                     (let [path (.getPath (.toURL ^File dest))]
                       (swap! env/*compiler* assoc-in [::compiled-cljs path] ret))
                     (ana/ensure-defs ns-name)
                     (let [{:keys [output-dir cache-analysis]} opts]
                       (when (and (true? cache-analysis) output-dir)
                         (ana/write-analysis-cache ns-name
                           (ana/cache-file src (ana/parse-ns src) output-dir :write)
                           src))
                       ret))))
               (catch Throwable e
                 ;; the forms analyzed before the failure are still queued:
                 ;; dropped, and the one being emitted finishes, before the
                 ;; output file is closed (they'd each fail writing to it)
                 (when emitter
                   (.shutdownNow ^ExecutorService emitter)
                   (try
                     (.awaitTermination ^ExecutorService emitter 1 TimeUnit/MINUTES)
                     ;; the thread interrupted: the failure is thrown still
                     (catch InterruptedException _)))
                 (throw e)))))))))

#?(:clj
   (defn emit-source [src ^File dest ext opts]
     (if (esm-mode? opts)
       ;; written to a temporary file first, dev servers watching the output
       ;; directory never see a partially written module
       (let [tmp (io/file (str (.getPath dest) ".tmp"))
             ret (try
                   (emit-source* src dest tmp ext opts)
                   (catch Throwable e
                     (.delete tmp)
                     (throw e)))
             dts (io/file (string/replace (.getPath dest) #"\.js$" ".d.ts"))]
         (when-not (false? (:esm-dts opts))
           (spit dts (:esm-dts ret)))
         (java.nio.file.Files/move (.toPath tmp) (.toPath dest)
           (into-array java.nio.file.CopyOption
             [java.nio.file.StandardCopyOption/REPLACE_EXISTING
              java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
         ret)
       (emit-source* src dest dest ext opts))))

#?(:clj
   (defn compile-file*
     ([^File src ^File dest]
      (compile-file* src dest
        (when env/*compiler*
          (:options @env/*compiler*))))
     ([^File src ^File dest opts]
      (ensure
        (with-core-cljs opts
          (fn []
            (when (and (or ana/*verbose* (:verbose opts))
                       (not (:compiler-stats opts)))
              (util/debug-prn "Compiling" (str src) "to" (str dest)))
            (util/measure (and (or ana/*verbose* (:verbose opts))
                               (:compiler-stats opts))
              (str "Compiling " (str src) " to " (str dest))
              (let [ext (util/ext src)
                   {:keys [ns] :as ns-info} (ana/parse-ns src)]
               (if-let [cached (cached-core ns ext opts)]
                 [(emit-cached-core src dest cached opts) false]
                 (let [opts (if (macro-ns? ns ext opts)
                              (assoc opts :macros-ns true)
                              opts)
                       dest-exists? (.exists dest)
                       ret [(emit-source src dest ext opts) dest-exists?]]
                   (.setLastModified ^File dest (util/last-modified src))
                   ret))))))))))

#?(:clj
   (defn requires-compilation?
     "Return true if the src file requires compilation."
     ([src dest]
      (requires-compilation? src dest
        (when env/*compiler*
          (:options @env/*compiler*))))
     ([^File src ^File dest opts]
      (let [{:keys [ns requires]} (ana/parse-ns src)]
        (if (and (= 'cljs.loader ns) (not (contains? opts :cache-key)))
          false
          (ensure
           (or (not (.exists dest))
               (util/changed? src dest)
               (let [version' (util/compiled-by-version dest)
                     version (util/clojurescript-version)]
                 (and version (not= version version')))
               (and opts
                    (not (and (io/resource "cljs/core.aot.js") (= 'cljs.core ns)))
                    (not= (ana/build-affecting-options opts)
                          (ana/build-affecting-options (util/build-options dest))))
               (and opts (:source-map opts)
                    (if (= (:optimizations opts) :none)
                      (not (.exists (io/file (str (.getPath dest) ".map"))))
                      (not (get-in @env/*compiler* [::compiled-cljs (.getAbsolutePath dest)]))))
               (when-let [recompiled' (and *recompiled* @*recompiled*)]
                 (some requires recompiled')))))))))

#?(:clj
   (defn compile-file
     "Compiles src to a file of the same name, but with a .js extension,
      in the src file's directory.

      With dest argument, write file to provided location. If the dest
      argument is a file outside the source tree, missing parent
      directories will be created. The src file will only be compiled if
      the dest file has an older modification time.

      Both src and dest may be either a String or a File.

      Returns a map containing {:ns .. :provides .. :requires .. :file ..}.
      If the file was not compiled returns only {:file ...}"
     ([src]
      (let [dest (rename-to-js src)]
        (compile-file src dest
          (when env/*compiler*
            (:options @env/*compiler*)))))
     ([src dest]
      (compile-file src dest
        (when env/*compiler*
          (:options @env/*compiler*))))
     ([src dest opts]
      {:post [map?]}
      (binding [ana/*file-defs*        (atom #{})
                ana/*unchecked-if*     false
                ana/*unchecked-arrays* false
                ana/*cljs-warnings*    ana/*cljs-warnings*]
        (let [nses      (get @env/*compiler* ::ana/namespaces)
              src-file  (io/file src)
              dest-file (io/file dest)
              opts      (merge {:optimizations :none} opts)]
          (if (.exists src-file)
            (try
              (let [{ns :ns :as ns-info} (ana/parse-ns src-file dest-file opts)
                    opts (if (and (not= (util/ext src) "clj") ;; skip cljs.core macro-ns
                                  (= ns 'cljs.core))
                           (cond-> opts
                             (not (false? (:static-fns opts))) (assoc :static-fns true)
                             true (dissoc :checked-arrays))
                           opts)]
                (if (or (requires-compilation? src-file dest-file opts)
                        (:force opts))
                  (do
                    (util/mkdirs dest-file)
                    (when (and (get-in nses [ns :defs])
                               (not= 'cljs.core ns)
                               (not= :interactive (:mode opts)))
                      (swap! env/*compiler* update-in [::ana/namespaces] dissoc ns))
                    (let [[ret recompiled?] (compile-file* src-file dest-file opts)]
                      (when (and *recompiled*
                                 recompiled?)
                        (swap! *recompiled* conj ns))
                      ret))
                  (do
                    ;; populate compilation environment with analysis information
                    ;; when constants are optimized
                    (when (or (and (= ns 'cljs.loader)
                                   (not (contains? opts :cache-key)))
                              (and (true? (:optimize-constants opts))
                                   (nil? (get-in nses [ns :defs]))))
                      (with-core-cljs opts (fn [] (ana/analyze-file src-file opts))))
                    (assoc ns-info :out-file (.toString dest-file)))))
              (catch Exception e
                (throw (ex-info (str "failed compiling file:" src) {:file src :clojure.error/phase :compilation} e))))
            (throw (util/compilation-error (java.io.FileNotFoundException. (str "The file " src " does not exist."))))))))))

#?(:clj
   (defn cljs-files-in
     "Return a sequence of all .cljs and .cljc files in the given directory."
     [dir]
     (map io/file
       (reduce
         (fn [m x]
           (if (.endsWith ^String x ".cljs")
             (cond-> (conj m x)
               (contains? m (str (subs x 0 (dec (count x))) "c"))
               (set/difference #{(str (subs x 0 (dec (count x))) "c")}))
             ;; ends with .cljc
             (cond-> m
               (not (contains? m (str (subs x 0 (dec (count x))) "s")))
               (conj x))))
         #{}
         (into []
           (comp
             (filter
               #(let [name (.getName ^File %)]
                  (and (or (.endsWith name ".cljs")
                         (.endsWith name ".cljc"))
                    (not= \. (first name))
                    (not (contains? cljs-reserved-file-names name)))))
             (map #(.getPath ^File %)))
           (file-seq dir))))))

#?(:clj
   (defn compile-root
     "Looks recursively in src-dir for .cljs files and compiles them to
      .js files. If target-dir is provided, output will go into this
      directory mirroring the source directory structure. Returns a list
      of maps containing information about each file which was compiled
      in dependency order."
     ([src-dir]
      (compile-root src-dir "out"))
     ([src-dir target-dir]
      (compile-root src-dir target-dir
        (when env/*compiler*
          (:options @env/*compiler*))))
     ([src-dir target-dir opts]
      (swap! env/*compiler* assoc :root src-dir)
      (let [src-dir-file (io/file src-dir)
            inputs (deps/dependency-order
                     (map #(ana/parse-ns %)
                       (cljs-files-in src-dir-file)))]
        (binding [*inputs* (zipmap (map :ns inputs) inputs)]
          (loop [inputs (seq inputs) compiled []]
            (if inputs
              (let [{:keys [source-file] :as ns-info} (first inputs)
                    output-file (util/to-target-file target-dir ns-info)
                    ijs (compile-file source-file output-file opts)]
                (recur
                  (next inputs)
                  (conj compiled
                    (assoc ijs :file-name (.getPath output-file)))))
              compiled)))))))

#?(:clj
   (defn find-source [file]
     (ana/parse-ns file)))

#?(:clj
   (defn find-root-sources
     [src-dir]
      (let [src-dir-file (io/file src-dir)]
        (map find-source (cljs-files-in src-dir-file)))))

;; TODO: needs fixing, table will include other things than keywords - David

(defn emit-constants-table [table]
  (emitln "goog.provide('" (munge ana/constants-ns-sym) "');")
  (emitln "goog.require('cljs.core');")
  (doseq [[sym value] table]
    (let [ns   (namespace sym)
          name (name sym)]
      (emits "cljs.core." value " = ")
      (cond
        (keyword? sym) (emits-keyword sym)
        (symbol? sym) (emits-symbol sym)
        :else (throw
                (ex-info
                  (str "Cannot emit constant for type " (type sym))
                  {:error :invalid-constant-type
                   :clojure.error/phase :compilation})))
      (emits ";\n"))))

#?(:clj
   (defn emit-esm-constants-table
     "The constants module of ES module output (:emit-constants): an export
     of each keyword and symbol constant in table."
     [table]
     (binding [*esm-emitting* true
               ana/*cljs-ns*  ana/constants-ns-sym
               ;; the namespaces cc references, cljs.core is imported below
               *esm*          {:refs (atom #{}) :self-import (atom false)}]
       (emitln "import * as " (esm-ns-alias 'cljs.core) " from \""
         (esm-ns-path ana/constants-ns-sym 'cljs.core) "\";")
       (doseq [[x value] (sort-by (comp str val) table)]
         (emits "export const " value " = /*@__PURE__*/ ")
         (if (keyword? x) (emits-keyword x) (emits-symbol x))
         (emitln ";")))))

#?(:clj
   (defn emit-constants-table-to-file [table dest]
     (io/make-parents dest)
     (with-open [out ^java.io.Writer (io/make-writer dest {})]
       (binding [*out* out]
         (emit-constants-table table)))))

(defn emit-externs
  ([externs]
   (emit-externs [] externs (atom #{})
     (when env/*compiler* (ana/get-externs))))
  ([prefix externs top-level known-externs]
   (loop [ks (seq (keys externs))]
     (when ks
       (let [k (first ks)
             [top :as prefix'] (conj prefix k)]
         (when (and (not= 'prototype k)
                    (nil? (get-in known-externs prefix')))
           (if-not (or (contains? @top-level top)
                       (contains? known-externs top))
             (do
               (emitln "var " (string/join "." (map munge prefix')) ";")
               (swap! top-level conj top))
             (emitln (string/join "." (map munge prefix')) ";")))
         (let [m (get externs k)]
           (when-not (empty? m)
             (emit-externs prefix' m top-level known-externs))))
       (recur (next ks))))))

#?(:clj
   (defn emit-inferred-externs-to-file [externs dest]
     (io/make-parents dest)
     (with-open [out ^java.io.Writer (io/make-writer dest {})]
       (binding [*out* out]
         (emit-externs externs)))))
