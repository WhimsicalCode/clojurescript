// React Compiler on compiled namespaces (the reactCompiler option): jsx()
// calls are declared to it as React.createElement is, so that it memoizes
// elements separately. Needs babel-plugin-react-compiler and @babel/core,
// from CLJF_REACT_COMPILER_DIR (a project with them installed), skipped
// without.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import path from 'node:path';
import { moduleTypes, reactCompilerTransform } from '../react-compiler.js';

const dir = process.env.CLJF_REACT_COMPILER_DIR;
const resolve = dir && (spec => createRequire(path.join(dir, 'noop.js')).resolve(spec));

test('JSX runtimes and cljf.jsx are typed like React.createElement', () => {
  for (const name of ['react/jsx-runtime', 'react/jsx-dev-runtime', 'preact/jsx-runtime', 'hono/jsx/jsx-runtime']) {
    assert.equal(moduleTypes(name).properties.jsx.restParam, 'freeze', name);
    assert.equal(moduleTypes(name).properties.jsxs.returnValueKind, 'frozen', name);
  }
  assert.equal(moduleTypes('../cljf/jsx.js').properties.jsx.restParam, 'freeze');
  assert.equal(moduleTypes('react'), null);
  assert.equal(moduleTypes('some-lib'), null);
});

// as the compiler emits a component: a namespace import of the runtime
const component = (directive) => `import * as rt from "react/jsx-runtime";
import * as react from "react";
var app$List = (function (props){${directive}
const v = react.useState(3); const n = v?.[0]; const w = react.useState(0); const t = w?.[0]; const set = w?.[1];
const items = Array.from({length: n}, (_, i) => rt.jsx(Row, {"label": "row " + i}, i));
return rt.jsxs("div", {"children": [rt.jsx("button", {"onClick": (function (){ return set(1); }), "children": t}), rt.jsx("ul", {"children": items})]});
});
export { app$List as List };
`;

test('compiles "use memo" fns, caching elements apart', { skip: !dir && 'CLJF_REACT_COMPILER_DIR not set' }, async () => {
  const transform = reactCompilerTransform(true, resolve);
  const out = await transform(component('"use memo";'), '/out/app.js');
  assert.match(out.code, /from "react\/compiler-runtime"/);
  // the list is cached on n alone, not on the counter's state
  assert.match(out.code, /if \(\$\[\d+\] !== n\) \{/);
  assert.ok(out.map);
});

test('leaves modules without "use memo" alone by default', { skip: !dir && 'CLJF_REACT_COMPILER_DIR not set' }, async () => {
  const transform = reactCompilerTransform(true, resolve);
  assert.equal(await transform(component(''), '/out/app.js'), null);
  const all = reactCompilerTransform({ compilationMode: 'all' }, resolve);
  assert.match((await all(component(''), '/out/app.js')).code, /react\/compiler-runtime/);
});
