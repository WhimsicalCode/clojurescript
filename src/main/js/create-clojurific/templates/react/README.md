# {{name}}

A ClojureScript app compiled by [Clojurific](https://github.com/WhimsicalCode/clojurific)
and bundled by [Vite](https://vite.dev).

- `npm run dev` starts the dev server, which hot reloads changed namespaces
- `npm run repl` starts a ClojureScript REPL evaluating in the pages the dev
  server runs (start `npm run dev` and open the page first)
- `npm run build` bundles the app into `dist/`
- `npm run preview` serves the bundle

The app is `src/{{nsDir}}/core.cljs`, React components rendering `#x`
literals: `#x [:div {:class "a" :on-click f} child]` is a React element,
hiccup compiled to `react/jsx-runtime` calls with its props map literals
as JavaScript objects (see Clojurific's README, JSX literals). ClojureScript dependencies go in
`deps.edn`, npm packages in `package.json`. The compiler needs Java 17 or
later: `npx cljf setup-java` downloads one.

Editors connect to the dev server's nREPL server, its port in `.nrepl-port`
(i.e. Calva's "Connect to a running REPL", CIDER's `cider-connect-clj`), and
evaluate `(cljf.esm.repl/repl)` to switch the session to ClojureScript. Its
cider-nrepl middleware completes the build's namespaces and vars and the
runtime's JavaScript properties, for editors and Tab in `npm run repl`.
