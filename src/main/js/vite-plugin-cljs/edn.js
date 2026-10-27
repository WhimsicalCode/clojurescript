// Compiler options given as JavaScript values, written as EDN.
//
// Object keys are keywords: camelCase keys are written in kebab-case
// (nreplPort is :nrepl-port), others as they are ('nrepl-port', ':nrepl-port').
// Strings starting with : are keywords, with ' symbols, others strings. null
// is nil, and so is undefined, except in maps, which leave out keys whose value
// is undefined: `repl: dev ? { nreplPort: 0 } : undefined` doesn't set :repl.

// The options whose map keys are names rather than options, written as they
// are: :closure-defines' (goog.DEBUG), :js-entries' entry names and their
// :exports' JavaScript names
const NAME_KEYS = new Set(['closure-defines', 'js-entries', 'exports']);

const CAMEL_CASE = /^[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+$/;

function optionKey(key) {
  if (key.startsWith(':')) return key.slice(1);
  return CAMEL_CASE.test(key) ? key.replace(/[A-Z]/g, c => `-${c.toLowerCase()}`) : key;
}

function ednString(s) {
  return JSON.stringify(s);
}

/**
 * The EDN of a JavaScript value.
 * @param {*} v
 * @param {boolean} [names] v's keys are names, written as they are
 * @returns {string}
 */
export function ednValue(v, names = false) {
  if (v === null || v === undefined) return 'nil';
  if (Array.isArray(v)) return `[${v.map(x => ednValue(x)).join(' ')}]`;
  if (typeof v === 'string') return v.startsWith(':') || v.startsWith("'") ? v.replace(/^'/, '') : ednString(v);
  if (typeof v === 'object') {
    return `{${Object.entries(v).filter(([, x]) => x !== undefined).map(([k, x]) => {
      const key = names ? k.replace(/^:/, '') : optionKey(k);
      return `:${key} ${ednValue(x, !names && NAME_KEYS.has(key))}`;
    }).join(' ')}}`;
  }
  return String(v);
}
