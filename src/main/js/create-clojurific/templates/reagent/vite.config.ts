import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      // the dev server starts an nREPL server, its port in .nrepl-port
      compilerOptions: { repl: { nreplPort: 0 } },
    }),
  ],
});
