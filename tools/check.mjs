#!/usr/bin/env node
// Headless walker check: runs every scenario for each walker and prints the metrics vs targets.
//   node tools/check.mjs                       all walkers, all scenarios
//   node tools/check.mjs quad-strider hexapod  specific walkers (id or path to .json)
//   --scenario flat,stairs   --md (markdown report)   --json   --strict (exit 1 on any fail)
import { normalizeSpec, runMatrix, formatValue, SCENARIOS } from '../src/sim/index.js';
import { loadConfig, listWalkers, resolveWalker, readJson } from './lib.mjs';

const args = process.argv.slice(2);
const flag = (n) => args.includes(`--${n}`);
const opt = (n) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : null; };
const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1] === '--scenario'));
const scenarios = opt('scenario') ? opt('scenario').split(',') : Object.keys(SCENARIOS);
const { costModel, targets } = loadConfig();
const ids = positional.length ? positional : listWalkers();

const icon = { pass: '✅', fail: '❌', warn: '⚠️', info: '·' };
const out = [];
let anyFail = false;

for (const arg of ids) {
  const { id, path } = resolveWalker(arg);
  let spec;
  try {
    spec = normalizeSpec(readJson(path), { id, sharedTargets: targets });
  } catch (e) {
    console.error(`✖ ${id}: ${e.message}`);
    anyFail = true;
    continue;
  }
  if (spec.errors.length) {
    console.error(`✖ ${id}: invalid spec\n  - ${spec.errors.join('\n  - ')}`);
    anyFail = true;
    continue;
  }
  const matrix = runMatrix(spec, costModel, scenarios);
  if (matrix.some((m) => m.evaluation.verdict === 'fail')) anyFail = true;
  out.push({ id, spec, matrix });
}

if (flag('json')) {
  console.log(JSON.stringify(out.map(({ id, spec, matrix }) => ({
    id, name: spec.name, warnings: spec.warnings,
    scenarios: Object.fromEntries(matrix.map((m) => [m.scenario, {
      verdict: m.evaluation.verdict,
      metrics: Object.fromEntries(m.evaluation.rows.map((r) => [r.key, { value: r.value, target: r.targetValue, status: r.status, unit: r.unit }])),
      events: m.summary.eventCounts,
    }])),
  })), null, 2));
} else {
  const md = flag('md');
  for (const { id, spec, matrix } of out) {
    const lines = [];
    lines.push(md ? `## ${spec.name} (\`${id}\`)` : `\n━━ ${spec.name} (${id}) ━━`);
    if (spec.description) lines.push(md ? `_${spec.description}_` : spec.description);
    lines.push(`${spec.legs.length} legs · ${spec.gait.preset} gait · duty ${spec.gait.duty} · cycle ${spec.gait.cycleTicks.toFixed(1)} ticks @ ${spec.drive.rpm.toFixed(0)} RPM · stabilizer ${spec.physics.stabilizer ? `${spec.physics.stabilizer.maxTorque} mass·b` : 'none'} · suspension ${spec.physics.suspension.frequency} Hz ζ ${spec.physics.suspension.damping} · legs ${spec.implementation.legRepresentation}`);
    for (const w of spec.warnings) lines.push(`⚠️  ${w}`);
    lines.push('');
    const rows = matrix[0].evaluation.rows;
    const head = ['Metric', 'Target', ...matrix.map((m) => m.label)];
    const table = rows.map((r, i) => [
      `${r.label}${r.unit ? ` (${r.unit})` : ''}`,
      r.targetValue == null ? '' : `${r.op} ${formatValue(r, r.targetValue)}${r.override ? '*' : ''}`,
      ...matrix.map((m) => { const c = m.evaluation.rows[i]; return `${icon[c.status]} ${formatValue(c)}`; }),
    ]);
    table.push(['Verdict', '', ...matrix.map((m) => `${icon[m.evaluation.verdict]} ${m.evaluation.verdict.toUpperCase()}`)]);
    if (md) {
      lines.push(`| ${head.join(' | ')} |`, `|${head.map(() => '---').join('|')}|`, ...table.map((r) => `| ${r.join(' | ')} |`));
    } else {
      const w = head.map((h, c) => Math.max(h.length, ...table.map((r) => [...r[c]].length)));
      const pad = (s, n) => s + ' '.repeat(Math.max(0, n - [...s].length));
      lines.push(head.map((h, c) => pad(h, w[c])).join('  '));
      for (const r of table) lines.push(r.map((s, c) => pad(s, w[c])).join('  '));
    }
    const ev = matrix.flatMap((m) => Object.entries(m.summary.eventCounts).map(([k, n]) => `${m.label}: ${n}× ${k}`));
    if (ev.length) lines.push('', `Events — ${ev.join(' · ')}`);
    if (!md) lines.push('', `Open: http://localhost:5177/?walkers=${id}&terrain=${scenarios[0]}`);
    out.push && console.log(lines.join('\n'));
  }
  if (!costModel.calibrated) console.log(`\n${md ? '> ' : ''}⚠️  Server metrics use the UNCALIBRATED cost model in config/cost-model.json (shown as warnings, not failures).`);
}
process.exit(flag('strict') && anyFail ? 1 : 0);
