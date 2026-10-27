// The EDN of compilerOptions given as JavaScript values
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ednValue } from '../edn.js';

test('keys are keywords, camelCase written in kebab-case', () => {
  assert.equal(ednValue({ nreplPort: 0, 'npm-interop': ':shadow', ':esm-hmr': true }),
    '{:nrepl-port 0 :npm-interop :shadow :esm-hmr true}');
  assert.equal(ednValue({ repl: { nreplPort: 0, portFiles: ['.nrepl-port'] } }),
    '{:repl {:nrepl-port 0 :port-files [".nrepl-port"]}}');
});

test('strings are strings, keywords with : and symbols with \'', () => {
  assert.equal(ednValue({ buildHooks: [["'my.build/hook", { outputTo: 'out/a.css', mode: ':dev' }]] }),
    '{:build-hooks [[my.build/hook {:output-to "out/a.css" :mode :dev}]]}');
  assert.equal(ednValue(['a "quote"', null, false, 1.5]), '["a \\"quote\\"" nil false 1.5]');
});

test('null is nil, map keys whose value is undefined are left out', () => {
  assert.equal(ednValue({ repl: undefined, prune: true }), '{:prune true}');
  assert.equal(ednValue({ closureDefines: { 'goog.DEBUG': null, 'my.app/x': undefined } }),
    '{:closure-defines {:goog.DEBUG nil}}');
  assert.equal(ednValue({ buildHooks: [["'my.build/hook", null, undefined]] }),
    '{:build-hooks [[my.build/hook nil nil]]}');
  assert.equal(ednValue(null), 'nil');
  assert.equal(ednValue({ ...(false && { repl: { nreplPort: 0 } }) }), '{}');
});

test('names are written as they are', () => {
  assert.equal(ednValue({ closureDefines: { 'goog.DEBUG': false, 'my.app/apiUrl': 'x' } }),
    '{:closure-defines {:goog.DEBUG false :my.app/apiUrl "x"}}');
  assert.equal(ednValue({ jsEntries: { myServer: { exports: { default: "'my.server/app", handleRequest: "'my.server/handle" } } } }),
    '{:js-entries {:myServer {:exports {:default my.server/app :handleRequest my.server/handle}}}}');
});
