# Clojurific

Clojurific is a fork of ClojureScript: `:module-format :esm` compiles namespaces to ES modules
bundled by Vite, without the Closure Compiler. Its coordinate is `com.whimsical/clojurific`;
ClojureScript's namespaces stay `cljs.*`, so libraries written for ClojureScript work unchanged,
and the fork's own are `cljf.*` (`cljf.esm`, `cljf.x`). Read
[`README.md`](README.md) (usage, design, known gaps) before changing it.

## Where things are

- `src/main/clojure/cljf/esm.clj` — the ES module build, watch and test runner generation
- `src/main/clojure/cljf/esm/repl.clj`, `src/main/cljs/cljf/esm/repl_runtime.cljs` — the nREPL server
  and its browser runtime
- `src/main/cljs/cljf/esm/` — runtime support: `goog.js`, `lazy`, `node_test`, `karma`
- `src/main/cljs/cljf/x.clj` — extensions to ClojureScript (`defclass`, over the analyzer's `class*`
  and `super*`)
- `src/main/cljs/cljf/jsx.clj`, `jsx.cljs` — `#x` / `#jsx` literals (reader tags in
  `cljs/tagged_literals.cljc`): hiccup compiled to `react/jsx-runtime` calls, and the runtime
  conversions of what the macro can't see into
- `src/main/js/` — the npm package `clojurific` (its `package.json`); `script/package-npm` packs it
  with `no-clojurescript/`. It has no compiler: the launcher gets `com.whimsical/clojurific` of its
  version from Clojars, or the fork's sources when run from this repository
- `src/main/js/vite-plugin-cljs/` — the Vite plugin
- `src/main/js/create-clojurific/` — the npm package `create-clojurific` (`npm create clojurific`,
  Node.js built-ins only): its prompts, the templates (`templates/{vanilla,react,reagent,uix,hono}`) and their
  tests. It's released with `clojurific`, of the same version, which its projects depend on
- `src/main/js/cljf/` — the `cljf` launcher (Node.js built-ins only, no npm dependencies): finding
  and installing Java (`java.js`), downloading the resolver's jars (`bootstrap.js`), the cached
  classpath and the compiler command (`launcher.js`), the terminal REPL, an nREPL client (`repl.js`), the resolver run with tools.deps
  (`resolver/clojurific/resolve.clj`), and its tests (`test/`)
- `script/clojars` (`clojars.clj`) — the Clojars artifact `com.whimsical/clojurific`: the
  compiler's sources, with the version `script/stamp-version` sets in them, and a pom
- `no-clojurescript/` — an empty project replacing stock ClojureScript (`org.clojure/clojurescript`)
  through `:override-deps`
- `src/test/clojure/cljf/esm_build_tests.clj`, `esm_repl_tests.clj` — build and REPL tests

## Testing

`script/test-esm` runs the build and REPL tests, then the ClojureScript runtime test suite compiled
to ES modules under Node.js. It must end with `0 failures, 0 errors.` It runs without the Closure
Compiler on the classpath, which the ES module build doesn't need. Changes outside `cljf.esm` that
can affect the classic compiler also need `script/test` (runtime tests, `:advanced`) and the
compiler tests (`clojure -M:closure:compiler.test:compiler.test.run`). The classic compiler needs
the Closure Compiler, which only the `:closure` alias adds: list it with the other aliases, e.g.
`clojure -M:closure:runtime.test.build`.

Without `script/bootstrap`'s jars and JS engine homes, `script/test` can't run: build the suites
with `clojure -M:closure:runtime.test.build` (and `lite.test.build`, `selfhost.test.build`), run
`builds/out-adv/core-advanced-test.js` and `builds/out-lite/lite-test.js` under Node with
`globalThis.print = console.log` preloaded (`node -r`), and `builds/out-self/core-self-test.js` from
the repository root (it reads `src/test/self/...` relative to the working directory).

Gate code generation changes meant for ES module output on `*esm-emitting*` (in `core.cljc`
macros, `(:module-format (cljs.analyzer/compiler-options))`): the compiler tests ratchet
`:advanced` output size (`build_api_tests.clj`) and assert the emitted classic code
(`test-optimized-invoke-emit`), which `script/test-esm` doesn't cover.

`script/test-clojars` installs the Clojars artifact into an empty local Maven repository and builds
with it from the Clojure CLI. `script/test-launcher` tests the launcher and `create-clojurific`
(`node --test`) and the npm packages: it packs them, installs `clojurific` into an empty project and
builds with `npx cljf`, then scaffolds and bundles each template. The integration tests resolve and build real
projects with the local Maven repository; `CLJF_TEST_DOWNLOADS=1` adds a cold start from an empty
one (about 20 MB from Maven Central). Java 17 is the launcher's minimum: run `script/test-esm` and
`script/test-launcher` on it too after changes that could need a newer Java.

After updating `com.google.javascript/closure-compiler` (e.g. when merging an upstream bump), run
`script/gen-default-externs` and commit `src/main/clojure/cljs/externs/default.edn`, the default
externs the analyzer reads without the Closure Compiler. After changing
`src/main/js/cljf/resolver/deps.edn` (i.e. to update tools.deps), run `script/lock-resolver` and
commit `src/main/js/cljf/resolver.lock.json`.

ClojureScript namespaces requiring `cljs.repl` load `cljs/repl.cljc` on the JVM for its macros, so
keep it free of load-time `cljs.closure` requires; `script/test-esm` fails without the jar otherwise.

When diffing the compiler's output across changes, `:parallel-build` makes some local names
nondeterministic (`shadow-depth` renames a local when a namespace starting with its name was already
analyzed): normalize `__$N` suffixes and gensym numbers, or build without `:parallel-build`.

## Versions and the changelog

The npm package's version (`src/main/js/package.json`, and `create-clojurific`'s, which must match
it) is `0.<minor>.<release>`: `<minor>` is the
ClojureScript 1.x release the fork is based on (12 for 1.12.x), `<release>` counts Clojurific's
releases from 1. Bump `<release>` for each release; when merging a new upstream minor release, set
`<minor>` to it and `<release>` back to 1.

[`CHANGELOG.md`](CHANGELOG.md) records the fork's changes since it was forked, not upstream's (its
`changes.md` isn't kept). Add each noteworthy change under the `[Next]` release, in `### Added`,
`### Changed` or `### Fixed` (in that order, only the ones with entries), as one concise bullet.
Record merges of upstream ClojureScript too, naming the upstream commit or release. Omit changes
only to tests, tooling or the fork's own unreleased features. When releasing, replace `[Next]` with
the release date (`[YYYY-MM-DD]`) and start a new `[Next]` release with the next version.

## Releasing

`.github/workflows/release.yml` releases when a GitHub release is published. Date the version in
`CHANGELOG.md`, then create the release from `main` with a tag matching
`src/main/js/package.json`'s version:
`gh release create v0.12.2 --repo WhimsicalCode/clojurific --target main`.

The workflow checks that the commit is on `main` and that the tag and the changelog match, runs
`script/test-esm`, `script/test-launcher` and `script/test-clojars` on Linux (Java 17 and 25) and
macOS, then:

- deploys the Clojars artifact in the `release` environment, whose required reviewer approves the
  deployment and which holds the `CLOJARS_USERNAME` and `CLOJARS_DEPLOY_TOKEN` secrets. It's public
  right away, Clojars has no staging.
- stages the npm packages (`clojurific`, `create-clojurific`) with npm's trusted publishing (no
  token) and provenance. A maintainer approves the staged versions with 2FA (`npm stage list
  clojurific`, `npm stage approve <id>`, or on npmjs.com) to publish them. When staging fails for
  one package, approve the staged versions, then re-run the failed `npm` job: it skips the versions
  already published.

Then start the next `[Next]` release, bump the version (both `package.json`s) and point
[`README.md`](README.md)'s `deps.edn` coordinate at the released version. Only release when the
user asks.
