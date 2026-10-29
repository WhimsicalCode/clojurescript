#!/usr/bin/env node
// create-clojurific: npm create clojurific@latest scaffolds a ClojureScript
// project built with Clojurific and Vite, like create-vite. See usage below.
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  DEFAULT_PROJECT, TEMPLATES, emptyDir, formatTargetDir, isEmpty, packageManager, packageName, scaffold, version,
} from './create.js';
import { CANCEL, c, cancel, confirm, log, outro, select, text } from './prompts.js';

const usage = `Usage: create-clojurific [options] [project-name]

Scaffolds a ClojureScript project compiled by Clojurific and bundled by Vite.
Asks for what the options don't say.

Options:
  -t, --template <name>   ${TEMPLATES.join(', ')}
  --overwrite             remove the target directory's files
  -i, --immediate         install the dependencies and start the dev server
  --no-interactive        don't ask, use the defaults (vanilla, no install)

  npm create clojurific@latest
  npm create clojurific@latest my-app -- --template reagent`;

const FRAMEWORKS = [
  { value: 'vanilla', label: 'Vanilla', color: c.yellow },
  { value: 'react', label: 'React', color: c.blue, hint: '#x literals, https://react.dev/' },
  { value: 'reagent', label: 'Reagent', color: c.cyan, hint: 'https://reagent-project.github.io/' },
  { value: 'uix', label: 'UIx', color: c.magenta, hint: 'https://uix-cljs.dev/' },
  { value: 'hono', label: 'Hono (backend only)', color: c.red, hint: 'https://hono.dev/' },
];

function parse(argv) {
  const args = { dir: undefined };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-t' || arg === '--template') args.template = argv[++i];
    else if (arg.startsWith('--template=')) args.template = arg.slice('--template='.length);
    else if (arg === '--overwrite') args.overwrite = true;
    else if (arg === '-i' || arg === '--immediate') args.immediate = true;
    else if (arg === '--no-immediate') args.immediate = false;
    else if (arg === '--interactive') args.interactive = true;
    else if (arg === '--no-interactive') args.interactive = false;
    else if (arg === '-h' || arg === '--help') args.help = true;
    else if (arg === '-v' || arg === '--version') args.version = true;
    else if (arg.startsWith('-')) throw new Error(`Unknown option ${arg}\n\n${usage}`);
    else args.dir ??= arg;
  }
  return args;
}

function cancelled(value) {
  if (value !== CANCEL) return value;
  cancel();
  process.exit(1);
}

// Runs a command in cwd, its output to the terminal; resolves to its exit
// code. The terminal's Ctrl-C reaches it too, this process waits for it.
function run([command, ...args], cwd) {
  return new Promise(resolve => {
    const child = spawn(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' });
    const ignore = () => {};
    process.on('SIGINT', ignore);
    child.on('error', e => {
      log.error(`${command}: ${e.message}`);
      resolve(1);
    });
    child.on('close', code => {
      process.off('SIGINT', ignore);
      resolve(code ?? 1);
    });
  });
}

// Java for the compiler, with the installed clojurific's launcher: true when
// there is one, or setup-java installed one (CLJF_INSTALL_JDK=1, or asked)
async function checkJava(root, interactive) {
  let launcher;
  try {
    launcher = await import(pathToFileURL(createRequire(path.join(root, 'package.json')).resolve('clojurific')).href);
  } catch {
    // i.e. Yarn's Plug'n'Play: the dev server checks
    return true;
  }
  const { findJava, setupJava, LauncherError, MIN_VERSION } = launcher;
  try {
    const java = await findJava({ log: line => log.message(line) });
    log.step(`Using Java ${java.version} (${java.source})`);
    return true;
  } catch (e) {
    if (!(e instanceof LauncherError)) throw e;
    const message = e.message.replace(/^cljf: /, '');
    log.warn(message[0].toUpperCase() + message.slice(1));
  }
  const install = interactive && cancelled(await confirm({ message: 'Download Eclipse Temurin into cljf\'s cache now?' }));
  if (!install) {
    log.message(`The dev server needs Java ${MIN_VERSION} or later.`);
    return false;
  }
  const java = await setupJava({ log: line => log.message(line) });
  log.step(`Using Java ${java.version} (cljf setup-java)`);
  return true;
}

async function main() {
  const args = parse(process.argv.slice(2));
  if (args.help) return console.log(usage);
  if (args.version) return console.log(version);
  const interactive = args.interactive ?? Boolean(process.stdin.isTTY && process.stdout.isTTY && !process.env.CI);
  const pm = packageManager();

  let targetDir = args.dir && formatTargetDir(args.dir);
  if (!targetDir) {
    targetDir = interactive
      ? formatTargetDir(cancelled(await text({ message: 'Project name:', placeholder: DEFAULT_PROJECT, defaultValue: DEFAULT_PROJECT })))
      : DEFAULT_PROJECT;
  }
  const root = path.resolve(targetDir);

  let overwrite = args.overwrite ? 'yes' : undefined;
  if (!isEmpty(root) && !overwrite) {
    if (!interactive) {
      log.error(`Target directory ${targetDir} isn't empty, --overwrite removes its files.`);
      cancelled(CANCEL);
    }
    overwrite = cancelled(await select({
      message: `${targetDir === '.' ? 'Current directory' : `Target directory "${targetDir}"`} is not empty. Please choose how to proceed:`,
      options: [
        { value: 'no', label: 'Cancel operation' },
        { value: 'yes', label: 'Remove existing files and continue' },
        { value: 'ignore', label: 'Ignore files and continue' },
      ],
    }));
    if (overwrite === 'no') cancelled(CANCEL);
  }

  let template = args.template?.toLowerCase();
  if (template && !TEMPLATES.includes(template)) {
    if (!interactive) throw new Error(`Unknown template ${args.template}, expected one of ${TEMPLATES.join(', ')}`);
    log.warn(`"${args.template}" isn't a valid template. Please choose from below:`);
    template = undefined;
  }
  if (!template) {
    template = interactive ? cancelled(await select({ message: 'Select a framework:', options: FRAMEWORKS })) : 'vanilla';
  }

  const immediate = args.immediate
    ?? (interactive && cancelled(await confirm({ message: `Install with ${pm.name} and start now?` })));

  log.step(`Scaffolding project in ${root}...`);
  if (overwrite === 'yes' && existsSync(root)) await emptyDir(root);
  const name = packageName(path.basename(root));
  await scaffold({ root, template, name });

  const cd = path.relative(process.cwd(), root);
  const next = [...(cd ? [`cd ${cd.includes(' ') ? `"${cd}"` : cd}`] : []), pm.install.join(' '), pm.dev.join(' ')];
  const done = steps => outro(`Done. Now run:\n\n${steps.map(s => `  ${s}`).join('\n')}`);

  if (!immediate) {
    return done(next);
  }
  log.step(`Installing dependencies with ${pm.name}...`);
  const installed = await run(pm.install, root);
  if (installed !== 0) {
    log.error(`${pm.install.join(' ')} failed`);
    process.exitCode = 1;
    return done(next);
  }
  if (!(await checkJava(root, interactive))) {
    return done(next.filter(s => s !== pm.install.join(' ')));
  }
  log.step('Starting dev server...');
  process.exitCode = await run(pm.dev, root);
}

main().catch(e => {
  log.error(e.message ?? e);
  cancel();
  process.exit(1);
});
