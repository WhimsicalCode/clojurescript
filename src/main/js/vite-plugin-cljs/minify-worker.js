// Minifies a pruned chunk with oxc's minifier (shipped with Rolldown) and
// composes the minified chunk's source map with the bundler's
// (@jridgewell/remapping) off the main thread, with Vite's preload edits'
// maps (preload-edit-map.js): composing maps is synchronous
// JavaScript, the chunks' maps are composed in parallel.
import { parentPort } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';
import { preloadEditMap } from './preload-edit-map.js';

let oxc, remapping;

parentPort.on('message', async ({ id, oxcPath, remappingPath, fileName, code, map, edit, options }) => {
  try {
    oxc ??= await import(pathToFileURL(oxcPath).href);
    if (map) remapping ??= (await import(pathToFileURL(remappingPath).href)).default;
    // the minifier runs off this thread: the chunk's map (with Vite's preload
    // edit composed in) is prepared meanwhile
    const minifying = oxc.minify(fileName, code, { ...options, sourcemap: Boolean(map) });
    // a rejected minify is awaited below, not reported as unhandled meanwhile
    minifying.catch(() => {});
    let input = null;
    if (map) {
      const parsed = JSON.parse(map);
      input = edit ? preloadEditMap(remappingPath, edit, parsed) : parsed;
    }
    const result = await minifying;
    let composed = null;
    if (map) {
      // blanking kept the positions of the bundler's map
      composed = remapping([result.map, input], () => null);
      composed = { ...composed };
    }
    parentPort.postMessage({ id, code: result.code, map: composed, error: result.errors?.[0]?.message });
  } catch (e) {
    parentPort.postMessage({ id, fatal: e.message });
  }
});
