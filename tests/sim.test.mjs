import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeSpec, runScenario, simulate, makeTerrain, SCENARIOS } from '../src/sim/index.js';
import { loadConfig, listWalkers, readJson, resolveWalker } from '../tools/lib.mjs';

const { costModel, targets } = loadConfig();
const load = (id) => normalizeSpec(readJson(resolveWalker(id).path), { id, sharedTargets: targets });

test('every walker in walkers/ is a valid spec', () => {
  for (const id of listWalkers()) assert.deepEqual(load(id).errors, [], id);
});

test('speed follows stride / cycle and RPM drives the cycle', () => {
  const s = load('quad-strider');
  assert.equal(s.gait.cycleTicks, 1200 / 48);
  assert.ok(Math.abs(s.speed - (4 / 25) * 20) < 1e-9);
});

test('planted feet do not slide on flat ground', () => {
  for (const id of listWalkers()) {
    const r = runScenario(load(id), 'flat', costModel);
    assert.ok(r.sim.summary.footErrMax < 1e-6, `${id} slips ${r.sim.summary.footErrMax}`);
    assert.equal(r.sim.summary.collisions, 0, `${id} collides on flat`);
  }
});

test('alternating gait puts diagonal quad legs in phase', () => {
  const s = load('quad-strider');
  const ph = Object.fromEntries(s.legs.map((l) => [l.id, l.phase]));
  assert.equal(ph.FL, ph.HR);
  assert.equal(ph.FR, ph.HL);
  assert.notEqual(ph.FL, ph.FR);
});

test('tripod hexapod is statically stable on flat', () => {
  const r = runScenario(load('hexapod'), 'flat', costModel);
  assert.ok(r.sim.summary.marginMin > 0.3);
  assert.ok(r.sim.summary.stanceMin >= 3);
});

test('foothold search avoids a trench that raw placement falls into', () => {
  const raw = readJson(resolveWalker('hexapod').path);
  const withSearch = normalizeSpec({ ...raw, gait: { ...raw.gait, footholdSearch: { radius: 2 } } }, { sharedTargets: targets });
  const without = normalizeSpec({ ...raw, gait: { ...raw.gait, footholdSearch: null } }, { sharedTargets: targets });
  const a = simulate(withSearch, makeTerrain('gap'));
  const b = simulate(without, makeTerrain('gap'));
  assert.equal(a.summary.eventCounts['void-step'] ?? 0, 0);
  assert.ok((b.summary.eventCounts['void-step'] ?? 0) > 0);
});

test('all scenarios build', () => {
  for (const k of Object.keys(SCENARIOS)) assert.equal(typeof makeTerrain(k).heightAt(20.5, 0.5), 'number');
});
