# ES module output without the Closure Compiler

`:module-format :esm` compiles every namespace to an ES module. Bundling,
minification, npm and TypeScript are left to standard JavaScript tooling
(Vite / Rolldown). The Closure Compiler isn't run, and isn't on the classpath.

Status: experimental. The ClojureScript runtime test suite passes, and the
Whimsical app (3037 namespaces) compiles, bundles and boots in both production
and development builds. See [Status](#status).

## Usage

### Compiling

```sh
clojure -M -m cljf.esm build '{:main my.app :output-dir "out"}'  # build
clojure -M -m cljf.esm watch @cljs.edn                            # watch, hot reload
```

or without the Clojure CLI, with the npm package's launcher (see
[Launcher](#launcher)): `npx cljf build …`, `npx cljf watch …`.

Options are EDN maps, `@file.edn` to read them from a file, and `:profile`
keywords (`cljf.esm/load-options`). Profiles are maps under `:profiles`, deep
merged over the rest; the profile names the build's `:mode`. `watch` defaults to
the `:dev` profile, `build` to `:release`:

```clojure
;; cljs.edn
{:main [my.app]
 :build-hooks [[my.build/hook {:some :arg}]]
 :profiles {:dev {:preloads [my.dev]}
            :release {:closure-defines {goog.DEBUG false}}}}
```

`cljf.esm/build` and `cljf.esm/watch` take the usual compiler options plus:

| option | |
|---|---|
| `:main` | a namespace or a collection of them, i.e. the app plus lazily loaded entries, optional with `:js-entries` |
| `:extra-main` | namespaces added to `:main`, i.e. by the Vite plugin, the namespaces of the pages' `.cljs` scripts |
| `:js-entries` | `{name {:exports {jsName my.ns/var}}}`, generated entry modules (`cljs-esm-entries/<name>.js`) exporting vars under JavaScript names, for libraries and workers; an undefined var fails the build |
| `:preloads` | namespaces the main namespaces import first |
| `:parallel-build` | compiles namespaces in parallel once their dependencies are compiled |
| `:npm-interop :shadow` | `["pkg" :as x]` binds CommonJS packages' `module.exports`, like shadow-cljs, needs the Vite plugin |
| `:closure-defines {goog.DEBUG false}` | `goog.DEBUG` and `goog-define`s are compile time constants |
| `:build-hooks` | `[[fn-sym & args]]`, called with the build (`:compiler-env`, `:namespaces` in dependency order, `:mode`, `:options`, `:trigger` and `:changed-namespaces`) after a build (`:trigger :build`), every watch recompile (`:watch`) and REPL forms changing a namespace's analysis (`:repl-eval`) |
| `:warnings` | a map of warning types (`true`, `false`, `:warning`, `:error`, `:off`), or one of them for the undeclared var warnings, like `cljs.closure` |
| `:warnings-as-errors true` | every enabled warning fails the compile, like shadow-cljs |
| `:optimize-constants true` | keyword and symbol constants are exports of one module, `cljs/core/constants.js`, instead of allocated at each use (`cljs.core` keeps its own) |
| `:checked-set-literals false` | set literals of runtime values collapse duplicates instead of throwing (before CLJS-3415) |
| `:esm-hmr` | hot reloading code, enabled by `watch` |
| `:esm-repl` | modules register themselves for the REPL, which main namespaces load first, enabled by `watch` with `:esm-hmr` |
| `:repl` | `{:nrepl-port 0 :nrepl-host "127.0.0.1" :port-files [".nrepl-port"]}`, `watch` starts an nREPL server (`0` picks a port), writes its port to the port files no other live server's port is in, and deletes them on exit; needs nREPL on the classpath |
| `:esm-dts false` | don't write `.d.ts` files |
| `:esm-after-load` / `:esm-before-load` | fns run around hot reloads, like `^:dev/after-load` |
| `:watch-dirs` | source directories, defaults to the classpath directories without the compiler's own |

The output directory has one module per namespace (`out/my/app.js`), plus
`goog.js` and `goog/*.js` shims, `goog-lib/`, the parts of the Closure Library
the build uses (see below), and `cljs-esm.json`, the main namespaces' modules
(the bundle's entry points).

A build reuses the output of the previous one: a namespace is recompiled when
its source changed, and so are the namespaces requiring it when its API (its
vars, their arities) changed, which their output may depend on (`:static-fns`
arity calls, `cljs.test`'s lists of tests). Changed macros aren't detected,
remove the output directory after changing a macro library.

Macros reading classpath resources call `cljf.esm/watch-resource!`, the watcher
then recompiles the namespace when the resource changes. `shadow.resource` is
provided for code written for shadow-cljs.

### Dependencies

`deps.edn` has the fork's dependencies, which don't include the Closure
Compiler (`com.google.javascript/closure-compiler`): `cljf.esm`, its REPL and
the analyzer (`cljs.analyzer.api`) don't need it. The classic compiler
(`cljs.closure`, `cljs.build.api`, `cljs.main` and the classic REPLs) does, and
fails with a `ClassNotFoundException` for `com.google.javascript.jscomp` classes
without it: add the `:closure` alias to every alias running them, i.e.
`clojure -M:closure:compiler.test:compiler.test.run` for the compiler tests,
`clojure -M:closure:cljs-repl` for `cljs.main`'s Node.js REPL (`:cljs-brepl`
and `:cljs-lite-repl` too).
`cljs.repl` loads `cljs.closure` on first use, so namespaces requiring
`cljs.repl` (its macros) compile without it.
`pom.template.xml` and `project.clj` are upstream's publishing setup, and still
list the Closure Compiler.

The fork's coordinate is `com.whimsical/clojurific`. Stock ClojureScript
(`org.clojure/clojurescript`, which many libraries depend on) has the same
namespaces, so the classpath order would decide whose namespaces load, and its
jar's precompiled classes win over the fork's sources. `cljf.esm`'s `build` and
`watch` fail when `cljs/analyzer.cljc` or `cljs/core.cljs` is on the classpath
more than once, listing where. Exclude it from the library bringing it in
(`:exclusions [org.clojure/clojurescript]`), or replace it in the whole tree
with the empty project in `no-clojurescript/`:
`:override-deps {org.clojure/clojurescript {:local/root "<fork>/no-clojurescript"}}`
(`:git/url`, `:git/sha` and `:deps/root "no-clojurescript"` for a git
dependency).

### Vite

`npm create clojurific@latest` scaffolds a project with the plugin (Vanilla,
Reagent, UIx, or a Hono backend), see `src/main/js/create-clojurific`.

`src/main/js/vite-plugin-cljs` runs the compiler: a one-shot build for
`vite build` (the main namespaces are the bundle's entry points), watch mode for
`vite`, compile errors go to Vite's error overlay. In watch mode Vite's file
watcher reports changed sources to the compiler, hot updates wait for the
compile and its build hooks to finish. Like shadow-cljs, compiler warnings
are shown in the overlay too, and hot reloading pauses while any namespace
has warnings: the output held back is reloaded once they're fixed. Pages
loaded meanwhile show the overlay of the current error or warnings.

```js
// vite.config.mjs
import cljs from 'clojurific/vite';

export default {
  plugins: [cljs({ aliases: ['cljs'], config: 'cljs.edn', outputDir: 'out' })],
};
```

The plugin starts the compiler with the launcher, on the classpath of the
project's `deps.edn` with `aliases`. `command` starts it with another
command instead, e.g. the Clojure CLI's `['clojure', '-M:cljs']`, cljf.esm's
arguments follow it.

The project's directory is Vite's root, and its `index.html` the dev server's
page and an input of builds. Like TypeScript files in a Vite project, pages
load ClojureScript sources, and the plugin compiles their namespaces as main
namespaces (`:extra-main`), so `:main` is optional:

```html
<!-- index.html -->
<script type="module" src="/src/my/app.cljs"></script>
```

The dev server serves the page with the compiled module's URL instead
(`/out/my/app.js`, which pages can load directly too), builds bundle it.
JavaScript and TypeScript modules can import sources as well
(`import './my/app.cljs'`), which loads the namespace; its vars aren't
exports, `:js-entries` export them under JavaScript names. The plugin finds
those sources by following the pages' modules (and JavaScript inputs) through
relative and root-absolute imports, not packages or aliases: list namespaces
imported otherwise in `:main`. A source's namespace comes from its `ns` form.
The pages and their imports are read when Vite starts: restart it after
adding a source.

Builds of more pages list them in `build.rollupOptions.input`
(`build.rolldownOptions.input` in Vite 8), the plugin adds the main namespaces
and `:js-entries` to them. The project's `package.json` needs `"type":
"module"` (or no type): with `"type": "commonjs"`, which `npm init` writes,
the bundler treats the project's `.js` files as CommonJS and wraps the
ClojureScript they import in initializers, which production bundles can't
prune; the plugin warns then.

`:js-entries` are bundle inputs too, their exports kept: a build of a library or
a Node service's worker (`build.ssr`) is one module exporting what the entry
names. The plugin's `entries` option picks the ones a build bundles. Its
`ssrEntries` option runs entries in the dev server's ssr environment once
they're compiled, i.e. a server's, which a plugin like `@hono/vite-dev-server`
imports from the same module runner: the REPL evaluates in it before its
first request.
`vite build --watch` runs the compiler's watch, and rebundles when it
recompiles.

For server rendered pages the plugin writes `manifest.json` to Vite's `outDir`,
each main namespace's module scripts and the chunks they import (to preload):
the Vite dev server's modules when serving, the hashed chunks of a build.

### Launcher

`src/main/js` is the npm package `clojurific` (`script/package-npm` packs it):
the `cljf` command, the Vite plugin (`clojurific/vite`) and the Karma adapter
(`clojurific/karma`). It has no compiler: the launcher gets the Clojars artifact
of the package's version, run from the repository it uses the fork's sources. Its
launcher (`src/main/js/cljf`) needs Java and Node.js, not the Clojure CLI:

```sh
npx cljf build '{:main my.app :output-dir "out"}'
npx cljf -A:test watch @cljs.edn :test
npx cljf classpath      # like clojure -Spath
npx cljf repl           # a ClojureScript REPL into the dev server's pages, see REPL
npx cljf setup-java     # downloads Eclipse Temurin, for machines without Java
```

It resolves the project's `deps.edn` (and the user's, as the Clojure CLI does)
with tools.deps, adding the compiler (`com.whimsical/clojurific` from Clojars,
unless the project lists it itself, e.g. as a git dependency) and replacing every
`org.clojure/clojurescript` with `no-clojurescript/`, then runs
`clojure.main -m cljf.esm` with the aliases' `:jvm-opts`. Dependencies are
downloaded into the local Maven repository (`~/.m2/repository`, or
`:mvn/local-repo`), shared with the Clojure CLI. The classpath is cached in the
project's `.cljf/cpcache` until a `deps.edn` it was resolved from changes,
`--force` resolves it again.

tools.deps can't download itself: the launcher downloads the jars listed in
`src/main/js/cljf/resolver.lock.json` with their SHA-256 (from Maven Central,
or `CLJF_MAVEN_REPO`), then runs the resolver (`clojurific.resolve`) with
them. `script/lock-resolver` writes the lock from `cljf/resolver/deps.edn`,
run it after changing that file. tools.deps' S3 transporter is left out:
`s3://` repositories aren't supported. Git dependencies need `git` on `PATH`.

Java 17 or later comes from `CLJF_JAVA`, `JAVA_HOME`, `PATH`, then the runtimes
`cljf setup-java [version]` installed into the per-user cache
(`~/Library/Caches/cljf`, `$XDG_CACHE_HOME/cljf`, `%LOCALAPPDATA%\cljf`, or
`CLJF_CACHE_DIR`). Without one, the launcher explains how to install it, and
offers to run `setup-java` in a terminal; `CLJF_INSTALL_JDK=1` runs it without
asking. setup-java gets Temurin's JRE (the latest LTS by default) from the
Adoptium API, checking the archive's SHA-256 against the API's.

Class data sharing (CDS) archives would load Clojure's classes faster, but the
JVM doesn't create them while the classpath has non-empty directories, which
the compiler's and the project's sources are.

### REPL

`watch` evaluates forms in the pages running the build: browser tabs, the
Electron window, and servers the dev server's ssr environment runs (runtimes).
Messages go through Vite's websocket (the ssr environment's module runner's
hot channel, in the dev server's process) and the plugin's pipe to the
compiler, nothing else listens on a port but the optional nREPL server
(`:repl`), which runs in the watcher's JVM:

```clojure
(require '[cljf.esm.repl :as repl])

(repl/runtimes)            ;=> [{:runtime-id 3 :url "..." :title "..." :visible true ...}]
(repl/cljs-eval "(+ 1 2)") ;=> {:results ["3"] :out "" :err "" :warnings [] :ns cljs.user :runtime-id 3}
(repl/cljs-eval "(foo)" {:ns 'my.app :runtime-id 3 :await true :timeout 10000})
(repl/load-file "src/my/app.cljs")
(repl/tag! 3 "A")          ; target with {:tag "A"}, survives reloading the page
(repl/repl)                ; this nREPL session evaluates ClojureScript, :cljs/quit to leave
```

`cljf repl` is that REPL in a terminal (`npm run repl` in
`create-clojurific`'s projects): an nREPL client, without Java, of the server
whose port is in `.nrepl-port` (or `--port`), waiting for the dev server to
write it. It switches its session with `(repl/repl)`, reads forms over several
lines until they're closed, keeps a history in `.cljf/repl-history`, and
interrupts the evaluation on Ctrl-C. The Vite plugin's projects start the
server with `compilerOptions: { repl: { 'nrepl-port': 0 } }` and
`nrepl/nrepl` in `deps.edn`.

- Forms are analyzed with the build's compiler environment and evaluated in
  the runtime focused last (visible ones first) unless `:runtime-id` or
  `:tag` pick one. The interactive REPL notes changes of its runtime.
- `def` redefines a namespace's var for the namespace's own code and its
  importers, as a hot reload does. Vars only defined at the REPL, and
  namespaces only created at the REPL (`in-ns`, `ns`), are the runtime's.
- `require` loads namespaces into the runtime. Namespaces outside the build
  are compiled by the watcher from then on, `:reload` recompiles and hot
  reloads them. npm modules can't be required at the REPL, a namespace's
  forms use the npm modules it requires.
- `load-file` compiles the file with the watcher and returns once the runtime
  applied the hot reload (or the warnings holding it back). A file outside the
  source directories, or an editor buffer differing from the file, is
  evaluated form by form instead.
- A form with compiler warnings isn't evaluated, unless `:warnings-ok`: the
  result has `:error` and `:warnings`, `:results` ends with
  `:cljf.esm.repl/failed`, and the forms after it aren't evaluated. The
  nREPL middleware replies with an `eval-error`.
- In the browser's console `cljs_eval("(+ 1 2)")`, `cljs_eval("(foo)", {ns:
  "my.app", await: true, print: true})` evaluates in its page, compiled by the
  REPL: a promise of the last form's value (printed with `print`), rejected
  for an error or warnings. The console keeps the namespace of its last
  `in-ns`. Compiling forms runs their macros in the compiler's JVM, so the
  Vite plugin accepts `cljs_eval` only from pages on the dev server's machine,
  unless its `replRemoteConsole` option is set. A proxy in front of the dev
  server has to set `X-Real-IP` to the client's address.
- `*1`, `*2`, `*3` and `*e` in the interactive REPL, `doc`, `source`, `dir`
  and `apropos` are answered from the compiler environment. Output printed
  during a form is its `:out` / `:err`, and still goes to the console.
  `:await true` waits for a promise's value. Errors' `:stack` is mapped to
  the ClojureScript sources (`my/app.cljs:12:3`, `<cljs repl>:1:2` for the
  forms evaluated) with the output's source maps.

## Interop

### npm packages and JavaScript / TypeScript files

String requires are plain imports, the bundler resolves them:

```clojure
(ns my.app
  (:require ["react" :as react]                ; npm package
            ["react-markdown" :default Markdown] ; default export
            ["./chart.ts" :as chart]           ; relative to this .cljs file
            ["/my/lib.js" :as lib]             ; on the classpath
            [rbush :as rbush]))                ; npm, like shadow-cljs
```

TypeScript is compiled by Vite, a TypeScript file or workspace package changed
in development is hot reloaded into the running app, no separate `tsc` process.

TypeScript imports ClojureScript modules like any other ES module, the compiler
writes `.d.ts` declarations next to them (fns with their arities and
docstrings):

```ts
import { describe } from '../out/app/state.js';
describe(1, 2); // error TS2554: Expected 1 arguments, but got 2.
```

### Classes

`cljf.x/defclass` (like shadow-cljs' `shadow.cljs.modern/defclass`)
defines a JavaScript class, for APIs constructing one with `new` or needing a
subclass, i.e. a Cloudflare Durable Object:

```clojure
(ns my.room
  (:require [cljf.x :refer [defclass]]))

(defclass Room
  (extends DurableObject)
  (constructor [this ctx env]
    (super ctx env)
    (set! (.-sessions this) #js []))
  Object
  (fetch [this request] ...))
```

`this` is bound once `(super ...)` called the base class' constructor, which a
constructor without a `(super ...)` call does first with its params. The
methods and protocol implementations are `extend-type`'s. It isn't specific to
`:module-format :esm`.

### `^:export`

Exported vars are also installed as globals (`my.app.init`), for scripts calling
into the build.

### Lazy loading

`cljf.esm.lazy` follows `shadow.lazy`'s API. `(lazy/loadable my.ns/var)` loads
the namespace with a dynamic `import()`, bundlers split it and what only it
requires into a separate chunk. Lazily loaded namespaces are additional `:main`
entries.

### Tests

`:test-runner {:ns my.test-runner :ns-regexp "-test$" :runner my.test/start}`
generates namespace `my.test-runner`, requiring the test namespaces of the
source directories matching `:ns-regexp`, and compiles it as a main
namespace. It calls `(my.test/start run-tests)`, run-tests running the tests
given a `cljs.test` env. `watch` regenerates it as test namespaces are added
and removed. Two runners come with the compiler:

- `cljf.esm.node-test/run`: runs the tests under Node.js, the process exits
  with 1 when any failed.
- `cljf.esm.karma/start`: reports to Karma, one result per test var. Karma
  loads the bundle through `src/main/js/karma-esm/adapter.js`, which imports
  the entry module named by Karma's `client.args`.

## Design

### Namespaces as modules

```js
import * as cljs$core from "../cljs/core.js";
import * as my$util from "./util.js";
var my$app$greet = (function my$app$greet(name){
return my$util.format(name);
});
export { my$app$greet as greet };
```

- A namespace's vars are module-local bindings (`my$app$greet`), references to
  other namespaces' vars go through namespace imports (`my$util.format`).
  Bundlers resolve these statically, tree-shake unused vars and rename them.
- All of the emitter's naming goes through `munge`: a var of another namespace
  becomes `ns$alias.name`, a var of the namespace being compiled
  `ns$alias$name`. Namespaces only referenced by fully qualified names
  (macroexpansions) are imported at the end of the module (imports are
  hoisted).
- Imported bindings are read-only. `set!` of another namespace's var, i.e.
  `binding` and `with-redefs`, calls a setter the owning module exports,
  `$set$name` for dynamic vars and `$$set` for any var.
- `def`s nested in functions are hoisted as `var` declarations.
- Multi-arity and variadic fns are `/*@__PURE__*/` annotated.
- `js/my.ns.foo` references to namespaces as JavaScript globals, and fields read
  from them (`(.. js/my -ns -foo)`), are rewritten to module references,
  `(exists? my.ns/foo)` checks the binding. `(exists? react/useEffectEvent)`
  checks the property of the npm module's binding.
- `js/eval` is an indirect eval, a direct one defeats tree shaking and renaming
  of the whole module.

### Closure Library

`cljs.core` uses little of the Closure Library. `src/main/cljs/cljf/esm/goog*`
are ES module versions of what it uses (`goog.typeOf`, `goog.object`,
`goog.string`, `goog.math.Long`, ...).

Other code using the Closure Library (cljs-time's `goog.date`, re-frame's
`goog.async`, cljs-http's `goog.Uri`, ...) and other Closure style libraries
(transit-js) gets the real thing: Closure Library files are wrapped as ES
modules (`goog-lib/`) run by Closure Library's `base.js`, with the debug loader
disabled, dependencies are imports. Members are resolved per reference, a shim
is used if it exports the member, otherwise the Closure Library. Its direct
`eval` calls (`goog.json.parse`, base.js' module loader) are made indirect: a
direct eval keeps minifiers from renaming the top level of the chunk it's in.

### Externs

Externs aren't needed: `:externs` and `:infer-externs` aren't used by
`cljf.esm`. The analyzer still reads the types of JavaScript's standard and
browser APIs from Closure's default externs, i.e. `isNaN` returns a boolean,
so `(if (js/isNaN x) ...)` compiles to `if(isNaN(x))` rather than a
`cljs.core.truth_` call. A build without `:externs-sources` (all `cljf.esm`
builds) reads them from `cljs/externs/default.edn`, the map
`cljs.externs/externs-map` parses, without docs and source locations
(`cljs.analyzer.api/resolve-extern`'s `:info` has no `:doc`). Only builds with
`:externs-sources` (the classic compiler's, with `:infer-externs`) parse
externs with the Closure Compiler. The classic REPLs (`cljs.main`,
`cljs.repl/repl`, `cljs.server.*`) don't set `:externs-sources` either, so
they get the same map: `(doc js/isNaN)` prints no JSDoc text.

`script/gen-default-externs` regenerates the file, run it after updating
the Closure Compiler. The `compiler` suite of the monorepo's
`clojurific.yml` fails when the file is out of date.

### Hot reloading

Modules accept their own updates (`import.meta.hot`). Importers keep the
bindings of the module instance they imported, a reloaded instance updates the
bindings of all previous ones through their `$$set`, so callers see new
definitions, like reloading a namespace under goog. A reloaded instance first
restores the previous instance's values, `defonce` keeps its value.
`^:dev/before-load` / `^:dev/after-load` fns run around updates. Changing
`cljs.core` reloads the page.

The watcher recompiles changed namespaces, their dependents when a
namespace's API (arities, macros) changed, and namespaces using changed
macros.

### REPL

Under `:esm-repl` each module registers its namespace object, the setters of
all its instances (hot reloads keep the earlier ones, see above) and the npm
modules it imports with `globalThis.$CLJS_ESM`. The REPL compiles a form as
the body of a function the runtime (`cljf.esm.repl-runtime`) calls with the
namespaces it references: views reading the module's bindings, where
assigning a var (`def`, `set!`, `binding`) calls the setters of all instances,
or adds it to an overlay if the module doesn't define it. Closure Library shims
are passed as modules. Forms carry an inline source map to the text they were
read from. Releases aren't compiled with `:esm-repl`, the REPL needs `eval`.

### Production bundles

Bundlers treat `Type.prototype.cljs$core$ISeq$_first$arity$1 = ...` as a side
effect, which keeps every protocol implementation of a used type. The Vite
plugin removes those of protocol methods never invoked in the bundle (and the
code only they reference), across chunks: chunks are parsed and summarized in
worker threads, removed statements are blanked, which keeps the bundler's
source maps valid. oxc then minifies the chunks in parallel, renaming
`cljs$...` properties with one mapping for the whole bundle (`mangleProps`).
Source maps (`build.sourcemap`) are composed through these steps to the
ClojureScript sources, which the compiler's maps embed (`sourcesContent`).
On the Whimsical app this pass takes ~5.5s (2.2s pruning).

## Status

Verified with:

- The runtime test suite: `script/test-esm`, 662 tests, 20971 assertions, also
  as a pruned, minified bundle. `cljs.npm-deps-test`, which tests Closure's
  foreign lib processing, is excluded.
- The classic runtime tests (`:advanced`) and compiler tests, unchanged.
- The Whimsical app (3037 namespaces, compiled with its shadow-cljs build's
  sources): the production bundle and the development build under Vite's dev
  server boot until they need the backend.

Measured on the Whimsical app (Apple Silicon laptop):

| | shadow-cljs release (Closure advanced) | `:module-format :esm` + Vite 8, `:optimize-constants` |
|---|---|---|
| production build | 125s | 55s (compile and bundle) |
| total JS, minified | 25.8 MB | 27.0 MB |
| total JS, gzip / brotli | 5.93 / 4.72 MB | 6.04 / 4.82 MB |
| initial page load, gzip / brotli | 4.17 / 3.32 MB | 4.55 / 3.65 MB |
| incremental compile | | 150-230ms per changed namespace |

Hello world: 177 KB / 35 KB gzipped (Closure advanced: 110 KB / 23 KB).

## Known gaps

- ES modules are strict mode, ClojureScript under Closure isn't: writes to
  frozen objects or deleting non-configurable properties throw instead of
  silently doing nothing, `this` in a plain function call is `undefined`.
  `goog.getUid` (used by `hash` for JavaScript objects) gives frozen objects
  stable ids from a `WeakMap` rather than failing.

- REPL: Node.js runtimes only through Vite's dev server (its ssr environment),
  not a build run with `node`; npm modules can't be required at the REPL.
- FlowStorm: ClojureStorm instruments through its own build of the compiler,
  which doesn't have `cljf.esm`.
- `cljs.core` references `goog.math.Long` / `goog.math.Integer` for `integer?`,
  which keeps them in every bundle.
- Closure Library files run whole: `goog.i18n`'s locale data (from
  `goog.date`, i.e. cljs-time) has every locale, which Closure's advanced
  compilation reduces to `goog.LOCALE`'s, about 340 KB of a bundle.
- Closure's `:modules` aren't supported, lazy loading uses `import()`.
- Self-hosted ClojureScript (`cljs.js`) doesn't emit ES modules.
