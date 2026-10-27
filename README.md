<br/>
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./resources/clojurific-light-60.svg">
    <source media="(prefers-color-scheme: light)" srcset="./resources/clojurific-dark-60.svg">
    <img alt="vite logo" src="./resources/clojurific-dark-60.svg" height="60">
  </picture>
</p>
<p align="center">
  <a href="https://clojars.org/com.whimsical/clojurific"><img src="https://img.shields.io/clojars/v/com.whimsical%2Fclojurific?color=2BB89A" alt="Clojars version"></a>
  <a href="https://npmjs.com/package/clojurific"><img src="https://img.shields.io/npm/v/clojurific.svg?color=2BB89A" alt="npm package"></a>
  <a href="https://clojurians.slack.com/archives/C0C7W7LTA4D"><img src="https://img.shields.io/badge/clojurians-%23clojurific-2BB89A?style=flat&logo=slack&logoColor=white" alt="#clojurific on Clojurians Slack"/></a>
</p>
<br/>

# Clojurific

Clojurific is a fork of the [ClojureScript](https://github.com/clojure/clojurescript) compiler by [Whimsical](https://whimsical.com), optimized for interoperability with modern JavaScript tooling, runtime performance and build speed.

* Compiles ClojureScript to ES modules
* Based on ClojureScript 1.12.145, compatible with existing ClojureScript libraries
* Runs as a Vite plugin, no need for shadow-cljs
* Browser and backend support
* Integrated REPL in dev for both browser and backend
* Doesn't need extern definitions
* 2x faster build times and hot reload ([Build speed](#build-speed-and-bundle-size))
* About 40% less time on runtime benchmarks ([Runtime](#runtime))
* Bundle sizes comparable to the classic compiler with advanced optimizations ([Bundle size](#build-speed-and-bundle-size))

This is a new project and the fork's changes are mostly written by agents. Whimsical uses Clojurific in production for both frontend and backend services, but it hasn't yet been extensively tested in other environments.

[`CHANGELOG.md`](CHANGELOG.md) lists the changes in each release.

## Contents

- [Getting started](#getting-started)
- [Why Clojurific](#why-clojurific)
- [Usage](#usage)
- [Interop](#interop)
- [Performance](#performance)
- [Known gaps](#known-gaps)
- [Design](#design)
- [Development](#development)
- [Questions and feedback](#questions-and-feedback)
- [License](#license)

## Getting started

```sh
npm create clojurific@latest
```

This scaffolds a Vite project based on one of the following templates:
* Vanilla
* [Reagent](https://reagent-project.github.io/)
* [UIx](https://uix-cljs.dev/)
* [Hono](https://hono.dev/) (backend)

It needs Node.js 20 and Java 17 or later, and offers to download Java when there's none.

## Why Clojurific

Compared to other language ecosystems, Clojure and ClojureScript have a well-deserved reputation for being stable and backwards compatible. You can pick a codebase that hasn't been touched for 10 years and it will typically build with the latest compiler version. This is rare among modern programming languages and a worthy goal to keep pursuing.

It does come at the cost of being more conservative:
- New host platform features take a while to be adopted (e.g. async/await in ClojureScript)
- Fast-shifting host platform ecosystem trends are generally avoided (consider how many go-to bundlers and package managers the JavaScript ecosystem has churned through in the last 10 years)
- There is higher risk-aversion for introducing non-functional changes, such as performance optimizations (as those come with more complexity and therefore risk of breakage)

Clojurific's intent is to optimize ClojureScript for the specific needs of Whimsical's product while choosing different tradeoffs:
- Integrate with the current state of the art in JavaScript tooling with the goal of better developer experience for using internal and external JS/TS libraries and faster build times
- Aggressive performance optimizations with the help of coding agents and automated techniques such as autoresearch, which does come at a cost of increased risk of functional regressions
- Aim to maintain but not guarantee 100% compatibility with the classic compiler and existing libraries
- (Maybe) Explore leveraging TypeScript static typing in ClojureScript code, at least for host platform APIs and TypeScript libraries

## Usage

### Dependencies

Clojure deps.edn, from [Clojars](https://clojars.org/com.whimsical/clojurific):

```clojure
com.whimsical/clojurific {:mvn/version "0.12.11"}
```

Clojurific is based on ClojureScript 1.12.145, and replaces it: stock ClojureScript has the same namespaces, so it mustn't be on the classpath too. The `cljf` launcher (and the Vite plugin, which runs it) removes it for you; with the Clojure CLI, exclude it from the library that brings it in with `:exclusions [org.clojure/clojurescript]`, or from the whole dependency tree with an alias overriding it with the empty project in `no-clojurescript/`:

```clojure
:aliases {:cljs {:override-deps {org.clojure/clojurescript {:git/url "https://github.com/WhimsicalCode/clojurific"
                                                            :git/tag "v0.12.7" :git/sha "ef958e6"
                                                            :deps/root "no-clojurescript"}}}}
```

The artifact doesn't depend on the Closure Compiler, which `cljf.esm` doesn't need. To use the classic compiler (`cljs.build.api`, `cljs.main`) too, add `com.google.javascript/closure-compiler` to the project's dependencies.

JavaScript package.json, from [npm](https://npmjs.com/package/clojurific):

```sh
npm install clojurific
```

### Compiling

```sh
clojure -M -m cljf.esm build '{:main my.app :output-dir "out"}'  # build
clojure -M -m cljf.esm watch @cljs.edn                            # watch, hot reload
```

or without the Clojure CLI, with the npm package's launcher (see [Launcher](#launcher)): `npx cljf build …`, `npx cljf watch …`.

Options are EDN maps, `@file.edn` to read them from a file, and `:profile` keywords (`cljf.esm/load-options`). Profiles are maps under `:profiles`, deep merged over the rest; the profile names the build's `:mode`. `watch` defaults to the `:dev` profile, `build` to `:release`:

```clojure
;; cljs.edn
{:main [my.app]
 :build-hooks [[my.build/hook {:some :arg}]]
 :profiles {:dev {:preloads [my.dev]}
            :release {:optimize-constants true}}}
```

`cljf.esm/build` and `cljf.esm/watch` take the usual compiler options plus:

| option | |
|---|---|
| `:main` | a namespace or a collection of them, e.g. the app plus lazily loaded entries, optional with `:js-entries` |
| `:extra-main` | namespaces added to `:main`, e.g. by the Vite plugin, the namespaces of the pages' `.cljs` scripts |
| `:js-entries` | `{name {:exports {jsName my.ns/var}}}`, generated entry modules (`cljs-esm-entries/<name>.js`) exporting vars under JavaScript names, for libraries and workers; an undefined var fails the build |
| `:preloads` | namespaces the main namespaces import first |
| `:parallel-build` | compiles namespaces in parallel once their dependencies are compiled |
| `:npm-interop :shadow` | `["pkg" :as x]` binds CommonJS packages' `module.exports`, like shadow-cljs; needs the Vite plugin |
| `:closure-defines {goog.DEBUG false}` | `goog.DEBUG` and `goog-define`s are compile time constants. `goog.DEBUG` is `false` in `:release` builds (the `:release` profile, and `build` without a profile), like shadow-cljs' release mode, unless `:closure-defines` sets it |
| `:build-hooks` | `[[fn-sym & args]]`, called with the build (`:compiler-env`, `:namespaces` in dependency order, `:mode`, `:options`, `:trigger` and `:changed-namespaces`) after a build (`:trigger :build`), every watch recompile (`:watch`) and REPL forms changing a namespace's analysis (`:repl-eval`) |
| `:warnings` | a map of warning types (`true`, `false`, `:warning`, `:error`, `:off`), or one of them for the undeclared var warnings, like `cljs.closure` |
| `:warnings-as-errors true` | every enabled warning fails the compile, like shadow-cljs |
| `:optimize-constants true` | keyword and symbol constants are exports of one module, `cljs/core/constants.js`, instead of allocated at each use (`cljs.core` keeps its own) |
| `:checked-set-literals false` | set literals of runtime values collapse duplicates instead of throwing (before CLJS-3415) |
| `:esm-hmr` | hot reloading code, enabled by `watch` |
| `:esm-repl` | modules register themselves for the REPL, which main namespaces load first, enabled by `watch` with `:esm-hmr` |
| `:repl` | `{:nrepl-port 0 :nrepl-host "127.0.0.1" :port-files [".nrepl-port"] :middleware []}`, `watch` starts an nREPL server (`0` picks a port), writes its port to the port files no other live server's port is in, and deletes them on exit; needs nREPL on the classpath. `:middleware` adds nREPL middleware (names of vars, or of vars holding collections of names), as does the `:middleware` of nREPL's configuration, its `nrepl.edn` (i.e. `~/.nrepl/nrepl.edn`) and the project's `.nrepl.edn`; their other keys don't apply (see [Editors](#editors)) |
| `:esm-dts false` | don't write `.d.ts` files |
| `:esm-after-load` / `:esm-before-load` | fns run around hot reloads, like `^:dev/after-load` |
| `:watch-dirs` | source directories, defaults to the classpath directories without the compiler's own |
| `:aot-macros true` | macro namespaces (Clojure) load from classes compiled by an earlier build, kept in the per-user cache's `macro-classes/` (see [Launcher](#launcher)) until a Clojure source on the classpath (or a jar) changes, shared by output directories: starting a watch loads them several times faster |

The output directory has one module per namespace (`out/my/app.js`), plus `goog.js` and `goog/*.js` shims, `goog-lib/`, the parts of the Closure Library the build uses (see below), and `cljs-esm.json`, the main namespaces' modules (the bundle's entry points).

`cljs.core`'s output is kept in the per-user cache's `cljs-core/` (see [Launcher](#launcher)) under a key of the compiler, `cljs.core`'s source and the options its output depends on, and copied into an output directory that lacks it: a cold build then reads `cljs.core`'s analysis instead of compiling it. `:core-cache false` disables it.

A build reuses the output of the previous one: a namespace is recompiled when its source changed, and so are the namespaces requiring it when its API (its vars, their arities) changed, which their output may depend on (`:static-fns` arity calls, `cljs.test`'s lists of tests). Macros changed since the previous build aren't detected: remove the output directory after changing a macro library between builds. A running `watch` does detect them, and recompiles the namespaces using them (see [Hot reloading](#hot-reloading)).

Macros reading classpath resources call `cljf.esm/watch-resource!`, and the watcher then recompiles the namespace when the resource changes. `shadow.resource` is provided for code written for shadow-cljs.

### Vite

`npm create clojurific@latest` scaffolds a project with the plugin (Vanilla, Reagent, UIx, or a Hono backend).

The plugin needs Vite 8, which the templates use and the plugin is tested with. Its production bundle pass (`prune`) uses the oxc parser and minifier that ship with Vite 8's Rolldown; with `prune: false`, earlier versions can build too, but they aren't tested.

The plugin (`clojurific/vite`) runs the compiler: a one-shot build for `vite build` (the main namespaces are the bundle's entry points) and watch mode for `vite`. Compile errors go to Vite's error overlay. In watch mode Vite's file watcher reports changed sources to the compiler, and hot updates wait for the compile and its build hooks to finish. Like shadow-cljs, compiler warnings are shown in the overlay too, and hot reloading pauses while any namespace has warnings: the output held back is reloaded once they're fixed. Pages loaded meanwhile show the overlay of the current error or warnings. The `ignoreWarnings` option switches the pause off, like shadow-cljs' `:devtools {:ignore-warnings true}`: the warnings then go to the terminal only. The `ssrEntries` run once they're compiled either way, a warning is no error: the HTML pages a server entry serves (those loading `@vite/client`) show the overlay, its other responses (i.e. JSON) only the terminal's line.

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  plugins: [cljf({ aliases: ['cljs'], config: 'cljs.edn', outputDir: 'out' })],
});
```

The plugin has TypeScript types (`vite-plugin-cljs/index.d.ts`): a `vite.config.ts` type checks its options, including the common compiler options of `compilerOptions`, and editors complete them in a `vite.config.mjs` too.

| option | |
|---|---|
| `outputDir` | the compiler's output directory, relative to `cwd`, required. Pages load namespaces from it: `'out'` serves `my.app` as `/out/my/app.js` |
| `aliases` | `deps.edn` aliases of the classpath the compiler runs with, e.g. `['cljs']` |
| `command` | starts Clojure with the compiler on the classpath instead of the launcher, e.g. `['clojure', '-M:cljs']` |
| `cwd` | the compiler's working directory, with the project's `deps.edn`, defaults to Vite's root |
| `config` | compiler options file (EDN, see [Compiling](#compiling)), relative to `cwd` |
| `profile` | the config file's profile, defaults to `dev` when serving and `release` when building |
| `compilerOptions` | more compiler options, an EDN string or an object, e.g. `{ npmInterop: ':shadow' }`: keys are keywords, camelCase ones in kebab-case (`nreplPort` is `:nrepl-port`), except the names under `closureDefines` and `jsEntries`; strings starting with `:` are keywords, with `'` symbols |
| `entries` | the `:js-entries` a build bundles, defaults to all |
| `ssrEntries` | `:js-entries` the dev server runs in its ssr environment once they're compiled |
| `manifest` | the file in Vite's `outDir` the entry points' scripts and preloads are written to, for server rendered pages, defaults to `'manifest.json'`; `false` doesn't write it |
| `prune` | removes unused protocol implementations and shortens ClojureScript property names in production bundles, defaults to `true` |
| `replRemoteConsole` | accepts `cljs_eval` from pages on other machines too, defaults to `false` |
| `ignoreWarnings` | hot reloads despite compiler warnings, which then go to the terminal only (the compiled line counts all namespaces' outstanding warnings), defaults to `false` |

The plugin starts the compiler with the launcher, on the classpath of the project's `deps.edn` with `aliases`. `command` starts it with another command instead, e.g. the Clojure CLI's `['clojure', '-M:cljs']`; cljf.esm's arguments follow it.

The project's directory is Vite's root, and its `index.html` is the dev server's page and an input of builds. Like TypeScript files in a Vite project, pages load ClojureScript sources, and the plugin compiles their namespaces as main namespaces (`:extra-main`), so `:main` is optional:

```html
<!-- index.html -->
<script type="module" src="/src/my/app.cljs"></script>
```

The dev server serves the page with the compiled module's URL instead (`/out/my/app.js`, which pages can load directly too), and builds bundle it. JavaScript and TypeScript modules can import sources as well (`import './my/app.cljs'`), which loads the namespace; its vars aren't exports, but `:js-entries` export them under JavaScript names. The plugin finds those sources by following the pages' modules (and JavaScript inputs) through relative and root-absolute imports, not packages or aliases: list namespaces imported otherwise in `:main`. A source's namespace comes from its `ns` form. The pages and their imports are read when Vite starts, so restart it after adding a source.

Builds of more pages list them in `build.rollupOptions.input` (`build.rolldownOptions.input` in Vite 8), and the plugin adds the main namespaces and `:js-entries` to them. The project's `package.json` needs `"type": "module"` (or no type). With `"type": "commonjs"`, which `npm init` writes, the bundler treats the project's `.js` files as CommonJS and wraps the ClojureScript they import in initializers, which production bundles can't prune. The plugin warns about it.

`:js-entries` are bundle inputs too, with their exports kept: a build of a library or a Node service's worker (`build.ssr`) is one module exporting what the entry names. The plugin's `entries` option picks the ones a build bundles. Its `ssrEntries` option runs entries in the dev server's ssr environment once they're compiled, e.g. a server's entry, which a plugin like `@hono/vite-dev-server` imports from the same module runner, so the REPL can evaluate in it before its first request. `vite build --watch` runs the compiler's watch, and rebundles when it recompiles.

For server rendered pages the plugin writes `manifest.json` to Vite's `outDir`, with each main namespace's module scripts and the chunks they import (to preload): the Vite dev server's modules when serving, and the hashed chunks of a build.

### Backend

A Node.js backend is a `:js-entries` module exporting the server, which the dev server runs and `vite build` bundles. `npm create clojurific@latest my-app -- --template hono` scaffolds one with [Hono](https://hono.dev/):

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';
import devServer from '@hono/vite-dev-server';
import build from '@hono/vite-build/node';

const entry = 'target/cljs/cljs-esm-entries/server.js';

export default defineConfig({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      compilerOptions: {
        jsEntries: { server: { exports: { default: "'my.server/app" } } },
        repl: { nreplPort: 0 },
      },
      ssrEntries: ['server'],
      manifest: false,
    }),
    devServer({
      entry,
      loadModule: async (server, entry) => (await server.environments.ssr.runner.import(entry)).default,
    }),
    build({ entry, emptyOutDir: true, minify: false }),
  ],
});
```

- `vite` runs the entry in the dev server's ssr environment as soon as it's compiled (`ssrEntries`), so the [REPL](#repl) evaluates in the server before its first request. `@hono/vite-dev-server` serves the app from the same module runner (`loadModule`), which hot reloads changed namespaces in place: `defonce` keeps its value. Its default, `ssrLoadModule`, would re-run a changed module and its importers instead.
- `vite build` bundles the server: `@hono/vite-build/node` writes `dist/index.js`, a Node.js server that doesn't need `node_modules`, run with `node dist/index.js`. Its `minify: false` leaves minifying to the cljf plugin, which minifies after pruning (`prune`).
- A backend has no pages, so `manifest: false` skips the manifest of their scripts.

### Launcher

The npm package `clojurific` has the `cljf` command, the Vite plugin (`clojurific/vite`) and the Karma adapter (`clojurific/karma`). It has no compiler: the launcher gets the Clojars artifact of the package's version. It needs Java and Node.js, not the Clojure CLI:

```sh
npx cljf build '{:main my.app :output-dir "out"}'
npx cljf -A:test watch @cljs.edn :test
npx cljf classpath      # like clojure -Spath
npx cljf repl           # a ClojureScript REPL into the dev server's pages, see REPL
npx cljf setup-java     # downloads Eclipse Temurin, for machines without Java
```

It resolves the project's `deps.edn` (and the user's, as the Clojure CLI does) with tools.deps, adding the compiler (`com.whimsical/clojurific` from Clojars, unless the project lists it itself, e.g. as a git dependency) and replacing every `org.clojure/clojurescript` with `no-clojurescript/`, then runs `clojure.main -m cljf.main` with the aliases' `:jvm-opts`. Dependencies are downloaded into the local Maven repository (`~/.m2/repository`, or `:mvn/local-repo`), shared with the Clojure CLI. The classpath is cached in the project's `.cljf/cpcache` until a `deps.edn` it was resolved from changes; `--force` resolves it again.

tools.deps can't download itself: the launcher downloads the jars of its locked resolver with their SHA-256 (from Maven Central, or `CLJF_MAVEN_REPO`), then runs the resolver with them. tools.deps' S3 transporter is left out, so `s3://` repositories aren't supported. Git dependencies need `git` on `PATH`.

Java 17 or later comes from `CLJF_JAVA`, `JAVA_HOME`, `PATH`, then the runtimes `cljf setup-java [version]` installed into the per-user cache (`~/Library/Caches/cljf`, `$XDG_CACHE_HOME/cljf`, `%LOCALAPPDATA%\cljf`, or `CLJF_CACHE_DIR`). Without one, the launcher explains how to install it and offers to run `setup-java` in a terminal; `CLJF_INSTALL_JDK=1` runs it without asking. `setup-java` gets Temurin's JRE (the latest LTS by default) from the Adoptium API, checking the archive's SHA-256 against the API's.

Class data sharing (CDS) archives would load Clojure's classes faster, but the JVM doesn't create them while the classpath has non-empty directories, which the compiler's and the project's sources are.

The launcher and the Vite plugin start the compiler with `cljf.main`, `cljf.esm`'s command line, which loads the compiler (and `cljs.core`'s macros) from classes compiled on its first run: loading its Clojure sources is most of a start's time. The classes are kept in the per-user cache's `compiler-classes/` (`CLJF_CACHE_DIR`, see above), under a key of the classpath, the Clojure and Java versions and the compiler's sources, and are deleted after a week unused. `CLJF_COMPILER_CLASSES=false` loads the sources.

### REPL

`watch` evaluates forms in the pages running the build (runtimes): browser tabs, the Electron window, and servers run by the dev server's ssr environment. Messages go through Vite's websocket (the ssr environment's module runner's hot channel, in the dev server's process) and the plugin's pipe to the compiler. Nothing else listens on a port but the optional nREPL server (`:repl`), which runs in the watcher's JVM:

```clojure
(require '[cljf.esm.repl :as repl])

(repl/runtimes)            ;=> [{:runtime-id 3 :url "..." :title "..." :visible true ...}]
(repl/cljs-eval "(+ 1 2)") ;=> {:results ["3"] :out "" :err "" :warnings [] :ns cljs.user :runtime-id 3}
(repl/cljs-eval "(foo)" {:ns 'my.app :runtime-id 3 :await true :timeout 10000})
(repl/load-file "src/my/app.cljs")
(repl/tag! 3 "A")          ; target with {:tag "A"}, survives reloading the page
(repl/repl)                ; this nREPL session evaluates ClojureScript, :cljs/quit to leave
```

`cljf repl` is that REPL in a terminal (`npm run repl` in `create-clojurific`'s projects). It's an nREPL client, without Java, of the server whose port is in `.nrepl-port` (or `--port`), and waits for the dev server to write it. It switches its session with `(repl/repl)`, reads forms over several lines until they're closed, keeps a history in `.cljf/repl-history`, and interrupts the evaluation on Ctrl-C. The Vite plugin's projects start the server with `compilerOptions: { repl: { nreplPort: 0 } }` and `nrepl/nrepl` in `deps.edn`.

- Forms are analyzed with the build's compiler environment and evaluated in the runtime focused last (visible ones first) unless `:runtime-id` or `:tag` pick one. The interactive REPL notes changes of its runtime.
- `def` redefines a namespace's var for the namespace's own code and its importers, as a hot reload does. Vars only defined at the REPL, and namespaces only created at the REPL (`in-ns`, `ns`), are the runtime's.
- `require` loads namespaces into the runtime. Namespaces outside the build are compiled by the watcher from then on, and `:reload` recompiles and hot reloads them. npm modules can't be required at the REPL; a namespace's forms use the npm modules it requires.
- `load-file` compiles the file with the watcher and returns once the runtime applied the hot reload (or the warnings holding it back). A file outside the source directories, or an editor buffer differing from the file, is evaluated form by form instead.
- A form with compiler warnings isn't evaluated, unless `:warnings-ok`: the result has `:error` and `:warnings`, `:results` ends with `:cljf.esm.repl/failed`, and the forms after it aren't evaluated. The nREPL middleware replies with an `eval-error`.
- In the browser's console, `cljs_eval("(+ 1 2)")` and `cljs_eval("(foo)", {ns: "my.app", await: true, print: true})` evaluate in its page, compiled by the REPL. They return a promise of the last form's value (printed with `print`), rejected for an error or warnings. The console keeps the namespace of its last `in-ns`. Compiling forms runs their macros in the compiler's JVM, so the Vite plugin accepts `cljs_eval` only from pages on the dev server's machine, unless its `replRemoteConsole` option is set. A proxy in front of the dev server has to set `X-Real-IP` to the client's address.
- The interactive REPL has `*1`, `*2`, `*3` and `*e`, and `doc`, `source`, `dir` and `apropos` are answered from the compiler environment. Output printed during a form is its `:out` / `:err`, and still goes to the console. `:await true` waits for a promise's value. Errors' `:stack` is mapped to the ClojureScript sources (`my/app.cljs:12:3`, `<cljs repl>:1:2` for the forms evaluated) with the output's source maps.

#### Editors

Editors connect to the nREPL server in `.nrepl-port`, then evaluate `(cljf.esm.repl/repl)` in a session to switch it to ClojureScript. For CIDER, add `cider/cider-nrepl` to `deps.edn` and its middleware to the server, in the Vite plugin's options, in `cljs.edn`, or in nREPL's configuration: the project's `.nrepl.edn` (`{:middleware [cider.nrepl/cider-middleware]}`), whose keys replace those of the user's `nrepl.edn` (`~/.nrepl/nrepl.edn`, or under `$NREPL_CONFIG_DIR` or `$XDG_CONFIG_HOME/nrepl`). Only their `:middleware` applies:

```js
compilerOptions: { repl: { nreplPort: 0, middleware: ["'cider.nrepl/cider-middleware"] } }
```

```clojure
;; cljs.edn
{:repl {:nrepl-port 0 :middleware [cider.nrepl/cider-middleware]}}
```

Middleware that doesn't load is left out with a warning, as is a configuration file that can't be read. With cider-nrepl 0.61 or later, a session switched to ClojureScript completes and looks up (`info`, `eldoc`) ClojureScript namespaces and vars from the build's compiler environment, through clj-suitable. Completions of JavaScript properties (`js/document.getE…`, CIDER's `cider-enhanced-cljs-completion-p`) are clj-suitable's too: its piggieback and shadow-cljs support doesn't cover these sessions, so they evaluate its completion forms with `cljf.esm.repl/cljs-eval`, after requiring `suitable.js-introspection` once in each runtime (a runtime it doesn't load in has no property completions, with one warning). cider-nrepl 0.63 or later is recommended: the clj-suitable of earlier releases requires it again for every completion. Cider's ops see a copy of the compiler environment taken for each message, so a macroexpansion can't change the build's while it recompiles.

### Migrating from shadow-cljs

Vite replaces shadow-cljs' build and dev server: `npm install -D clojurific vite`, then move `shadow-cljs.edn` over:

- `:source-paths` and `:dependencies` go to `deps.edn`'s `:paths` and `:deps`. The Vite plugin resolves it with the `cljf` launcher; the Clojure CLI isn't needed.
- The build's `:compiler-options` (`:closure-defines`, `:warnings-as-errors`, …) go to `cljs.edn`, the plugin's `config`, or its `compilerOptions`. `:dev`/`:release` settings are the `:dev` and `:release` profiles.
- `:modules`' `:init-fn` becomes a script in `index.html` (`<script type="module" src="/src/my/app.cljs"></script>`), whose namespace calls its init fn at the top level. `:output-dir` is the plugin's `outputDir`, and `:dev-http` is Vite's dev server.
- Add `:npm-interop :shadow` to keep shadow-cljs' bindings of CommonJS packages (`["pkg" :as x]` is `module.exports`).

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  plugins: [cljf({ config: 'cljs.edn', outputDir: 'target/cljs',
                   compilerOptions: { npmInterop: ':shadow' } })],
});
```

In the code, `^:dev/before-load` / `^:dev/after-load`, `shadow.resource` and symbol requires of npm packages work as they are. `shadow.cljs.modern`'s `defclass` is `cljf.x`'s ([Classes](#classes)), `shadow.lazy` is `cljf.esm.lazy`, and lazily loaded namespaces are `:main` entries split by the bundler instead of `:modules` ([Lazy loading](#lazy-loading)). `:test` builds become a `:test-runner` ([Tests](#tests)). See also the [known gaps](#known-gaps).

## Interop

### npm packages and JavaScript / TypeScript files

String requires are plain imports, which the bundler resolves:

```clojure
(ns my.app
  (:require ["react" :as react]                ; npm package
            ["react-markdown" :default Markdown] ; default export
            ["./chart.ts" :as chart]           ; relative to this .cljs file
            ["/my/lib.js" :as lib]             ; on the classpath
            [rbush :as rbush]))                ; npm, like shadow-cljs
```

TypeScript is compiled by Vite. A TypeScript file or workspace package changed in development is hot reloaded into the running app, with no separate `tsc` process.

TypeScript imports ClojureScript modules like any other ES module, and the compiler writes `.d.ts` declarations next to them (fns with their arities and docstrings):

```ts
import { describe } from '../out/app/state.js';
describe(1, 2); // error TS2554: Expected 1 arguments, but got 2.
```

### Classes

`cljf.x/defclass` (like shadow-cljs' `shadow.cljs.modern/defclass`) defines a JavaScript class, for APIs constructing one with `new` or needing a subclass, e.g. a Cloudflare Durable Object:

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

`this` is bound once `(super ...)` has called the base class' constructor, which a constructor without a `(super ...)` call does first with its params. The methods and protocol implementations are `extend-type`'s. It isn't specific to `:module-format :esm`.

### `^:export`

Exported vars are also installed as globals (`my.app.init`), for scripts calling into the build.

### Lazy loading

`cljf.esm.lazy` follows `shadow.lazy`'s API. `(lazy/loadable my.ns/var)` loads the namespace with a dynamic `import()`, and bundlers split it, with what only it requires, into a separate chunk. Lazily loaded namespaces are additional `:main` entries.

### Tests

`:test-runner {:ns my.test-runner :ns-regexp "-test$" :runner my.test/start}` generates namespace `my.test-runner`, requiring the test namespaces of the source directories matching `:ns-regexp`, and compiles it as a main namespace. It calls `(my.test/start run-tests)`, where `run-tests` runs the tests given a `cljs.test` env. `watch` regenerates it as test namespaces are added and removed. Two runners come with the compiler:

- `cljf.esm.node-test/run`: runs the tests under Node.js; the process exits with 1 when any failed.
- `cljf.esm.karma/start`: reports to Karma, one result per test var. Karma loads the bundle through the npm package's adapter (`clojurific/karma`), which imports the entry module named by Karma's `client.args`:

  ```js
  // karma.conf.js
  files: [require.resolve('clojurific/karma'),
          { pattern: 'build/ci-test/**/*', included: false }],
  client: { args: ['/base/build/ci-test/my.test-runner.js'] },
  ```

## Performance

### Runtime

ClojureScript's own [benchmarks](benchmark/cljs/benchmark_runner.cljs) (185 benchmarks of `cljs.core`'s collections, sequences, protocol dispatch, printing and the reader), compared with a shadow-cljs release build:

| | shadow-cljs 3.5.7 release (Closure advanced, ClojureScript 1.12.145) | Clojurific (`d9cf6345`) + Vite 8, pruned and minified |
|---|---|---|
| total time | 8.2s | 4.7s (−42%) |
| geometric mean of time ratios (the 99 benchmarks taking 10ms or more) | 1 | 0.64 |
| faster benchmarks | | 88 of those 99 |

Clojurific's build uses `:optimize-constants` and `:static-fns`, shadow-cljs's its release defaults (with `:static-fns`). Both ran under Node.js 26 on an Apple M1 Max, 7 runs each, interleaved; the times are each benchmark's median. The largest gains are in sequential destructuring (0.13), keyword lookups in hash maps (0.22–0.32), variadic `=` (0.32) and `last` of a vector (0.36). Sorting by keyword `compare` (1.16) and `reduce` over lazy sequences (up to 1.33) are slower, and `cljs.reader` is even (1.01).

On the Whimsical app's performance suite (rendering, layout, text editing, syntax highlighting and data validation of its boards and docs), the ES module build takes about 30% less time than its shadow-cljs build (Closure advanced): the geometric mean of the median time ratios is 0.70, down from 1.04 before the optimizations below. The two builds run interleaved on the same machine.

The main changes, in ES module output (see [`CHANGELOG.md`](CHANGELOG.md) for all of them):

- `let` and `do` in expression position are comma expressions with hoisted `var`s instead of IIFEs. The app's output had about 14,000 IIFEs (shadow-cljs's about 70), which caused most of the original gap and extra garbage collection.
- Call site dispatch: `get`, keyword invokes, `nth`, `seq` / `first` / `next`, `count`, `=`, `empty?`, `not-empty`, `vector?`, `map?` and map destructuring of a local call the protocol method at the call site when the value has it, instead of going through one megamorphic shared dispatcher. Anything else goes through the function as before.
- Array map lookups of a keyword first check the index the keyword was last found at, before scanning the keys (about 4%).
- `cljs.core` fast paths: keyword and string paths in `hash`, `=`, `str` and `compare`, a one-entry cache in multimethods, fixed arities of `merge` and `assoc`, and vector walks by index in `some`, `every?`, `get-in`, `last` and `second`.

Call site dispatch adds code: about 5% more minified and 3% more gzipped JavaScript on the Whimsical app. Classic builds' code is unchanged.

### Build speed and bundle size

Measured on the 400K LOC Whimsical app (Apple Silicon laptop):

| | shadow-cljs release (Closure advanced) | `:module-format :esm` + Vite 8, `:optimize-constants` |
|---|---|---|
| production build | 125s | 55s (compile and bundle) |
| total JS, minified | 25.8 MB | 27.0 MB |
| total JS, gzip / brotli | 5.93 / 4.72 MB | 6.04 / 4.82 MB |
| initial page load, gzip / brotli | 4.17 / 3.32 MB | 4.55 / 3.65 MB |
| incremental compile | | 150-230ms per changed namespace |

We haven't run automated benchmarks of dev build times and hot reload speed, but subjectively they feel more than twice as fast as shadow-cljs's.

Hello world: 177 KB / 35 KB gzipped (Closure advanced: 110 KB / 23 KB).

## Known gaps

- ES modules are strict mode and ClojureScript under Closure isn't: writes to frozen objects or deleting non-configurable properties throw instead of silently doing nothing, and `this` in a plain function call is `undefined`. `goog.getUid` (used by `hash` for JavaScript objects) gives frozen objects stable ids from a `WeakMap` rather than failing.
- REPL: npm modules can't be required at the REPL.
- FlowStorm: ClojureStorm instruments through its own build of the compiler, which doesn't have `cljf.esm`.
- `cljs.core` references `goog.math.Long` / `goog.math.Integer` for `integer?`, which keeps them in every bundle.
- Closure Library files run whole: `goog.i18n`'s locale data (from `goog.date`, e.g. cljs-time) has every locale, which Closure's advanced compilation reduces to `goog.LOCALE`'s, about 340 KB of a bundle.
- Closure's `:modules` aren't supported; lazy loading uses `import()`.
- Self-hosted ClojureScript (`cljs.js`) doesn't emit ES modules.

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

- A namespace's vars are module-local bindings (`my$app$greet`), and references to other namespaces' vars go through namespace imports (`my$util.format`). Bundlers resolve these statically, tree-shake unused vars and rename them.
- All of the emitter's naming goes through `munge`: a var of another namespace becomes `ns$alias.name`, a var of the namespace being compiled `ns$alias$name`. Namespaces only referenced by fully qualified names (macroexpansions) are imported at the end of the module (imports are hoisted).
- Imported bindings are read-only. `set!` of another namespace's var (e.g. by `binding` and `with-redefs`) calls a setter the owning module exports: `$set$name` for dynamic vars and `$$set` for any var.
- `def`s nested in functions are hoisted as `var` declarations.
- Multi-arity and variadic fns are `/*@__PURE__*/` annotated.
- `js/my.ns.foo` references to namespaces as JavaScript globals, and fields read from them (`(.. js/my -ns -foo)`), are rewritten to module references, and `(exists? my.ns/foo)` checks the binding. `(exists? react/useEffectEvent)` checks the property of the npm module's binding.
- `js/eval` is an indirect eval: a direct one defeats tree shaking and renaming of the whole module.

### Closure Library

`cljs.core` uses little of the Closure Library. `src/main/cljs/cljf/esm/goog*` are ES module versions of what it uses (`goog.typeOf`, `goog.object`, `goog.string`, `goog.math.Long`, ...).

Other code using the Closure Library (cljs-time's `goog.date`, re-frame's `goog.async`, cljs-http's `goog.Uri`, ...) and other Closure style libraries (transit-js) gets the real thing: Closure Library files are wrapped as ES modules (`goog-lib/`) run by Closure Library's `base.js` with the debug loader disabled, and dependencies are imports. Members are resolved per reference: a shim is used if it exports the member, otherwise the Closure Library. Its direct `eval` calls (`goog.json.parse`, base.js' module loader) are made indirect, since a direct eval keeps minifiers from renaming the top level of the chunk it's in.

### Externs

Externs aren't needed: `:externs` and `:infer-externs` aren't used by `cljf.esm`. The analyzer still reads the types of JavaScript's standard and browser APIs from Closure's default externs, e.g. that `isNaN` returns a boolean, so `(if (js/isNaN x) ...)` compiles to `if(isNaN(x))` rather than a `cljs.core.truth_` call. A build without `:externs-sources` (all `cljf.esm` builds) reads them from `cljs/externs/default.edn` (see [Default externs](#default-externs)), the map `cljs.externs/externs-map` parses, without docs and source locations (`cljs.analyzer.api/resolve-extern`'s `:info` has no `:doc`). Only builds with `:externs-sources` (the classic compiler's, with `:infer-externs`) parse externs with the Closure Compiler. The classic REPLs (`cljs.main`, `cljs.repl/repl`, `cljs.server.*`) don't set `:externs-sources` either, so they get the same map: `(doc js/isNaN)` prints no JSDoc text.

### Hot reloading

Modules accept their own updates (`import.meta.hot`). Importers keep the bindings of the module instance they imported. A reloaded instance updates the bindings of all previous ones through their `$$set`, so callers see new definitions, like reloading a namespace under goog. A reloaded instance first restores the previous instance's values, so `defonce` keeps its value. `^:dev/before-load` / `^:dev/after-load` fns run around updates. Changing `cljs.core` reloads the page.

The watcher recompiles changed namespaces, their dependents when a namespace's API (arities, macros) changed, and namespaces using changed macros.

### REPL internals

Under `:esm-repl` each module registers its namespace object, the setters of all its instances (hot reloads keep the earlier ones, see above) and the npm modules it imports with `globalThis.$CLJS_ESM`. The REPL compiles a form as the body of a function the runtime (`cljf.esm.repl-runtime`) calls with the namespaces it references: views reading the module's bindings, where assigning a var (`def`, `set!`, `binding`) calls the setters of all instances, or adds it to an overlay if the module doesn't define it. Closure Library shims are passed as modules. Forms carry an inline source map to the text they were read from. Releases aren't compiled with `:esm-repl`, since the REPL needs `eval`.

### Production bundles

Bundlers treat `Type.prototype.cljs$core$ISeq$_first$arity$1 = ...` as a side effect, which keeps every protocol implementation of a used type. The Vite plugin removes those of protocol methods never invoked in the bundle (and the code only they reference), across chunks. Chunks are parsed and summarized in worker threads, and removed statements are blanked, which keeps the bundler's source maps valid. oxc then minifies the chunks in parallel, renaming `cljs$...` properties with one mapping for the whole bundle (`mangleProps`). Source maps (`build.sourcemap`) are composed through these steps to the ClojureScript sources, which the compiler's maps embed (`sourcesContent`). On the Whimsical app this pass takes ~5.5s (2.2s pruning).

Minifying and composing the source maps run in worker threads. The statement summaries pruning needs come from a native summarizer when one is built for the platform (`script/build-native-prune`, Rust with oxc's parser, in `src/main/rust/prune`, needs cargo): it parses and summarizes the chunks in parallel without turning their ASTs into JavaScript objects. Without it (or with `CLJF_NATIVE_PRUNE=false`) the chunks are summarized in JavaScript, with the same result.

## Development

The npm package `clojurific` is `src/main/js`: the launcher (`src/main/js/cljf`), the Vite plugin (`src/main/js/vite-plugin-cljs`) and the Karma adapter (`src/main/js/karma-esm`). `script/package-npm` packs it with `no-clojurescript/`. Run from this repository, the launcher uses the fork's sources instead of the Clojars artifact. `create-clojurific` and its templates are in `src/main/js/create-clojurific`. [`AGENTS.md`](AGENTS.md) describes the rest of the layout and the test suites.

### Closure compiler

`deps.edn` has the fork's dependencies, which don't include the Closure Compiler (`com.google.javascript/closure-compiler`): `cljf.esm`, its REPL and the analyzer (`cljs.analyzer.api`) don't need it. The classic compiler (`cljs.closure`, `cljs.build.api`, `cljs.main` and the classic REPLs) does, and fails with a `ClassNotFoundException` for `com.google.javascript.jscomp` classes without it. Add the `:closure` alias to every alias running them, e.g. `clojure -M:closure:compiler.test:compiler.test.run` for the compiler tests and `clojure -M:closure:cljs-repl` for `cljs.main`'s Node.js REPL (`:cljs-brepl` and `:cljs-lite-repl` too). `cljs.repl` loads `cljs.closure` on first use, so namespaces requiring `cljs.repl` (its macros) compile without it. `pom.template.xml` and `project.clj` are upstream's publishing setup and still list the Closure Compiler.

### Resolver lock

The jars of the launcher's resolver (`clojurific.resolve`, run with tools.deps) are listed in `src/main/js/cljf/resolver.lock.json`. `script/lock-resolver` writes it from `src/main/js/cljf/resolver/deps.edn`; run it after changing that file.

### Default externs

`script/gen-default-externs` regenerates `src/main/clojure/cljs/externs/default.edn`; run it after updating the Closure Compiler. CI's `compiler` suite (`.github/workflows/test.yaml`) fails when the file is out of date.

## Questions and feedback

Please point all of your questions and feedback to the [#clojurific](https://clojurians.slack.com/archives/C0C7W7LTA4D) Slack channel.

Bug reports and feature requests can be opened as issues in the [GitHub repo](https://github.com/WhimsicalCode/clojurific/issues).

## License

Clojurific is licensed under Eclipse Public License 1.0 (https://opensource.org/license/epl-1-0/), same as ClojureScript.

Copyright © Whimsical, Inc.

Copyright © Rich Hickey
