// The dev server's compiler warnings: hot updates held back while namespaces
// have warnings, unless ignoreWarnings
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { watchWarnings } from '../warnings.js';

const warning = (output, message = 'Use of undeclared Var') => ({ output, message, file: `${output}.cljs`, line: 1, column: 1 });

test('a compile without warnings holds nothing back', () => {
  const warnings = watchWarnings();
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [] }), { released: [], outstanding: [], ignored: 0 });
  assert.equal(warnings.held('a.js'), false);
});

test('warnings hold back the output until fixed, then release what the fix did not update', () => {
  const warnings = watchWarnings();
  const w = warning('a.js');
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [w] }), { released: [], outstanding: [w], ignored: 0 });
  assert.equal(warnings.held('a.js'), true);
  // another namespace compiled meanwhile is held back too, a's warning is still outstanding
  assert.deepEqual(warnings.compiled({ files: ['b.js'], warnings: [] }), { released: [], outstanding: [w], ignored: 0 });
  assert.equal(warnings.held('b.js'), true);
  // fixing a releases b, a is hot reloaded by its own update
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [] }), { released: ['b.js'], outstanding: [], ignored: 0 });
  assert.equal(warnings.held('a.js'), false);
  assert.equal(warnings.held('b.js'), false);
});

test('a namespace\'s warnings replace those of its previous compile', () => {
  const warnings = watchWarnings();
  warnings.compiled({ files: ['a.js'], warnings: [warning('a.js', 'one')] });
  const two = warning('a.js', 'two');
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [two] }).outstanding, [two]);
});

test('ignoreWarnings holds nothing back and counts all namespaces\' warnings', () => {
  const warnings = watchWarnings({ ignoreWarnings: true });
  assert.deepEqual(warnings.compiled({ files: ['a.js'], warnings: [warning('a.js')] }), { released: [], outstanding: [], ignored: 1 });
  assert.equal(warnings.held('a.js'), false);
  // a's warning is still counted after b's compile
  assert.deepEqual(warnings.compiled({ files: ['b.js'], warnings: [warning('b.js')] }), { released: [], outstanding: [], ignored: 2 });
  assert.deepEqual(warnings.compiled({ files: ['a.js', 'b.js'], warnings: [] }), { released: [], outstanding: [], ignored: 0 });
});
