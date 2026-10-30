// Scaffolding a project from a template: its name, its namespace, the files,
// and the package manager's commands.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = path.dirname(fileURLToPath(import.meta.url));

// The version of clojurific the projects depend on, released with this package
export const version = JSON.parse(readFileSync(path.join(packageRoot, 'package.json'), 'utf8')).version;

export const templatesDir = path.join(packageRoot, 'templates');

export const TEMPLATES = ['vanilla', 'react', 'react-compiler', 'reagent', 'uix', 'hono'];

export const DEFAULT_PROJECT = 'clojurific-project';

// Trimmed, without trailing slashes
export function formatTargetDir(dir) {
  return dir.trim().replace(/[/\\]+$/g, '');
}

// A valid npm package name from a directory's name
export function packageName(name) {
  return name.trim().toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/^[._]/, '')
    .replace(/[^a-z\d\-~]+/g, '-') || DEFAULT_PROJECT;
}

// Names whose namespace would be munged (JavaScript's reserved words) or
// collide with the compiler's own (cljs.core, clojure.core)
const RESERVED = new Set([
  'await', 'break', 'case', 'catch', 'class', 'const', 'continue', 'debugger', 'default', 'delete', 'do',
  'else', 'enum', 'export', 'extends', 'false', 'finally', 'for', 'function', 'if', 'implements', 'import',
  'in', 'instanceof', 'interface', 'let', 'new', 'null', 'package', 'private', 'protected', 'public',
  'return', 'static', 'super', 'switch', 'this', 'throw', 'true', 'try', 'typeof', 'var', 'void', 'while',
  'with', 'yield', 'cljs', 'clojure', 'goog',
]);

// The app's namespace from the package's name: my-app.core, in
// src/my_app/core.cljs
export function namespace(pkg) {
  let name = pkg.replace(/^@[^/]*\//, '').replace(/[^a-z\d]+/g, '-').replace(/^-+|-+$/g, '');
  if (!name) name = 'app';
  else if (/^\d/.test(name) || RESERVED.has(name)) name = `app-${name}`;
  return { ns: `${name}.core`, nsDir: name.replace(/-/g, '_') };
}

// Whether dir is missing or has nothing but .git
export function isEmpty(dir) {
  const files = existsSync(dir) ? readdirSync(dir) : [];
  return files.length === 0 || (files.length === 1 && files[0] === '.git');
}

// Removes the directory's files but .git
export async function emptyDir(dir) {
  for (const file of await fs.readdir(dir)) {
    if (file !== '.git') await fs.rm(path.join(dir, file), { recursive: true, force: true });
  }
}

async function copy(src, dest, replace) {
  for (const entry of await fs.readdir(src, { withFileTypes: true })) {
    // npm leaves .gitignore files out of packages
    const name = replace(entry.name === '_gitignore' ? '.gitignore' : entry.name);
    const from = path.join(src, entry.name);
    const to = path.join(dest, name);
    if (entry.isDirectory()) {
      await fs.mkdir(to, { recursive: true });
      await copy(from, to, replace);
    } else {
      await fs.writeFile(to, replace(await fs.readFile(from, 'utf8')));
    }
  }
}

/**
 * Writes the template into root (created if missing): {{name}}, {{ns}} and
 * {{nsDir}} in files and paths are the package's name and namespace, and
 * package.json gets the name and clojurific's version.
 */
export async function scaffold({ root, template, name = packageName(path.basename(root)) }) {
  if (!TEMPLATES.includes(template)) throw new Error(`Unknown template ${template}, expected one of ${TEMPLATES.join(', ')}`);
  const vars = { name, ...namespace(name) };
  await fs.mkdir(root, { recursive: true });
  await copy(path.join(templatesDir, template), root, s => s.replace(/\{\{(\w+)\}\}/g, (m, v) => vars[v] ?? m));
  const pkgFile = path.join(root, 'package.json');
  const pkg = JSON.parse(await fs.readFile(pkgFile, 'utf8'));
  pkg.name = name;
  pkg.devDependencies.clojurific = `^${version}`;
  await fs.writeFile(pkgFile, `${JSON.stringify(pkg, null, 2)}\n`);
  return vars;
}

// The package manager running this (npm_config_user_agent, e.g.
// "pnpm/9.1.0 npm/? node/v22.3.0"), and its commands
export function packageManager(userAgent = process.env.npm_config_user_agent ?? '') {
  const name = userAgent.split(' ')[0]?.split('/')[0];
  const pm = ['pnpm', 'yarn', 'bun'].includes(name) ? name : 'npm';
  return {
    name: pm,
    install: [pm, 'install'],
    dev: pm === 'npm' || pm === 'bun' ? [pm, 'run', 'dev'] : [pm, 'dev'],
  };
}
