// The compiler warnings of the dev server's watch: like shadow-cljs, the
// output isn't hot reloaded while namespaces have warnings, it would fail at
// runtime (undeclared vars), unless ignoreWarnings (shadow-cljs'
// :devtools {:ignore-warnings true}): the warnings are only counted then.

/**
 * The warnings of a watch: compiled(event) records a compiled event's
 * warnings (by output file, a namespace's replace those of its previous
 * compile) and returns
 * - released: the output files held back that this compile didn't update, to
 *   hot reload now that no namespace has warnings
 * - outstanding: the warnings pausing hot reload, all namespaces', none with
 *   ignoreWarnings
 * - ignored: the number of all namespaces' warnings ignoreWarnings ignored
 * held(file) is whether file's hot update is held back.
 */
export function watchWarnings({ ignoreWarnings = false } = {}) {
  const warnings = new Map();
  const held = new Set();
  return {
    held: file => held.has(file),
    compiled(event) {
      const files = event.files ?? [];
      for (const file of files) warnings.delete(file);
      for (const w of event.warnings ?? []) warnings.set(w.output, [...(warnings.get(w.output) ?? []), w]);
      const all = [...warnings.values()].flat();
      let released = [];
      if (all.length && !ignoreWarnings) {
        for (const file of files) held.add(file);
      } else {
        released = [...held].filter(file => !files.includes(file));
        held.clear();
      }
      return {
        released,
        outstanding: ignoreWarnings ? [] : all,
        ignored: ignoreWarnings ? all.length : 0,
      };
    },
  };
}
