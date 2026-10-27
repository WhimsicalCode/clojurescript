// Minifies a pruned chunk with oxc's minifier (shipped with Rolldown) and
// composes the minified chunk's source map with the bundler's
// (@jridgewell/remapping) off the main thread: composing maps is synchronous
// JavaScript, the chunks' maps are composed in parallel.
import { parentPort } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';

let oxc, remapping;

parentPort.on('message', async ({ id, oxcPath, remappingPath, fileName, code, map, options }) => {
  try {
    oxc ??= await import(pathToFileURL(oxcPath).href);
    const result = await oxc.minify(fileName, code, { ...options, sourcemap: Boolean(map) });
    let composed = null;
    if (map) {
      remapping ??= (await import(pathToFileURL(remappingPath).href)).default;
      // blanking kept the positions of the bundler's map
      composed = remapping([result.map, JSON.parse(map)], () => null);
      composed = { ...composed };
    }
    parentPort.postMessage({ id, code: result.code, map: composed, error: result.errors?.[0]?.message });
  } catch (e) {
    parentPort.postMessage({ id, fatal: e.message });
  }
});
