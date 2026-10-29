(ns {{ns}}
  (:require ["hono" :refer [Hono]]))

;; kept across hot reloads
(defonce clicks (atom 0))

(defn- state [c]
  (.json c #js {:clicks @clicks}))

;; #x [...] is an element of Hono's JSX (hono/jsx, vite.config.ts'
;; jsxImportSource), rendered to HTML by c.html
(defn- page []
  #x [:html
      [:head [:title "{{name}}"]]
      [:body
       [:h1 "Hello Hono!"]
       [:p "Edit " [:code "src/{{nsDir}}/core.cljs"] " and save to hot reload"]]])

(def app
  (doto (Hono.)
    (.get "/" (fn [c] (.html c (page))))
    (.get "/clicks" state)
    (.post "/clicks" (fn [c] (swap! clicks inc) (state c)))))
