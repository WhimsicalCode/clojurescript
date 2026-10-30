(ns {{ns}}
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]))

;; #x [...] is a React element: hiccup compiled to react/jsx-runtime calls,
;; props map literals to JavaScript objects.
;;
;; React Compiler (vite.config.ts' reactCompiler) memoizes the ^:memo
;; components: clicking count re-renders App, but not the
;; Rows, whose labels don't change (React DevTools' "Highlight updates when
;; components render" shows it). Components follow React's rules: they read
;; props and state, not atoms or other mutable values, during render.

(defn ^:memo Row [^js props]
  #x [:li (.-label props)])

(defn ^:memo App []
  (let [[count set-count!] (react/useState 0)
        [rows set-rows!] (react/useState 3)]
    #x [:<>
        [:h1 "Clojurific + React Compiler"]
        [:div.card
         [:button {:type "button" :on-click #(set-count! inc)}
          "count is " count]
         [:button {:type "button" :on-click #(set-rows! inc)}
          "add a row"]]
        [:ul (for [i (range rows)]
               #x [Row {:key i :label (str "row " (inc i))}])]
        [:p.hint "Edit " [:code "src/{{nsDir}}/core.cljs"] " and save to hot reload"]]))

(defonce root (rdc/createRoot (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (.render root #x [App]))

(render)
