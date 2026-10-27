#!/usr/bin/env node
// cljf: builds and watches ClojureScript with the compiler (cljf.esm), on the
// project's classpath resolved from its deps.edn. See usage below.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { LauncherError } from './errors.js';
import { classpath, compilerCommand, findJava, setupJava, version } from './launcher.js';
import { repl } from './repl.js';

const usage = `Usage: cljf [options] <command> [arguments]

Commands:
  build [options...]     compile once, cljf.esm's options: EDN maps, @file.edn
                         and :profile keywords (defaults to :release)
  watch [options...]     compile, then recompile on changes (defaults to :dev)
  classpath              print the project's classpath
  repl [--port <port>]   a ClojureScript REPL into the pages running the dev
                         server's (or watch's) build, through its nREPL server
                         (:repl {:nrepl-port 0}, port from .nrepl-port)
  setup-java [version]   download Eclipse Temurin (the latest LTS by default)
                         into cljf's cache, for machines without Java

Options:
  -A:alias1:alias2       apply deps.edn aliases, like the Clojure CLI's -A
  --force                resolve the classpath again instead of using
                         .cljf/cpcache
  --version              print cljf's version

The project is the current directory, with its deps.edn. Java 17 or later
comes from CLJF_JAVA, JAVA_HOME, PATH or setup-java, in that order.

  cljf build '{:main my.app :output-dir "out"}'
  cljf -A:test watch @cljs.edn :test`;

function parse(argv) {
  const opts = { aliases: [], force: false };
  let i = 0;
  for (; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('-A')) opts.aliases.push(arg.length > 2 ? arg.slice(2) : argv[++i]);
    else if (arg === '--force') opts.force = true;
    else break;
  }
  return { opts, command: argv[i], args: argv.slice(i + 1) };
}

function run(command, args) {
  const child = spawn(command, args, { stdio: 'inherit' });
  // forwarded: only a terminal signals the JVM too (SIGINT), not kill, an IDE
  // or a process supervisor; a second SIGINT is harmless
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(signal, () => child.kill(signal));
  }
  child.on('error', e => {
    console.error(`cljf: ${command}: ${e.message}`);
    process.exit(1);
  });
  child.on('exit', (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exit(code);
  });
}

async function main() {
  const { opts, command, args } = parse(process.argv.slice(2));
  const interactive = Boolean(process.stdin.isTTY && process.stderr.isTTY && !process.env.CI);
  const log = line => process.stderr.write(`${line}\n`);
  switch (command) {
    case 'build':
    case 'watch': {
      const cmd = await compilerCommand({ ...opts, interactive, log });
      run(cmd.command, [...cmd.args, '-m', 'cljf.main', command, ...args]);
      break;
    }
    case 'classpath': {
      const cp = await classpath({ ...opts, java: findJava({ interactive, log }), log });
      console.log(cp.classpath.join(path.delimiter));
      break;
    }
    case 'repl': {
      const i = args.indexOf('--port');
      process.exit(await repl({ port: i >= 0 ? Number(args[i + 1]) : undefined }));
      break;
    }
    case 'setup-java': {
      const { java } = await setupJava({ version: args[0], log });
      console.log(java);
      break;
    }
    case '--version':
    case 'version':
      console.log(version);
      break;
    case undefined:
    case 'help':
    case '--help':
    case '-h':
      console.log(usage);
      break;
    default:
      console.error(`cljf: unknown command ${command}\n\n${usage}`);
      process.exit(2);
  }
}

main().catch(e => {
  console.error(e instanceof LauncherError ? e.message : e);
  process.exit(1);
});
