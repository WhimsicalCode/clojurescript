// cljf repl: bencode, reading forms, and sessions with a fake nREPL server.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { PassThrough } from 'node:stream';
import { test } from 'node:test';
import { Connection, Decoder, bencode, complete, completionRequest, completions, repl } from '../repl.js';
import { tmpDir } from './helpers.js';

test('bencode', () => {
  assert.equal(bencode({ op: 'eval', code: '(+ 1 2)', id: 1 }).toString(), 'd4:code7:(+ 1 2)2:idi1e2:op4:evale');
  assert.equal(bencode(['a', 2, ['é']]).toString(), 'l1:ai2el2:éee');
});

test('Decoder decodes values split across chunks', () => {
  const bytes = Buffer.concat([bencode({ out: 'héllo', status: ['done'] }), bencode({ value: '3' })]);
  const decoder = new Decoder();
  const values = [];
  // one byte at a time, splitting é's bytes too
  for (let i = 0; i < bytes.length; i++) values.push(...decoder.push(bytes.subarray(i, i + 1)));
  assert.deepEqual(values, [{ out: 'héllo', status: ['done'] }, { value: '3' }]);
});

test('complete', () => {
  assert.ok(complete('(+ 1 2)'));
  assert.ok(complete(':k'));
  assert.ok(complete(''));
  assert.ok(!complete('(defn f [x]'));
  assert.ok(!complete('{:a [1'));
  assert.ok(!complete('"a'));
  assert.ok(complete('"a\nb"'));
  assert.ok(complete('(str "(" \\( #"[")'));
  assert.ok(complete('(+ 1 ; (\n 2)'));
  assert.ok(!complete('(+ 1 ; )'));
  // the reader reports it
  assert.ok(complete('(+ 1))'));
});

test('completionRequest', () => {
  assert.deepEqual(completionRequest('ma'), { prefix: 'ma', context: null });
  assert.deepEqual(completionRequest('(foo) ba'), { prefix: 'ba', context: null });
  assert.deepEqual(completionRequest('(util/gr'), { prefix: 'util/gr', context: '(__prefix__)' });
  // the top-level form around the cursor, closed, for clj-suitable's interop
  assert.deepEqual(completionRequest('(let [d (js/Date.)] (.getF'),
    { prefix: '.getF', context: '(let [d (js/Date.)] (__prefix__))' });
  assert.deepEqual(completionRequest('(-> el .-par'), { prefix: '.-par', context: '(-> el __prefix__)' });
  assert.deepEqual(completionRequest('(defn f [x]\n  (str/up'), { prefix: 'str/up', context: '(defn f [x]\n  (__prefix__))' });
  assert.deepEqual(completionRequest('"done" #{:a :b'), { prefix: ':b', context: '#{:a __prefix__}' });
  assert.deepEqual(completionRequest('@sta'), { prefix: 'sta', context: null });
  // with the input after the cursor: the rest of the form, without the rest
  // of the symbol at the cursor
  assert.deepEqual(completionRequest('(.-inn', 'er js/window)'), { prefix: '.-inn', context: '(__prefix__ js/window)' });
  assert.deepEqual(completionRequest('(let [w js/window] (.-in', ' w) (f "a)")) (next form)'),
    { prefix: '.-in', context: '(let [w js/window] (__prefix__ w) (f "a)"))' });
  assert.deepEqual(completionRequest('(-> el (.-par', ' ; a comment )\n'), { prefix: '.-par', context: '(-> el (__prefix__ ; a comment )\n))' });
  // brackets the input after the cursor opens are closed too
  assert.deepEqual(completionRequest('(.-inn', ' (foo'), { prefix: '.-inn', context: '(__prefix__ (foo))' });
  assert.deepEqual(completionRequest('(f [.-x', ' {:a (g'), { prefix: '.-x', context: '(f [__prefix__ {:a (g)}])' });
  // which can't be closed in a string or a comment: no context
  assert.deepEqual(completionRequest('(.-inn', ' "abc'), { prefix: '.-inn', context: null });
  assert.deepEqual(completionRequest('(.-inn', ' ; open ('), { prefix: '.-inn', context: null });
  // nothing to complete
  for (const code of ['', '(foo ', '(str "ab', '(foo ; comm', '\\a', '(str \\newl']) {
    assert.equal(completionRequest(code), null, code);
  }
});

// An nREPL server answering eval with handler(code, msg): responses, and
// other ops with onOp(msg): responses, if it returns them
async function fakeServer(handler, onOp = () => null) {
  const requests = [];
  const server = net.createServer(socket => {
    const decoder = new Decoder();
    socket.on('data', chunk => {
      for (const msg of decoder.push(chunk)) {
        requests.push(msg);
        const reply = r => socket.write(bencode({ id: msg.id, session: msg.session, ...r }));
        const answer = onOp(msg);
        if (answer) for (const r of answer) reply(r);
        else if (msg.op === 'clone') reply({ 'new-session': 's1', status: ['done'] });
        else if (msg.op === 'eval') {
          for (const r of handler(msg.code, msg)) reply(r);
          reply({ status: ['done'] });
        } else reply({ status: ['done'] });
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { port: server.address().port, requests, close: () => server.close() };
}

async function connection(server) {
  const socket = await new Promise((resolve, reject) => {
    const s = net.connect(server.port, '127.0.0.1', () => resolve(s));
    s.once('error', reject);
  });
  return new Connection(socket);
}

// cider-nrepl's complete, of candidates
const cider = candidates => msg => msg.op === 'complete'
  ? [{
      completions: candidates.filter(c => c.startsWith(msg.prefix)).map(candidate => ({ candidate, type: 'function' })),
      status: ['done'],
    }]
  : null;

test('completions asks cider-nrepl\'s complete op, with the context for JavaScript properties', async () => {
  const server = await fakeServer(() => [], cider(['util/greet', 'util/grow', '.-innerWidth']));
  const conn = await connection(server);
  try {
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'my.app', code: '(util/gr' }), [['util/greet', 'util/grow'], 'util/gr']);
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'my.app', code: '(.-inner js/window' }), [[], 'js/window']);
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'my.app', code: '(+ 1 (.-inner' }), [['.-innerWidth'], '.-inner']);
    const [first] = server.requests.filter(r => r.op === 'complete');
    assert.equal(first.session, 's1');
    assert.equal(first.ns, 'my.app');
    assert.equal(first.prefix, 'util/gr');
    assert.equal(first.context, '(__prefix__)');
    assert.equal(first['enhanced-cljs-completion?'], 'true');
    // nothing to complete, nothing asked
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'my.app', code: '(str "ab' }), [[], '']);
    assert.equal(server.requests.filter(r => r.op === 'complete').length, 3);
  } finally {
    conn.close();
    server.close();
  }
});

test('completions keep the first of duplicate candidates, in order', async () => {
  const server = await fakeServer(() => [], msg => msg.op === 'complete'
    ? [{ completions: ['ab', 'ac', 'ab', 'ad', 'ac'].map(candidate => ({ candidate })), status: ['done'] }]
    : null);
  const conn = await connection(server);
  try {
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'cljs.user', code: 'a' }), [['ab', 'ac', 'ad'], 'a']);
  } finally {
    conn.close();
    server.close();
  }
});

test('completions without cider-nrepl\'s middleware', async () => {
  const server = await fakeServer(() => [], msg => msg.op === 'complete' ? [{ status: ['done', 'error', 'unknown-op'] }] : null);
  const conn = await connection(server);
  const ops = {};
  try {
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'cljs.user', code: 'ma', ops }), [[], 'ma']);
    assert.equal(ops.complete, false);
    // not asked again
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'cljs.user', code: 'ma', ops }), [[], 'ma']);
    assert.equal(server.requests.filter(r => r.op === 'complete').length, 1);
  } finally {
    conn.close();
    server.close();
  }
});

test('completions of a server that doesn\'t answer in time', async () => {
  const server = await fakeServer(() => [], msg => msg.op === 'complete' ? [] : null);
  const conn = await connection(server);
  try {
    const start = Date.now();
    assert.deepEqual(await completions(conn, { session: 's1', ns: 'cljs.user', code: 'ma', timeout: 100 }), [[], 'ma']);
    assert.ok(Date.now() - start < 1000);
  } finally {
    conn.close();
    server.close();
  }
});

async function session(server, lines, opts = {}) {
  const input = new PassThrough();
  const output = new PassThrough();
  const errorOutput = new PassThrough();
  let out = '';
  let err = '';
  output.on('data', d => (out += d));
  errorOutput.on('data', d => (err += d));
  const done = repl({ port: server.port, input, output, errorOutput, terminal: false, ...opts });
  for (const line of lines) input.write(`${line}\n`);
  input.end();
  return { code: await done, out, err };
}

test('repl evaluates forms in the session switched to ClojureScript', async () => {
  const server = await fakeServer(code => {
    if (code === '(cljf.esm.repl/repl)') return [{ out: '; ClojureScript REPL\n' }];
    if (code.startsWith('(in-ns')) return [{ value: 'nil', ns: 'my.app' }];
    if (code.startsWith('(println')) return [{ out: 'hi\n' }, { value: 'nil', ns: 'cljs.user' }];
    if (code.startsWith('(boom')) return [{ err: 'Error: boom\n' }, { ex: 'cljf.esm.repl/eval-error', status: ['eval-error'] }];
    return [{ value: `[${code.trim()}]`, ns: 'cljs.user' }];
  });
  try {
    const { code, out, err } = await session(server,
      ['(+ 1', '2)', '(println "hi")', '', '(in-ns (quote my.app))', '(boom)', ':cljs/quit', '(never)']);
    assert.equal(code, 0);
    assert.equal(out, '; ClojureScript REPL\n[(+ 1\n2)]\nhi\nnil\nnil\n');
    assert.equal(err, 'Error: boom\n');
    const evals = server.requests.filter(r => r.op === 'eval');
    assert.deepEqual(evals.map(r => r.code.trim()), ['(cljf.esm.repl/repl)', '(+ 1\n2)', '(println "hi")', '(in-ns (quote my.app))', '(boom)']);
    assert.ok(evals.slice(1).every(r => r.session === 's1'));
    // the namespace the last form left
    assert.equal(evals.at(-1).ns, 'my.app');
    assert.equal(server.requests.at(-1).op, 'close');
  } finally {
    server.close();
  }
});

test('repl completes on Tab in a terminal, in the namespace of the last form', async () => {
  const server = await fakeServer(
    code => (code.startsWith('(in-ns') ? [{ value: 'nil', ns: 'my.app' }] : [{ value: 'nil', ns: 'cljs.user' }]),
    cider(['util/greet', 'greet', 'green']));
  const input = new PassThrough();
  const output = new PassThrough();
  let out = '';
  output.on('data', d => (out += d));
  // readline asks a terminal's columns
  output.columns = 80;
  const done = repl({ port: server.port, input, output, errorOutput: new PassThrough(), terminal: true, historyFile: path.join(tmpDir(), 'history') });
  const typed = async text => {
    input.write(text);
    await new Promise(resolve => setTimeout(resolve, 150));
  };
  try {
    await typed('');
    await typed("(in-ns 'my.app)\r");
    // one candidate: completed in the line
    await typed('(util/gr\t');
    await typed(')\r');
    const evals = server.requests.filter(r => r.op === 'eval');
    assert.equal(evals.at(-1).code, '(util/greet)\n');
    const [complete] = server.requests.filter(r => r.op === 'complete');
    assert.equal(complete.ns, 'my.app');
    assert.equal(complete.prefix, 'util/gr');
    // several: listed
    await typed('gre\t');
    await typed('\t');
    assert.match(out, /greet\s+green/);
    input.write('\u0015:cljs/quit\r');
    assert.equal(await within(done, 5000), 0);
  } finally {
    input.end();
    server.close();
  }
});

test('repl fails when the server has no ClojureScript REPL', async () => {
  const server = await fakeServer(() => [{ err: 'Could not resolve var: cljf.esm.repl/repl\n' }, { ex: 'class clojure.lang.Compiler$CompilerException' }]);
  try {
    await assert.rejects(session(server, ['(+ 1 2)']), /has no ClojureScript REPL/);
  } finally {
    server.close();
  }
});

test('repl connects to the port in .nrepl-port', async () => {
  const server = await fakeServer(code => [{ value: code === '(+ 1 2)\n' ? '3' : 'nil' }]);
  const cwd = tmpDir();
  writeFileSync(path.join(cwd, '.nrepl-port'), `${server.port}\n`);
  try {
    const { out } = await session(server, ['(+ 1 2)'], { port: undefined, cwd });
    assert.equal(out, '3\n');
  } finally {
    server.close();
  }
});

// An nREPL server that answers clone, and closes the connection on the request
// for which closeOn(msg) is true
async function closingServer(closeOn) {
  const server = net.createServer(socket => {
    const decoder = new Decoder();
    socket.on('data', chunk => {
      for (const msg of decoder.push(chunk)) {
        if (closeOn(msg)) {
          socket.destroy();
          return;
        }
        const reply = r => socket.write(bencode({ id: msg.id, session: msg.session, ...r }));
        if (msg.op === 'clone') reply({ 'new-session': 's1', status: ['done'] });
        else reply({ status: ['done'] });
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { port: server.address().port, close: () => server.close() };
}

// session's result, or a rejection when it doesn't settle within ms
function within(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`didn't settle within ${ms} ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

test('repl fails when the server closes the connection before switching to ClojureScript', async () => {
  const server = await closingServer(msg => msg.op === 'clone');
  try {
    await assert.rejects(within(session(server, ['(+ 1 2)']), 5000), /the nREPL server closed the connection/);
  } finally {
    server.close();
  }
});

test('repl reports a connection the server closes during an evaluation', async () => {
  const server = await closingServer(msg => msg.op === 'eval' && msg.code === '(+ 1 2)\n');
  try {
    const { code, err } = await within(session(server, ['(+ 1 2)', '(never)']), 5000);
    assert.equal(code, 1);
    assert.match(err, /the nREPL server closed the connection/);
  } finally {
    server.close();
  }
});
