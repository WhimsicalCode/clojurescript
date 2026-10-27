# {{name}}

A [Hono](https://hono.dev) backend in ClojureScript, compiled by
[Clojurific](https://github.com/WhimsicalCode/clojurific) and bundled by
[Vite](https://vite.dev).

- `npm run dev` starts the dev server, which hot reloads changed namespaces
- `npm run repl` starts a ClojureScript REPL evaluating in the backend the dev
  server runs (start `npm run dev` first)
- `npm run build` bundles the backend into `dist/index.js`, a Node.js server
  (no `node_modules` needed)
- `npm start` runs it (on port 3000)

The app is `src/{{nsDir}}/core.cljs`. It's compiled as a `:js-entries` module
exporting the app (see `vite.config.ts`), which
[`@hono/vite-dev-server`](https://github.com/honojs/vite-plugins) serves and
`@hono/vite-build` bundles. Changed namespaces are hot reloaded in place:
`defonce` keeps its value.

ClojureScript dependencies go in `deps.edn`, npm packages in `package.json`.
The compiler needs Java 17 or later: `npx cljf setup-java` downloads one.

Editors connect to the dev server's nREPL server, its port in `.nrepl-port`
(i.e. Calva's "Connect to a running REPL", CIDER's `cider-connect-clj`), and
evaluate `(cljf.esm.repl/repl)` to switch the session to ClojureScript.
