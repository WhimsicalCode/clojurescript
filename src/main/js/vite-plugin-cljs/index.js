// Vite plugin for ClojureScript compiled to ES modules (:module-format :esm).
//
// The ClojureScript compiler runs in a JVM, `vite build` runs a one-shot build
// before bundling, `vite` (dev) runs the compiler in watch mode. Compiled
// namespaces are plain ES modules in outputDir, Vite serves, hot reloads and
// bundles them like any other JavaScript, including the npm packages and
// TypeScript files they import.
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import { existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { Worker } from 'node:worker_threads';
import { compilerCommand } from '../cljf/launcher.js';
import { pruneChunks, propertyRenames } from './prune.js';
import { ednValue } from './edn.js';
import { watchWarnings } from './warnings.js';

const EVENT_PREFIX = '[cljf.esm] ';
const NPM_AS = 'cljs-npm-as:';
// a ClojureScript source loaded by a page or imported from JavaScript, which
// the dev server serves as an import of its compiled module
const SOURCE = 'cljs-source:';
const SOURCE_FILE = /\.clj[sc]$/;

// The namespace of a ClojureScript source file, from its ns form
async function sourceNamespace(file) {
  const code = (await fs.readFile(file, 'utf8')).replace(/;.*$/gm, '');
  const ns = code.match(/\(\s*ns\s+(?:\^(?:\{[^}]*\}|\S+)\s+)*([^\s()[\]{}"^;]+)/)?.[1];
  if (!ns) throw new Error(`${file} has no ns form`);
  return ns;
}

const SCRIPT_SRC = /<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi;
// import "x", import x from "x", export … from "x", import("x")
const IMPORT = /(?:\bimport\s*(?:[\w$*{}\s,]+\bfrom\s*)?|\bexport\s*[\w$*{}\s,]+\bfrom\s*|\bimport\s*\(\s*)["']([^"']+)["']/g;
const SCRIPT_FILE = /\.(?:[cm]?[jt]sx?)$/;
// extensionless imports, as TypeScript projects write them
const SCRIPT_SUFFIXES = ['', '.ts', '.tsx', '.js', '.jsx', '.mjs', '/index.ts', '/index.js'];

// The file a page's script or a module's import names: root-absolute or
// relative paths, not packages or aliases, which the bundler resolves
function importedFile(spec, from, root) {
  const file = spec.replace(/[?#].*$/, '');
  if (!file.startsWith('/') && !file.startsWith('.')) return null;
  return file.startsWith('/') ? path.join(root, file) : path.resolve(path.dirname(from), file);
}

// The ClojureScript sources the entries (pages and JavaScript or TypeScript
// modules) load: the pages' scripts and the modules' imports, followed through
// the project's modules
async function entrySources(entries, root) {
  const sources = new Set();
  const seen = new Set();
  const queue = [...entries];
  while (queue.length) {
    const file = queue.shift();
    if (seen.has(file) || file.includes(`${path.sep}node_modules${path.sep}`)) continue;
    seen.add(file);
    let code;
    try {
      code = await fs.readFile(file, 'utf8');
    } catch (e) {
      continue;
    }
    for (const [, spec] of code.matchAll(file.endsWith('.html') ? SCRIPT_SRC : IMPORT)) {
      const target = importedFile(spec, file, root);
      if (!target) continue;
      if (SOURCE_FILE.test(target)) {
        if (existsSync(target)) sources.add(target);
      } else {
        const module = SCRIPT_SUFFIXES.map(suffix => target + suffix)
          .find(f => SCRIPT_FILE.test(f) && existsSync(f) && !statSync(f).isDirectory());
        if (module) queue.push(module);
      }
    }
  }
  return [...sources];
}

// Whether address is this machine's
function isLoopback(address) {
  return /^(127\.|::1$|::ffff:127\.)/.test(address ?? '');
}

// Whether a websocket connection comes from this machine: its peer, and the
// client a proxy on this machine (nginx) names in X-Real-IP
function isLocalConnection(req) {
  const realIp = req.headers['x-real-ip'];
  return isLoopback(req.socket.remoteAddress) && (realIp === undefined || isLoopback(realIp));
}

// A module without ES module syntax is CommonJS, i.e. its module.exports is
// what shadow-cljs binds with :as.
function isCommonJS(code) {
  const stripped = code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  return !/(^|[;\n])\s*(import\s*[\w*{"']|export\s+[\w*{]|export\s*\{)/.test(stripped);
}

// Whether the project's package.json makes its .js files CommonJS ("type":
// "commonjs", which npm init writes; without a type, the bundler detects ES
// modules)
async function commonJSPackage(root) {
  try {
    return JSON.parse(await fs.readFile(path.join(root, 'package.json'), 'utf8')).type === 'commonjs';
  } catch (e) {
    return false;
  }
}

// A Rollup input (a file, files or {name: file}) as {name: absolute file},
// files named by their path in the root without the extension, so pages with
// the same file name in different directories (about/index.html) keep apart
function inputObject(input, root) {
  if (!input) return {};
  if (typeof input === 'object' && !Array.isArray(input)) {
    return Object.fromEntries(Object.entries(input).map(([name, file]) => [name, path.resolve(root, file)]));
  }
  return Object.fromEntries([].concat(input).map(file => {
    const absolute = path.resolve(root, file);
    const relative = path.relative(root, absolute);
    const name = relative.startsWith('..') || path.isAbsolute(relative) ? path.basename(absolute) : relative;
    return [name.slice(0, name.length - path.extname(name).length).split(path.sep).join('/'), absolute];
  }));
}

// oxc's minifier shipped with Rolldown, as used by Vite
function resolveFromProject(root, specifier, purpose) {
  const tryResolve = (base, spec) => {
    try {
      return createRequire(path.join(base, 'noop.js')).resolve(spec);
    } catch (e) {
      return null;
    }
  };
  // Vite's own dependencies (rolldown) from Vite's install: isolated installs
  // link only the project's direct dependencies
  const vite = tryResolve(root, 'vite') ?? tryResolve(process.cwd(), 'vite');
  for (const base of [root, process.cwd(), ...(vite ? [path.dirname(vite)] : [])]) {
    const resolved = tryResolve(base, specifier);
    if (resolved) return resolved;
  }
  throw new Error(`${specifier} not found, can not ${purpose}`);
}

// The native summarizer (src/main/rust/prune, script/build-native-prune) for
// this platform, null without one.
let nativePruneWarned = false;

function loadNativePrune() {
  const file = new URL(`./native/cljf-prune.${process.platform}-${process.arch}.node`, import.meta.url);
  if (process.env.CLJF_NATIVE_PRUNE === 'false' || !existsSync(file)) return null;
  try {
    return createRequire(import.meta.url)(fileURLToPath(file));
  } catch (e) {
    // a binary that's there but doesn't load (i.e. built for another Node.js
    // ABI) is said once, not mistaken for none
    if (!nativePruneWarned) {
      nativePruneWarned = true;
      console.warn(`[cljf] the native prune summarizer ${fileURLToPath(file)} didn't load, ` +
        `summarizing in JavaScript instead (same output): ${e.message.split('\n')[0]}`);
    }
    return null;
  }
}

// The native summaries (string table, statements as u32s) as prune.js'.
function decodeSummary({ strings, statements: a }) {
  const kinds = ['root', 'decl', 'prop'];
  const out = [];
  let i = 0;
  const list = () => {
    const n = a[i++];
    const r = new Array(n);
    for (let k = 0; k < n; k++) r[k] = strings[a[i++]];
    return r;
  };
  while (i < a.length) {
    const s = { start: a[i++], end: a[i++], kind: kinds[a[i++]] };
    const object = a[i++], prop = a[i++];
    const names = list();
    if (s.kind === 'prop') {
      s.object = strings[object];
      s.prop = prop === 0xFFFFFFFF ? null : strings[prop];
    }
    if (s.kind === 'decl') s.names = names;
    s.ids = list();
    s.props = list();
    out.push(s);
  }
  return out;
}

// Statement summaries of chunks for pruning (prune.js): by the native
// summarizer if there's one for this platform (and it supports the chunk),
// else parsed with oxc's parser (shipped with Rolldown, at oxcPath) in
// worker threads, the largest chunks first.
async function summarizeChunks(oxcPath, chunks) {
  const native = loadNativePrune();
  const summaries = native ? native.summarizeChunks(chunks.map(c => c.code)).map(s => s && decodeSummary(s)) : [];
  const rest = chunks.map((c, i) => i).filter(i => !summaries[i]);
  if (rest.length) {
    const results = await summarizeChunksJS(oxcPath, rest.map(i => chunks[i]));
    rest.forEach((i, k) => (summaries[i] = results[k]));
  }
  return summaries;
}

async function summarizeChunksJS(oxcPath, chunks) {
  const order = chunks.map((c, i) => i).sort((a, b) => chunks[b].code.length - chunks[a].code.length);
  const results = new Array(chunks.length);
  const size = Math.min(chunks.length, os.availableParallelism());
  const workers = Array.from({ length: size }, () => new Worker(new URL('./prune-worker.js', import.meta.url)));
  try {
    await Promise.all(workers.map(async worker => {
      for (let i = order.shift(); i !== undefined; i = order.shift()) {
        const { fileName, code } = chunks[i];
        const reply = await new Promise((resolve, reject) => {
          worker.once('message', resolve);
          worker.once('error', reject);
          worker.postMessage({ id: i, oxcPath, fileName, code });
        });
        worker.removeAllListeners('error');
        if (reply.error) throw new Error(reply.error);
        results[i] = reply.summary;
      }
    }));
  } finally {
    await Promise.all(workers.map(w => w.terminate()));
  }
  return results;
}

// A composed source map, as the bundler's chunks have them: with toString
// and toUrl.
class ChunkSourceMap {
  constructor(map) {
    Object.assign(this, map);
  }
  toString() {
    return JSON.stringify(this);
  }
  toUrl() {
    return `data:application/json;charset=utf-8;base64,${Buffer.from(this.toString()).toString('base64')}`;
  }
}

// Vite's build-import-analysis rewrites dynamic imports' __VITE_PRELOAD__
// markers in a chunk (and prepends __vite__mapDeps), then composes a source
// map of that edit with the chunk's: serially, chunk after chunk, a few
// seconds for large bundles. When the plugin composes every chunk's map again
// after minifying anyway, Vite rewrites the code without the map, and the
// minify workers compose the same edit's map (preload-edit-map.js). The
// edit, from the chunk's code before (code) and after (edited): {prefix,
// updates: [[start, end, text]]}, or null when the edited code isn't exactly
// such an edit.
const PRELOAD_MARKER = '__VITE_PRELOAD__';
const PRELOAD_REPLACEMENT = /^(?:void 0|\[\]|__vite__mapDeps\(\[[\d,]*\]\))/;
const MAP_DEPS = /^const __vite__mapDeps=[^\n]*\n/;

function preloadEdit(code, edited) {
  const prefix = MAP_DEPS.exec(edited)?.[0] ?? '';
  const updates = [];
  let at = prefix.length, from = 0;
  for (let i = code.indexOf(PRELOAD_MARKER); ; i = code.indexOf(PRELOAD_MARKER, from)) {
    const end = i < 0 ? code.length : i;
    if (!edited.startsWith(code.slice(from, end), at)) return null;
    at += end - from;
    if (i < 0) break;
    const replacement = PRELOAD_REPLACEMENT.exec(edited.slice(at, at + 4096))?.[0];
    if (replacement === undefined) return null;
    updates.push([i, i + PRELOAD_MARKER.length, replacement]);
    at += replacement.length;
    from = i + PRELOAD_MARKER.length;
  }
  return at === edited.length ? { prefix, updates } : null;
}

// Wraps Vite's build-import-analysis generateBundle (plugin) to rewrite the
// chunks' preload markers without composing their source maps; pending maps
// each edited chunk's file name to {code, edit, map} for generateBundle.
// Chunks whose edit preloadEdit doesn't recognize are rewritten by Vite again
// from their original code, with their maps.
function deferPreloadSourceMaps(plugin, pending) {
  const hook = plugin.generateBundle;
  const handler = typeof hook === 'function' ? hook : hook.handler;
  const deferred = async function (outputOptions, bundle) {
    // this build's edits only: a watch (or reused plugin instance) builds
    // again, chunks without hashed names keep their file names
    pending.clear();
    const chunks = Object.values(bundle).filter(c => c.type === 'chunk' && c.map && c.code.includes(PRELOAD_MARKER));
    const before = new Map(chunks.map(c => [c, { code: c.code, map: c.map }]));
    chunks.forEach(c => (c.map = null));
    try {
      await handler.call(this, outputOptions, bundle);
    } finally {
      chunks.forEach(c => (c.map = before.get(c).map));
    }
    const redo = [];
    for (const [chunk, { code, map }] of before) {
      if (chunk.code === code) continue;
      const edit = preloadEdit(code, chunk.code);
      if (edit) {
        pending.set(chunk.fileName, { code, edit, map });
      } else {
        chunk.code = code;
        redo.push(chunk);
      }
    }
    if (redo.length) {
      // the other chunks' markers are rewritten already
      await handler.call(this, outputOptions, bundle);
    }
  };
  plugin.generateBundle = typeof hook === 'function' ? deferred : { ...hook, handler: deferred };
}

// Minifies the pruned chunks (codes) in worker threads (minify-worker.js), the
// largest first, composing their source maps with the bundler's (maps, JSON
// or null) with @jridgewell/remapping, which the project depends on, at
// remappingPath. Returns [{code, map, error}].
async function minifyChunks(oxcPath, remappingPath, fileNames, codes, maps, edits, options) {
  const order = codes.map((c, i) => i).sort((a, b) => codes[b].length - codes[a].length);
  const results = new Array(codes.length);
  const size = Math.min(codes.length, os.availableParallelism());
  const workers = Array.from({ length: size }, () => new Worker(new URL('./minify-worker.js', import.meta.url)));
  try {
    await Promise.all(workers.map(async worker => {
      for (let i = order.shift(); i !== undefined; i = order.shift()) {
        const reply = await new Promise((resolve, reject) => {
          worker.once('message', resolve);
          worker.once('error', reject);
          worker.postMessage({ id: i, oxcPath, remappingPath, fileName: fileNames[i], code: codes[i], map: maps[i], edit: edits[i], options });
        });
        worker.removeAllListeners('error');
        if (reply.fatal) throw new Error(reply.fatal);
        results[i] = reply;
      }
    }));
  } finally {
    await Promise.all(workers.map(w => w.terminate()));
  }
  return results;
}

// Whether a dependency pre-bundled by Vite's dev server was CommonJS, from the
// optimizer's metadata.
async function preBundledCommonJS(file) {
  const metadata = JSON.parse(await fs.readFile(path.join(path.dirname(file), '_metadata.json'), 'utf8'));
  const name = path.basename(file);
  return Object.values({ ...metadata.optimized, ...metadata.chunks })
    .some(dep => dep.file === name && dep.needsInterop);
}

// Wraps a CommonJS module as an ES module, static require("x") calls become
// imports of x's module.exports (or namespace).
function commonJSToESM(code) {
  const imports = [];
  const body = code.replace(/\brequire\(\s*(['"])([^'"]+)\1\s*\)/g, (_, q, spec) => {
    const name = `__cljs_require_${imports.length}`;
    imports.push(`import ${name} from ${JSON.stringify(NPM_AS + spec)};`);
    return name;
  });
  return `${imports.join('\n')}
const module = { exports: {} };
const exports = module.exports;
${body}
export default module.exports;
`;
}

/**
 * @param {object} options
 * @param {string[]} [options.aliases] deps.edn aliases of the classpath the
 *   compiler runs with, i.e. ["cljs"]. The launcher (cljf) resolves it with
 *   the compiler added and stock ClojureScript removed, see cljf/launcher.js.
 * @param {string[]} [options.command] starts Clojure with the compiler on the
 *   classpath instead of the launcher, i.e. ["clojure", "-M:cljs"],
 *   cljf.esm's arguments follow
 * @param {string} [options.cwd] the compiler's working directory, with the
 *   project's deps.edn, defaults to Vite's root (the project's directory)
 * @param {string} [options.config] compiler options file (EDN, see
 *   cljf.esm/load-options), relative to cwd
 * @param {string} [options.profile] profile of the config file, defaults to
 *   dev when serving and release when building
 * @param {string} options.outputDir the compiler's output directory, relative
 *   to cwd. Pages load namespaces from it, i.e. outputDir "out" serves my.app
 *   as /out/my/app.js
 * @param {object|string} [options.compilerOptions] more compiler options, an
 *   EDN string or an object with keyword keys, camelCase or kebab-case
 * @param {string|false} [options.manifest] writes the entry points' scripts
 *   and preloads to this file in Vite's outDir, for server rendered pages,
 *   defaults to "manifest.json"
 * @param {boolean} [options.prune] remove unused protocol implementations and
 *   shorten ClojureScript property names in production bundles, defaults to
 *   true
 * @param {string[]} [options.entries] the :js-entries this build bundles,
 *   defaults to all; each is self-contained when it's the only one
 * @param {string[]} [options.ssrEntries] :js-entries the dev server runs in
 *   its ssr environment once they're compiled, i.e. a server's (which a
 *   plugin like @hono/vite-dev-server imports from the same runner): the REPL
 *   evaluates in them before their first request
 * @param {boolean} [options.ignoreWarnings] hot reload despite compiler
 *   warnings, like shadow-cljs' :devtools {:ignore-warnings true}: they go
 *   to the terminal only, defaults to false: hot reload pauses while any
 *   namespace has warnings, and the overlay shows them
 * @param {boolean} [options.replRemoteConsole] accept cljs_eval (forms the
 *   compiler compiles, running its macros) from pages on other hosts too,
 *   defaults to false: only from this machine. A proxy in front of the dev
 *   server must set X-Real-IP to the client's address.
 */
export default function cljf(options) {
  const manifestName = options.manifest ?? 'manifest.json';
  let config, proc, server, minify, root, cwd, outputDir, compiler;
  // chunks' preload edits whose source maps generateBundle composes
  const preloadEdits = new Map();
  // the namespaces of the pages' scripts, compiled as main namespaces
  let pageNamespaces = [];
  let compiling = Promise.resolve();
  const localModules = new Set();
  // the compiler warnings, which hold hot updates back (see warnings.js)
  const warnings = watchWarnings({ ignoreWarnings: options.ignoreWarnings });

  function compilerArgs(command, extra) {
    const profile = options.profile ?? (command === 'watch' ? 'dev' : 'release');
    const more = typeof options.compilerOptions === 'string'
      ? options.compilerOptions
      : ednValue(options.compilerOptions ?? {});
    return [
      command,
      ...(options.config ? [`@${options.config}`] : []),
      `:${profile}`,
      ednValue({
        ':output-dir': outputDir,
        ...(pageNamespaces.length ? { ':extra-main': pageNamespaces.map(ns => `'${ns}`) } : {}),
        ...extra,
      }),
      more,
    ];
  }

  function run(args, onEvent) {
    const [cmd, ...cmdArgs] = compiler;
    // cljf.main: cljf.esm's command line, loading the compiler from cached classes
    const child = spawn(cmd, [...cmdArgs, '-m', 'cljf.main', ...args], {
      cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    const log = (level, line) => (config ? config.logger[level](line) : console[level === 'warn' ? 'warn' : 'log'](line));
    let buffer = '';
    child.stdout.on('data', chunk => {
      buffer += chunk;
      let i;
      while ((i = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, i);
        buffer = buffer.slice(i + 1);
        if (line.startsWith(EVENT_PREFIX)) onEvent(JSON.parse(line.slice(EVENT_PREFIX.length)));
        else if (line.trim()) log('info', line);
      }
    });
    child.stderr.on('data', chunk => {
      for (const line of String(chunk).split('\n')) {
        if (line.trim()) log('warn', line);
      }
    });
    return child;
  }

  // The compiler's watch for vite build --watch, compiling tracks the
  // compile in progress. Resolves once a build succeeded, the bundle's inputs
  // are its output: a failed initial build is logged and the watch waits for
  // the fix, like the dev server. Rejects when the compiler exits first.
  function watchCompiler() {
    let compiled = () => {};
    return new Promise((resolve, reject) => {
      let first = true;
      // the build's options: bundles don't hot reload, the dev server's watch does
      proc = run(compilerArgs('watch', { ':exit-with-parent': true, ':esm-hmr': false }), event => {
        if (event.type === 'compiling') {
          compiling = new Promise(resolve => (compiled = resolve));
        } else if (event.type === 'compiled' || event.type === 'error') {
          compiled();
          const logger = config?.logger ?? console;
          if (event.type === 'error') logger.error(`[cljf] ${event.message}`);
          else logger.info(`[cljf] compiled ${event.namespaces} namespace(s) in ${event.ms}ms`);
          if (first && event.type === 'compiled') {
            first = false;
            resolve();
          }
        }
      });
      proc.on('exit', code => first && reject(new Error(`ClojureScript watch exited (${code})`)));
    });
  }

  async function buildInfo() {
    try {
      return JSON.parse(await fs.readFile(path.join(outputDir, 'cljs-esm.json'), 'utf8'));
    } catch (e) {
      return null;
    }
  }

  // Source map sources: compiled namespaces by classpath path (app/main.cljs),
  // other files relative to cwd (the project), not to the machine that built
  // them; the maps embed their content. source is relative to directory dir.
  function sourcePath(dir, source) {
    if (source == null) return source;
    const file = path.resolve(dir, source);
    const compiled = file.startsWith(outputDir + path.sep);
    // already transformed (Vite rewrites the maps of chunks it edits)
    if (!compiled && !existsSync(file)) return source;
    return (compiled ? path.relative(outputDir, file) : path.relative(cwd, file)).split(path.sep).join('/');
  }

  // Whether a ClojureScript source may be served: in Vite's root or
  // server.fs.allow
  function servedSource(file) {
    const dirs = [config.root, ...(config.server?.fs?.allow ?? [])].map(dir => path.resolve(dir));
    return SOURCE_FILE.test(file) && dirs.some(dir => file.startsWith(dir + path.sep));
  }

  // The compiled module of a ClojureScript source file: its namespace's in the
  // build info, or its munged name
  async function compiledModule(file) {
    // a source added while serving is compiled first
    await compiling;
    const ns = await sourceNamespace(file);
    const relative = (await buildInfo())?.main?.[ns] ??
      `${ns.split('.').map(part => part.replace(/-/g, '_')).join('/')}.js`;
    const module = path.join(outputDir, relative);
    if (!existsSync(module)) {
      throw new Error(`${ns} (${file}) isn't compiled: load it from a page's script or a module the pages ` +
        'import (through relative or root-absolute imports, restarting Vite after adding it), or add it to :main');
    }
    return module;
  }

  // The dev server's URL of an output file (relative to outputDir)
  function devUrl(file) {
    const absolute = path.resolve(outputDir, file);
    const relative = path.relative(config.root, absolute);
    return config.base + (relative.startsWith('..') || path.isAbsolute(relative)
      ? `@fs${absolute}`
      : relative).split(path.sep).join('/');
  }

  async function writeManifest(manifest) {
    if (!manifestName) return;
    const file = path.resolve(config.root, config.build.outDir, manifestName);
    await fs.mkdir(path.dirname(file), { recursive: true });
    // written atomically, a server may read it any time
    await fs.writeFile(`${file}.tmp`, JSON.stringify(manifest, null, 2));
    await fs.rename(`${file}.tmp`, file);
  }

  return {
    name: 'cljf',
    // before Vite's resolver, which would take cljs-npm-as: specifiers
    enforce: 'pre',

    async config(userConfig, env) {
      root = path.resolve(userConfig.root ?? process.cwd());
      cwd = path.resolve(options.cwd ?? root);
      outputDir = path.resolve(cwd, options.outputDir);
      if (options.command) {
        compiler = options.command;
      } else if (!env.isPreview) {
        const launched = await compilerCommand({
          cwd,
          aliases: options.aliases,
          // Vite reads the terminal only once its server listens
          interactive: Boolean(process.stdin.isTTY && process.stderr.isTTY && !process.env.CI),
        });
        compiler = [launched.command, ...launched.args];
      }
      const result = {};
      // the project's page, an input of builds and the dev server's /
      const indexHtml = existsSync(path.join(root, 'index.html')) ? path.join(root, 'index.html') : null;
      const userInput = userConfig.build?.rolldownOptions?.input ?? userConfig.build?.rollupOptions?.input;
      const entries = Object.values(inputObject(userInput ?? indexHtml, root));
      pageNamespaces = await Promise.all((await entrySources(entries, root)).map(sourceNamespace));
      if (env.command === 'build' && !env.isPreview) {
        const start = Date.now();
        if (userConfig.build?.watch) {
          // vite build --watch: the compiler's watch builds, then watches the
          // sources; the bundler rebuilds when the compiler's output changes
          await watchCompiler();
        } else {
          await new Promise((resolve, reject) => {
            const child = run(compilerArgs('build', { ':verbose': false }), () => {});
            child.stdin.end();
            child.on('exit', code => code === 0 ? resolve() : reject(new Error(`ClojureScript build failed (${code})`)));
          });
        }
        console.log(`ClojureScript compiled in ${Date.now() - start}ms`);
        const info = await buildInfo();
        const jsEntries = Object.fromEntries(Object.entries(info.entries ?? {})
          .filter(([name]) => !options.entries || options.entries.includes(name)));
        const entries = { ...info.main, ...jsEntries };
        // The namespaces and :js-entries, with the HTML pages: the project's
        // input, else its index.html. Set on the user's config, since merging
        // an input object into a string or array doesn't work.
        userConfig.build ??= {};
        // rolldownOptions since Vite 8
        const key = userConfig.build.rolldownOptions ? 'rolldownOptions' : 'rollupOptions';
        userConfig.build[key] ??= {};
        userConfig.build[key].input = {
          ...Object.fromEntries(Object.entries(entries).map(([name, file]) => [name, path.join(outputDir, file)])),
          ...inputObject(userConfig.build[key].input ?? indexHtml, root),
        };
        result.build = {
          rollupOptions: {
            // :js-entries' exports are the bundle's interface, namespaces'
            // exports are only for each other
            preserveEntrySignatures: Object.keys(jsEntries).length ? 'exports-only' : false,
            output: {
              sourcemapPathTransform: (source, sourcemapPath) => sourcePath(path.dirname(sourcemapPath), source),
            },
          },
        };
        // Production bundles: bundlers treat every
        // Type.prototype.cljs$core$ISeq$_first$arity$1 = ... as a side effect.
        // generateBundle removes the ones never read in the whole bundle,
        // renames ClojureScript's long property names, then minifies, pruning
        // works on unminified code.
        if (options.prune !== false) {
          minify = userConfig.build?.minify ?? true;
          result.build.minify = false;
        }
      } else {
        // dependencies to pre-bundle, from the previous build's entry points
        // and the page, not every HTML file under the root
        const info = await buildInfo();
        result.optimizeDeps = {
          entries: [
            ...Object.values(info?.main ?? {}).map(file => path.relative(root, path.join(outputDir, file)).split(path.sep).join('/')),
            ...(indexHtml ? ['index.html'] : []),
          ],
        };
      }
      return result;
    },

    configResolved(resolved) {
      config = resolved;
      // generateBundle composes every chunk's source map after minifying:
      // Vite's composition of its preload edits moves there, into workers
      if (config.command === 'build' && options.prune !== false && minify &&
          (config.build.sourcemap === true || config.build.sourcemap === 'hidden')) {
        const importAnalysis = config.plugins.find(p => p.name === 'vite:build-import-analysis');
        if (importAnalysis?.generateBundle) deferPreloadSourceMaps(importAnalysis, preloadEdits);
      }
    },

    // a watch rebuild, started by the compiler writing its output, bundles
    // the finished compile
    async buildStart() {
      if (config.command === 'build') await compiling;
    },

    // :npm-interop :shadow, ["pkg" :as x] binds module.exports of CommonJS
    // modules and the namespace of ES modules, like shadow-cljs
    async resolveId(source, importer, opts) {
      // A ClojureScript source: its compiled module, as a module importing it
      // when serving, which a page loading it by its source's URL and the
      // modules importing it by its own share
      const file = source.split(/[?#]/)[0];
      if (SOURCE_FILE.test(file) && !source.startsWith('\0')) {
        // URLs are root-relative, files are only served from the project
        const candidates = [
          ...(file.startsWith('/') ? [path.join(root, file)] : []),
          ...(importer && !importer.startsWith('\0') && !file.startsWith('/')
            ? [path.resolve(path.dirname(importer.split('?')[0]), file)]
            : []),
          ...(path.isAbsolute(file) ? [file] : []),
        ];
        const found = candidates.find(f => servedSource(f) && existsSync(f));
        if (!found) return null;
        return config.command === 'build' ? compiledModule(found) : '\0' + SOURCE + found;
      }
      if (!source.startsWith(NPM_AS)) return null;
      const resolved = await this.resolve(source.slice(NPM_AS.length), importer, { ...opts, skipSelf: true });
      if (!resolved) return null;
      if (!resolved.id.includes('/node_modules/')) localModules.add(resolved.id.split('?')[0]);
      return '\0' + NPM_AS + resolved.id;
    },

    // Vite's dev server only converts CommonJS in node_modules (pre-bundling),
    // project CommonJS files required by ClojureScript are converted here
    transform(code, id) {
      if (config.command !== 'serve' || !localModules.has(id.split('?')[0]) || !isCommonJS(code)) return null;
      return commonJSToESM(code);
    },

    async load(id) {
      if (id.startsWith('\0' + SOURCE)) {
        const file = id.slice(SOURCE.length + 1);
        if (!servedSource(file)) this.error(`${file} is outside the project`);
        const module = await compiledModule(file);
        return `import ${JSON.stringify(module)};\nexport * from ${JSON.stringify(module)};\n`;
      }
      // compiled namespaces with their source maps, to the ClojureScript sources
      if (config.command === 'build' && config.build.sourcemap && id.startsWith(outputDir + path.sep) && id.endsWith('.js')) {
        try {
          const [code, map] = await Promise.all([fs.readFile(id, 'utf8'), fs.readFile(id + '.map', 'utf8')]);
          // read here, not by the bundler: vite build --watch rebuilds when it changes
          this.addWatchFile(id);
          return { code, map: JSON.parse(map) };
        } catch (e) {
          return null;
        }
      }
      if (!id.startsWith('\0' + NPM_AS)) return null;
      const target = id.slice(NPM_AS.length + 1);
      const file = target.split('?')[0];
      let commonjs = false;
      try {
        commonjs = file.includes('/.vite/deps/')
          // pre-bundled by the dev server, which records CommonJS deps
          ? await preBundledCommonJS(file)
          : isCommonJS(await fs.readFile(file, 'utf8'));
      } catch (e) {
        // virtual or unreadable modules are treated as ES modules
      }
      // An ES module only exporting default binds the default export: dual
      // packages' module.exports, which shadow-cljs resolved them to.
      return commonjs
        ? `import m from ${JSON.stringify(target)};\nexport default m;\n`
        : `import * as m from ${JSON.stringify(target)};\n` +
          `const keys = Object.keys(m);\n` +
          `export default keys.length === 1 && keys[0] === 'default' ? m[keys[0]] : m;\n`;
    },

    // after Vite's own generateBundle hooks, i.e. its preloading of dynamic
    // imports' dependencies, which edits chunks and their source maps
    generateBundle: { order: 'post', async handler(outputOptions, bundle) {
      if (config.command !== 'build') return;
      const chunks = Object.values(bundle).filter(c => c.type === 'chunk');
      if (options.prune !== false) {
        const start = Date.now();
        const oxcPath = resolveFromProject(config.root, 'rolldown/utils', 'prune and minify');
        const pruned = pruneChunks(chunks.map(c => c.code), await summarizeChunks(oxcPath, chunks));
        const pruneMs = Date.now() - start;
        const renames = propertyRenames(pruned.codes);
        const minified = minify && await minifyChunks(
          oxcPath,
          chunks.some(c => c.map) ? resolveFromProject(config.root, '@jridgewell/remapping', 'compose source maps') : null,
          // the file name in the chunk's directory: the source the map's
          // sources are composed relative to
          chunks.map(c => path.basename(c.fileName)),
          pruned.codes,
          chunks.map(c => c.map ? c.map.toString() : null),
          chunks.map(c => {
            const pending = preloadEdits.get(c.fileName);
            return pending && c.map && { fileName: c.fileName, code: pending.code, ...pending.edit };
          }),
          {
            module: true,
            compress: true,
            mangle: true,
            // the renames apply across chunks, cljs$ properties are the
            // compiler's, only accessed as properties (or quoted)
            mangleProps: { include: /^cljs\$/, quoted: true, cache: renames },
            // licence notices (/*! ... */, @license, @preserve), as other minifiers keep them
            codegen: { legalComments: 'inline' },
          });
        chunks.forEach((chunk, i) => {
          let code = pruned.codes[i];
          if (minified) {
            const result = minified[i];
            // its output may be partial: a build that ships it isn't one
            if (result.error) this.error(`minifying ${chunk.fileName}: ${result.error}`);
            code = result.code;
            if (chunk.map) {
              const map = new ChunkSourceMap(result.map);
              const dir = path.join(path.resolve(config.root, config.build.outDir), path.dirname(chunk.fileName));
              map.sources = map.sources.map(source => sourcePath(dir, source));
              chunk.map = map;
              // the bundler has emitted the chunk's map file already
              const asset = bundle[chunk.fileName + '.map'];
              if (asset?.type === 'asset') asset.source = map.toString();
              // the minifier drops the bundler's source map comment
              if (config.build.sourcemap === true) code += `\n//# sourceMappingURL=${path.basename(chunk.fileName)}.map\n`;
            }
          }
          chunk.code = code;
        });
        if (!pruned.removed && await commonJSPackage(config.root)) {
          this.warn('nothing was pruned: package.json has "type": "commonjs", so the bundler treats the project\'s ' +
            '.js files as CommonJS and wraps the ClojureScript they import in initializers. Use "type": "module".');
        }
        config.logger.info(`[cljf] removed ${pruned.removed} unused statements (${pruneMs}ms), ` +
          `renamed ${Object.keys(renames).length} properties, ` +
          `minified (${Date.now() - start - pruneMs}ms)`);
      }
      if (manifestName) {
        // entry points' chunks, with the chunks they import statically
        const byFile = Object.fromEntries(chunks.map(c => [c.fileName, c]));
        const imports = (chunk, seen = new Set()) => {
          for (const file of chunk.imports) {
            // external modules aren't chunks
            if (!seen.has(file) && byFile[file]) {
              seen.add(file);
              imports(byFile[file], seen);
            }
          }
          return seen;
        };
        const info = await buildInfo();
        const manifest = {};
        for (const [ns, file] of Object.entries(info.main)) {
          const chunk = chunks.find(c => c.facadeModuleId === path.join(outputDir, file));
          if (!chunk) continue;
          manifest[ns] = {
            scripts: [config.base + chunk.fileName],
            preload: [...imports(chunk)].map(f => config.base + f),
          };
        }
        this.emitFile({ type: 'asset', fileName: manifestName, source: JSON.stringify(manifest, null, 2) });
      }
    } },

    // The dev server's pages load the compiled modules of their scripts'
    // ClojureScript sources, which Vite would serve as files. Builds resolve
    // them (resolveId).
    transformIndexHtml: {
      order: 'pre',
      async handler(html, { filename }) {
        if (config.command !== 'serve') return html;
        const replacements = await Promise.all(
          // with a query or fragment, which the compiled module's URL keeps
          [...html.matchAll(/(<script\b[^>]*\bsrc\s*=\s*["'])([^"'?#]+\.clj[sc])([?#][^"']*)?(["'])/gi)].map(async match => {
            const file = match[2].startsWith('/') ? path.join(config.root, match[2]) : path.resolve(path.dirname(filename), match[2]);
            return [match[0], match[1] + devUrl(await compiledModule(file)) + (match[3] ?? '') + match[4]];
          }));
        return replacements.reduce((result, [from, to]) => result.replace(from, to), html);
      },
    },

    // Hot updates of compiled modules wait for the compile (and its build
    // hooks, i.e. generated CSS) to finish.
    async handleHotUpdate({ file }) {
      await compiling;
      if (warnings.held(file)) return [];
    },

    configureServer(devServer) {
      server = devServer;
      let ready, compiled = () => {};
      const initialBuild = new Promise(resolve => (ready = resolve));
      // the overlay of the last compile's error or warnings, for pages loaded
      // since: a reloaded page runs the output with warnings
      let problem = null;
      const showProblem = err => {
        problem = { type: 'error', err: { stack: '', plugin: 'cljf', ...err } };
        server.ws.send(problem);
      };
      server.ws.on('connection', socket => problem && socket.send(JSON.stringify(problem)));
      // options.ssrEntries run in the ssr environment's runner once compiled,
      // until they ran
      let ssrEntries = options.ssrEntries ?? [];
      // one run at a time: a compile's run waits for the previous compile's,
      // which imports the entries it doesn't run yet
      let ssrEntriesRun = Promise.resolve();
      const runSsrEntries = () => (ssrEntriesRun = ssrEntriesRun.then(runSsrEntriesOnce));
      const runSsrEntriesOnce = async () => {
        if (!ssrEntries.length) return;
        // created when it's first read
        const runner = server.environments.ssr?.runner;
        if (!runner) return;
        const info = await buildInfo();
        for (const name of ssrEntries) {
          const file = info?.entries?.[name];
          if (!file) {
            config.logger.error(`[cljf] ssrEntries: no :js-entries ${name}`, { timestamp: true });
            continue;
          }
          try {
            await runner.import(path.join(outputDir, file));
            ssrEntries = ssrEntries.filter(n => n !== name);
          } catch (e) {
            config.logger.error(`[cljf] running ${name} (${file}): ${e.stack ?? e}`, { timestamp: true });
          }
        }
      };
      // The REPL (cljf.esm.repl): pages running the build (cljf.esm.repl-runtime)
      // say hello over Vite's websocket, and modules the ssr environment's
      // runner runs (a server's :js-entries) over its hot channel; each
      // connection gets a runtime id. Their cljs:repl messages go to the
      // compiler as `repl <json>` lines, the compiler's repl-send events to the
      // runtime of their runtime id, runtimes[id](data).
      const runtimes = new Map();
      let lastRuntimeId = 0;
      const toRepl = msg => proc.stdin.writable && proc.stdin.write(`repl ${JSON.stringify(msg)}\n`);
      // A runtime's connection: receive takes the data of its cljs:repl events
      // for the REPL, close says bye. send replies to it, consoleAllowed whether
      // it may cljs_eval.
      const connectRuntime = (send, consoleAllowed) => {
        let id;
        const receive = data => {
          if (data.op === 'hello' && id === undefined) {
            id = ++lastRuntimeId;
            runtimes.set(id, send);
          }
          if (id === undefined) return;
          if (data.op === 'hello') send({ op: 'welcome', runtime: id });
          if (data.op === 'console-eval' && !consoleAllowed) {
            send({
              op: 'console-result',
              rid: data.rid,
              result: { error: 'cljs_eval only runs in pages on the dev server\'s machine, see the cljf plugin\'s replRemoteConsole' },
            });
            return;
          }
          toRepl({ ...data, runtime: id });
        };
        const close = () => {
          if (id !== undefined && runtimes.delete(id)) toRepl({ op: 'bye', runtime: id });
        };
        return { receive, close };
      };
      server.ws.on('connection', (socket, req) => {
        // cljs_eval compiles forms in the compiler's JVM, which runs their macros:
        // only for pages on this machine, unless replRemoteConsole
        const runtime = connectRuntime(
          data => socket.send(JSON.stringify({ type: 'custom', event: 'cljs:repl', data })),
          options.replRemoteConsole || isLocalConnection(req));
        socket.on('message', raw => {
          let msg;
          try {
            msg = JSON.parse(String(raw));
          } catch {
            return;
          }
          if (msg?.type !== 'custom' || msg.event !== 'cljs:repl' || !msg.data) return;
          runtime.receive(msg.data);
        });
        socket.on('close', runtime.close);
      });
      // the ssr runner's modules run in this process, each runner is a client
      // of the environment's hot channel
      const ssrHot = server.environments.ssr?.hot;
      if (ssrHot) {
        const ssrRuntimes = new Map();
        const ssrRuntime = client => {
          if (!ssrRuntimes.has(client)) ssrRuntimes.set(client, connectRuntime(data => client.send('cljs:repl', data), true));
          return ssrRuntimes.get(client);
        };
        ssrHot.on('cljs:repl', (data, client) => data && ssrRuntime(client).receive(data));
        ssrHot.on('vite:client:disconnect', (data, client) => {
          ssrRuntimes.get(client)?.close();
          ssrRuntimes.delete(client);
        });
      }
      proc = run(compilerArgs('watch', { ':esm-hmr': true, ':exit-with-parent': true, ':watch-events': ':stdin' }), async event => {
        if (event.type === 'repl-send') {
          const send = runtimes.get(event.runtime);
          if (send) send(event.msg);
          else toRepl({ op: 'bye', runtime: event.runtime });
          return;
        }
        if (event.type === 'watch-dirs') {
          // Vite's watcher (native file events) reports changes in the
          // compiler's source directories, polling them is expensive
          const dirs = event.dirs.map(dir => dir + path.sep);
          server.watcher.add(event.dirs);
          server.watcher.on('all', (type, file) => {
            // removed files too: a generated test runner requires what is left
            if ((type === 'change' || type === 'add' || type === 'unlink') && dirs.some(dir => file.startsWith(dir))) {
              proc.stdin.write(`changed ${file}\n`);
            }
          });
          return;
        }
        if (event.type === 'compiling') {
          compiling = new Promise(resolve => (compiled = resolve));
          return;
        }
        // before the hot updates waiting for the compile
        const held = event.type === 'compiled' ? warnings.compiled(event) : null;
        compiled();
        // The dev manifest, the pages' scripts, is written after the first
        // compile even if it failed: pages then show the error overlay.
        const info = await buildInfo();
        if (info) {
          await writeManifest(Object.fromEntries(Object.entries(info.main).map(([ns, file]) =>
            [ns, { scripts: [`${config.base}@vite/client`, devUrl(file)], preload: [] }])));
        }
        if (held?.outstanding.length) {
          const { outstanding } = held;
          const [first] = outstanding;
          config.logger.warn(`[cljf] compiled ${event.namespaces} namespace(s) in ${event.ms}ms, ` +
            `hot reload paused by ${outstanding.length} warning(s)`, { timestamp: true });
          showProblem({
            message: outstanding
              .map(w => `WARNING: ${w.message}${w.file ? ` at ${path.relative(cwd, w.file)}:${w.line}` : ''}`)
              .join('\n') + '\n\nHot reload is paused until the warnings are fixed.',
            id: first.file,
            loc: first.file ? { file: first.file, line: first.line, column: first.column } : undefined,
          });
          // The ssr entries run regardless: they run once, and a warning is no
          // error. The HTML pages a server entry serves show the overlay above
          // (those loading @vite/client); a port that never opens shows
          // nothing but the line above.
          runSsrEntries();
          ready();
        } else if (held) {
          const { released, ignored } = held;
          // all namespaces' warnings, not only this compile's
          config.logger.info(`[cljf] compiled ${event.namespaces} namespace(s) in ${event.ms}ms` +
            (ignored ? `, ${ignored} outstanding warning(s) ignored` : ''), { timestamp: true });
          problem = null;
          // the namespaces held back that this compile didn't update, in every
          // environment: a server entry's (ssr) as well as the pages'
          for (const environment of Object.values(server.environments)) {
            for (const file of released) {
              for (const mod of environment.moduleGraph.getModulesByFile(file) ?? []) environment.reloadModule(mod);
            }
          }
          runSsrEntries();
          ready();
        } else if (event.type === 'error') {
          config.logger.error(`[cljf] ${event.message}`, { timestamp: true });
          showProblem({
            message: event.message,
            id: event.file,
            loc: event.file ? { file: event.file, line: event.line, column: event.column } : undefined,
          });
          ready();
        }
      });
      const stop = () => proc.kill();
      server.httpServer?.once('close', stop);
      process.once('exit', stop);
      // hold requests until the initial compile finished
      server.middlewares.use(async (req, res, next) => {
        await initialBuild;
        next();
      });
    },
  };
}
