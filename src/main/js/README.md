# clojurific

ClojureScript compiled to ES modules, bundled by Vite.

[Clojurific](https://github.com/WhimsicalCode/clojurific) is a fork of
ClojureScript whose `:module-format :esm` compiles every namespace to an ES
module, leaving bundling, minification, npm packages and TypeScript to Vite,
without the Google Closure Compiler. This package has the `cljf` command, a
Vite plugin with hot reloading, and a Karma adapter; `cljf` gets the compiler,
`com.whimsical/clojurific`, from Clojars. It needs
Node.js 20 or later and Java 17 or later, not the Clojure CLI.

Status: experimental. Versions are `0.<minor>.<release>`: `<minor>` is the
ClojureScript 1.x release it's compatible with (12 for 1.12.x), `<release>`
counts Clojurific's releases, starting from 1.

## Usage

`npm create clojurific@latest` scaffolds a project (Vanilla, Reagent, UIx, or
a Hono backend) like the one below, installs it and starts the dev server.

Dependencies and source paths come from the project's `deps.edn`:

```clojure
{:paths ["src"]
 :deps {reagent/reagent {:mvn/version "2.0.1"}}}
```

```js
// vite.config.mjs
import cljs from 'clojurific/vite';

export default {
  plugins: [cljs({ outputDir: 'target/cljs' })],
};
```

```html
<!-- index.html -->
<!doctype html>
<div id="app"></div>
<script type="module" src="/src/my/app.cljs"></script>
```

`npx vite` serves the page and hot reloads changed namespaces, `npx vite build`
bundles it into `dist/`. JavaScript can import namespaces too:
`import './my/app.cljs'`. The project's `package.json` needs `"type":
"module"`.

or with the command line:

```sh
npx cljf build '{:main my.app :output-dir "out"}'
npx cljf -A:test watch @cljs.edn
npx cljf classpath
npx cljf repl
```

`cljf repl` is a ClojureScript REPL evaluating in the pages the dev server
runs, through its nREPL server: `cljs({ outputDir: 'target/cljs',
compilerOptions: { repl: { 'nrepl-port': 0 } } })` with `nrepl/nrepl` in
`deps.edn`.

`cljf` resolves `deps.edn` with tools.deps, adds the compiler, and removes
stock ClojureScript (`org.clojure/clojurescript`) that libraries depend on.
Dependencies are downloaded into `~/.m2/repository`, which the Clojure CLI
shares, and the classpath is cached in `.cljf/` until `deps.edn` changes.

## Java

`cljf` uses `CLJF_JAVA`, `JAVA_HOME` or `java` on `PATH`. Without Java 17 or
later it explains how to install one, or downloads Eclipse Temurin into its
own cache:

```sh
npx cljf setup-java
```

`CLJF_INSTALL_JDK=1` does that without asking, i.e. in CI.

## Configuration

| Variable | |
|---|---|
| `CLJF_JAVA` | the `java` binary to use |
| `CLJF_INSTALL_JDK=1` | install Java with `setup-java` when there's none |
| `CLJF_MAVEN_REPO` | a Maven Central mirror to download the dependency resolver from; other dependencies use `:mvn/repos` |
| `CLJF_CACHE_DIR` | where `setup-java` installs Java |

See [ESM.md](https://github.com/WhimsicalCode/clojurific/blob/main/ESM.md) for
compiler options, the Vite plugin's options, the REPL and how the launcher
works.

## License

Eclipse Public License 1.0, like ClojureScript.
