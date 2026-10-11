// Terminal prompts drawn like @clack/prompts (which create-vite uses), with
// Node.js built-ins only: text, select and confirm on a rail of │, and log
// lines on the same rail.
import readline from 'node:readline';
import { stripVTControlCharacters } from 'node:util';

const output = process.stdout;
const colors = output.isTTY && !process.env.NO_COLOR;
const sgr = (open, close) => s => (colors ? `\x1b[${open}m${s}\x1b[${close}m` : String(s));

export const c = {
  bold: sgr(1, 22),
  dim: sgr(2, 22),
  inverse: sgr(7, 27),
  strikethrough: sgr(9, 29),
  red: sgr(31, 39),
  green: sgr(32, 39),
  yellow: sgr(33, 39),
  blue: sgr(34, 39),
  magenta: sgr(35, 39),
  cyan: sgr(36, 39),
  gray: sgr(90, 39),
};

const S = {
  active: '◆',
  submit: '◇',
  cancel: '■',
  warn: '▲',
  bar: '│',
  barEnd: '└',
  radioOn: '●',
  radioOff: '○',
};

export const CANCEL = Symbol('cancel');

const write = s => output.write(s);

// The terminal rows the lines take, wrapped at the terminal's width
function rows(text) {
  const columns = output.columns || 80;
  return text.split('\n').slice(0, -1)
    .reduce((n, line) => n + Math.max(1, Math.ceil(stripVTControlCharacters(line).length / columns)), 0);
}

// Runs a prompt until it's submitted or cancelled (Ctrl-C, Escape): render
// draws its state, key updates it and returns 'submit' to finish. Resolves
// to value(state), or CANCEL.
function prompt({ state, render, key, value }) {
  return new Promise(resolve => {
    const input = process.stdin;
    let drawn = 0;
    let status = 'active';
    const draw = () => {
      const frame = render(state, status);
      if (drawn) write(`\x1b[${drawn}A\r\x1b[J`);
      write(frame);
      drawn = rows(frame);
    };
    const onKey = (str, k = {}) => {
      if ((k.ctrl && k.name === 'c') || k.name === 'escape') status = 'cancel';
      else if (key(state, str, k) === 'submit') status = 'submit';
      draw();
      if (status !== 'active') {
        input.off('keypress', onKey);
        if (input.isTTY) input.setRawMode(false);
        input.pause();
        write('\x1b[?25h');
        resolve(status === 'cancel' ? CANCEL : value(state));
      }
    };
    readline.emitKeypressEvents(input);
    if (input.isTTY) input.setRawMode(true);
    input.on('keypress', onKey);
    input.resume();
    write('\x1b[?25l');
    draw();
  });
}

function title(message, status) {
  const symbol = { active: c.cyan(S.active), submit: c.green(S.submit), cancel: c.red(S.cancel) }[status];
  return `${c.gray(S.bar)}\n${symbol}  ${message}\n`;
}

// The prompt's lines below its title: the answer when it's done, the input
// with an end of the rail while active
function body(status, active, done) {
  if (status === 'submit') return `${c.gray(S.bar)}  ${c.dim(done)}\n`;
  if (status === 'cancel') return `${c.gray(S.bar)}  ${c.strikethrough(c.dim(done))}\n`;
  return `${active.map(line => `${c.cyan(S.bar)}  ${line}\n`).join('')}${c.cyan(S.barEnd)}\n`;
}

/** A line of text, defaultValue when left empty. */
export function text({ message, placeholder = '', defaultValue = '' }) {
  return prompt({
    state: { value: '', cursor: 0 },
    value: s => s.value.trim() || defaultValue,
    key(s, str, k) {
      if (k.name === 'return') return 'submit';
      if (k.name === 'backspace') {
        if (s.cursor > 0) {
          s.value = s.value.slice(0, s.cursor - 1) + s.value.slice(s.cursor);
          s.cursor--;
        }
      } else if (k.name === 'delete') s.value = s.value.slice(0, s.cursor) + s.value.slice(s.cursor + 1);
      else if (k.name === 'left') s.cursor = Math.max(0, s.cursor - 1);
      else if (k.name === 'right') s.cursor = Math.min(s.value.length, s.cursor + 1);
      else if (k.name === 'home' || (k.ctrl && k.name === 'a')) s.cursor = 0;
      else if (k.name === 'end' || (k.ctrl && k.name === 'e')) s.cursor = s.value.length;
      else if (k.ctrl && k.name === 'u') {
        s.value = s.value.slice(s.cursor);
        s.cursor = 0;
      } else if (k.name === 'tab' && !s.value) {
        s.value = defaultValue;
        s.cursor = s.value.length;
      } else if (str && !k.ctrl && !k.meta && !/[\x00-\x1f\x7f]/.test(str)) {
        s.value = s.value.slice(0, s.cursor) + str + s.value.slice(s.cursor);
        s.cursor += str.length;
      }
    },
    render(s, status) {
      const input = s.value
        ? s.value.slice(0, s.cursor) + c.inverse(s.value[s.cursor] ?? ' ') + s.value.slice(s.cursor + 1)
        : placeholder ? c.inverse(placeholder[0]) + c.dim(placeholder.slice(1)) : c.inverse(' ');
      return title(message, status) + body(status, [input], status === 'cancel' ? s.value : s.value.trim() || defaultValue);
    },
  });
}

/** One of options ({value, label, color, hint}), initialValue first. */
export function select({ message, options, initialValue }) {
  const paint = option => (option.color ? option.color(option.label) : option.label);
  // hints (i.e. links) aligned after the longest label
  const width = Math.max(...options.map(o => o.label.length));
  const line = option => (option.hint
    ? `${paint(option)}${' '.repeat(width - option.label.length + 2)}${c.dim(option.hint)}`
    : paint(option));
  return prompt({
    state: { index: Math.max(0, options.findIndex(o => o.value === initialValue)) },
    value: s => options[s.index].value,
    key(s, str, k) {
      if (k.name === 'return') return 'submit';
      if (k.name === 'up' || k.name === 'k') s.index = (s.index + options.length - 1) % options.length;
      else if (k.name === 'down' || k.name === 'j') s.index = (s.index + 1) % options.length;
    },
    render(s, status) {
      const lines = options.map((option, i) => (i === s.index
        ? `${c.green(S.radioOn)} ${line(option)}`
        : `${c.dim(S.radioOff)} ${c.dim(line(option))}`));
      lines.push(`${c.dim('↑/↓')} to navigate ${c.dim('•')} ${c.dim('Enter:')} confirm`);
      return title(message, status) + body(status, lines, paint(options[s.index]));
    },
  });
}

/** Yes or no. */
export function confirm({ message, initialValue = true }) {
  return prompt({
    state: { value: initialValue },
    value: s => s.value,
    key(s, str, k) {
      if (k.name === 'return') return 'submit';
      if (['up', 'down', 'left', 'right', 'tab'].includes(k.name)) s.value = !s.value;
      else if (k.name === 'y') s.value = true;
      else if (k.name === 'n') s.value = false;
    },
    render(s, status) {
      const radio = (on, label) => (on ? `${c.green(S.radioOn)} ${label}` : `${c.dim(S.radioOff)} ${c.dim(label)}`);
      return title(message, status)
        + body(status, [`${radio(s.value, 'Yes')} ${c.dim('/')} ${radio(!s.value, 'No')}`], s.value ? 'Yes' : 'No');
    },
  });
}

function lines(symbol, message) {
  const [first, ...rest] = String(message).split('\n');
  return `${c.gray(S.bar)}\n${symbol}  ${first}\n${rest.map(line => `${c.gray(S.bar)}  ${line}\n`).join('')}`;
}

export const log = {
  step: message => write(lines(c.green(S.submit), message)),
  message: message => write(lines(c.gray(S.bar), message)),
  warn: message => write(lines(c.yellow(S.warn), message)),
  error: message => write(lines(c.red(S.cancel), message)),
};

export function cancel(message = 'Operation cancelled.') {
  write(`${c.gray(S.barEnd)}  ${c.red(message)}\n\n`);
}

export function outro(message) {
  write(`${c.gray(S.bar)}\n${c.gray(S.barEnd)}  ${message}\n\n`);
}
