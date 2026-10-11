# Clojurific #

ClojureScript compiled to ES modules, bundled by Vite.

Clojurific is a fork of [ClojureScript](https://github.com/clojure/clojurescript)
by [Whimsical](https://whimsical.com). Its `:module-format :esm` compiles every
namespace to an ES module and leaves bundling, minification, npm packages and
TypeScript to Vite, without the Google Closure Compiler. It includes a Vite
plugin, hot reloading and an nREPL server evaluating in the browser. See
[ESM.md](ESM.md) for usage, design and known gaps. The classic compiler
(`cljs.main`, `cljs.build.api`) is still there and needs the Closure Compiler,
see [Dependencies](ESM.md#dependencies).

It's based on ClojureScript's master after the 1.12.145 release; see
[CHANGELOG.md](CHANGELOG.md) for where it forked and what it changed. It keeps ClojureScript's
namespaces (`cljs.core`, `cljs.analyzer`, …), so libraries written for
ClojureScript work unchanged. Its own are `cljf.*`: the ES module build
(`cljf.esm`) and the extensions to ClojureScript (`cljf.x`).
Status: experimental.

Versions are `0.<minor>.<release>`: `<minor>` is the ClojureScript 1.x release
it's compatible with (12 for 1.12.x), `<release>` counts Clojurific's releases,
starting from 1.

## Getting started ##

```sh
npm create clojurific@latest
```

scaffolds a Vite project (Vanilla, [Reagent](https://reagent-project.github.io/),
[UIx](https://uix-cljs.dev/), or a [Hono](https://hono.dev/) backend), installs it
and starts the dev server.
It needs Node.js 20 and Java 17 or later, and offers to download Java when there's none.

## Migrating from shadow-cljs ##

Vite replaces shadow-cljs' build and dev server: `npm install -D clojurific vite`,
then move `shadow-cljs.edn` over:

- `:source-paths` and `:dependencies` go to `deps.edn`'s `:paths` and `:deps`.
  The Vite plugin resolves it with the `cljf` launcher, the Clojure CLI isn't needed.
- The build's `:compiler-options` (`:closure-defines`, `:warnings-as-errors`, …)
  go to `cljs.edn`, the plugin's `config`, or its `compilerOptions`.
  `:dev`/`:release` settings are the `:dev` and `:release` profiles.
- `:modules`' `:init-fn` becomes a script in `index.html`
  (`<script type="module" src="/src/my/app.cljs"></script>`), whose namespace
  calls its init fn at the top level. `:output-dir` is the plugin's `outputDir`,
  `:dev-http` is Vite's dev server.
- Add `:npm-interop :shadow` to keep shadow-cljs' bindings of CommonJS packages
  (`["pkg" :as x]` is `module.exports`).

```js
// vite.config.mjs
import cljs from 'clojurific/vite';

export default {
  plugins: [cljs({ config: 'cljs.edn', outputDir: 'target/cljs',
                   compilerOptions: { 'npm-interop': ':shadow' } })],
};
```

In the code, `^:dev/before-load` / `^:dev/after-load`, `shadow.resource` and
symbol requires of npm packages work as they are. `shadow.cljs.modern`'s
`defclass` is `cljf.x`'s, `shadow.lazy` is `cljf.esm.lazy`, and
lazily loaded namespaces are `:main` entries split by the bundler instead of
`:modules`. `:test` builds become a `:test-runner`. See [ESM.md](ESM.md) for
the details and the [known gaps](ESM.md#known-gaps).

## Dependency information ##

[Clojure deps.edn](https://clojure.org/guides/deps_and_cli), from
[Clojars](https://clojars.org/com.whimsical/clojurific):

```clojure
com.whimsical/clojurific {:mvn/version "0.12.6"}
```

[npm](https://www.npmjs.com/package/clojurific), with the `cljf` launcher, which
needs Java but not the Clojure CLI: `npm install clojurific`.

Stock ClojureScript (`org.clojure/clojurescript`) has the same namespaces, so
it mustn't be on the classpath too: `cljf.esm` fails at startup, naming both,
when it is. Exclude it from the library that brings it in with
`:exclusions [org.clojure/clojurescript]`, or from the whole dependency tree
with the empty project in `no-clojurescript/`:

```clojure
:override-deps {org.clojure/clojurescript {:git/url "https://github.com/WhimsicalCode/clojurific"
                                           :git/sha "…"
                                           :deps/root "no-clojurescript"}}
```

## Questions, Feedback? ##

Please point all of your questions and feedback to the [#clojurific](https://clojurians.slack.com/archives/C0C7W7LTA4D) Slack channel. 

Bug and features requests can be opened as issues in [GitHub repo](https://github.com/WhimsicalCode/clojurific/issues).

## License ##

Clojurific is licensed under Eclipse Public License 1.0 (https://opensource.org/license/epl-1-0/), same as ClojureScript.

Copyright © Whimsical, Inc.
Copyright © Rich Hickey
