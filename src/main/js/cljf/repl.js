// cljf repl: a ClojureScript REPL in the terminal, evaluating in the pages
// running a watched build. It's an nREPL client of the watcher's nREPL server
// (:repl {:nrepl-port 0}, see cljf.esm.repl.nrepl), whose session it
// switches to ClojureScript with (cljf.esm.repl/repl).
import { existsSync } from 'node:fs';
import fs from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import readline from 'node:readline';
import { LauncherError } from './errors.js';

// bencode, nREPL's encoding: dicts, lists, integers and (UTF-8) strings

export function bencode(v) {
  if (typeof v === 'number') return Buffer.from(`i${Math.trunc(v)}e`);
  if (typeof v === 'string' || Buffer.isBuffer(v)) {
    const bytes = Buffer.isBuffer(v) ? v : Buffer.from(v);
    return Buffer.concat([Buffer.from(`${bytes.length}:`), bytes]);
  }
  if (Array.isArray(v)) return Buffer.concat([Buffer.from('l'), ...v.map(bencode), Buffer.from('e')]);
  const keys = Object.keys(v).filter(k => v[k] !== undefined).sort();
  return Buffer.concat([Buffer.from('d'), ...keys.flatMap(k => [bencode(k), bencode(v[k])]), Buffer.from('e')]);
}

const INCOMPLETE = Symbol('incomplete');

// The value at buf[i], [value, next index], or INCOMPLETE
function decodeAt(buf, i) {
  if (i >= buf.length) return INCOMPLETE;
  const c = String.fromCharCode(buf[i]);
  if (c === 'i') {
    const end = buf.indexOf('e', i);
    return end < 0 ? INCOMPLETE : [Number(buf.toString('latin1', i + 1, end)), end + 1];
  }
  if (c === 'l' || c === 'd') {
    const items = [];
    let j = i + 1;
    while (j < buf.length && buf[j] !== 0x65) {
      const item = decodeAt(buf, j);
      if (item === INCOMPLETE) return INCOMPLETE;
      items.push(item[0]);
      j = item[1];
    }
    if (j >= buf.length) return INCOMPLETE;
    if (c === 'l') return [items, j + 1];
    const dict = {};
    for (let k = 0; k < items.length; k += 2) dict[items[k]] = items[k + 1];
    return [dict, j + 1];
  }
  if (c >= '0' && c <= '9') {
    const colon = buf.indexOf(':', i);
    if (colon < 0) return INCOMPLETE;
    const start = colon + 1;
    const end = start + Number(buf.toString('latin1', i, colon));
    return end > buf.length ? INCOMPLETE : [buf.toString('utf8', start, end), end];
  }
  throw new Error(`bencode: unexpected ${JSON.stringify(c)}`);
}

/** Decodes the values of a byte stream, as chunks arrive. */
export class Decoder {
  #buf = Buffer.alloc(0);

  /** The values chunk completes. */
  push(chunk) {
    this.#buf = Buffer.concat([this.#buf, chunk]);
    const values = [];
    for (;;) {
      const decoded = decodeAt(this.#buf, 0);
      if (decoded === INCOMPLETE) return values;
      values.push(decoded[0]);
      this.#buf = this.#buf.subarray(decoded[1]);
    }
  }
}

/**
 * Whether code's forms are all closed, so that the REPL evaluates it rather
 * than reading another line: no open (, [ or {, string or regex. Extra
 * closing brackets are the reader's to report.
 */
export function complete(code) {
  let depth = 0;
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if (c === ';') {
      while (i < code.length && code[i] !== '\n') i++;
    } else if (c === '\\') {
      i++;
    } else if (c === '"') {
      for (i++; i < code.length && code[i] !== '"'; i++) if (code[i] === '\\') i++;
      if (i >= code.length) return false;
    } else if ('([{'.includes(c)) {
      depth++;
    } else if (')]}'.includes(c)) {
      depth--;
    }
  }
  return depth <= 0;
}

// The characters that end a symbol or keyword, when reading back from the
// cursor
const SYMBOL_END = /[\s,()[\]{}"'`;@^~\\]/;

// The brackets text leaves open, innermost last, and whether it ends in a
// string or a comment, which closing brackets wouldn't close
function openBrackets(text) {
  const stack = [];
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === ';') {
      while (i < text.length && text[i] !== '\n') i++;
      if (i >= text.length) return { stack, unclosable: true };
    } else if (c === '\\') {
      i++;
    } else if (c === '"') {
      for (i++; i < text.length && text[i] !== '"'; i++) if (text[i] === '\\') i++;
      if (i >= text.length) return { stack, unclosable: true };
    } else if ('([{'.includes(c)) {
      stack.push(c);
    } else if (')]}'.includes(c)) {
      stack.pop();
    }
  }
  return { stack, unclosable: false };
}

// The forms of after (the input after the cursor) up to the one closing the
// depth brackets open before it, with the rest of the symbol at the cursor
// left out
function formRest(after, depth) {
  let i = 0;
  while (i < after.length && !SYMBOL_END.test(after[i])) i++;
  const start = i;
  for (; i < after.length && depth > 0; i++) {
    const c = after[i];
    if (c === ';') {
      while (i < after.length && after[i] !== '\n') i++;
    } else if (c === '\\') {
      i++;
    } else if (c === '"') {
      for (i++; i < after.length && after[i] !== '"'; i++) if (after[i] === '\\') i++;
    } else if ('([{'.includes(c)) {
      depth++;
    } else if (')]}'.includes(c)) {
      depth--;
    }
  }
  return after.slice(start, i);
}

/**
 * What Tab completes at the end of code, the input so far up to the cursor,
 * with after the input after it: {prefix, context}, prefix the symbol or
 * keyword before the cursor, context the top-level form around it with the
 * symbol replaced by __prefix__ and its open brackets closed, as compliment
 * and clj-suitable read it (nil at the top level). null when there's nothing
 * to complete: no prefix, or in a string, comment or character.
 */
export function completionRequest(code, after = '') {
  const stack = [];
  let formStart = 0;
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if (c === ';') {
      while (i < code.length && code[i] !== '\n') i++;
      if (i >= code.length) return null;
    } else if (c === '\\') {
      i++;
    } else if (c === '"') {
      for (i++; i < code.length && code[i] !== '"'; i++) if (code[i] === '\\') i++;
      if (i >= code.length) return null;
    } else if ('([{'.includes(c)) {
      if (!stack.length) formStart = c === '{' && code[i - 1] === '#' ? i - 1 : i;
      stack.push(c);
    } else if (')]}'.includes(c)) {
      stack.pop();
    }
  }
  let start = code.length;
  while (start > 0 && !SYMBOL_END.test(code[start - 1])) start--;
  const prefix = code.slice(start);
  // nothing typed, or a character (\a, \newline)
  if (!prefix || code[start - 1] === '\\') return null;
  if (!stack.length) return { prefix, context: null };
  // closed: the brackets open before the cursor and those the input after it
  // opens without closing them. Input after it ending in a string or a
  // comment can't be closed: no context
  const form = `${code.slice(formStart, start)}__prefix__${formRest(after, stack.length)}`;
  const { stack: open, unclosable } = openBrackets(form);
  if (unclosable) return { prefix, context: null };
  const closers = open.map(c => ({ '(': ')', '[': ']', '{': '}' })[c]).reverse().join('');
  return { prefix, context: `${form}${closers}` };
}

/**
 * The completions of code (the input up to the cursor, after the input after
 * it) in namespace ns, from
 * cider-nrepl's complete op: the build's vars and the runtime's JavaScript
 * properties. Resolves to [candidates, prefix] for readline, no candidates
 * when the server has no complete op (see ops.complete, set false then) or
 * doesn't answer within timeout ms.
 */
export async function completions(conn, { session, ns, code, after = '', ops = {}, timeout = 2000 }) {
  const request = completionRequest(code, after);
  if (!request || ops.complete === false) return [[], request?.prefix ?? ''];
  const candidates = new Set();
  const answered = conn.request({
    op: 'complete',
    session,
    ns,
    prefix: request.prefix,
    context: request.context ?? undefined,
    'enhanced-cljs-completion?': 'true',
    'sort-order': 'by-name',
  }, msg => {
    if (msg.status?.includes('unknown-op')) ops.complete = false;
    for (const c of msg.completions ?? []) if (c.candidate) candidates.add(c.candidate);
  }).then(() => true, () => true);
  let timer;
  const timedOut = new Promise(resolve => {
    timer = setTimeout(() => resolve(false), timeout);
    timer.unref?.();
  });
  const done = await Promise.race([answered, timedOut]);
  clearTimeout(timer);
  return [done ? [...candidates] : [], request.prefix];
}

/**
 * An nREPL connection: requests whose responses end with status done. Once the
 * socket closes, pending and later requests reject.
 */
export class Connection {
  #socket;
  #ids = 0;
  #pending = new Map();
  #closed = null;

  constructor(socket) {
    this.#socket = socket;
    const fail = e => {
      if (this.#closed) return;
      this.#closed = new LauncherError(`cljf repl: the nREPL server closed the connection${e ? `: ${e.message}` : ''}`);
      for (const { reject } of this.#pending.values()) reject(this.#closed);
      this.#pending.clear();
    };
    // an error is followed by close
    socket.on('error', fail);
    socket.on('close', () => fail());
    const decoder = new Decoder();
    socket.on('data', chunk => {
      for (const msg of decoder.push(chunk)) {
        const request = this.#pending.get(msg.id);
        if (!request) continue;
        request.onResponse(msg);
        if (msg.status?.includes('done')) {
          this.#pending.delete(msg.id);
          request.resolve(msg);
        }
      }
    });
  }

  /** A message id for request. */
  newId() {
    return String(++this.#ids);
  }

  /**
   * Sends msg (with an id from newId, or a new one), calls onResponse with
   * its responses; resolves to the last.
   */
  request(msg, onResponse = () => {}) {
    const id = msg.id ?? this.newId();
    return new Promise((resolve, reject) => {
      if (this.#closed) {
        reject(this.#closed);
        return;
      }
      this.#pending.set(id, { onResponse, resolve, reject });
      this.#socket.write(bencode({ ...msg, id }));
    });
  }

  close() {
    this.#socket.end();
  }
}

function connect(port, host) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, host);
    socket.once('connect', () => {
      socket.off('error', reject);
      resolve(socket);
    });
    socket.once('error', reject);
  });
}

/**
 * Connects to the nREPL server on port, or the one in cwd's .nrepl-port,
 * waiting for it to start (the dev server writes the file once it built).
 */
export async function connectToServer({ cwd = process.cwd(), port, host = '127.0.0.1', log = () => {}, interval = 500 } = {}) {
  const portFile = path.join(cwd, '.nrepl-port');
  let waiting = false;
  for (;;) {
    const p = port ?? (existsSync(portFile) ? Number((await fs.readFile(portFile, 'utf8')).trim()) : null);
    if (p) {
      try {
        return await connect(p, host);
      } catch (e) {
        if (port) throw new LauncherError(`cljf repl: no nREPL server on ${host}:${port}: ${e.message}`);
      }
    }
    if (!waiting) {
      log(`; waiting for the nREPL server of the dev server (npm run dev) or cljf watch, with :repl {:nrepl-port 0},\n; to write ${portFile}`);
      waiting = true;
    }
    await new Promise(resolve => setTimeout(resolve, interval));
  }
}

async function readHistory(file) {
  try {
    return (await fs.readFile(file, 'utf8')).split('\n').filter(Boolean).reverse();
  } catch {
    return [];
  }
}

/**
 * The REPL: reads forms from input, evaluates them in the session switched
 * to ClojureScript, writes their output and values to output (errors and
 * warnings to errorOutput). Resolves to the exit code once input ends or
 * :cljs/quit.
 */
export async function repl({
  cwd = process.cwd(),
  port,
  host,
  input = process.stdin,
  output = process.stdout,
  errorOutput = process.stderr,
  terminal = Boolean(input.isTTY && output.isTTY),
  historyFile = path.join(cwd, '.cljf', 'repl-history'),
} = {}) {
  const socket = await connectToServer({ cwd, port, host, log: line => errorOutput.write(`${line}\n`) });
  const conn = new Connection(socket);
  let closing = false;
  const closed = new Promise(resolve => socket.on('close', resolve));
  socket.on('error', () => {});

  const write = (stream, text) => text && stream.write(text);
  const { 'new-session': session } = await conn.request({ op: 'clone' });
  let failed = false;
  await conn.request({ op: 'eval', session, code: '(cljf.esm.repl/repl)' }, msg => {
    write(output, msg.out);
    write(errorOutput, msg.err);
    if (msg.ex) failed = true;
  });
  if (failed) {
    conn.close();
    throw new LauncherError('cljf repl: the nREPL server has no ClojureScript REPL (cljf.esm.repl), is it the dev server\'s?');
  }

  let ns = 'cljs.user';
  let pending = '';
  // Tab completes the symbol before the cursor, with cider-nrepl's
  // middleware on the server
  const ops = {};
  // readline passes the line up to the cursor
  const completer = (line, callback) => {
    completions(conn, { session, ns, code: pending + line, after: rl.line.slice(rl.cursor), ops })
      .then(result => callback(null, result), () => callback(null, [[], '']));
  };

  const history = terminal ? await readHistory(historyFile) : [];
  const rl = readline.createInterface({
    input, output, terminal, history, historySize: 1000, removeHistoryDuplicates: true,
    completer: terminal ? completer : undefined,
  });
  if (terminal) {
    rl.on('history', lines => {
      fs.mkdir(path.dirname(historyFile), { recursive: true })
        .then(() => fs.writeFile(historyFile, `${[...lines].reverse().join('\n')}\n`))
        .catch(() => {});
    });
  }

  let evaluating = null;
  let interrupted = false;
  const prompt = () => {
    if (!terminal) return;
    rl.setPrompt(pending ? `${' '.repeat(Math.max(0, ns.length - 1))}#_=> ` : `${ns}=> `);
    rl.prompt();
  };

  const done = new Promise(resolve => {
    const quit = code => {
      closing = true;
      rl.close();
      // the server forgets the session, unless it doesn't answer
      const timeout = new Promise(r => setTimeout(r, 1000).unref());
      Promise.race([conn.request({ op: 'close', session }), timeout]).catch(() => {}).finally(() => {
        conn.close();
        resolve(code);
      });
    };
    closed.then(() => {
      if (closing) return;
      errorOutput.write('\n; the nREPL server closed the connection\n');
      closing = true;
      rl.close();
      resolve(1);
    });

    rl.on('SIGINT', () => {
      if (evaluating) {
        conn.request({ op: 'interrupt', session, 'interrupt-id': evaluating }).catch(() => {});
      } else if (pending || rl.line) {
        pending = '';
        interrupted = false;
        rl.write(null, { ctrl: true, name: 'u' });
        output.write('\n');
        prompt();
      } else if (interrupted) {
        quit(0);
      } else {
        interrupted = true;
        output.write('\n; Ctrl-C again, Ctrl-D or :cljs/quit to quit\n');
        prompt();
      }
    });

    // lines are read one at a time: a form waits for the one before
    const lines = [];
    let busy = false;
    const next = async () => {
      if (busy) return;
      busy = true;
      while (lines.length) {
        const line = lines.shift();
        if (line === null) {
          quit(0);
          return;
        }
        interrupted = false;
        pending += `${line}\n`;
        if (!complete(pending)) {
          prompt();
          continue;
        }
        const code = pending;
        pending = '';
        if (!code.trim()) {
          prompt();
          continue;
        }
        if (code.trim() === ':cljs/quit') {
          quit(0);
          return;
        }
        evaluating = conn.newId();
        // a lost connection rejects, closed (above) reports it
        await conn.request({ op: 'eval', session, code, ns, id: evaluating }, msg => {
          write(output, msg.out);
          write(errorOutput, msg.err);
          if (msg.value !== undefined) output.write(`${msg.value}\n`);
          if (msg.ns) ns = msg.ns;
        }).catch(() => {});
        evaluating = null;
        if (closing) return;
        prompt();
      }
      busy = false;
    };
    rl.on('line', line => {
      lines.push(line);
      next();
    });
    rl.on('close', () => {
      if (closing) return;
      lines.push(null);
      next();
    });
  });

  prompt();
  return done;
}
