// The source map Vite's build-import-analysis composes for its preload edit
// of a chunk (index.js' deferPreloadSourceMaps): MagicString's map of the
// edit (generateMap with hires: 'boundary') composed with the chunk's map by
// @jridgewell/remapping, as Vite's combineSourcemaps does, computed directly.
//
// The edit map has a segment at every word boundary of the chunk (~7 million
// for a 20MB chunk), remapping traces each through the chunk's map and keeps
// the ones that differ from the previous: per segment of the chunk's map,
// the first edit-map segment within its columns. Those are found here by
// scanning the code, and added with the same gen-mapping calls remapping
// makes, so sources, names and their order come out the same.
import { createRequire } from 'node:module';

const MARKER_LENGTH = '__VITE_PRELOAD__'.length;

// Vite's combineSourcemaps' path escaping
const escapePath = p => /^[A-Z]:/.test(p) ? p.replace(/^([A-Z]):\//, '/windows/$1/') : /^\/[^/]/.test(p) ? `/linux${p}` : p;
const unescapePath = p => p.startsWith('/linux/') ? p.slice(6) : p.startsWith('/windows/') ? p.replace(/^\/windows\/([A-Z])\//, '$1:/') : p;

// MagicString's word boundaries: /\w/ per UTF-16 code unit
const isWord = c => (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || c === 95 || (c >= 97 && c <= 122);

let jridgewell;

// remapping's own trace-mapping and gen-mapping, resolved from remappingPath
function load(remappingPath) {
  if (!jridgewell) {
    const require = createRequire(remappingPath);
    const { TraceMap, decodedMappings } = require('@jridgewell/trace-mapping');
    const { GenMapping, maybeAddSegment, setSourceContent, setIgnore, toEncodedMap } = require('@jridgewell/gen-mapping');
    jridgewell = { TraceMap, decodedMappings, GenMapping, maybeAddSegment, setSourceContent, setIgnore, toEncodedMap };
  }
  return jridgewell;
}

// edit: {fileName, code, prefix, updates: [[start, end, text]]}, code the
// chunk before the edit, prefix a line prepended, updates the replaced
// __VITE_PRELOAD__ markers in order. map: the chunk's source map (parsed).
// Returns the fields of Vite's composed map that reach the plugin.
export function preloadEditMap(remappingPath, { fileName, code, prefix, updates }, map) {
  const { TraceMap, decodedMappings, GenMapping, maybeAddSegment, setSourceContent, setIgnore, toEncodedMap } = load(remappingPath);
  const escaped = { ...map, sources: map.sources.map(p => p ? escapePath(p) : null),
                    ...(map.sourceRoot ? { sourceRoot: escapePath(map.sourceRoot) } : {}) };
  const traced = new TraceMap(escaped, '');
  const decoded = decodedMappings(traced);
  const { resolvedSources, sourcesContent, ignoreList, names } = traced;
  const sources = resolvedSources.map((source, i) => ({
    source: source || '',
    content: sourcesContent ? sourcesContent[i] : null,
    ignore: ignoreList ? ignoreList.includes(i) : false,
  }));
  const gen = new GenMapping({});
  const add = (genLine, genColumn, segment) => {
    if (segment.length === 1) {
      maybeAddSegment(gen, genLine, genColumn, '', -1, -1, '');
      return;
    }
    const { source, content, ignore } = sources[segment[1]];
    maybeAddSegment(gen, genLine, genColumn, source, segment[2], segment[3], segment.length === 5 ? names[segment[4]] : '');
    if (source && content != null) setSourceContent(gen, source, content);
    if (ignore) setIgnore(gen, source, true);
  };
  // the edit's generated lines: the prefix's, then the chunk's
  const lineOffset = prefix ? prefix.split('\n').length - 1 : 0;
  // markers' starts and ends, chunk starts (MagicString's 'first' segment)
  const starts = new Set(updates.map(([start]) => start));
  const ends = new Set(updates.map(([, end]) => end));
  let update = 0;
  let lineStart = 0;
  for (let line = 0; lineStart <= code.length; line++) {
    let lineEnd = code.indexOf('\n', lineStart);
    if (lineEnd < 0) lineEnd = code.length;
    // the markers on this line, with the shift of the generated columns
    // after each: [start, end, shift after it]
    const markers = [];
    let shift = 0;
    while (update < updates.length && updates[update][0] < lineEnd) {
      const [start, end, text] = updates[update++];
      shift += text.length - (end - start);
      markers.push([start, end, shift]);
    }
    const segments = line < decoded.length ? decoded[line] : null;
    if (segments?.length) {
      // whether the edit map has a segment at index k (k < lineEnd)
      const isPoint = k => {
        for (const [start, end] of markers) {
          if (k > start && k < end) return false;
        }
        return k === lineStart || starts.has(k) || ends.has(k) ||
          !isWord(code.charCodeAt(k)) || !isWord(code.charCodeAt(k - 1));
      };
      // the first segment at or after index from, before index to
      const firstPoint = (from, to) => {
        for (let k = from; k < to; k++) {
          if (isPoint(k)) return k;
        }
        return -1;
      };
      const genColumn = k => {
        let s = 0;
        for (const [, end, after] of markers) {
          if (end <= k) s = after;
        }
        return k - lineStart + s;
      };
      for (let j = 0; j < segments.length;) {
        // segments at the same column: an edit-map segment at that column
        // traces to the first of them, later ones to the last
        const column = segments[j][0];
        let last = j;
        while (last + 1 < segments.length && segments[last + 1][0] === column) last++;
        const to = Math.min(lineEnd, last + 1 < segments.length ? lineStart + segments[last + 1][0] : lineEnd);
        const from = lineStart + column;
        const k = firstPoint(from, to);
        if (k >= 0) {
          if (k === from) {
            add(line + lineOffset, genColumn(k), segments[j]);
            if (last !== j) {
              const k2 = firstPoint(k + 1, to);
              if (k2 >= 0) add(line + lineOffset, genColumn(k2), segments[last]);
            }
          } else {
            add(line + lineOffset, genColumn(k), segments[last]);
          }
        }
        j = last + 1;
      }
    }
    lineStart = lineEnd + 1;
  }
  const composed = toEncodedMap(gen);
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
