// The dev server running a server entry (ssrEntries) while a namespace has
// a compiler warning, run by script/test-launcher in a project of
// create-clojurific's hono template (its node_modules and deps.edn):
// - the entry runs despite the warning, the server answers
// - a namespace compiled while the warning is outstanding isn't hot updated,
//   and is released in the server's environment too once it's fixed
// node dev-server.mjs, in the project's directory.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const [coreFile] = fs.readdirSync('src', { recursive: true })
  .filter(f => f.endsWith(`${path.sep}core.cljs`)).map(f => path.join('src', f));
const ns = fs.readFileSync(coreFile, 'utf8').match(/\(ns\s+([^\s()]+)/)[1];
// namespaces next to the core namespace: app.core's are app.text, app.broken
const parent = ns.slice(0, ns.lastIndexOf('.'));
const dir = path.dirname(coreFile);
const textFile = path.join(dir, 'text.cljs');
const brokenFile = path.join(dir, 'broken.cljs');
const text = s => fs.writeFileSync(textFile, `(ns ${parent}.text)\n(defn greeting [] ${JSON.stringify(s)})\n`);
const broken = form => fs.writeFileSync(brokenFile, `(ns ${parent}.broken)\n(defn f [] ${form})\n`);

text('one');
broken('(undeclared-thing)');
fs.writeFileSync(coreFile, `(ns ${ns}
  (:require ["hono" :refer [Hono]] [${parent}.text :as text] [${parent}.broken]))

(def app
  (doto (Hono.)
    (.get "/" (fn [c] (.text c (text/greeting))))))
`);

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

// The dev servers' process groups: npx, Vite and the compiler it starts.
// Killing npx alone leaves Vite running on Linux, holding the pipes open.
const groups = new Set();
const killGroup = pid => {
  try {
    process.kill(-pid, 'SIGKILL');
  } catch {}
  groups.delete(pid);
};
process.on('exit', () => groups.forEach(killGroup));
// a hang fails the test instead of the CI job's timeout
setTimeout(() => {
  console.error('dev server: timed out after 5 minutes');
  process.exit(1);
}, 5 * 60 * 1000).unref();

async function until(what, f, timeout = 60000) {
  const end = Date.now() + timeout;
  for (;;) {
    const value = await f().catch(() => undefined);
    if (value) return value;
    if (Date.now() > end) throw new Error(`timed out: ${what}`);
    await sleep(250);
  }
}

// One dev server, its output in log. Returns the result of test, or throws.
async function withDevServer(test) {
  let log = '';
  // 127.0.0.1: on Linux, localhost can resolve to ::1 for Vite and to
  // 127.0.0.1 for fetch
  const proc = spawn('npx', ['vite', '--host', '127.0.0.1', '--port', '0', '--clearScreen', 'false'], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NO_COLOR: '1' },
    detached: true,
  });
  groups.add(proc.pid);
  proc.stdout.on('data', chunk => (log += chunk));
  proc.stderr.on('data', chunk => (log += chunk));
  const exited = new Promise(resolve => proc.on('exit', resolve));
  try {
    const url = await until('the dev server\'s URL', async () => log.match(/Local:\s+(http:\/\/\S+?)\/?\s/)?.[1]);
    const compiles = () => (log.match(/\[cljf\] compiled/g) ?? []).length;
    const get = async () => {
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
      return res.ok ? res.text() : `${res.status} ${(await res.text()).slice(0, 200)}`;
    };
    return await test({ get, compiles, log: () => log });
  } catch (e) {
    e.message += `\n--- vite's output:\n${log}`;
    throw e;
  } finally {
    killGroup(proc.pid);
    await Promise.race([exited, sleep(5000)]);
  }
}

// The server entry's first run sometimes fails at startup (Namespace
// "goog.math.Long" already declared, WHIM-8648): a few attempts
let attempt = 0;
for (;;) {
  attempt++;
  try {
    await withDevServer(async ({ get, compiles, log }) => {
      await until('the server entry\'s first answer, despite the warning', async () => (await get()) === 'one');
      if (!/hot reload paused by 1 warning\(s\)/.test(log())) throw new Error('no warning paused hot reload');
      // compiled while the warning is outstanding: its hot update held back
      let n = compiles();
      text('two');
      await until('the compile of the text namespace', async () => compiles() > n);
      await sleep(1000);
      const textUpdate = /\(ssr\) hmr update \S*\/text\.js/;
      if (textUpdate.test(log())) throw new Error('the held back namespace was hot updated in the server');
      // the warning fixed: released in the server's environment too (the
      // template's server imports its entry per request, which reads the
      // changed module anyway, a server started once doesn't)
      n = compiles();
      const before = log().length;
      broken('nil');
      await until('the compile of the fixed namespace', async () => compiles() > n);
      await until('the held back namespace released in the server', async () => textUpdate.test(log().slice(before)), 20000);
      if ((await get()) !== 'two') throw new Error('the server doesn\'t answer with the released namespace');
    });
    break;
  } catch (e) {
    if (attempt >= 3 || !/already declared/.test(e.message)) throw e;
    console.error(`dev server: retrying, the server entry failed at startup (WHIM-8648), attempt ${attempt}`);
    text('one');
    broken('(undeclared-thing)');
    fs.rmSync('target', { recursive: true, force: true });
  }
}
console.log('dev server: ok');
// fetch's keep-alive sockets don't hold the test
process.exit(0);
