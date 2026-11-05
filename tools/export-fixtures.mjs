#!/usr/bin/env node
// Writes the fixtures that prove the mod's Java walking loop (com.lbogerd.walkers.sim) reproduces src/sim.
//
//   npm run fixtures
//
// For every walker in walkers/ (not _template) and every course in SCENARIOS it writes
// mod/src/test/resources/sim-fixtures/<walker>__<scenario>.json.gz with:
//   raw        the walker JSON as written
//   scenario, seed, ticks, commands
//   spec       derived values of normalizeSpec (phases, timing, turn radius, mass, errors, warnings, stress)
//   frames     ticks 0..min(ticks, 600): every tick up to DENSE_TICKS, then every EVERY_NTH tick (now 1: all).
//              { t, phase, actual: [x, y, z, yaw, pitch, roll], planned: [...same], legs: [[foot xyz, knee xyz,
//              hip xyz, stance 0/1, err], ...] }
//   series     every per-tick series of the run (all ticks), for dense coverage past the frames
//   footholds  per leg: [k, x, y, z, void, adjusted, probes, yaw, tStart, tEnd, tDecided]
//   events     [{ type, t, leg, detail }]
//   summary    every summary number except refUsPerTick (wall-clock timing), plus eventCounts
// Plus spec-cases.json.gz: normalizeSpec on hand-written raw specs (valid and invalid) for the errors and
// warnings, and index.json listing every file.
//
// Rounding: finite numbers are rounded to 9 decimals (|error| ≤ 5e-10). Non-finite numbers are written as the
// strings "Infinity", "-Infinity" and "NaN" (JSON has no Infinity).
import { mkdirSync, writeFileSync, readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { normalizeSpec, simulate, makeTerrain, SCENARIOS, stress } from '../src/sim/index.js';
import { ROOT, listWalkers, readJson, resolveWalker } from './lib.mjs';

const OUT = join(ROOT, 'mod/src/test/resources/sim-fixtures');
const SEED = 7;
const MAX_FRAME_TICK = 600;
const DENSE_TICKS = 100;
const EVERY_NTH = 1;
const DIGITS = 1e9;

const r = (x) => {
  if (typeof x !== 'number') return x;
  if (!Number.isFinite(x)) return String(x);
  const v = Math.round(x * DIGITS) / DIGITS;
  return Object.is(v, -0) ? 0 : v;
};
const rv = (v) => v.map(r);
const nf = (x) => (typeof x === 'number' && !Number.isFinite(x) ? String(x) : x); // keep exact, encode ±Infinity

function specInfo(spec) {
  return {
    errors: spec.errors,
    warnings: spec.warnings,
    legs: spec.legs.map((l) => ({ id: l.id, mount: l.mount, neutral: l.neutral, segments: l.segments, knee: l.knee, phase: l.phase, side: l.side })),
    gait: {
      preset: spec.gait.preset, duty: spec.gait.duty, cycleTicks: spec.gait.cycleTicks, restPhase: spec.gait.restPhase,
      restPlanted: spec.gait.restPlanted, footholdSearch: spec.gait.footholdSearch, comShift: spec.gait.comShift ?? null,
      maxStepUp: spec.gait.maxStepUp ?? null,
    },
    body: { size: spec.body.size, rideHeight: spec.body.rideHeight, comOffset: spec.body.comOffset, blocks: spec.body.blocks },
    rpm: spec.drive.rpm,
    turnRadius: spec.turnRadius,
    speed: spec.speed,
    maxYawRate: spec.maxYawRate,
    mass: spec.mass,
    footholdProbesPerStep: spec.implementation.footholdProbesPerStep,
    legRepresentation: spec.implementation.legRepresentation,
    stabilizer: spec.physics.stabilizer,
    suspension: spec.physics.suspension,
    stress: stress(spec),
  };
}

function walkerFixture(id, raw, scenario) {
  const spec = normalizeSpec(raw, { id });
  const terrain = makeTerrain(scenario, SEED);
  const sim = simulate(spec, terrain);
  const frames = sim.frames
    .filter((f) => f.t <= MAX_FRAME_TICK && (f.t <= DENSE_TICKS || f.t % EVERY_NTH === 0))
    .map((f) => ({
      t: f.t,
      phase: r(f.phase),
      actual: rv([...f.body.pos, f.body.yaw, f.body.pitch, f.body.roll]),
      planned: rv([f.body.pos[0], f.body.plannedY, f.body.pos[2], f.body.yaw, f.body.plannedPitch, f.body.plannedRoll]),
      legs: f.legs.map((l) => [...rv(l.foot), ...rv(l.knee), ...rv(l.hip), l.stance ? 1 : 0, r(l.err)]),
    }));
  const series = Object.fromEntries(Object.entries(sim.series).map(([k, v]) => [k, v.map((x) => (x === null ? null : r(x)))]));
  const { refUsPerTick, eventCounts, ...nums } = sim.summary;
  return {
    raw,
    walker: id,
    scenario,
    seed: SEED,
    ticks: sim.ticks,
    commands: (terrain.commands ?? null)?.map((c) => ({ ...c, ticks: nf(c.ticks) })) ?? null,
    rounding: 1 / DIGITS,
    spec: specInfo(spec),
    frames,
    series,
    footholds: sim.footholds.map((l) => l.map((f) => [f.k, r(f.x), f.y, r(f.z), f.void ? 1 : 0, f.adjusted ? 1 : 0, f.probes,
      r(f.yaw), r(f.tStart), r(f.tEnd), r(f.tDecided)])),
    events: sim.events,
    summary: { ...Object.fromEntries(Object.entries(nums).map(([k, v]) => [k, v === null ? null : r(v)])), eventCounts },
  };
}

// Hand-written specs for normalizeSpec: defaults, presets, mirroring, and every error / warning message.
const quad = (gait = {}, extra = {}) => ({
  body: { size: [6, 2, 4] },
  legs: [
    { id: 'FL', mount: [2.5, 0, -2], neutral: [0.2, -1], mirror: true },
    { id: 'HL', mount: [-2.5, 0, -2], neutral: [-0.2, -1], mirror: true },
  ],
  gait,
  ...extra,
});
const hexLegs = [
  { id: 'L1', mount: [2.5, 0, -1.5], neutral: [0.5, -1.5], mirror: true },
  { id: 'L2', mount: [0, 0, -1.5], neutral: [0, -1.8], mirror: true },
  { id: 'L3', mount: [-2.5, 0, -1.5], neutral: [-0.5, -1.5], mirror: true },
];
const SPEC_CASES = {
  'empty object': {},
  'defaults quad': quad(),
  'wave quad, low duty': quad({ preset: 'wave', duty: 0.6 }),
  'wave quad': quad({ preset: 'wave', duty: 0.8 }),
  'ripple hexapod': { legs: hexLegs, gait: { preset: 'ripple', duty: 0.7 } },
  'pace hexapod': { legs: hexLegs, gait: { preset: 'pace', duty: 0.6 } },
  'bound hexapod': { legs: hexLegs, gait: { preset: 'bound', duty: 0.6 } },
  'alternating hexapod, coxa legs, knee out': {
    legDefaults: { segments: [1, 2.5, 3], knee: 'out' }, legs: hexLegs, gait: { duty: 0.55, footholdSearch: true },
  },
  'custom without phases': quad({ preset: 'custom' }),
  'custom with phases': {
    legs: [
      { id: 'A', mount: [1, 0, -1], phase: 0.1, mirror: true, mirrorPhase: 0.6, mirrorId: 'A2' },
      { id: 'front', mount: [2, 0, 1], phase: 0.3, mirror: true },
      { id: 'Rx', mount: [-2, 0, 1], mirror: true },
      { id: 'yL', mount: [-1, 0, 0], mirror: true },
    ],
    gait: { preset: 'custom', duty: 0.75 },
  },
  'bad gait values': quad({ preset: 'gallop', duty: 1.2, swing: 'loop', bodyAdapt: 'roll', accel: 0, settleRate: 2 }),
  'null gait values': quad({ comShift: null, maxStepUp: null, footholdSearch: null, cycleTicks: null }),
  'fast drive': quad({}, { drive: { rpm: 300 } }),
  'rpm and cycleTicks': quad({ cycleTicks: 30 }, { drive: { rpm: 48, revsPerCycle: 2 } }),
  'very short cycle': quad({ cycleTicks: 6 }),
  'cycleTicks only': quad({ cycleTicks: 50, stride: 2.5 }),
  'legacy support': quad({}, { physics: { support: 'kinematic' } }),
  'bad stabilizer': quad({}, { physics: { stabilizer: { maxTorque: 0, stressPerTorque: -1 } } }),
  'stabilizer not an object': quad({}, { physics: { stabilizer: 5 } }),
  'stabilizer defaults': quad({}, { physics: { stabilizer: { maxTorque: 50 } } }),
  'bad suspension': quad({}, { physics: { suspension: { frequency: 20, damping: -1 } } }),
  'bad leg representation': quad({}, { implementation: { legRepresentation: 'entity' } }),
  'bad legs': {
    legs: [
      { id: 'A', mount: [1, 0], neutral: [0, 1, 2], segments: [3], knee: 'sideways' },
      { id: 'A', mount: [1, 0, 0], segments: [3, -1] },
      { mount: [0, 0, 1], segments: [1, 2, 3, 4] },
    ],
  },
  'body overrides': { body: { size: [3, 1, 2], blocks: 4, comOffset: [0.5, 0, 0], rideHeight: 2 }, legs: [{ mount: [1, 0, 1] }] },
  'bad body': { body: { size: [3, 1], comOffset: 'centre' }, legs: [{ mount: [1, 0, 1] }] },
};

function specCases() {
  const cases = [];
  for (const id of listWalkers()) cases.push({ name: `walker ${id}`, id, raw: readJson(resolveWalker(id).path) });
  for (const [name, raw] of Object.entries(SPEC_CASES)) cases.push({ name, id: 'case', raw });
  return cases.map((c) => {
    try {
      return { ...c, result: specInfo(normalizeSpec(c.raw, { id: c.id })) };
    } catch (e) {
      return { ...c, throws: e.message };
    }
  });
}

const json = (o) => JSON.stringify(o);
mkdirSync(OUT, { recursive: true });
for (const f of readdirSync(OUT)) if (f.endsWith('.json.gz') || f === 'index.json') rmSync(join(OUT, f));
const files = [];
let total = 0;
const write = (name, obj) => {
  const buf = gzipSync(json(obj), { level: 9 });
  writeFileSync(join(OUT, name), buf);
  files.push(name);
  total += buf.length;
};
for (const id of listWalkers()) {
  const raw = readJson(resolveWalker(id).path);
  for (const sc of Object.keys(SCENARIOS)) write(`${id}__${sc}.json.gz`, walkerFixture(id, raw, sc));
}
write('spec-cases.json.gz', { cases: specCases() });
writeFileSync(join(OUT, 'index.json'), JSON.stringify({ files }, null, 1) + '\n');
console.log(`Wrote ${files.length} fixtures to ${OUT} (${(total / 1024 / 1024).toFixed(2)} MB)`);
