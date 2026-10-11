;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.esm.repl-runtime
  "Evaluates the REPL's forms (cljf.esm.repl) in a running build compiled
  with :esm-repl, which the main namespaces import first.

  Under :esm-repl modules register their namespace object, the setters of
  all their instances and the npm modules they import with
  globalThis.$CLJS_ESM (see cljs.compiler/emit-esm-repl-footer). A form
  compiled by the REPL is the body of a function taking the namespaces it
  references, views of them: a var reads the module's binding, assigning it
  (def, set!) sets the bindings of all of the module's instances, as a hot
  reload does. Vars the module doesn't define, and namespaces only defined
  at the REPL, live in an overlay.

  Messages are JSON-compatible objects, handle! takes the REPL's and replies
  with send!; connect-vite! carries them over Vite's hot module reloading
  websocket, and defines cljs_eval for the browser's console (see
  console-eval).")

(def ^:private registry
  (or (.-$CLJS_ESM js/globalThis)
      (set! (.-$CLJS_ESM js/globalThis) #js {:nses (js/Map.)})))

(def ^:private overlays (js/Map.))

(def ^:private views (js/Map.))

(defn- overlay [ns]
  (or (.get overlays ns)
      (let [o #js {}]
        (.set overlays ns o)
        o)))

(defn- has-own? [o k]
  (.call (.. js/Object -prototype -hasOwnProperty) o k))

(defn- set-var!
  "Sets var nm of namespace ns: the bindings of all instances of the
  namespace's module if it defines the var, otherwise the overlay."
  [ns nm v]
  (let [entry (.get (.-nses registry) ns)]
    (if (and entry (js-in nm (.-mod entry)))
      (doseq [set (.-sets entry)]
        (try
          (set nm v)
          ;; an earlier instance without the var
          (catch :default _)))
      (aset (overlay ns) nm v))
    v))

(defn- lookup [ns k]
  (let [entry (.get (.-nses registry) ns)]
    (cond
      (and entry (js-in k (.-mod entry))) (unchecked-get (.-mod entry) k)
      (and entry (has-own? (.-libs entry) k)) (unchecked-get (.-libs entry) k)
      :else (unchecked-get (overlay ns) k))))

(defn view
  "The view of namespace ns REPL forms reference it through."
  [ns]
  (or (.get views ns)
      (let [v (js/Proxy.
                #js {}
                #js {:get (fn [_ k]
                            (cond
                              (not (string? k)) nil
                              (= "$$set" k) (fn [nm v] (set-var! ns nm v))
                              ;; set! of a dynamic var, binding
                              (.startsWith k "$set$") (fn [v] (set-var! ns (subs k 5) v))
                              :else (lookup ns k)))
                     :set (fn [_ k v]
                            (set-var! ns k v)
                            true)
                     :has (fn [_ k]
                            (let [entry (.get (.-nses registry) ns)]
                              (or (and entry (js-in k (.-mod entry)))
                                  (has-own? (overlay ns) k))))})]
        (.set views ns v)
        v)))

(defn- root
  "The output directory's URL, the REPL sends paths relative to it."
  []
  ;; not new URL("../../", import.meta.url), which Vite rewrites to the URL of
  ;; an asset
  (let [url (.-url (js* "import.meta"))]
    (js/URL. "../../" url)))

(defn- import-path [path]
  (js* "import(/* @vite-ignore */ ~{})" (.-href (js/URL. path (root)))))

(defn- load-ns
  "A promise of namespace ns' view, once its module (at path, relative to
  the output directory) ran. Namespaces without a path only exist at the
  REPL."
  [ns path]
  (if (or (nil? path) (.has (.-nses registry) ns))
    (js/Promise.resolve (view ns))
    (-> (import-path path)
        (.then (fn [mod]
                 ;; registered by the module, unless it's compiled without
                 ;; :esm-repl
                 (when-not (.has (.-nses registry) ns)
                   (.set (.-nses registry) ns
                     #js {:mod mod :sets #js [(.-$$set mod)] :libs #js {} :gen 1}))
                 (view ns))))))

(defn- load-ref
  "A promise of what a reference [alias ns kind path] of a REPL form is
  bound to: a namespace's view (kind ns), a module's namespace object
  (module, i.e. a goog shim) or its default export (default, Closure Library
  namespaces run by the compatibility layer)."
  [[_ ns kind path]]
  (case kind
    "ns"      (load-ns ns path)
    "module"  (import-path path)
    "default" (.then (import-path path) #(.-default %))))

(defn- error-reply [e]
  (if (instance? js/Error e)
    #js {:error   (or (ex-message e) (str e))
         :stack   (.-stack e)
         :exData  (some-> (ex-data e) pr-str)}
    #js {:error (pr-str e)}))

(defn- print-value [v {:strs [printLength printLevel]}]
  (binding [*print-length* printLength
            *print-level*  printLevel]
    (pr-str v)))

(defn- capture
  "Calls f with the output printed meanwhile captured into the arrays out
  and err, still printed where it would be, i.e. the console, which prints
  lines."
  [f out err]
  (let [print-fn     *print-fn*
        print-err-fn *print-err-fn*]
    (binding [*print-newline* true
              *print-fn*      (fn [s]
                                (.push out s)
                                (when (and print-fn (not= "\n" s)) (print-fn s)))
              *print-err-fn*  (fn [s]
                                (.push err s)
                                (when (and print-err-fn (not= "\n" s)) (print-err-fn s)))]
      (f))))

(def ^:private console-evals
  "The console's evaluations waiting for the REPL, {id {:resolve :reject
  :opts}}, and the value of their last form evaluated, :value."
  (atom {}))

(def ^:private console-ids (atom 0))

(defn- eval-js
  "Evaluates a compiled REPL form, replies with its printed value, or its
  error. Keeps the value of a console evaluation (see console-eval)."
  [{:strs [code refs repl] :as msg} reply!]
  (-> (js/Promise.all (into-array (map load-ref refs)))
      (.then
        (fn [args]
          (let [out   #js []
                err   #js []
                done! (fn [m]
                        (reply! (doto m
                                  (aset "out" (.join out ""))
                                  (aset "err" (.join err "")))))
                fail! (fn [e]
                        (when repl (set! *e e))
                        (done! (error-reply e)))
                ok!   (fn [v]
                        (when repl
                          (set! *3 *2)
                          (set! *2 *1)
                          (set! *1 v))
                        (when-let [k (get msg "keep")]
                          (when (contains? @console-evals k)
                            (swap! console-evals assoc-in [k :value] v)))
                        (done! #js {:value (print-value v msg)}))]
            (try
              (let [f (js/Reflect.construct js/Function (into-array (concat (map first refs) [code])))
                    v (capture #(.apply f nil args) out err)]
                (if (and (get msg "await") (some? v) (fn? (.-then v)))
                  (.then v ok! fail!)
                  (ok! v)))
              (catch :default e
                (fail! e))))))
      (.catch (fn [e] (reply! (error-reply e))))))

(def ^:private stamp-waiters (atom []))

(defn- stamps-reached?
  "Whether the namespaces of want, {ns stamp}, run the module of that
  compile (see cljs.compiler/emit-esm-repl-footer), or aren't loaded:
  nothing to reload."
  [want]
  (every? (fn [[ns stamp]]
            (let [entry (.get (.-nses registry) ns)]
              (or (nil? entry) (= stamp (.-stamp entry)))))
    want))

(defn- check-stamp-waiters! []
  (let [[ready waiting] ((juxt filter remove) #(stamps-reached? (:want %)) @stamp-waiters)]
    (reset! stamp-waiters (vec waiting))
    (doseq [{:keys [reply!]} ready]
      (reply! #js {:value "true"}))))

(defn- await-stamps
  "Replies once the namespaces of want, {ns stamp}, run the module of that
  compile, i.e. its hot reload applied (and the after-load hooks ran),
  false after timeout ms."
  [want timeout reply!]
  (if (stamps-reached? want)
    (reply! #js {:value "true"})
    (let [waiter {:want want :reply! reply!}]
      (swap! stamp-waiters conj waiter)
      (js/setTimeout
        (fn []
          (when (some #(identical? waiter %) @stamp-waiters)
            (swap! stamp-waiters (fn [ws] (vec (remove #(identical? waiter %) ws))))
            (reply! #js {:value "false"})))
        timeout))))

(def ^:private tag-key "cljs-esm-repl-tag")

(defn- session-storage []
  (try
    (.-sessionStorage js/globalThis)
    (catch :default _ nil)))

(defn- tag []
  (some-> (session-storage) (.getItem tag-key)))

(defn- format-warning [{:strs [message file line column]}]
  (str "WARNING: " message (when file (str " at " file ":" line ":" column))))

(defn- console-result
  "Settles the console evaluation id with the REPL's result (see
  cljf.esm.repl/cljs-eval)."
  [id {:strs [results warnings error stack] :as result}]
  (when-let [{:keys [resolve reject value opts]} (get @console-evals id)]
    (swap! console-evals dissoc id)
    (doseq [w warnings]
      (js/console.warn (format-warning w)))
    (if error
      (reject (doto (js/Error. error)
                (aset "stack" (or stack error))
                (aset "result" (clj->js result))))
      (resolve (if (:print opts) (last results) value)))))

(defn console-eval
  "Evaluates the ClojureScript of string code, compiled by the REPL, in this
  runtime: returns a promise of the value of its last form, rejected with
  an Error if a form failed or has compiler warnings (which aren't
  evaluated). Forms are evaluated in the namespace of the console's last
  in-ns, default cljs.user.

  opts:
  - :ns - evaluate in this namespace
  - :await - the value of a promise
  - :print - the printed value instead
  - :timeout - give up waiting after these ms, default 60000"
  [send! code opts]
  (let [{:keys [ns timeout] :as opts} (cond-> opts (object? opts) (js->clj :keywordize-keys true))]
    (js/Promise.
      (fn [resolve reject]
        (let [id (swap! console-ids inc)]
          (swap! console-evals assoc id {:resolve resolve :reject reject :opts opts})
          (send! #js {:op "console-eval" :rid id :code code :ns ns :await (boolean (:await opts))})
          (js/setTimeout
            (fn []
              (when (contains? @console-evals id)
                (swap! console-evals dissoc id)
                (reject (js/Error. "No reply from the REPL, is the dev server running?"))))
            (or timeout 60000)))))))

(defn handle!
  "Handles message msg of the REPL, replies with send!."
  [msg send!]
  (let [msg    (js->clj msg)
        id     (get msg "id")
        reply! (fn [m]
                 (aset m "op" "result")
                 (aset m "id" id)
                 (send! m))]
    (case (get msg "op")
      "eval"      (eval-js msg reply!)
      "load"      (-> (js/Promise.all (into-array (map load-ref (get msg "refs"))))
                      (.then (fn [_] (reply! #js {:value "nil"}))
                             (fn [e] (reply! (error-reply e)))))
      "await-stamps" (await-stamps (get msg "stamps") (get msg "timeout" 10000) reply!)
      "tag"       (let [tag (get msg "tag")]
                    (when-let [storage (session-storage)]
                      (if tag
                        (.setItem storage tag-key tag)
                        (.removeItem storage tag-key)))
                    (reply! #js {:value (pr-str tag)}))
      "console-result" (console-result (get msg "rid") (get msg "result"))
      "welcome"   nil
      nil)))

(defn- document [] (.-document js/globalThis))

(defn- hello []
  (let [doc (document)]
    #js {:op        "hello"
         :url       (some-> (.-location js/globalThis) .-href)
         :title     (some-> doc .-title)
         :userAgent (some-> (.-navigator js/globalThis) .-userAgent)
         :visible   (if doc (= "visible" (.-visibilityState doc)) true)
         :focused   (boolean (and doc (.hasFocus doc)))
         :tag       (tag)
         ;; the output directory's URL, the REPL maps stack traces to the
         ;; ClojureScript sources with the source maps there
         :root      (.-href (root))}))

(defn connect-vite!
  "Connects to the REPL through Vite's hot module reloading websocket (or the
  channel of the ssr environment's module runner), hot is import.meta.hot.
  Says hello again when the websocket reconnects, reports focus and
  visibility changes, which pick the runtime evaluating forms by default.
  Defines cljs_eval(code, opts), console-eval for the browser's console:
  cljs_eval(\"(+ 1 2)\"), cljs_eval(\"(foo)\", {ns: \"my.app\", await:
  true}).

  Connects again when the module is evaluated again (a hot reload of it,
  whose listeners Vite drops): the connection made last handles the REPL's
  messages."
  [^js hot]
  (let [send!    (fn [m] (.send hot "cljs:repl" m))
        token    #js {}
        current? #(identical? token (.-runtime registry))
        state    (fn [focused]
                   #js {:op      "focus"
                        :visible (= "visible" (.-visibilityState (document)))
                        :focused focused
                        :title   (.-title (document))})]
    (set! (.-runtime registry) token)
    (set! (.-cljs_eval js/globalThis)
      (fn [code opts]
        (console-eval send! code opts)))
    (.on hot "cljs:repl" #(when (current?) (handle! % send!)))
    (.on hot "vite:ws:connect" #(when (current?) (send! (hello))))
    ;; hot reloads re-register modules, after-load hooks have run
    (.on hot "vite:afterUpdate" #(when (current?) (check-stamp-waiters!)))
    (set! (.-registered registry) (fn [_] (js/setTimeout check-stamp-waiters! 0)))
    ;; the first instance's listeners report for the later ones too
    (when (and (document) (not (.-connected registry)))
      (.addEventListener js/globalThis "focus" #(send! (state true)))
      (.addEventListener (document) "visibilitychange" #(send! (state (.hasFocus (document))))))
    (set! (.-connected registry) true)
    (send! (hello))))

(when-let [hot (js* "import.meta.hot")]
  (connect-vite! hot))
