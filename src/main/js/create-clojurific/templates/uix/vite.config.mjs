import cljf from 'clojurific/vite';

export default {
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      // the dev server starts an nREPL server, its port in .nrepl-port
      compilerOptions: { repl: { 'nrepl-port': 0 } },
    }),
  ],
};
