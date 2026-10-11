(ns {{ns}}
  (:require ["hono" :refer [Hono]]))

;; kept across hot reloads
(defonce clicks (atom 0))

(defn- state [c]
  (.json c #js {:clicks @clicks}))

(def app
  (doto (Hono.)
    (.get "/" (fn [c] (.text c "Hello Hono! Edit src/{{nsDir}}/core.cljs and save to hot reload")))
    (.get "/clicks" state)
    (.post "/clicks" (fn [c] (swap! clicks inc) (state c)))))
