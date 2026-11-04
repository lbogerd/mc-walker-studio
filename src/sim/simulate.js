// Kinematic walking-loop simulation at Minecraft tick resolution (20 TPS).
//
// Model (deliberately simple, see docs/model.md):
//  * The body moves forward (+x) at constant speed = stride / cycle.
//  * Each leg cycles stance → swing. A foothold is planned for every stance so that the foot sits
//    at its neutral position under the hip at mid-stance. Footholds are snapped onto the block
//    grid's surface and optionally moved by a foothold search (avoid edges / void).
//  * Body height (and optionally pitch/roll) follows the average ground under the legs.
//  * Each leg is solved with analytic 2-bone IK (+ optional coxa), with a pole hint for the knee.
//  * Everything else (stability, reach, collisions, joint speed) is measured, not enforced.
import {
  TPS, clamp, lerp, frac, smoothstep, add, sub, scale, dot, len, dist, norm, deg,
  bodyToWorld, worldToBody, supportMargin, hullMargin, convexHull,
} from './math.js';
import { VOID } from './terrain.js';

const now = () => (globalThis.performance ? performance.now() : Date.now());

function prepLeg(leg, spec) {
  const segs = leg.segments;
  const coxa = segs.length === 3 ? segs[0] : 0;
  const femur = segs[segs.length - 2];
  const tibia = segs[segs.length - 1];
  const side = leg.side;
  const hints = {
    forward: [1, 0, 0],
    back: [-1, 0, 0],
    up: [0, 1, 0],
    down: [0, -1, 0],
    out: norm([0, 0.8, side * 0.6]),
  };
  return {
    ...leg,
    coxa, femur, tibia,
    hint: hints[leg.knee],
    footLocal: [leg.mount[0] + leg.neutral[0], 0, leg.mount[2] + leg.neutral[1]],
    outward: [0, 0, side],
  };
}

function edgeDistance(terrain, x, z, h) {
  const ix = Math.floor(x), iz = Math.floor(z);
  const fx = x - ix, fz = z - iz;
  let d = 1;
  if (terrain.column(ix - 1, iz) !== h) d = Math.min(d, fx);
  if (terrain.column(ix + 1, iz) !== h) d = Math.min(d, 1 - fx);
  if (terrain.column(ix, iz - 1) !== h) d = Math.min(d, fz);
  if (terrain.column(ix, iz + 1) !== h) d = Math.min(d, 1 - fz);
  return d;
}

function planFoothold(terrain, x, z, search) {
  const h0 = terrain.heightAt(x, z);
  if (!search) return { x, y: h0, z, void: h0 <= VOID, adjusted: false, probes: 1 };
  const r = search.radius ?? 1;
  const step = search.step ?? 0.25;
  const edgeClear = search.edgeClearance ?? 0.3;
  let best = null, probes = 0;
  for (let dz = -r / 2; dz <= r / 2 + 1e-9; dz += r / 2) {
    for (let dx = -r; dx <= r + 1e-9; dx += step) {
      probes++;
      const cx = x + dx, cz = z + dz;
      const h = terrain.heightAt(cx, cz);
      if (h <= VOID) continue;
      const e = edgeDistance(terrain, cx, cz, h);
      const score = Math.hypot(dx, dz) + (e < edgeClear ? (edgeClear - e) * 8 : 0);
      if (!best || score < best.score) best = { x: cx, y: h, z: cz, score };
    }
  }
  if (!best) return { x, y: h0, z, void: true, adjusted: false, probes };
  return { x: best.x, y: best.y, z: best.z, void: false, adjusted: Math.abs(best.x - x) + Math.abs(best.z - z) > 1e-6, probes };
}

// Swing trajectory from A to B at normalised swing time s ∈ [0,1].
function swingPoint(A, B, s, gait, terrain) {
  let hx, lift;
  if (gait.swing === 'box') {
    hx = smoothstep((s - 0.25) / 0.5);
    lift = smoothstep(s / 0.25) * smoothstep((1 - s) / 0.25);
  } else {
    hx = smoothstep(s);
    lift = Math.sin(Math.PI * s);
  }
  // apex clears the highest column between the two footholds by stepHeight
  let top = Math.max(A.void ? -Infinity : A.y, B.void ? -Infinity : B.y);
  const n = Math.max(2, Math.ceil(Math.abs(B.x - A.x) * 2));
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const h = terrain.heightAt(lerp(A.x, B.x, t), lerp(A.z, B.z, t));
    if (h > VOID) top = Math.max(top, h);
  }
  if (!Number.isFinite(top)) top = 0;
  const apex = top + gait.stepHeight;
  const base = lerp(A.y, B.y, hx);
  return { pos: [lerp(A.x, B.x, hx), lerp(base, apex, lift), lerp(A.z, B.z, hx)], hx };
}

function solveLeg(leg, targetL) {
  const hip = leg.mount;
  let base = hip;
  if (leg.coxa > 0) {
    const d = sub(targetL, hip);
    let hd = norm([d[0], 0, d[2]]);
    if (len(hd) < 0.5) hd = leg.outward;
    base = add(hip, scale(hd, leg.coxa));
  }
  const a = leg.femur, b = leg.tibia;
  const toT = sub(targetL, base);
  const d = len(toT);
  const dir = d > 1e-6 ? scale(toT, 1 / d) : [0, -1, 0];
  const maxR = a + b - 1e-4, minR = Math.abs(a - b) + 1e-4;
  let status = 'ok', r = d;
  if (d > maxR) { r = maxR; status = 'over'; }
  else if (d < minR) { r = minR; status = 'under'; }
  const foot = add(base, scale(dir, r));
  const cosA = clamp((a * a + r * r - b * b) / (2 * a * r), -1, 1);
  const along = a * cosA;
  const perp = Math.sqrt(Math.max(0, a * a - along * along));
  let pole = sub(leg.hint, scale(dir, dot(leg.hint, dir)));
  if (len(pole) < 1e-3) pole = sub([0, 1, 0], scale(dir, dot([0, 1, 0], dir)));
  if (len(pole) < 1e-3) pole = [1, 0, 0];
  pole = norm(pole);
  const knee = add(base, add(scale(dir, along), scale(pole, perp)));
  return { base, knee, foot, status, util: d / (a + b) };
}

function jointAngles(hip, base, knee, foot) {
  const toFoot = sub(foot, hip);
  const yaw = Math.atan2(toFoot[2], toFoot[0]);
  const femurV = sub(knee, base);
  const tibiaV = sub(foot, knee);
  const pitch = Math.atan2(femurV[1], Math.hypot(femurV[0], femurV[2]));
  const kneeA = Math.acos(clamp(dot(norm(femurV), norm(tibiaV)), -1, 1));
  return [yaw, pitch, kneeA];
}

/** Default run length: long enough to cover `distance` blocks and at least 4 gait cycles. */
export function defaultTicks(spec, distance = 60) {
  const T = spec.gait.cycleTicks;
  const v = spec.gait.stride / T;
  return clamp(Math.ceil(Math.max(4 * T, distance / Math.max(v, 1e-3))), 40, 4000);
}

/**
 * Run the walking loop.
 * @returns {{frames, footholds, events, series, summary, ticks, cycleTicks}}
 */
export function simulate(spec, terrain, opts = {}) {
  const T = spec.gait.cycleTicks;
  const duty = spec.gait.duty;
  const v = spec.gait.stride / T; // blocks / tick
  const warm = Math.ceil(T * 2);
  const ticks = opts.ticks ?? defaultTicks(spec, opts.distance);
  const x0 = opts.startX ?? 0;
  const legs = spec.legs.map((l) => prepLeg(l, spec));
  const search = spec.gait.footholdSearch;
  const xBodyAt = (t) => x0 + v * t;

  // ---- foothold planner (memoised per leg & stance index) ----
  const fhCache = legs.map(() => new Map());
  let probeCount = 0;
  const foothold = (li, k) => {
    let f = fhCache[li].get(k);
    if (f) return f;
    const leg = legs[li];
    const tStart = (k - leg.phase) * T;
    const tMid = tStart + (duty * T) / 2;
    f = planFoothold(terrain, xBodyAt(tMid) + leg.footLocal[0], leg.footLocal[2], search);
    probeCount += f.probes;
    f.k = k;
    f.tStart = tStart;
    f.tEnd = tStart + duty * T;
    fhCache[li].set(k, f);
    return f;
  };

  const events = [];
  const seen = new Set();
  const emit = (type, t, legId, detail, key) => {
    const k = `${type}|${legId}|${key}`;
    if (seen.has(k)) return;
    seen.add(k);
    events.push({ type, t, leg: legId, detail });
  };

  const frames = [];
  const series = { margin: [], bodyY: [], reach: [], footErr: [], stance: [], clearance: [] };
  const sideGroups = [1, -1].map((s) => legs.map((l, i) => [l, i]).filter(([l]) => l.side === s).sort((a, b) => b[0].mount[0] - a[0].mount[0]));

  let bodyY = null, pitch = 0, roll = 0, shiftX = 0, shiftZ = 0;
  const comShift = clamp(spec.gait.comShift ?? 0, 0, 1);
  const horizon = (1 - duty) * T + 2;
  let prevAngles = null;
  let jointSpeedMax = 0, reachMax = 0, footErrMax = 0, marginMin = Infinity, stanceMin = Infinity;
  let clearanceMin = Infinity, pitchMax = 0, vyMax = 0;
  let solveTime = 0, solveTicks = 0;
  const comLocal = add([0, spec.body.size[1] / 2, 0], spec.body.comOffset);
  const [sx, , sz] = spec.body.size;
  const bottomSamples = [];
  for (let ix = 0; ix <= Math.ceil(sx); ix++)
    for (let iz = 0; iz <= Math.ceil(sz); iz++)
      bottomSamples.push([-sx / 2 + (ix / Math.ceil(sx)) * sx, 0, -sz / 2 + (iz / Math.ceil(sz)) * sz]);

  for (let t = -warm; t <= ticks; t++) {
    const t0 = now();
    // 1. leg targets in world space
    const legState = legs.map((leg, li) => {
      const u = t / T + leg.phase;
      const k = Math.floor(u);
      const phi = frac(u);
      const A = foothold(li, k);
      if (phi < duty) {
        return { stance: true, target: [A.x, A.y, A.z], ground: A.void ? null : A.y, fh: A, next: null, k, s: null };
      }
      const B = foothold(li, k + 1);
      const s = (phi - duty) / (1 - duty);
      const sp = swingPoint(A, B, s, spec.gait, terrain);
      const ga = A.void ? B.y : A.y, gb = B.void ? A.y : B.y;
      return { stance: false, target: sp.pos, ground: A.void && B.void ? null : lerp(ga, gb, sp.hx), fh: A, next: B, k, s };
    });

    // 2. body pose follows the ground under the legs
    const grounds = legState.map((s, i) => [s.ground, legs[i]]).filter(([g]) => g !== null);
    const gMean = grounds.length ? grounds.reduce((a, [g]) => a + g, 0) / grounds.length : 0;
    const yTarget = gMean + spec.body.rideHeight;
    let pTarget = 0, rTarget = 0;
    if (spec.gait.bodyAdapt === 'pitch' && grounds.length >= 2) {
      const fit = (axis) => {
        const xs = grounds.map(([, l]) => l.footLocal[axis]);
        const mx = xs.reduce((a, b) => a + b, 0) / xs.length;
        let num = 0, den = 0;
        grounds.forEach(([g, l], i) => { num += (xs[i] - mx) * (g - gMean); den += (xs[i] - mx) ** 2; });
        return den > 1e-6 ? Math.atan(num / den) : 0;
      };
      pTarget = clamp(fit(0), -0.45, 0.45);
      rTarget = -clamp(fit(2), -0.3, 0.3);
    }
    const kS = clamp(spec.gait.bodySmoothing, 0.01, 1);
    const prevY = bodyY;
    if (bodyY === null) bodyY = yTarget;
    else bodyY += (yTarget - bodyY) * kS;
    pitch += (pTarget - pitch) * kS;
    roll += (rTarget - roll) * kS;
    // optional sway: shift the hull toward the point that is safest for the support polygons
    // of the next swing window (coarse→fine grid search). This is what makes crawl gaits stable.
    if (comShift > 0) {
      const sets = [];
      for (let j = 0; j <= 3; j++) {
        const tt = t + (j / 3) * horizon;
        const pts = [];
        legs.forEach((leg, li) => {
          const u = tt / T + leg.phase;
          if (frac(u) < duty) {
            const f = foothold(li, Math.floor(u));
            if (!f.void) pts.push([f.x, f.z]);
          }
        });
        if (pts.length >= 3) sets.push(convexHull(pts));
      }
      if (sets.length) {
        const c0 = [xBodyAt(t) + comLocal[0], comLocal[2]];
        const score = (dx, dz) => {
          const p = [c0[0] + dx, c0[1] + dz];
          let m = Infinity;
          for (const h of sets) m = Math.min(m, hullMargin(p, h));
          return m - 0.02 * Math.hypot(dx, dz);
        };
        let best = [0, 0], bestS = score(0, 0);
        for (const [stepSize, span, cx, cz] of [[0.5, 2, 0, 0], [0.125, 0.375, null, null]]) {
          const ox = cx ?? best[0], oz = cz ?? best[1];
          for (let dx = -span; dx <= span + 1e-9; dx += stepSize)
            for (let dz = -span; dz <= span + 1e-9; dz += stepSize) {
              const sc = score(ox + dx, oz + dz);
              if (sc > bestS) { bestS = sc; best = [ox + dx, oz + dz]; }
            }
        }
        shiftX += (best[0] * comShift - shiftX) * kS;
        shiftZ += (best[1] * comShift - shiftZ) * kS;
      }
    }
    const pose = { pos: [xBodyAt(t) + shiftX, bodyY, shiftZ], pitch, roll };

    // 3. IK per leg
    const legOut = legState.map((st, li) => {
      const leg = legs[li];
      const tl = worldToBody(pose, st.target);
      const sol = solveLeg(leg, tl);
      return {
        st, sol,
        hip: bodyToWorld(pose, leg.mount),
        base: bodyToWorld(pose, sol.base),
        knee: bodyToWorld(pose, sol.knee),
        foot: bodyToWorld(pose, sol.foot),
      };
    });
    solveTime += now() - t0;
    solveTicks++;
    if (t < 0) continue;

    // 4. measurements
    const support = [];
    let reach = 0, ferr = 0, stanceN = 0;
    const angles = [];
    legOut.forEach((o, li) => {
      const leg = legs[li], st = o.st;
      const err = dist(o.foot, st.target);
      reach = Math.max(reach, o.sol.util);
      ferr = Math.max(ferr, st.stance ? err : 0);
      if (st.stance && !st.fh.void) {
        stanceN++;
        support.push([o.foot[0], o.foot[2]]);
      }
      if (st.stance && st.fh.void) emit('void-step', t, leg.id, 'Foot planted over the void (no ground)', st.k);
      if (o.sol.status === 'over' && err > 0.05 && !(st.stance && st.fh.void))
        emit('overreach', t, leg.id, `Target ${err.toFixed(2)} b beyond reach`, st.k);
      if (o.sol.status === 'under' && err > 0.05) emit('overcompress', t, leg.id, `Target ${err.toFixed(2)} b inside min reach`, st.k);
      if (!st.stance && st.s > 0.08 && st.s < 0.92) {
        const h = terrain.heightAt(o.foot[0], o.foot[2]);
        if (h > VOID && o.foot[1] < h - 0.05) emit('toe-stub', t, leg.id, 'Swinging foot passes through a block', st.k);
      }
      const kh = terrain.heightAt(o.knee[0], o.knee[2]);
      if (kh > VOID && o.knee[1] < kh - 0.05) emit('knee-clip', t, leg.id, 'Knee inside terrain', st.k);
      if (!st.stance && st.s < 0.05 && st.next && spec.gait.maxStepUp != null) {
        const A = st.fh, B = st.next;
        if (!A.void && !B.void && B.y - A.y > spec.gait.maxStepUp)
          emit('step-too-high', t, leg.id, `Step of ${B.y - A.y} b exceeds maxStepUp ${spec.gait.maxStepUp}`, st.k);
      }
      angles.push(...jointAngles(o.hip, o.base, o.knee, o.foot));
    });
    // foot clash between neighbours on the same side
    for (const group of sideGroups) {
      for (let g = 0; g + 1 < group.length; g++) {
        const a = legOut[group[g][1]].foot, b = legOut[group[g + 1][1]].foot;
        if (Math.hypot(a[0] - b[0], a[2] - b[2]) < 0.6)
          emit('foot-clash', t, `${group[g][0].id}/${group[g + 1][0].id}`, 'Neighbouring feet closer than 0.6 b', Math.floor(t / T));
      }
    }
    if (prevAngles) {
      for (let i = 0; i < angles.length; i++) {
        let d = Math.abs(angles[i] - prevAngles[i]);
        if (d > Math.PI) d = 2 * Math.PI - d;
        jointSpeedMax = Math.max(jointSpeedMax, d);
      }
    }
    prevAngles = angles;

    const comW = bodyToWorld(pose, comLocal);
    const { margin, hull } = supportMargin([comW[0], comW[2]], support);
    let clearance = Infinity;
    for (const p of bottomSamples) {
      const w = bodyToWorld(pose, p);
      const h = terrain.heightAt(w[0], w[2]);
      if (h > VOID) clearance = Math.min(clearance, w[1] - h);
    }
    if (clearance < 0) emit('body-scrape', t, 'body', `Hull ${(-clearance).toFixed(2)} b into terrain`, Math.floor(t / T));

    marginMin = Math.min(marginMin, margin);
    stanceMin = Math.min(stanceMin, stanceN);
    reachMax = Math.max(reachMax, reach);
    footErrMax = Math.max(footErrMax, ferr);
    clearanceMin = Math.min(clearanceMin, clearance);
    pitchMax = Math.max(pitchMax, Math.abs(pitch), Math.abs(roll));
    if (prevY !== null) vyMax = Math.max(vyMax, Math.abs(bodyY - prevY));

    series.margin.push(margin);
    series.bodyY.push(bodyY);
    series.reach.push(reach);
    series.footErr.push(ferr);
    series.stance.push(stanceN);
    series.clearance.push(Number.isFinite(clearance) ? clearance : null);

    if (opts.frames !== false) frames.push({
      t,
      body: { pos: pose.pos, pitch, roll },
      com: comW,
      hull,
      margin,
      legs: legOut.map((o) => ({
        hip: o.hip, base: o.base, knee: o.knee, foot: o.foot,
        stance: o.st.stance,
        err: dist(o.foot, o.st.target),
        next: o.st.next && !o.st.next.void ? [o.st.next.x, o.st.next.y, o.st.next.z] : null,
      })),
    });
  }

  // bob = spread of body height after removing the one-cycle moving average (terrain trend)
  const win = Math.max(1, Math.round(T));
  const ys = series.bodyY;
  const hp = [];
  let acc = 0;
  for (let i = 0; i < ys.length; i++) {
    acc += ys[i];
    if (i >= win) acc -= ys[i - win];
    if (i >= win) hp.push(ys[i - Math.floor(win / 2)] - acc / win);
  }
  const bob = hp.length ? Math.max(...hp) - Math.min(...hp) : 0;

  const footholds = legs.map((leg, li) =>
    [...fhCache[li].values()].filter((f) => f.tStart <= ticks && f.tEnd >= 0).sort((a, b) => a.k - b.k));

  const counts = {};
  for (const e of events) counts[e.type] = (counts[e.type] || 0) + 1;

  return {
    ticks,
    cycleTicks: T,
    frames,
    footholds,
    events: events.sort((a, b) => a.t - b.t),
    series,
    summary: {
      speed: v * TPS,
      distance: v * ticks,
      marginMin,
      stanceMin,
      reachMax,
      footErrMax,
      clearanceMin,
      bob,
      vyMax: vyMax * TPS,
      pitchMaxDeg: deg(pitchMax),
      jointSpeedMaxDegS: deg(jointSpeedMax) * TPS,
      eventCounts: counts,
      collisions: events.filter((e) => COLLISION_TYPES.includes(e.type)).length,
      refUsPerTick: (solveTime / Math.max(1, solveTicks)) * 1000,
      footholdProbesPerTick: probeCount / (ticks + warm),
    },
  };
}

export const COLLISION_TYPES = ['toe-stub', 'knee-clip', 'body-scrape', 'void-step', 'foot-clash', 'step-too-high'];
