// Scaffolding projects with create-clojurific, without prompts
// (--no-interactive) or installing them; script/test-launcher builds them.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { TEMPLATES, namespace, packageManager, packageName, scaffold, version } from '../create.js';

const cli = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'index.js');

function tmpDir() {
  return mkdtempSync(path.join(os.tmpdir(), 'create-clojurific-test-'));
}

function create(cwd, ...args) {
  return spawnSync(process.execPath, [cli, ...args], { cwd, encoding: 'utf8', env: { ...process.env, NO_COLOR: '1' } });
}

test('the version is clojurific\'s', () => {
  const clojurific = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8'));
  assert.equal(version, clojurific.version);
});

test('packageName', () => {
  assert.equal(packageName('my-app'), 'my-app');
  assert.equal(packageName(' My App '), 'my-app');
  assert.equal(packageName('.hidden'), 'hidden');
  assert.equal(packageName('a+b'), 'a-b');
});

test('namespace', () => {
  assert.deepEqual(namespace('my-app'), { ns: 'my-app.core', nsDir: 'my_app' });
  assert.deepEqual(namespace('app'), { ns: 'app.core', nsDir: 'app' });
  assert.deepEqual(namespace('my.app'), { ns: 'my-app.core', nsDir: 'my_app' });
  assert.deepEqual(namespace('@scope/my-app'), { ns: 'my-app.core', nsDir: 'my_app' });
  assert.deepEqual(namespace('2048'), { ns: 'app-2048.core', nsDir: 'app_2048' });
  assert.deepEqual(namespace('---'), { ns: 'app.core', nsDir: 'app' });
  // cljs.core is the compiler's, class.core would be munged
  assert.deepEqual(namespace('cljs'), { ns: 'app-cljs.core', nsDir: 'app_cljs' });
  assert.deepEqual(namespace('class'), { ns: 'app-class.core', nsDir: 'app_class' });
});

test('packageManager', () => {
  assert.deepEqual(packageManager('npm/10.8.2 node/v22.3.0 darwin arm64'),
    { name: 'npm', install: ['npm', 'install'], dev: ['npm', 'run', 'dev'] });
  assert.deepEqual(packageManager('pnpm/9.1.0 npm/? node/v22.3.0'),
    { name: 'pnpm', install: ['pnpm', 'install'], dev: ['pnpm', 'dev'] });
  assert.equal(packageManager('yarn/1.22.22 npm/? node/v22.3.0').name, 'yarn');
  assert.equal(packageManager('bun/1.1.0').dev.join(' '), 'bun run dev');
  assert.equal(packageManager('').name, 'npm');
});

for (const template of TEMPLATES) {
  test(`scaffold ${template}`, async () => {
    const root = path.join(tmpDir(), 'my-app');
    assert.deepEqual(await scaffold({ root, template }), { name: 'my-app', ns: 'my-app.core', nsDir: 'my_app' });
    const pkg = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
    assert.equal(pkg.name, 'my-app');
    assert.equal(pkg.type, 'module');
    assert.equal(pkg.devDependencies.clojurific, `^${version}`);
    const deps = readFileSync(path.join(root, 'deps.edn'), 'utf8');
    assert.match(deps, /nrepl\/nrepl/);
    assert.match(deps, /cider\/cider-nrepl/);
    const viteConfig = readFileSync(path.join(root, 'vite.config.ts'), 'utf8');
    assert.match(viteConfig, /repl: \{ nreplPort: 0, middleware: \["'cider\.nrepl\/cider-middleware"\] \}/);
    assert.ok(existsSync(path.join(root, '.gitignore')));
    assert.ok(!existsSync(path.join(root, '_gitignore')));
    const source = readFileSync(path.join(root, 'src', 'my_app', 'core.cljs'), 'utf8');
    assert.match(source, /^\(ns my-app\.core\b/);
    assert.match(source, /src\/my_app\/core\.cljs/);
    if (template === 'hono') {
      assert.deepEqual(pkg.scripts, { dev: 'vite', build: 'vite build', start: 'node dist/index.js', repl: 'cljf repl' });
      assert.ok(pkg.dependencies.hono);
      // the app is a :js-entries export the dev server runs, no pages
      assert.match(viteConfig, /default: "'my-app\.core\/app"/);
      assert.match(viteConfig, /ssrEntries: \['server'\]/);
      assert.match(source, /\(def app\b/);
      assert.ok(!existsSync(path.join(root, 'index.html')));
    } else {
      assert.deepEqual(pkg.scripts, { dev: 'vite', build: 'vite build', preview: 'vite preview', repl: 'cljf repl' });
      assert.match(readFileSync(path.join(root, 'index.html'), 'utf8'), /<script type="module" src="\/src\/my_app\/core\.cljs">/);
      assert.match(readFileSync(path.join(root, 'index.html'), 'utf8'), /<title>my-app<\/title>/);
    }
  });
}

test('the CLI scaffolds the template the options name', () => {
  const cwd = tmpDir();
  const result = create(cwd, 'my-app', '--template', 'reagent', '--no-interactive');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Scaffolding project in .*my-app/);
  assert.match(result.stdout, /cd my-app\n\s+npm install\n\s+npm run dev/);
  assert.match(readFileSync(path.join(cwd, 'my-app', 'deps.edn'), 'utf8'), /reagent\/reagent/);
});

test('the CLI defaults to vanilla in the default directory without prompts', () => {
  const cwd = tmpDir();
  const result = create(cwd, '--no-interactive');
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(readFileSync(path.join(cwd, 'clojurific-project', 'deps.edn'), 'utf8'), /reagent|uix/);
  assert.ok(existsSync(path.join(cwd, 'clojurific-project', 'src', 'clojurific_project', 'core.cljs')));
});

test('the CLI scaffolds into the current directory, named after it', () => {
  const cwd = path.join(tmpDir(), 'here');
  mkdirSync(path.join(cwd, '.git'), { recursive: true });
  const result = create(cwd, '.', '--template', 'uix', '--no-interactive');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Now run:\n\n\s+npm install\n/);
  assert.equal(JSON.parse(readFileSync(path.join(cwd, 'package.json'), 'utf8')).name, 'here');
  assert.ok(existsSync(path.join(cwd, 'src', 'here', 'core.cljs')));
});

test('the CLI leaves a directory with files alone, unless --overwrite', () => {
  const cwd = tmpDir();
  mkdirSync(path.join(cwd, 'my-app'));
  writeFileSync(path.join(cwd, 'my-app', 'old.txt'), 'old');
  const refused = create(cwd, 'my-app', '--no-interactive');
  assert.notEqual(refused.status, 0);
  assert.match(refused.stdout, /isn't empty, --overwrite/);
  assert.ok(!existsSync(path.join(cwd, 'my-app', 'package.json')));

  const overwritten = create(cwd, 'my-app', '--overwrite', '--no-interactive');
  assert.equal(overwritten.status, 0, overwritten.stderr);
  assert.ok(!existsSync(path.join(cwd, 'my-app', 'old.txt')));
  assert.ok(existsSync(path.join(cwd, 'my-app', 'package.json')));
});

test('the CLI rejects an unknown template without prompts', () => {
  const result = create(tmpDir(), 'my-app', '--template', 'vue', '--no-interactive');
  assert.notEqual(result.status, 0);
  assert.match(result.stdout, /Unknown template vue/);
});
