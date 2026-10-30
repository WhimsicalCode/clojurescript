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
hiccup compiled to `react/jsx-runtime` calls with its props map literals as
JavaScript objects (see Clojurific's README, JSX literals).

[React Compiler](https://react.dev/learn/react-compiler) memoizes the
`^:memo` components, `(defn ^:memo App [] ...)` (the Vite plugin's
`reactCompiler` option, `vite.config.ts`): they and the elements they create
re-render only when their props or state change. `{ compilationMode: 'all' }` instead of `true` compiles
every function React Compiler recognizes as a component or hook.

Editors connect to the dev server's nREPL server, its port in `.nrepl-port`
(i.e. Calva's "Connect to a running REPL", CIDER's `cider-connect-clj`), and
evaluate `(cljf.esm.repl/repl)` to switch the session to ClojureScript. Its
cider-nrepl middleware completes the build's namespaces and vars and the
runtime's JavaScript properties, for editors and Tab in `npm run repl`.
