// Minifies a pruned chunk with oxc's minifier (shipped with Rolldown) and
// composes the minified chunk's source map with the bundler's
// (@jridgewell/remapping) off the main thread: composing maps is synchronous
// JavaScript, the chunks' maps are composed in parallel.
import { parentPort } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';

let oxc, remapping, MagicString;

// Vite's combineSourcemaps' path escaping
const escapePath = p => /^[A-Z]:/.test(p) ? p.replace(/^([A-Z]):\//, '/windows/$1/') : /^\/[^/]/.test(p) ? `/linux${p}` : p;
const unescapePath = p => p.startsWith('/linux/') ? p.slice(6) : p.startsWith('/windows/') ? p.replace(/^\/windows\/([A-Z])\//, '$1:/') : p;

// The source map Vite's build-import-analysis composes for its preload edit
// of a chunk (index.js' deferPreloadSourceMaps): the edit's map (MagicString,
// as Vite generates it) composed with the chunk's map, as JSON would read it.
async function viteEditMap({ magicStringPath, fileName, code, prefix, updates }, map) {
  MagicString ??= (await import(pathToFileURL(magicStringPath).href)).default;
  const s = new MagicString(code);
  if (prefix) s.prepend(prefix);
  for (const [start, end, text] of updates) s.update(start, end, text);
  const edit = s.generateMap({ source: fileName, hires: 'boundary' });
  const escape = m => ({ ...m, sources: m.sources.map(p => p ? escapePath(p) : null),
                         ...(m.sourceRoot ? { sourceRoot: escapePath(m.sourceRoot) } : {}) });
  const composed = remapping([escape(edit), escape(map)], () => null);
  // the fields of Vite's composed map that reach the plugin's generateBundle
  // when Vite assigns it to the chunk: the bundler keeps the map's own
  // fields, not remapping's ignoreList (Vite's composed maps lose their
  // ignore list there)
  return JSON.parse(JSON.stringify({
    version: composed.version,
    file: map.file || fileName,
    names: composed.names,
    sources: composed.sources.map(p => p ? unescapePath(p) : p),
    sourcesContent: composed.sourcesContent,
    mappings: composed.mappings,
    ...(map.debugId ? { debugId: map.debugId } : {}),
  }));
}

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
      input = edit ? await viteEditMap(edit, parsed) : parsed;
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
