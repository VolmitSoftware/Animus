// Records web editor tutorial clips.
//
//   node qa/demo/editor/record.cjs 05-conversation [07-routes ...]
//   node qa/demo/editor/record.cjs --all
//
// Flags (each overrides the environment variable in lib.cjs):
//   --web=<url>    ANIMUS_WEB_URL     editor site
//   --fake=<url>   ANIMUS_FAKE_URL    fake server (each clip's header names its data)
//   --pair=<link>  ANIMUS_PAIR_LINK   real server pairing link for clips 11, 12, 44-47 and 49
//   --out=<dir>    ANIMUS_DEMO_OUT    output folder
// A clip can be named by its full id or its number (05).
const fs = require('node:fs');
const path = require('node:path');

const FLAGS = {web: 'ANIMUS_WEB_URL', fake: 'ANIMUS_FAKE_URL', pair: 'ANIMUS_PAIR_LINK', out: 'ANIMUS_DEMO_OUT'};
const CLIPS = fs.readdirSync(__dirname).filter((f) => /^\d\d-.+\.cjs$/.test(f)).map((f) => f.slice(0, -4)).sort();

function usage(message) {
  if (message) console.error(message);
  console.error(`usage: node ${path.relative(process.cwd(), __filename)} [--web=URL] [--fake=URL] [--pair=LINK] [--out=DIR] (--all | CLIP...)`);
  console.error(`clips: ${CLIPS.join(' ')}`);
  process.exit(64);
}

const wanted = [];
let all = false;
for (const arg of process.argv.slice(2)) {
  const m = /^--([a-z]+)(?:=(.*))?$/.exec(arg);
  if (!m) { wanted.push(arg); continue; }
  if (m[1] === 'all') all = true;
  else if (m[1] === 'help') usage();
  else if (FLAGS[m[1]] && m[2]) process.env[FLAGS[m[1]]] = m[2];
  else usage(`unknown flag ${arg}`);
}
const ids = all ? CLIPS : wanted.map((w) => CLIPS.find((id) => id === w || id.startsWith(`${w}-`)) || usage(`unknown clip ${w}`));
if (!ids.length) usage();

// lib.cjs reads the environment on load, so it is required after the flags are applied.
const L = require('./lib.cjs');

(async () => {
  fs.mkdirSync(L.OUT, {recursive: true});
  let failed = 0;
  for (const id of ids) {
    try {
      const r = await require(`./${id}.cjs`)(L);
      console.log(`${id}: ${path.relative(process.cwd(), r.out)} ${r.secs.toFixed(1)}s${r.errors.length ? `, ${r.errors.length} console errors` : ''}`);
      for (const e of r.errors) console.log(`  ${e.slice(0, 300)}`);
    } catch (e) {
      failed++;
      console.error(`${id}: FAILED ${String(e && e.message || e).slice(0, 600)}`);
    }
  }
  process.exit(failed ? 1 : 0);
})();
