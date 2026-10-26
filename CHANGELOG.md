# Changelog

Changes since Clojurific was forked from ClojureScript. For ClojureScript's own
releases, see its [changelog](https://github.com/clojure/clojurescript/blob/master/changes.md).

## 0.12.8 [Next]

### Changed

- The Vite plugin is named `cljf` instead of `cljs`: its log lines start with `[cljf]`, and `create-clojurific`'s projects and the docs import it as `cljf` (`import cljf from 'clojurific/vite'`).

### Fixed

- `:parallel-build` compile errors name their file, line, column and the macro whose expansion failed instead of only the innermost message, the watcher no longer stops (and stops recompiling) when an error's data can't be written as JSON, and a failed namespace's queued emits no longer print a `Stream closed` trace each nor keep the JVM from exiting.

## 0.12.7 [2026-10-11]

### Added

- `npm create clojurific@latest` scaffolds a [Hono](https://hono.dev/) backend (`--template hono`): its app is a `:js-entries` export the dev server runs and hot reloads with `@hono/vite-dev-server`, `npm run build` bundles it into a Node.js server, `npm run repl` evaluates in it. The framework prompt links to each framework's site.
- The REPL evaluates in servers the Vite dev server's ssr environment runs: under `:esm-repl`, `:js-entries` modules import the REPL runtime first, which connects through the module runner's hot channel. The plugin's `ssrEntries` option runs entries in the ssr environment once compiled, so the REPL has a runtime before the server's first request.

### Fixed

- The REPL stopped answering a runtime whose REPL runtime module was hot reloaded (i.e. by a late file event of the dev server's first compile): Vite drops the listeners of the module's previous instance, the new instance now connects again.

## 0.12.6 [2026-10-10]

### Changed

- Array map lookups of a keyword first check the index the keyword was last found at (a new `_idx` field of `Keyword`), and scan the keys only when it doesn't hold that keyword: maps made by the same code share the order of their keys (about 4% faster on the Whimsical app's benchmarks). Keyword constants are emitted with the field initialized in ES module output; classic builds' emitted code is as before.
- The call site dispatch below (protocol methods called at the call site of `get`, keyword invokes, `nth`, `seq`, `first`, `next`, `count`, `=`, `empty?`, `not-empty`, `vector?`, `map?` and map destructuring) is in ES module output only: classic builds' code size is as before.
- In fn bodies, `let`s in expression position (`or`, `and`, higher order calls binding their arguments, ...) assign their locals in a comma expression instead of binding them in an IIFE, and `do`s in expression position are comma expressions: their functions allocate less and V8 optimizes them better (about 4% faster on the Whimsical app's benchmarks).
- In fn bodies, a `let` binding initialized by a `loop`, `case`, `try` or `letfn` (or an `if`, `do` or `let` ending in one) is assigned from statements instead of an IIFE. At a namespace's top level it keeps the IIFE: its statements would run in module scope, where a loop over module variables is several times slower than over a function's locals.
- `hash` of a keyword reads the hash the keyword caches (computing it once through its `-hash` method when it isn't cached yet), and of other `IHash` values calls their `-hash` method directly instead of through `-hash`'s dispatch: hash maps with keyword keys are faster.
- `mapv`, `filterv`, `vec` and `into` a vector add to their transient vector through its method rather than `-conj!`, which dispatches on every kind of transient collection.
- `merge` of up to two maps and `assoc` of two key/value pairs have fixed arities: their calls don't build a seq of their arguments.
- `assoc` of more than two key/value pairs reads the remaining pairs from its arguments' array by index instead of walking them with `first`, `second` and `nnext` (about 1.4% faster on the Whimsical app's benchmarks).
- A multimethod remembers the method of its last dispatch value, used while its method cache is unchanged and the next dispatch value is identical: repeated dispatch skips the cache lookup.
- Multimethods read their method cache and hierarchy atoms directly instead of through `deref`'s protocol dispatch, and look up the method cache with `get`: dispatch is faster.
- `random-uuid` formats its hex digits from a table, about 2.5 times faster, with the same UUIDs for the same `Math.random` numbers.
- `compare` of two strings skips the `IComparable` lookup when `IComparable` isn't extended to strings: sorted maps and sorts with string keys are faster.
- Map destructuring of a local tests at the call site whether it's a seq (keyword arguments) and only then calls `--destructure-map`, anything else is the value itself (about 0.7% faster on the Whimsical app's benchmarks, about 0.15% more compressed JavaScript).
- `(= x y)` of a local `x` and a local or constant `y` is true when they're identical, else calls `x`'s `-equiv` method at the call site when it has one, anything else through `=` as before (about 0.8% faster on the Whimsical app's benchmarks, about 0.1% more JavaScript).
- `(empty? x)`, `(not-empty x)`, `(vector? x)` and `(map? x)` of a local `x` call the protocol method their checks use at the call site when `x` has it: `empty?` is `(zero? (-count x))`, `not-empty` is `x` when `(-seq x)` isn't nil, `vector?` and `map?` are true; anything else through the function as before (about 1% faster on the Whimsical app's benchmarks, about 0.1% more JavaScript).
- `(count x)` of a local `x` calls `x`'s `-count` method at the call site when it has one, as `seq` below (about 1.4% faster on the Whimsical app's benchmarks, about 0.2% more JavaScript).
- `(seq x)`, `(first x)` and `(next x)` of a local `x` call `x`'s `-seq`, `-first` or `-next` method at the call site when it has one, as `get` below, anything else through the function as before (about 1.4% faster on the Whimsical app's benchmarks, about 0.5% more gzipped JavaScript, slightly less with brotli).
- `(nth v i)` and `(nth v i not-found)` of a local `v` and a constant number `i` (as sequential destructuring compiles to) or a local `i` that is a number, call `v`'s `-nth` method at the call site when it has one, as `get` below (about 0.8% faster on the Whimsical app's benchmarks, about 0.3% more gzipped JavaScript).
- `(get-in m [k1 k2 ...])` with a literal path of constant keys compiles to the `get`s it makes, each a call site lookup of a local as `get`'s below, without building the path vector (about 1% faster on the Whimsical app's benchmarks, +0.15% unminified JavaScript). Other `get-in` calls are unchanged.
- `str` of a single string returns it without calling its `toString` through a call site every type goes through: `str` with string arguments is faster (about 2% on the Whimsical app's benchmarks).
- `key-test` (hash map key comparison) doesn't test `keyword-identical?` before `=`, which compares keywords the same way.
- `=` of a keyword and another value compares them as the keyword's `-equiv` does without dispatching `-equiv` on every type.
- A `defn` of one fixed arity calls itself directly in its body, as other code calls it once it's defined, rather than through the higher order invoke.
- A fn created in a loop is wrapped to capture only the loop's locals it refers to, and not wrapped when it refers to none: less JavaScript.
- In ES module output, the call site lookups of `get` and keyword invokes below test for the method with optional chaining (`m?.method`), about 2% less unminified JavaScript.
- `(get m k)` and `(get m k not-found)` of a local `m` and a constant or local `k`, as map destructuring compiles to, call `m`'s `-lookup` method at the call site when it has one, like keyword invokes below (about 2% faster on the Whimsical app's benchmarks, about 1.5% more gzipped JavaScript).
- A keyword invoked on a local, `(:k m)` and `(:k m not-found)`, calls the local's `-lookup` method at the call site when it has one, as `get` would, so each call site's lookups are only as polymorphic as its maps (about 2% faster on the Whimsical app's benchmarks, about 1% more gzipped JavaScript).
- The `goog.string` shim's `startsWith` and `endsWith` (`clojure.string/starts-with?`, `ends-with?`) use the native string methods for string arguments, about twice as fast.
- `last` and `second` of a persistent vector read it by index instead of walking a seq: `last` of a vector is no longer linear.
- `some`, `every?` and `get-in` walk persistent vectors by index instead of allocating a seq per element (about 5% faster on the Whimsical app's benchmarks).
- `aclone` clones arrays with `.slice()`, about twice as fast for the 32 element nodes transients and hash maps clone, and `persistent!` of a transient vector trims its tail with it. Its result is still inferred as an array.

### Fixed

- `cljf repl` waited forever when the nREPL server closed the connection before the REPL had switched to ClojureScript (the server stopping or restarting while it connects): it now fails, saying the server closed the connection.

## 0.12.5 [2026-10-09]

### Changed

- The fork's namespaces moved from `cljs.*` to `cljf.*`: `cljs.esm` is `cljf.esm` (`clojure -M -m cljf.esm`), and so are its REPL (`cljf.esm.repl`, `(cljf.esm.repl/repl)`), `cljf.esm.lazy` and the test runners `cljf.esm.node-test` and `cljf.esm.karma`.
- `defclass` moved from `cljs.core` to `cljf.x`: require it with `[cljf.x :refer [defclass]]`.

## 0.12.4 [2026-10-08]

### Added

- `npm create clojurific@latest` (the npm package `create-clojurific`) scaffolds a Vite project, Vanilla, Reagent or UIx, with its namespace named after the project, then installs it and starts the dev server, offering to download Java when there's none. Its projects' dev server starts an nREPL server, and `npm run repl` evaluates in their pages.
- `cljf repl`, a ClojureScript REPL in the terminal evaluating in the pages running a watched build: an nREPL client of the watcher's nREPL server (`.nrepl-port`), with multi-line forms and history.

### Fixed

- `exists?` of an npm module's property (`(exists? react/useEffectEvent)`, in UIx) checked globals that don't exist under ES modules, so it was always false, and Vite warned of the import.
- Namespaces loaded by pages' scripts or JavaScript imports (`:extra-main`) didn't import the `:preloads` or, when watched, the REPL runtime, so the REPL found no runtime to evaluate in.
- Namespaces read as fields of JavaScript globals (`(.. js/cljs -core -PersistentArrayMap -EMPTY)`, in cljs-bean) weren't rewritten to module references, and threw `cljs is not defined`.

## 0.12.3 [2026-10-08]

### Fixed

- ClojureScript sources imported only from JavaScript or TypeScript modules weren't compiled. The Vite plugin now follows the pages' modules through relative and root-absolute imports to find them.
- Released compilers (0.12.2 from npm and Clojars) recompiled every namespace on every build: output compiled by a version like `1.12.clojurific-0.12.2` wasn't recognised as up to date.
- Page scripts loading ClojureScript sources with a query or fragment (`/src/app.cljs?v=1`) weren't compiled or rewritten in dev.
- Builds of several pages with the same file name in different directories (`index.html`, `about/index.html`) kept only one of them.
- The Vite dev server no longer serves ClojureScript sources outside Vite's root and `server.fs.allow`.
- `cljf watch` and its JVM kept running after a SIGINT sent only to `cljf`, i.e. by an IDE or a process supervisor.

## 0.12.2 [2026-10-08]

### Added

- The Clojars artifact `com.whimsical/clojurific`, for the Clojure CLI and Leiningen.

### Changed

- The npm package gets the compiler from Clojars (`com.whimsical/clojurific` of its version) instead of including its sources, which makes it 35 KB instead of 650 KB.
- Released compilers report their version as ClojureScript's major and minor version with Clojurific's release, i.e. `1.12.clojurific-0.12.2`, in compiled files' headers and `cljs.core/*clojurescript-version*`, instead of a hash of their sources.

## 0.12.1 [2026-10-08]

### Added

- Forked from ClojureScript's master at [0142b46589](https://github.com/clojure/clojurescript/commit/0142b465892517c9f530ec180792443266dcbcb7), 26 commits after the 1.12.145 release.
- `:module-format :esm` compiles every namespace to an ES module without the Closure Compiler, leaving bundling, minification, npm packages and TypeScript to Vite. `cljs.esm` builds and watches with options files, profiles, build hooks and preloads, and builds reuse their previous output, recompiling the namespaces that depend on a changed API ([README.md](README.md)).
- A Vite plugin compiles for `vite build` and `vite build --watch` and watches for the dev server, which hot reloads changed namespaces. It shows compiler errors and warnings in Vite's overlay and pauses hot reloading while there are warnings, like shadow-cljs. Production bundles drop unused protocol implementations, shorten ClojureScript's property names and are minified, with source maps and licence comments kept. A project's `index.html` is the dev server's page and an input of builds, its scripts and JavaScript modules can load ClojureScript sources (`<script type="module" src="/src/my/app.cljs">`) whose namespaces are compiled without listing them in `:main`, and the plugin writes a manifest of the entry points' scripts for server-rendered pages.
- `:js-entries` generate entry modules exporting vars under JavaScript names, for libraries and workers.
- Generated test runners (`:test-runner`), run in Node.js or with Karma. Watching regenerates them as tests are added and removed.
- `:extra-main` adds namespaces to `:main`, as the Vite plugin does with the pages' scripts.
- `:optimize-constants` puts keyword and symbol constants in one shared module, `:warnings-as-errors` fails the compile on any warning, and `:checked-set-literals false` lets set literals collapse duplicates, as before CLJS-3415.
- shadow-cljs compatibility: `:npm-interop :shadow` binds CommonJS packages as shadow-cljs does, and `shadow.resource`, `shadow.lazy`'s API (`cljs.esm.lazy`) and `defclass` work as in shadow-cljs.
- A REPL into the pages running a watched build, with an nREPL server, `cljs_eval` in the browser's console (only from the dev server's machine unless `replRemoteConsole` is set) and error stacks mapped to ClojureScript sources.
- `cljs.esm` fails at startup, naming both locations, when stock ClojureScript (`org.clojure/clojurescript`) is on the classpath too. `no-clojurescript/` is an empty project that replaces it in a whole dependency tree through `:override-deps`.
- The npm package `clojurific`, with the `cljf` command, the Vite plugin (`clojurific/vite`) and the Karma adapter (`clojurific/karma`). `cljf` needs only Java and Node.js: it resolves `deps.edn` with tools.deps, adds the compiler, removes stock ClojureScript, and caches the classpath. Without Java 17 or later it explains how to install it, or downloads Eclipse Temurin with `cljf setup-java`.

### Changed

- The coordinate is `com.whimsical/clojurific`. Namespaces stay `cljs.*`, so libraries written for ClojureScript work unchanged.
- The Closure Compiler is optional: only the classic compiler (`cljs.closure`, `cljs.build.api`, `cljs.main` and its REPLs) needs it, through the `:closure` alias. The analyzer reads the default externs' types from `cljs/externs/default.edn`.

### Fixed

- `cljs.analyzer.api/resolve-extern`, compiled to JavaScript, no longer expands the JVM's `with-compiler-env`, which warned of `cljs.core/class`.
