;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.jsx
  "#x (or #jsx) literals: hiccup compiled to calls of the JSX runtime, see x."
  (:require [clojure.string :as string])
  (:import [cljs.tagged_literals JSValue]))

;; prop names, as cljf.jsx (cljs) converts them at runtime

(defn- camel [^String s]
  (string/replace s #"-([a-z])" (fn [[_ c]] (string/upper-case c))))

(defn prop-name
  "The JavaScript name of prop key k: strings as they are, :class className,
  :for htmlFor, data-* and aria-* as they are, kebab-case camelCased."
  [k]
  (cond
    (string? k) k

    (simple-keyword? k)
    (let [n (name k)]
      (cond
        (= "class" n) "className"
        (= "for" n) "htmlFor"
        (or (string/starts-with? n "data-") (string/starts-with? n "aria-")) n
        :else (camel n)))

    :else
    (throw (ex-info (str "#x props' keys are strings or unqualified keywords, not " (pr-str k))
                    {:key k}))))

(defn style-name
  "The JavaScript name of style key k: custom properties (--x) as they are,
  kebab-case camelCased."
  [k]
  (let [n (if (string? k) k (name k))]
    (if (string/starts-with? n "--") n (camel n))))

;; code: [template args], a js* template and the forms of its ~{}

(defn- arg [form] ["~{}" [form]])

(defn- pos
  "Form's source position (reader metadata), for the source map entries of
  the forms generated for it."
  [form]
  (select-keys (meta form) [:line :column :end-line :end-column :file]))

(defn- wrap
  "(f form), at form's position."
  [f form]
  (with-meta (list f form) (pos form)))

(defn- js-obj-code
  "The code of a JavaScript object literal of entries, [:spread code] or
  [name code]."
  [entries]
  (let [parts (map (fn [[k [t args]]]
                     [(if (= :spread k) (str "..." t) (str (pr-str k) ": " t)) args])
                   entries)]
    [(str "{" (string/join ", " (map first parts)) "}")
     (vec (mapcat second parts))]))

(def ^:private js-tags '#{js object array string number boolean})

(defn- js-tagged?
  "Whether form is known to be a JavaScript value (or a string, number or
  boolean): a #js literal, a form or local tagged as one."
  [env form]
  (boolean
    (or (instance? JSValue form)
        (js-tags (:tag (meta form)))
        (and (symbol? form)
             (js-tags (get-in env [:locals form :tag]))))))

(defn- x-form?
  "Whether form is another #x literal (cljf.jsx/x, or x referred)."
  [env form]
  (and (seq? form)
       (let [h (first form)]
         (and (symbol? h)
              (nil? (get-in env [:locals h]))
              (or (= 'cljf.jsx/x h)
                  (and (= 'x h) (= 'cljf.jsx (get-in env [:ns :use-macros 'x]))))))))

(def ^:private ^:dynamic *runtime*
  "The namespace (or alias) of the JSX runtime's jsx, jsxs and Fragment."
  'cljf.jsx)

(defn- rt [sym] (symbol (str *runtime*) (str sym)))

(declare element)

(defn- element-vector?
  "Hiccup in a #x literal: [:tag ...] or [Component ...]."
  [form]
  (and (vector? form) (or (keyword? (first form)) (symbol? (first form)))))

(defn- static-child?
  "Whether child form evaluates to something React renders as it is: a
  string, number, boolean, nil, JavaScript value or element. Then it isn't
  converted at runtime, the branches of if and when, and the bodies of do
  and let, are looked into."
  [env form]
  (or (string? form) (number? form) (boolean? form) (nil? form)
      (js-tagged? env form)
      (x-form? env form)
      (and (seq? form)
           (symbol? (first form))
           (nil? (get-in env [:locals (first form)]))
           (let [[h & more] form]
             (case h
               (if if-not) (every? #(static-child? env %) (rest more))
               (when when-not do) (static-child? env (last more))
               ;; the locals they bind shadow env's, untagged
               (let when-let when-some)
               (let [bindings (first more)
                     names    (when (vector? bindings)
                                (->> (take-nth 2 bindings) (tree-seq coll? seq) (filter simple-symbol?)))]
                 (static-child? (update env :locals #(reduce dissoc % names)) (last more)))
               (str cljs.core/str) true
               false)))))

(declare element-form)

(defn- child-code [env form]
  (cond
    (element-vector? form) (arg (element-form env form))
    (static-child? env form) (arg form)
    :else (arg (wrap 'cljf.jsx/child form))))

(defn- style-code
  "A :style value: a map literal as a JavaScript object (keyword values as
  their names), other values converted at runtime unless known to be
  JavaScript values."
  [env v]
  (cond
    (map? v)
    (js-obj-code (map (fn [[k v]] [(style-name k) (arg (if (keyword? v) (name v) v))]) v))

    (or (nil? v) (string? v) (js-tagged? env v)) (arg v)
    :else (arg (wrap 'cljf.jsx/style v))))

(defn- prop-value
  "A prop's value: a keyword literal as its name, other forms as they are
  (a keyword they evaluate to isn't converted)."
  [v]
  (if (keyword? v) (name v) v))

(defn- class-code
  "A :class value: a string, a vector literal of classes joined (nil and
  false left out) when compiling, others joined at runtime when they're
  collections, see cljf.jsx/class-name."
  [env v]
  (cond
    (string? v) (arg v)
    (keyword? v) (arg (name v))

    (and (vector? v) (every? #(or (string? %) (keyword? %)) v))
    (arg (string/join " " (map #(if (keyword? %) (name %) %) v)))

    (or (nil? v) (js-tags (:tag (meta v)))
        (and (symbol? v) ('#{string} (get-in env [:locals v :tag]))))
    (arg v)

    :else (arg (wrap 'cljf.jsx/class-name v))))

(defn- prop-entries
  "The entries of a props map literal's JavaScript object, without :key."
  [env props]
  (mapcat
    (fn [[k v]]
      (case k
        :& (cond
             (map? v) (prop-entries env v)
             (or (nil? v) (js-tagged? env v)) [[:spread (arg v)]]
             :else [[:spread (arg (wrap 'cljf.jsx/props v))]])
        :key nil
        :style [["style" (style-code env v)]]
        :class [["className" (class-code env v)]]
        [[(prop-name k) (arg (prop-value v))]]))
    ;; spreads first, the map's other props override them
    (sort-by (fn [[k]] (if (= :& k) 0 1)) props)))

(defn- literal-key
  "[found? form] of the :key of a props map literal: its own, else its
  nested {:& {...}} literals' (the last one's)."
  [props]
  (if (contains? props :key)
    [true (:key props)]
    (let [nested (filter map? (keep (fn [[k v]] (when (= :& k) v)) props))]
      (or (last (filter first (map literal-key nested))) [false nil]))))

(defn- runtime-spreads
  "The spreads of a props map literal converted at runtime, nested {:& {...}}
  literals' too, in order."
  [env props]
  (mapcat (fn [[k v]]
            (when (= :& k)
              (cond
                (map? v) (runtime-spreads env v)
                (or (nil? v) (js-tagged? env v)) nil
                :else [v])))
          props))

(defn- parse-tag
  "[type id classes] of an element's tag, :div.a.b#id's [\"div\" \"id\" [\"a\" \"b\"]]."
  [t]
  (cond
    (= :<> t) [(rt 'Fragment) nil nil]

    (keyword? t)
    (let [n     (name t)
          parts (re-seq #"[#.]?[^#.]+" n)
          tag   (if (#{\# \.} (first n)) "div" (first parts))]
      [tag
       (some #(when (string/starts-with? % "#") (subs % 1)) parts)
       (keep #(when (string/starts-with? % ".") (subs % 1)) parts)])

    :else [t nil nil]))

(defn- key-code
  "The code of an element's key, the runtime's key argument, nil without
  one: a literal :key (nil leaves the element without a key, as in
  Reagent), else the :key of the maps spread at runtime, the last one's
  that has one (spread-forms, locals the element binds them to)."
  [props spread-forms]
  (let [[found? k] (literal-key props)]
    (cond
      (and found? (or (string? k) (number? k))) (arg k)
      (and found? (keyword? k)) (arg (name k))
      found? ["(~{} ?? undefined)" [k]]
      (seq spread-forms)
      [(str "(" (string/join " ?? " (repeat (count spread-forms) "~{}")) ")")
       (vec (map #(list 'cljf.jsx/spread-key %) (reverse spread-forms)))])))

(defn- bind-spreads
  "Props with the forms of the maps spread at runtime that aren't locals
  replaced by gensyms, read twice (for the props and the key): [props
  bindings]."
  [env props]
  (let [bindings (volatile! [])
        walk     (fn walk [props]
                   (into (empty props)
                     (map (fn [[k v]]
                            (if (= :& k)
                              (cond
                                (map? v) [k (walk v)]
                                (or (nil? v) (js-tagged? env v) (symbol? v)) [k v]
                                :else (let [g (gensym "spread")]
                                        (vswap! bindings conj g v)
                                        [k (with-meta g (pos v))]))
                              [k v])))
                     props))]
    (let [props (walk props)]
      [props @bindings])))

(defn element
  "The code calling the JSX runtime for hiccup form [tag props? & children]:
  [js*-template args bindings], bindings to bind around it."
  [env [t & more :as form]]
  (let [[props children] (if (map? (first more)) [(first more) (rest more)] [nil more])
        [props bindings] (if (and props (not (first (literal-key props))) (seq (runtime-spreads env props)))
                           (bind-spreads env props)
                           [props []])
        [type id classes] (parse-tag t)
        props    (cond-> (or props {})
                   id (update :id #(or % id))
                   (seq classes)
                   (update :class (fn [c]
                                    (let [s (string/join " " classes)]
                                      (cond
                                        (nil? c) s
                                        (string? c) (str s " " c)
                                        (keyword? c) (str s " " (name c))
                                        (vector? c) (into [s] c)
                                        :else [s c])))))
        children (remove nil? children)
        entries  (concat (prop-entries env props)
                         (case (count children)
                           0 nil
                           1 [["children" (child-code env (first children))]]
                           [["children" (let [cs (map #(child-code env %) children)]
                                          [(str "[" (string/join ", " (map first cs)) "]")
                                           (vec (mapcat second cs))])]]))
        [pt pargs] (js-obj-code entries)
        [kt kargs] (key-code props (runtime-spreads env props))]
    [(str "~{}(~{}, " pt (when kt (str ", " kt)) ")")
     (vec (concat [(rt (if (< 1 (count children)) 'jsxs 'jsx)) type]
                  pargs
                  kargs))
     bindings]))

(defn- element-form
  "The form calling the JSX runtime for hiccup form, at its position."
  [env form]
  (let [[t args bindings] (element env form)
        call (with-meta (list* 'js* t args) (assoc (pos form) :tag 'js))]
    (if (seq bindings)
      (with-meta (list 'let* bindings call) (assoc (pos form) :tag 'js))
      call)))

(defmacro x
  "#x [:div {:class \"a\" :on-click f} \"Hi \" name] (or #jsx) is
  (cljf.jsx/x [...]): the element of hiccup, created by the JSX runtime
  (react/jsx-runtime): jsx(\"div\", {className: \"a\", onClick: f, children:
  [\"Hi \", name]}).

  A props map literal is a JavaScript object built where the element is,
  with JavaScript prop names (see prop-name), :style's map literal one with
  CSS names: only values the compiler can't see into are converted at
  runtime. {:& props} spreads props, a map converted (once per map) or a
  JavaScript object. :key is the runtime's key argument. [:<> ...] is a
  fragment, [:div.a#b] has id b and class a.

  Children vectors are elements too, other forms escape back to
  ClojureScript: #x again returns elements from them. Children that aren't
  known to be strings, numbers, JavaScript values or elements are converted
  at runtime, sequences (i.e. of for) to arrays."
  [form]
  (when-not (element-vector? form)
    (throw (ex-info (str "#x needs hiccup, [:tag ...] or [Component ...], not " (pr-str form))
                    {:form form})))
  (when-not (or (= 'cljf.jsx (get-in &env [:ns :name]))
                (some #{'cljf.jsx} (vals (get-in &env [:ns :requires]))))
    (throw (ex-info (str "#x needs cljf.jsx, which a namespace whose source file has #x literals"
                         " requires implicitly: (:require [cljf.jsx]) in the ns of "
                         (get-in &env [:ns :name]))
                    {:form form})))
  ;; a namespace's own :jsx-import-source, see cljs.analyzer/implicit-jsx-require
  (binding [*runtime* (if (get-in &env [:ns :requires 'cljf-jsx-runtime]) 'cljf-jsx-runtime 'cljf.jsx)]
    (element-form &env form)))
