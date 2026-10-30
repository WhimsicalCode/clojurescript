// React Compiler (babel-plugin-react-compiler) on compiled namespaces, the
// Vite plugin's reactCompiler option.
//
// ClojureScript code creates elements with jsx() calls (#x literals, or
// react/jsx-runtime called directly), which React Compiler doesn't know: it
// assumes an unknown function may mutate its arguments, and memoizes nothing
// separately around them. moduleTypes declares the JSX runtimes and cljf.jsx's
// fns, as React Compiler declares React.createElement.

import { createRequire } from 'node:module';

const ANY = { kind: 'type', name: 'Any' };

const fn = (restParam, returnValueKind) => ({
  kind: 'function', positionalParams: [], restParam, calleeEffect: 'read', returnType: ANY, returnValueKind,
});

// like React.createElement's: freeze their arguments, return frozen elements
const element = fn('freeze', 'frozen');

const runtime = {
  kind: 'object',
  properties: { jsx: element, jsxs: element, jsxDEV: element, Fragment: ANY },
};

/**
 * The type of module name (an import specifier) for React Compiler's
 * moduleTypeProvider, null for modules it doesn't describe: JSX runtimes
 * (react/jsx-runtime, preact/jsx-runtime, hono/jsx/jsx-runtime, ...) and
 * cljf.jsx's compiled module.
 */
export function moduleTypes(name) {
  if (/(^|\/)jsx-(dev-)?runtime$/.test(name)) return runtime;
  if (/(^|\/)cljf\/jsx\.js$/.test(name)) {
    return {
      kind: 'object',
      properties: {
        ...runtime.properties,
        // the conversions of what #x can't see into: a child's new array, a
        // map's JavaScript object (cached per map, shared), strings
        child: fn('read', 'mutable'),
        props: fn('read', 'frozen'),
        style: fn('read', 'frozen'),
        class_name: fn('read', 'primitive'),
        spread_key: fn('read', 'primitive'),
        prop_name: fn('read', 'primitive'),
      },
    };
  }
  return null;
}

/**
 * A transform of compiled modules running React Compiler on them, given
 * options (true, or babel-plugin-react-compiler's options) and resolve, which
 * resolves a package from the project. Under compilationMode annotation (the
 * default) only modules with "use memo" functions are compiled: the
 * ^:memo ClojureScript fns.
 */
export function reactCompilerTransform(options, resolve) {
  const opts = options === true ? {} : options;
  const annotation = (opts.compilationMode ?? 'annotation') === 'annotation';
  const userTypes = opts.environment?.moduleTypeProvider;
  let loaded;
  const load = () => {
    if (!loaded) {
      const require = createRequire(import.meta.url);
      const plugin = require(resolve('babel-plugin-react-compiler'));
      loaded = { babel: require(resolve('@babel/core')), plugin: plugin.default ?? plugin };
    }
    return loaded;
  };
  return async (code, file) => {
    if (annotation && !code.includes('"use memo"')) return null;
    const { babel, plugin } = load();
    const result = await babel.transformAsync(code, {
      filename: file,
      babelrc: false,
      configFile: false,
      sourceType: 'module',
      sourceMaps: true,
      // Vite composes the map with the compiled module's
      inputSourceMap: false,
      plugins: [[plugin, {
        compilationMode: 'annotation',
        ...opts,
        environment: {
          ...opts.environment,
          moduleTypeProvider: name => userTypes?.(name) ?? moduleTypes(name),
        },
      }]],
    });
    return result && { code: result.code, map: result.map };
  };
}
