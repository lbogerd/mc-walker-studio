import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeSpec, runScenario, simulate, makeTerrain, evaluate, SCENARIOS } from '../src/sim/index.js';
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

test('planted feet do not slide while turning, stopping or reversing on flat', () => {
  for (const id of listWalkers()) {
    for (const sc of ['turn', 'startstop', 'reverse']) {
      const r = runScenario(load(id), sc, costModel);
      assert.ok(r.sim.summary.footErrMax < 1e-6, `${id} on ${sc} slips ${r.sim.summary.footErrMax}`);
      assert.equal(r.sim.summary.collisions, 0, `${id} collides on ${sc}`);
    }
  }
});

test('turning in place yaws the hull without moving it', () => {
  const spec = load('quad-strider');
  const r = runScenario(spec, 'turn', costModel);
  const m = r.sim.summary;
  assert.ok(m.yawTotalDeg > 180, `only turned ${m.yawTotalDeg}°`);
  assert.ok(m.netDisplacement < 1e-9, `hull drifted ${m.netDisplacement} b`);
  assert.ok(Math.abs(m.yawRateMaxDegS - m.maxYawRateDegS) < 1e-6, 'full steer reaches the max yaw rate');
  // max yaw rate = top speed / turn radius
  assert.ok(Math.abs(spec.maxYawRate - spec.speed / spec.turnRadius) < 1e-12);
  // turning in place must not fail the speed check: it is judged on top speed
  assert.equal(r.evaluation.rows.find((x) => x.key === 'speed').status, 'pass');
  // the feet actually step: footholds of one leg move around the hull
  const fh = r.sim.footholds[0];
  assert.ok(fh.length > 5);
});

test('start-stop ends stationary with every foot planted', () => {
  for (const id of listWalkers()) {
    const spec = load(id);
    const r = simulate(spec, makeTerrain('startstop'));
    const m = r.summary;
    assert.equal(m.endSpeed, 0, id);
    assert.equal(m.endPlanted, spec.legs.length, `${id} ends with ${m.endPlanted}/${spec.legs.length} feet down`);
    const last = r.frames.slice(-20);
    for (const f of last) {
      assert.ok(f.legs.every((l) => l.stance), `${id} has a foot in the air at tick ${f.t}`);
      // (hull sway is a low-pass filter, so allow it to converge at the 1e-6 b level)
      assert.ok(f.body.pos.every((v, i) => Math.abs(v - last[0].body.pos[i]) < 1e-6), `${id} hull still moving at tick ${f.t}`);
      assert.equal(f.phase, last[0].phase, `${id} gait clock still running at tick ${f.t}`);
    }
    // it also stood still in the middle (after braking) and then moved again
    assert.ok(r.series.speed.some((v, i) => v === 0 && i > 200 && i < 260), `${id} never stopped mid-course`);
    assert.ok(m.distance > 10, `${id} barely moved`);
  }
});

test('reverse walks backwards and the feet step backwards', () => {
  const spec = load('hexapod');
  const r = simulate(spec, makeTerrain('flat'), { commands: [{ ticks: 200, throttle: -1, steer: 0 }] });
  assert.ok(r.summary.netForward < -0.9 * spec.speed * 10, `net ${r.summary.netForward}`);
  assert.ok(r.summary.footErrMax < 1e-6);
  for (const fh of r.footholds) {
    for (let i = 1; i < fh.length; i++) assert.ok(fh[i].x < fh[i - 1].x, 'each new foothold is behind the previous one');
  }
  // and the course version: reverse first, then a forward curve
  const c = simulate(spec, makeTerrain('reverse'), { frames: false });
  assert.ok(c.summary.yawTotalDeg > 10);
});

test('acceleration is limited by gait.accel', () => {
  const raw = readJson(resolveWalker('quad-strider').path);
  const spec = normalizeSpec({ ...raw, gait: { ...raw.gait, accel: 1 } }, { sharedTargets: targets });
  const r = simulate(spec, makeTerrain('startstop'));
  const sp = r.series.speed;
  for (let i = 1; i < sp.length; i++) assert.ok(Math.abs(sp[i] - sp[i - 1]) <= 1 / 20 + 1e-9, `jump at ${i}`);
  assert.ok(Math.max(...sp) > spec.speed - 1e-9, 'still reaches top speed');
});

test('stabilizer: stability passes when the stabilizer can hold the walker', () => {
  const raw = readJson(resolveWalker('quad-strider').path);
  const mk = (stabilizer) => normalizeSpec({ ...raw, physics: { ...raw.physics, stabilizer } }, { sharedTargets: targets });
  const status = (spec) => {
    const r = runScenario(spec, 'flat', costModel);
    return { row: r.evaluation.rows.find((x) => x.key === 'margin'), sim: r.sim };
  };
  const none = status(mk(null));
  assert.ok(none.sim.summary.marginMin < 0, 'trot is not statically stable');
  assert.equal(none.row.status, 'fail');
  assert.equal(none.sim.summary.stabLoad, null);
  // required torque = mass × max(0, −margin)
  const spec = mk({ maxTorque: 1000 });
  assert.ok(Math.abs(none.sim.summary.torqueMax - spec.mass * -none.sim.summary.marginMin) < 1e-9);
  const big = status(spec);
  assert.equal(big.row.status, 'pass');
  assert.ok(big.sim.summary.stabLoad < 1);
  assert.ok(Math.abs(big.sim.summary.stabStress - big.sim.summary.torqueMax * 2) < 1e-9, 'default stressPerTorque is 2');
  const small = status(mk({ maxTorque: 10, stressPerTorque: 3 }));
  assert.equal(small.row.status, 'fail');
  assert.ok(small.sim.summary.stabLoad > 1);
  assert.equal(small.sim.summary.stabStress, 30, 'stress is capped at maxTorque × stressPerTorque');
  // a statically stable walker passes without one and needs no torque
  const hex = status(load('hexapod'));
  assert.equal(hex.row.status, 'pass');
  assert.equal(hex.sim.summary.torqueMax, 0);
});

test('legacy physics.support is rejected with a migration hint', () => {
  const raw = readJson(resolveWalker('quad-strider').path);
  const spec = normalizeSpec({ ...raw, physics: { support: 'kinematic' } }, { sharedTargets: targets });
  assert.ok(spec.errors.some((e) => e.includes('physics.support was removed') && e.includes('stabilizer')), spec.errors.join('\n'));
});

test('suspension: the actual hull lags the planned height on steps, not on flat', () => {
  const spec = load('quad-strider');
  assert.equal(runScenario(spec, 'flat', costModel).sim.summary.trackErrMax, 0);
  const stairs = runScenario(spec, 'stairs', costModel).sim.summary.trackErrMax;
  assert.ok(stairs > 0.01 && stairs < 1, `tracking error ${stairs}`);
  const raw = readJson(resolveWalker('quad-strider').path);
  const stiff = normalizeSpec({ ...raw, physics: { ...raw.physics, suspension: { frequency: 6, damping: 0.7 } } }, { sharedTargets: targets });
  assert.ok(runScenario(stiff, 'stairs', costModel).sim.summary.trackErrMax < stairs, 'stiffer suspension tracks better');
});

test('contraption legs are the default representation (D2)', () => {
  const raw = readJson(resolveWalker('hexapod').path);
  delete raw.implementation;
  assert.equal(normalizeSpec(raw).implementation.legRepresentation, 'contraption');
  for (const id of listWalkers()) assert.equal(load(id).implementation.legRepresentation, 'contraption', id);
});

// ---- regressions from review round 1 ----

test('suspension follower stays finite for overdamped and stiff settings', () => {
  const raw = readJson(resolveWalker('quad-strider').path);
  for (const suspension of [{ frequency: 10, damping: 3 }, { frequency: 10, damping: 10 }, { frequency: 0.2, damping: 0 }]) {
    const spec = normalizeSpec({ ...raw, physics: { ...raw.physics, suspension } }, { sharedTargets: targets });
    assert.deepEqual(spec.errors, []);
    const r = simulate(spec, makeTerrain('stairs'));
    for (const f of r.frames) assert.ok(f.body.pos.every(Number.isFinite), `non-finite hull at tick ${f.t} for ${JSON.stringify(suspension)}`);
    assert.ok(Number.isFinite(r.summary.bob) && Number.isFinite(r.summary.trackErrMax ?? 0));
  }
});

test('rest phase lies inside a window where that many feet really are planted', () => {
  const raw = readJson(resolveWalker('quad-strider').path);
  for (const duty of [0.501, 0.55, 0.6, 0.75]) {
    const spec = normalizeSpec({ ...raw, gait: { ...raw.gait, duty } }, { sharedTargets: targets });
    const planted = spec.legs.filter((l) => (((spec.gait.restPhase + l.phase) % 1) + 1) % 1 < duty).length;
    assert.equal(planted, spec.gait.restPlanted, `duty ${duty}: rest phase ${spec.gait.restPhase}`);
    // standing still from the start must end with that many feet planted
    const r = simulate(spec, makeTerrain('flat'), { commands: [{ ticks: 200, throttle: 0, steer: 0 }], ticks: 200 });
    assert.equal(r.frames.at(-1).legs.filter((l) => l.stance).length, spec.gait.restPlanted, `duty ${duty}`);
  }
});

test('foothold planning is causal: identical inputs up to a tick give identical frames up to it', () => {
  const spec = load('quad-strider');
  const prefix = [{ ticks: 100, throttle: 1, steer: 0 }];
  const a = simulate(spec, makeTerrain('flat'), { commands: [...prefix, { ticks: 200, throttle: 1, steer: 1 }], ticks: 300 });
  const b = simulate(spec, makeTerrain('flat'), { commands: [...prefix, { ticks: 200, throttle: 1, steer: -1 }], ticks: 300 });
  for (let t = 0; t <= 100; t++) {
    const fa = a.frames[t], fb = b.frames[t];
    assert.deepEqual(fb.body.pos, fa.body.pos, `hull differs at tick ${t}`);
    fa.legs.forEach((l, i) => assert.deepEqual(fb.legs[i].foot, l.foot, `leg ${i} foot differs at tick ${t}`));
  }
  // and the futures do diverge
  assert.notDeepEqual(a.frames[300].body.pos, b.frames[300].body.pos);
});
