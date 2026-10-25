// Starts the Scala / Play API (with an embedded PostgreSQL unless DATABASE_URL is set) and the web app together.
// Needs JDK 21, sbt and Node.js.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const windows = process.platform === 'win32';

// Fail early with install steps instead of a cryptic "command not found".
function onPath(cmd) {
  return spawnSync(windows ? 'where' : 'which', [cmd], { stdio: 'ignore' }).status === 0;
}
const missing = [
  !onPath('java') && 'JDK 21+   https://adoptium.net/  (Windows: winget install Microsoft.OpenJDK.21)',
  !onPath('sbt') && 'sbt 1.10+ https://www.scala-sbt.org/download/  (Windows: the .msi installer)',
].filter(Boolean);
if (!existsSync('frontend/node_modules')) missing.push('web app dependencies: run `npm run setup` first');
if (missing.length) {
  console.error('\nMissing before the app can start:\n  ' + missing.join('\n  '));
  console.error('Fix these (open a new terminal after installing tools), then run `npm run dev` again.\n');
  process.exit(1);
}

const procs = [
  { name: 'api', color: '\x1b[36m', cmd: 'sbt', args: ['run'], cwd: 'backend',
    env: { LOG_FORMAT: process.env.LOG_FORMAT ?? 'pretty' },
    // `sbt run` stops when its stdin closes, so keep a pipe open.
    keepStdin: true },
  { name: 'web', color: '\x1b[35m', cmd: 'npm', args: ['--prefix', 'frontend', 'run', 'dev'], env: {} },
];

const children = procs.map(({ name, color, cmd, args, cwd, env, keepStdin }) => {
  // On Windows sbt and npm are .bat/.cmd scripts, which only a shell can start; pass one command
  // string (the arguments are fixed literals) so Node does not warn about unescaped arguments.
  const options = { cwd, env: { ...process.env, ...env }, stdio: [keepStdin ? 'pipe' : 'ignore', 'pipe', 'pipe'] };
  const child = windows ? spawn([cmd, ...args].join(' '), { ...options, shell: true }) : spawn(cmd, args, options);
  const prefix = `${color}[${name}]\x1b[0m `;
  const pipe = (stream, out) =>
    stream.on('data', (buf) => buf.toString().split('\n').filter(Boolean).forEach((l) => out.write(prefix + l + '\n')));
  pipe(child.stdout, process.stdout);
  pipe(child.stderr, process.stderr);
  child.on('error', (e) => console.error(`${prefix}could not start ${cmd}: ${e.message}`));
  child.on('exit', (code) => {
    console.log(`${prefix}exited with code ${code}`);
    shutdown();
  });
  return child;
});

let stopping = false;
function shutdown() {
  if (stopping) return;
  stopping = true;
  for (const c of children) c.kill('SIGINT');
  setTimeout(() => process.exit(0), 3000);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
console.log('\nOpen http://localhost:5173  (API docs: http://localhost:8081/docs)');
console.log('The first start compiles the Scala code (a minute or two); the API answers once it is ready.\n');
