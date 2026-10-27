// The output held back while namespaces have compiler warnings: like
// shadow-cljs, it isn't hot reloaded, it would fail at runtime (undeclared
// vars), unless ignoreWarnings (shadow-cljs' :devtools {:ignore-warnings
// true}). The compiler keeps the warnings: a compiled event's outstanding
// are those of all namespaces in the build.

/**
 * The output held back of a watch: compiled(event) holds back the event's
 * files while it has outstanding warnings and returns
 * - released: the files held back that this compile didn't update, to hot
 *   reload now that no namespace has warnings
 * - outstanding: the warnings pausing hot reload, none with ignoreWarnings
 * - ignored: the number of warnings ignoreWarnings ignored
 * held(file) is whether file's hot update is held back. Paths are compared
 * normalized, as Vite's (forward slashes): the compiler's are the
 * platform's.
 */
export function watchWarnings({ ignoreWarnings = false, platform = process.platform } = {}) {
  const held = new Set();
  return {
    held: file => held.has(normalizePath(file, platform)),
    compiled(event) {
      const files = (event.files ?? []).map(file => normalizePath(file, platform));
      // a compiler of a version before :outstanding: the compile's warnings
      const all = event.outstanding ?? event.warnings ?? [];
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

/**
 * A path with forward slashes on Windows, like Vite's normalizePath
 * (elsewhere a backslash is a file name's character).
 */
export function normalizePath(file, platform = process.platform) {
  return platform === 'win32' ? file.replaceAll('\\', '/') : file;
}
