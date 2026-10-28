;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljf.jsx
  "#x (or #jsx) literals' runtime: the JSX runtime (react/jsx-runtime, a
  bundler alias picks another, i.e. preact/jsx-runtime) and the conversions
  of the values the compiler can't see into, see cljf.jsx/x."
  (:require ["react/jsx-runtime" :as rt])
  (:require-macros [cljf.jsx]))

(def jsx rt/jsx)
(def jsxs rt/jsxs)
(def Fragment rt/Fragment)

(defn- camel [s]
  (.replace s (js/RegExp. "-([a-z])" "g") (fn [_ c] (.toUpperCase c))))

(defn- prop-name* [n]
  (cond
    (identical? "class" n) "className"
    (identical? "for" n) "htmlFor"
    (or (.startsWith n "data-") (.startsWith n "aria-")) n
    :else (camel n)))

(def ^:private prop-names (js/Map.))

(defn prop-name
  "The JavaScript name of prop key k, as cljf.jsx/x names literal props."
  [k]
  (if (keyword? k)
    (let [fqn (.-fqn k)]
      (or (.get prop-names fqn)
          (let [n (prop-name* (name k))]
            (.set prop-names fqn n)
            n)))
    (str k)))

(defn- style-name [k]
  (let [n (if (keyword? k) (name k) (str k))]
    (if (.startsWith n "--") n (camel n))))

(def ^:private styles (js/WeakMap.))

(defn style
  "A :style value: a map as a JavaScript object (once per map), anything
  else as it is."
  [s]
  (if (map? s)
    (or (.get styles s)
        (let [o #js {}]
          (reduce-kv (fn [_ k v] (unchecked-set o (style-name k) (if (keyword? v) (name v) v))) nil s)
          (.set styles s o)
          o))
    s))

(def ^:private props-objs (js/WeakMap.))

(defn props
  "Spread props ({:& p}): a map as a JavaScript object (once per map, then
  spread into the element's own), anything else as it is."
  [p]
  (if (map? p)
    (or (.get props-objs p)
        (let [o #js {}]
          (reduce-kv (fn [_ k v]
                       (unchecked-set o (prop-name k) (if (keyword-identical? :style k) (style v) v)))
            nil p)
          (.set props-objs p o)
          o))
    p))

(defn child
  "A child the compiler can't see into: sequences (i.e. of for) as arrays,
  anything else as it is. Hiccup vectors outside #x are data, as in squint:
  they need #x."
  [c]
  (cond
    (vector? c)
    (throw (js/Error. (str "A vector isn't a #x child, " (pr-str c)
                           ": hiccup in ClojureScript code needs #x, i.e. (for [i xs] #x [:li i])")))

    (seq? c)
    (let [a #js []]
      (doseq [x c] (.push a (child x)))
      a)

    :else c))
