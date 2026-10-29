import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      // the dev server starts an nREPL server, its port in .nrepl-port, with
      // cider-nrepl's middleware: completion and lookup
      compilerOptions: { repl: { nreplPort: 0, middleware: ["'cider.nrepl/cider-middleware"] } },
    }),
  ],
});
