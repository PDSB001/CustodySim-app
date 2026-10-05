import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Only use successful on-device ProfileRule outputs, never hand-written ART rules.
const root = fileURLToPath(new URL('../', import.meta.url));
const directory = resolve(process.argv[2] ?? '');
if (!process.argv[2]) throw new Error('Supply the pulled profile directory');
function files(path) {
  return readdirSync(path, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? files(join(path, entry.name)) : [join(path, entry.name)]);
}
const input = files(directory);
for (const journey of ['startup', 'libraryReading', 'nativeText']) {
  const log = input.find(path => path.endsWith(`${journey}.txt`));
  if (!log || !/OK \(1 test\)/.test(readFileSync(log, 'utf8'))) throw new Error(`Missing successful ${journey} evidence`);
}
const output = resolve(root, 'app/src/main/generated/baselineProfiles');
const profiles = ['baseline-prof.txt', 'startup-prof.txt'].map(suffix => {
  const selected = input.filter(path => path.endsWith(suffix));
  if (!selected.length) throw new Error(`Missing generated ${suffix}`);
  const rules = [...new Set(selected.flatMap(path => readFileSync(path, 'utf8').split(/\r?\n/)))]
    .filter(line => /Lcom\/custodysim\/app\//.test(line) && !/Lcom\/custodysim\/app\/benchmark\//.test(line)).sort();
  if (!rules.length) throw new Error(`No app rules in ${suffix}`);
  return { suffix, rules };
});
if (!profiles[0].rules.some(line => /ui\/library/.test(line))) throw new Error('Reading paths were not recorded');
if (profiles[1].rules.some(line => /ui\/library\/.*Reader/.test(line)))
  throw new Error('Reader code entered startup profile; inspect startup journey');
mkdirSync(output, { recursive: true });
for (const { suffix, rules } of profiles) {
  writeFileSync(join(output, suffix), `${rules.join('\n')}\n`);
  console.log(`${suffix}: ${rules.length} app rules`);
}
