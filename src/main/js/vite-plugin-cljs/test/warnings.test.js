// The dev server's output held back while namespaces have compiler
// warnings, unless ignoreWarnings
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizePath, watchWarnings } from '../warnings.js';

const warning = (output, message = 'Use of undeclared Var') => ({ output, message, file: `${output}.cljs`, line: 1, column: 1 });

test('a compile without warnings holds nothing back', () => {
  const warnings = watchWarnings();
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [], outstanding: [] }), { released: [], outstanding: [], ignored: 0 });
  assert.equal(warnings.held('a.js'), false);
});

test('outstanding warnings hold back the output until fixed, then release what the fix did not update', () => {
  const warnings = watchWarnings();
  const w = warning('a.js');
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [w], outstanding: [w] }), { released: [], outstanding: [w], ignored: 0 });
  assert.equal(warnings.held('a.js'), true);
  // another namespace compiled meanwhile is held back too: a's warning is
  // still outstanding, though not this compile's
  assert.deepEqual(warnings.compiled({ files: ['b.js'], warnings: [], outstanding: [w] }), { released: [], outstanding: [w], ignored: 0 });
  assert.equal(warnings.held('b.js'), true);
  // fixing a releases b, a is hot reloaded by its own update
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [], outstanding: [] }), { released: ['b.js'], outstanding: [], ignored: 0 });
  assert.equal(warnings.held('a.js'), false);
  assert.equal(warnings.held('b.js'), false);
});

test('a compile with no outstanding warnings releases the output, i.e. once a namespace with warnings is deleted', () => {
  const warnings = watchWarnings();
  warnings.compiled({ files: ['a.js', 'b.js'], warnings: [warning('a.js')], outstanding: [warning('a.js')] });
  // a deleted: the compiler no longer reports its warning
  assert.deepEqual(warnings.compiled({ files: ['main.js'], warnings: [], outstanding: [] }), { released: ['a.js', 'b.js'], outstanding: [], ignored: 0 });
});

test('ignoreWarnings holds nothing back and counts the outstanding warnings', () => {
  const warnings = watchWarnings({ ignoreWarnings: true });
  const a = warning('a.js');
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [a], outstanding: [a] }), { released: [], outstanding: [], ignored: 1 });
  assert.equal(warnings.held('a.js'), false);
  const b = warning('b.js');
  assert.deepEqual(warnings.compiled({ files: ['b.js'], warnings: [b], outstanding: [a, b] }), { released: [], outstanding: [], ignored: 2 });
  assert.deepEqual(warnings.compiled({ files: ['a.js', 'b.js'], warnings: [], outstanding: [] }), { released: [], outstanding: [], ignored: 0 });
});

test('a compiler without outstanding: the compile\'s warnings', () => {
  const warnings = watchWarnings();
  const w = warning('a.js');
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [w] }).outstanding, [w]);
});

test('on Windows, the compiler\'s paths are compared as Vite\'s', () => {
  const warnings = watchWarnings({ platform: 'win32' });
  const w = warning('C:\\proj\\target\\a.js');
  warnings.compiled({ files: ['C:\\proj\\target\\a.js', 'C:\\proj\\target\\b.js'], warnings: [w], outstanding: [w] });
  assert.equal(warnings.held('C:/proj/target/a.js'), true);
  assert.deepEqual(warnings.compiled({ files: ['C:\\proj\\target\\a.js'], warnings: [], outstanding: [] }).released,
    ['C:/proj/target/b.js']);
  assert.equal(normalizePath('a\\b', 'linux'), 'a\\b');
});
