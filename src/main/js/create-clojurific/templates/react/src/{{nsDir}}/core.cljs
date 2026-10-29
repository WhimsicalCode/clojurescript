(ns {{ns}}
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]))

;; #x [...] is a React element: hiccup compiled to react/jsx-runtime calls,
;; props map literals to JavaScript objects

(defn App []
  (let [[count set-count!] (react/useState 0)]
    #x [:<>
        [:h1 "Clojurific + React"]
        [:div.card
         [:button {:type "button" :on-click #(set-count! inc)}
          "count is " count]]
        [:p.hint "Edit " [:code "src/{{nsDir}}/core.cljs"] " and save to hot reload"]]))

(defonce root (rdc/createRoot (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (.render root #x [App]))

(render)
