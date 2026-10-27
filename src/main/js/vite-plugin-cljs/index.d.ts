// Types of the Vite plugin (clojurific/vite), see index.js and README.md.
import type { Plugin } from 'vite';

/**
 * An EDN value written from JavaScript: strings starting with `:` are
 * keywords, with `'` symbols (`"'my.app/main"`), others strings.
 */
export type EdnValue = string | number | boolean | null | EdnValue[] | { [key: string]: EdnValue };

/** A namespace or var, as a symbol: `"'my.app"`, `"'my.app/main"`. */
export type CljSymbol = `'${string}`;

/** A build hook, `[fn, ...args]`: `["'uni.style.build/esm-hook", { outputTo: 'out.css' }]`. */
export type BuildHook = [CljSymbol, ...EdnValue[]];

/**
 * Compiler options (cljf.esm, see README.md's Compiling). Keys are keywords,
 * camelCase ones written in kebab-case (`nreplPort` is `:nrepl-port`), except
 * the names under `closureDefines` and `jsEntries`. Options not listed here
 * are passed on too.
 */
export interface CompilerOptions {
  /** The main namespaces. */
  main?: CljSymbol | CljSymbol[];
  /** Namespaces added to main. */
  extraMain?: CljSymbol[];
  /** Generated entry modules exporting vars under JavaScript names: `{ server: { exports: { default: "'my.server/app" } } }`. */
  jsEntries?: { [name: string]: { exports: { [jsName: string]: CljSymbol } } };
  /** Namespaces the main namespaces import first. */
  preloads?: CljSymbol[];
  /** Compile namespaces in parallel once their dependencies are compiled. */
  parallelBuild?: boolean;
  /** `':shadow'`: `["pkg" :as x]` binds CommonJS packages' `module.exports`, like shadow-cljs. */
  npmInterop?: ':shadow';
  /** goog-define values by name, as written: `{ 'goog.DEBUG': false }`. `goog.DEBUG` is false in release builds unless set. */
  closureDefines?: { [name: string]: string | number | boolean };
  /** Called with the build after each build, watch recompile and REPL form changing a namespace's analysis. */
  buildHooks?: BuildHook[];
  /** Warning types to true, false, `':warning'`, `':error'` or `':off'`, or one of them for the undeclared var warnings. */
  warnings?: boolean | ':warning' | ':error' | ':off' | { [type: string]: boolean | ':warning' | ':error' | ':off' };
  /** Every enabled warning fails the compile. */
  warningsAsErrors?: boolean;
  /** Keyword and symbol constants are exports of one module. */
  optimizeConstants?: boolean;
  /** false: set literals of runtime values collapse duplicates instead of throwing. */
  checkedSetLiterals?: boolean;
  /** Elide assert forms. */
  elideAsserts?: boolean;
  /** false: leave out deftests. */
  loadTests?: boolean;
  /** Emit source maps. */
  sourceMap?: boolean;
  /** The language level of the output, i.e. `':es2020'`. */
  languageOut?: string;
  /** false: don't write `.d.ts` files. */
  esmDts?: boolean;
  /** Fns run after hot reloads, like `^:dev/after-load`. */
  esmAfterLoad?: CljSymbol[];
  /** Fns run before hot reloads, like `^:dev/before-load`. */
  esmBeforeLoad?: CljSymbol[];
  /** Source directories to watch, defaults to the classpath directories. */
  watchDirs?: string[];
  /** The dev server's nREPL server: `{ nreplPort: 0 }` picks a free port, written to the port files. */
  repl?: false | { nreplPort?: number; nreplHost?: string; portFiles?: string[] };
  /** A generated test runner requiring the test namespaces. */
  testRunner?: { ns: CljSymbol; nsRegexp?: string; runner: CljSymbol };
  [option: string]: EdnValue | undefined;
}

export interface CljfOptions {
  /** The compiler's output directory, relative to cwd: `'out'` serves my.app as `/out/my/app.js`. */
  outputDir: string;
  /** deps.edn aliases of the classpath the compiler runs with, i.e. `['cljs']`. */
  aliases?: string[];
  /** Starts Clojure with the compiler on the classpath instead of the launcher, i.e. `['clojure', '-M:cljs']`. */
  command?: string[];
  /** The compiler's working directory, with the project's deps.edn, defaults to Vite's root. */
  cwd?: string;
  /** Compiler options file (EDN), relative to cwd. */
  config?: string;
  /** The config file's profile, defaults to dev when serving and release when building. */
  profile?: string;
  /** More compiler options, an EDN string or an object. */
  compilerOptions?: CompilerOptions | string;
  /** The file in Vite's outDir the entry points' scripts and preloads are written to, defaults to `'manifest.json'`; false doesn't write it. */
  manifest?: string | false;
  /** Remove unused protocol implementations and shorten property names in production bundles, defaults to true. */
  prune?: boolean;
  /** The `:js-entries` a build bundles, defaults to all. */
  entries?: string[];
  /** `:js-entries` the dev server runs in its ssr environment once compiled. */
  ssrEntries?: string[];
  /** Accept cljs_eval from pages on other hosts too, defaults to false. */
  replRemoteConsole?: boolean;
  /** Hot reload despite compiler warnings, like shadow-cljs' `:devtools {:ignore-warnings true}`: they go to the terminal only, defaults to false (hot reload pauses while any namespace has warnings, and the overlay shows them). */
  ignoreWarnings?: boolean;
}

/** The Vite plugin compiling ClojureScript to ES modules. */
export default function cljf(options: CljfOptions): Plugin;
