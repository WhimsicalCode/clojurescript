import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';
import devServer from '@hono/vite-dev-server';
import build from '@hono/vite-build/node';

// the backend: a module exporting the Hono app (:js-entries below)
const entry = 'target/cljs/cljs-esm-entries/server.js';

export default defineConfig({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      compilerOptions: {
        jsEntries: { server: { exports: { default: "'{{ns}}/app" } } },
        // #x literals are elements of Hono's JSX
        jsxImportSource: 'hono/jsx',
        // the dev server starts an nREPL server, its port in .nrepl-port, with
        // cider-nrepl's middleware: completion and lookup
        repl: { nreplPort: 0, middleware: ["'cider.nrepl/cider-middleware"] },
      },
      // the dev server runs the backend once compiled, not on its first
      // request: the REPL evaluates in it
      ssrEntries: ['server'],
      // no pages, no manifest of their scripts
      manifest: false,
    }),
    devServer({
      entry,
      // ssrLoadModule's runner has no HMR, it would re-run a changed module
      // (and its importers), the ssr environment's runner hot reloads
      // namespaces in place: defonce keeps its value
      loadModule: async (server, entry) => (await server.environments.ssr.runner.import(entry)).default,
    }),
    // the cljf plugin minifies, after pruning unused protocol implementations
    build({ entry, emptyOutDir: true, minify: false }),
  ],
});
