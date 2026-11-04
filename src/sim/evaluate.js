// Turns a simulation result into the metric table the team argues about:
// gameplay feel, mechanical feasibility, and estimated server/network cost, each checked against targets.
import { TPS } from './math.js';
import { simulate } from './simulate.js';
import { makeTerrain, SCENARIOS } from './terrain.js';

export function estimateCost(spec, sim, costModel) {
  const cm = costModel;
  const impl = spec.implementation;
  const rep = cm.legRepresentation[impl.legRepresentation] ?? cm.legRepresentation.renderOnly;
  const n = spec.legs.length;
  const segs = spec.legs.reduce((s, l) => s + l.segments.length, 0);
  // one foothold search per leg per cycle
  const probesPerTick = (n * impl.footholdProbesPerStep) / spec.gait.cycleTicks;
  const parts = {
    base: cm.perWalkerBaseUs,
    ik: n * cm.perLegIkUs,
    legs: n * rep.perLegUs + segs * rep.perSegmentUs,
    footholds: probesPerTick * cm.perFootholdProbeUs,
    ground: n * impl.groundChecksPerFootPerTick * cm.perGroundCheckUs,
    body: spec.body.blocks * cm.bodySubLevelPerBlockUs,
  };
  const us = Object.values(parts).reduce((a, b) => a + b, 0);
  const budgetUs = cm.tickBudgetMs * 1000 * cm.shareOfTickForWalkers;
  const netBps = (n * rep.netBytesPerLegPerSync * TPS) / Math.max(1, impl.syncEveryTicks);
  return {
    us,
    parts,
    walkersPerServer: Math.floor(budgetUs / us),
    netKBps: netBps / 1024,
    calibrated: !!cm.calibrated,
  };
}

export function stress(spec) {
  const d = spec.drive;
  return d.rpm * (spec.legs.length * d.stressPerLegPerRpm + spec.mass * d.stressPerBlockPerRpm);
}

/** Metric definitions: key → how to read it from (spec, sim, cost) and which target applies. */
export const METRICS = [
  { group: 'Feel', key: 'speed', label: 'Ground speed', unit: 'b/s', fmt: 2, get: (s, r) => r.summary.speed, target: 'speedMin', op: '>=',
    help: 'stride ÷ cycle time. Player walk ≈ 4.3 b/s, sprint ≈ 5.6 b/s, horse ≈ 9 b/s.' },
  { group: 'Feel', key: 'bob', label: 'Body bob', unit: 'b', fmt: 2, get: (s, r) => r.summary.bob, target: 'bodyBobMax', op: '<=',
    help: 'Peak-to-peak vertical body oscillation after removing terrain trend. Players standing on the deck feel this.' },
  { group: 'Feel', key: 'pitch', label: 'Max body tilt', unit: '°', fmt: 1, get: (s, r) => r.summary.pitchMaxDeg, target: 'bodyPitchMaxDeg', op: '<=',
    help: 'Largest pitch or roll of the hull (only non-zero with bodyAdapt "pitch").' },
  { group: 'Feel', key: 'vy', label: 'Peak vertical speed', unit: 'b/s', fmt: 2, get: (s, r) => r.summary.vyMax, target: null, op: '<=',
    help: 'How fast the hull moves up/down at worst (stairs, ledges).' },
  { group: 'Stability', key: 'margin', label: 'Min stability margin', unit: 'b', fmt: 2, get: (s, r) => r.summary.marginMin, target: 'stabilityMarginMin', op: '>=',
    help: 'Smallest distance from the centre of mass to the edge of the support polygon. Negative = would tip if the body were physically simulated. Only advisory for physics.support "kinematic".' },
  { group: 'Stability', key: 'stance', label: 'Min feet planted', unit: '', fmt: 0, get: (s, r) => r.summary.stanceMin, target: 'stanceMin', op: '>=',
    help: 'Fewest feet on the ground at any tick.' },
  { group: 'Mechanics', key: 'reach', label: 'Max reach used', unit: '%', fmt: 0, scale: 100, get: (s, r) => r.summary.reachMax, target: 'reachMax', op: '<=',
    help: 'Hip→foot distance ÷ total leg length at the worst tick. >100% means the leg cannot reach its target.' },
  { group: 'Mechanics', key: 'footErr', label: 'Max foot slip', unit: 'b', fmt: 2, get: (s, r) => r.summary.footErrMax, target: 'footErrorMax', op: '<=',
    help: 'How far a planted foot is from where it should be (IK could not reach). Visible as sliding/floating feet.' },
  { group: 'Mechanics', key: 'collisions', label: 'Collision events', unit: '', fmt: 0, get: (s, r) => r.summary.collisions, target: 'collisionsMax', op: '<=',
    help: 'Toe stubs, knee clips, hull scrapes, steps into the void, foot clashes, steps above maxStepUp.' },
  { group: 'Mechanics', key: 'jointSpeed', label: 'Max joint speed', unit: '°/s', fmt: 0, get: (s, r) => r.summary.jointSpeedMaxDegS, target: 'jointSpeedMaxDegS', op: '<=',
    help: 'Fastest any joint rotates. Very high values read as snapping/jitter at 20 TPS.' },
  { group: 'Mechanics', key: 'clearance', label: 'Min hull clearance', unit: 'b', fmt: 2, get: (s, r) => r.summary.clearanceMin, target: null, op: '>=',
    help: 'Smallest gap between the hull bottom and the terrain.' },
  { group: 'Drive', key: 'rpm', label: 'Drive speed', unit: 'RPM', fmt: 1, get: (s) => s.drive.rpm, target: 'rpmMax', op: '<=',
    help: 'Rotational speed the leg drive needs (1200 × revsPerCycle ÷ cycleTicks).' },
  { group: 'Drive', key: 'stress', label: 'Stress', unit: 'SU', fmt: 0, get: (s) => stress(s), target: 'stressMax', op: '<=',
    help: 'Create stress impact: RPM × (legs × stressPerLegPerRpm + mass × stressPerBlockPerRpm).' },
  { group: 'Server', key: 'serverUs', label: 'Server cost', unit: 'µs/tick', fmt: 0, get: (s, r, c) => c.us, target: 'serverUsMax', op: '<=',
    help: 'Estimated main-thread time per walker per tick from config/cost-model.json.' },
  { group: 'Server', key: 'perServer', label: 'Walkers per server', unit: '', fmt: 0, get: (s, r, c) => c.walkersPerServer, target: 'walkersPerServerMin', op: '>=',
    help: 'How many of this walker fit in the walker share of a 50 ms tick.' },
  { group: 'Server', key: 'net', label: 'Network', unit: 'KB/s', fmt: 2, get: (s, r, c) => c.netKBps, target: 'netKBpsMax', op: '<=',
    help: 'Leg pose sync bandwidth per watching player.' },
  { group: 'Server', key: 'refUs', label: 'Reference solver', unit: 'µs/tick', fmt: 1, get: (s, r) => r.summary.refUsPerTick, target: null, op: '<=',
    help: 'Measured time of THIS JavaScript implementation (gait + IK + body) per tick. A lower bound sanity check, not the server cost.' },
];

export function evaluate(spec, sim, costModel) {
  const cost = estimateCost(spec, sim, costModel);
  const overrides = spec.raw.targets || {};
  const rows = METRICS.map((m) => {
    const value = m.get(spec, sim, cost);
    const target = m.target ? spec.targets[m.target] : null;
    let status = 'info';
    if (target !== null && target !== undefined) {
      const ok = m.op === '>=' ? value >= target - 1e-9 : value <= target + 1e-9;
      status = ok ? 'pass' : 'fail';
      if (!ok && m.key === 'margin' && spec.physics.support === 'kinematic') status = 'warn';
      if (!ok && m.group === 'Server' && !cost.calibrated) status = 'warn';
    }
    return { ...m, value, targetValue: target ?? null, override: m.target ? m.target in overrides : false, status };
  });
  const fails = rows.filter((r) => r.status === 'fail').length;
  const warns = rows.filter((r) => r.status === 'warn').length;
  return { rows, cost, fails, warns, verdict: fails ? 'fail' : warns ? 'warn' : 'pass' };
}

export function formatValue(row, v = row.value) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  if (!Number.isFinite(v)) return v > 0 ? '∞' : '−∞';
  const x = v * (row.scale ?? 1);
  return x.toFixed(row.fmt ?? 2);
}

/** Simulate + evaluate one walker on one scenario. */
export function runScenario(spec, scenario, costModel, opts = {}) {
  const terrain = makeTerrain(scenario, opts.seed);
  const sim = simulate(spec, terrain, opts);
  return { scenario, terrain, sim, evaluation: evaluate(spec, sim, costModel) };
}

/** Quick pass over every scenario (no frames kept) for the scenario matrix / CLI. */
export function runMatrix(spec, costModel, scenarios = Object.keys(SCENARIOS), opts = {}) {
  return scenarios.map((sc) => {
    const r = runScenario(spec, sc, costModel, { ...opts, frames: false });
    return { scenario: sc, label: SCENARIOS[sc].label, evaluation: r.evaluation, events: r.sim.events, summary: r.sim.summary };
  });
}
