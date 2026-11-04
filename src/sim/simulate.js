// Kinematic walking-loop simulation at Minecraft tick resolution (20 TPS).
//
// Model (deliberately simple, see docs/model.md):
//  * Pass 1 (planPath): the course's command timeline (throttle, steer) is integrated into a hull path
//    (x, z, yaw) and a gait clock Φ per tick. Commands are saturated so no foot moves over the ground
//    faster than the top speed, and rate-limited by gait.accel. Φ advances at max(activity, settleRate)
//    / cycleTicks; when stopped it runs on until every foot is planted and then freezes.
//  * Pass 2 (per tick): each leg cycles stance → swing on Φ. A foothold is planned for every stance
//    so that the foot sits at its neutral position under the hip at mid-stance, using the predicted
//    hull pose (position + yaw) at that moment. Footholds are snapped onto the block grid's surface
//    and optionally moved by a foothold search (avoid edges / void).
//  * Planned body height (and optionally pitch/roll) follows the average ground under the legs. The
//    actual hull follows the planned one as a damped spring (physics.suspension, decision D1).
//  * Each leg is solved with analytic 2-bone IK (+ optional coxa), with a pole hint for the knee.
//  * Everything else (stability, reach, collisions, joint speed) is measured, not enforced.
import {
  TPS, clamp, lerp, frac, smoothstep, add, sub, scale, dot, len, dist, norm, deg,
  bodyToWorld, worldToBody, supportMargin, hullMargin, convexHull, heading,
} from './math.js';
import { VOID, commandAt, commandTicks } from './terrain.js';

const now = () => (globalThis.performance ? performance.now() : Date.now());
// The gait clock is integrated tick by tick, so exact touchdown instants (Φ + phase = integer) can
// land a hair below the integer. Nudge before flooring so constant-speed runs match t / cycleTicks.
const EPS = 1e-9;

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

// Search offsets are in the hull's frame (dx along its heading), rotated into the world by yaw.
function planFoothold(terrain, x, z, search, yaw = 0) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw);
  const h0 = terrain.heightAt(x, z);
  if (!search) return { x, y: h0, z, void: h0 <= VOID, adjusted: false, probes: 1 };
  const r = search.radius ?? 1;
  const step = search.step ?? 0.25;
  const edgeClear = search.edgeClearance ?? 0.3;
  let best = null, probes = 0;
  for (let dz = -r / 2; dz <= r / 2 + 1e-9; dz += r / 2) {
    for (let dx = -r; dx <= r + 1e-9; dx += step) {
      probes++;
      const cx = x + dx * cy + dz * sy, cz = z - dx * sy + dz * cy;
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
  const n = Math.max(2, Math.ceil(Math.hypot(B.x - A.x, B.z - A.z) * 2));
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

// Hip yaw is the coxa direction (3-segment legs) or the orientation of the leg plane (2-segment legs).
// The bearing hip → foot is not used: it spins wildly when the foot passes under the hip.
function jointAngles(leg, hip, base, knee, foot) {
  let yaw;
  if (leg.coxa > 0) yaw = Math.atan2(base[2] - hip[2], base[0] - hip[0]);
  else {
    const a = sub(foot, base), b = sub(knee, base);
    yaw = Math.atan2(a[0] * b[1] - a[1] * b[0], a[1] * b[2] - a[2] * b[1]); // plane normal (a × b), z over x
  }
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

/** Run length for a course: its command timeline if it has one, else defaultTicks. */
export function courseTicks(spec, commands, distance) {
  const n = commands ? commandTicks(commands) : Infinity;
  return Number.isFinite(n) ? n : defaultTicks(spec, distance);
}

/**
 * Pass 1: integrate the driver commands into a hull path and gait clock.
 *
 *  vTop  = stride / cycleTicks                 (b/tick, top speed at the drive RPM, D7)
 *  ωTop  = vTop / turnRadius                   (rad/tick, turning in place at full steer)
 *  target (v, ω) = (throttle · vTop, steer · ωTop), then scaled down together so the fastest foot
 *         speed max_i |(v + ω·z_i, −ω·x_i)| stays ≤ vTop (outer legs set the limit when curving)
 *  v, ω move toward the target by at most accel/20² b/tick² and (accel/turnRadius)/20² rad/tick²
 *  activity a = max(|v|/vTop, |ω|/ωTop, footSpeedMax/vTop) ∈ [0, 1]
 *  dΦ/dt = max(a, settleRate) / cycleTicks while moving; when stopped (v = ω = 0, no command) Φ runs on
 *         at settleRate until the rest stance is reached (all feet planted) and then freezes.
 */
function planPath(spec, legs, commands, tA, tB, x0) {
  const T = spec.gait.cycleTicks;
  const duty = spec.gait.duty;
  const vTop = spec.gait.stride / T;
  const R = spec.turnRadius;
  const wTop = vTop / R;
  const aLin = spec.gait.accel / (TPS * TPS);
  const aYaw = aLin / R;
  const settle = spec.gait.settleRate;
  const feet = legs.map((l) => [l.footLocal[0], l.footLocal[2]]);
  const footSpeed = (v, w) => feet.reduce((m, [px, pz]) => Math.max(m, Math.hypot(v + w * pz, w * px)), 0);
  const saturate = (v, w) => {
    const f = footSpeed(v, w);
    const k = f > vTop ? vTop / f : 1;
    return [v * k, w * k];
  };
  const activity = (v, w) => clamp(Math.max(Math.abs(v) / vTop, Math.abs(w) / wTop, footSpeed(v, w) / vTop), 0, 1);
  const planted = (P) => legs.reduce((a, l) => a + (frac(P + l.phase + EPS) < duty ? 1 : 0), 0);
  const restN = spec.gait.restPlanted ?? legs.length; // feet planted in the rest stance
  const target = (t) => {
    const c = commandAt(commands, t);
    return saturate(clamp(c.throttle, -1, 1) * vTop, clamp(c.steer, -1, 1) * wTop);
  };
  const towards = (x, goal, step) => (Math.abs(goal - x) <= step ? goal : x + Math.sign(goal - x) * step);

  const N = tB - tA + 1;
  const X = new Float64Array(N), Z = new Float64Array(N), YAW = new Float64Array(N), PH = new Float64Array(N);
  const V = new Float64Array(N), W = new Float64Array(N), ACT = new Float64Array(N);
  const i0 = -tA; // index of tick 0
  // before tick 0 the walker is in steady state on the first command
  const [v0, w0] = target(Math.min(-1, tA));
  const moving0 = v0 !== 0 || w0 !== 0;
  const rate0 = moving0 ? Math.max(activity(v0, w0), settle) / T : 0;
  X[i0] = x0; Z[i0] = 0; YAW[i0] = 0; PH[i0] = moving0 ? 0 : spec.gait.restPhase;
  V[i0] = v0; W[i0] = w0; ACT[i0] = moving0 ? activity(v0, w0) : 0;
  for (let i = i0 - 1; i >= 0; i--) {
    YAW[i] = YAW[i + 1] - w0;
    const h = heading(YAW[i] + w0 / 2);
    X[i] = X[i + 1] - v0 * h[0];
    Z[i] = Z[i + 1] - v0 * h[2];
    PH[i] = PH[i + 1] - rate0;
    V[i] = v0; W[i] = w0; ACT[i] = ACT[i0];
  }
  let v = v0, w = w0;
  for (let i = i0; i < N - 1; i++) {
    const t = tA + i;
    const [tv, tw] = target(t);
    [v, w] = saturate(towards(v, tv, aLin), towards(w, tw, aYaw));
    const h = heading(YAW[i] + w / 2);
    YAW[i + 1] = YAW[i] + w;
    X[i + 1] = X[i] + v * h[0];
    Z[i + 1] = Z[i] + v * h[2];
    let dP;
    if (v === 0 && w === 0 && tv === 0 && tw === 0) {
      // stopped: run on to the rest stance (never past the middle of the rest window), then freeze
      if (planted(PH[i]) >= restN) dP = 0;
      else {
        const goal = PH[i] + frac(spec.gait.restPhase - PH[i]);
        dP = Math.min(settle / T, goal - PH[i]);
      }
    } else dP = Math.max(activity(v, w), settle) / T;
    PH[i + 1] = PH[i] + dP;
    V[i + 1] = v; W[i + 1] = w; ACT[i + 1] = activity(v, w);
  }
  const rateEnd = N > 1 ? PH[N - 1] - PH[N - 2] : 0;

  const idx = (t) => t - tA;
  /** Hull pose { x, z, yaw } at fractional tick tf (extrapolated outside the planned range). */
  const poseAt = (tf) => {
    if (!(tf > tA)) {
      if (!Number.isFinite(tf) || tf === tA) return { x: X[0], z: Z[0], yaw: YAW[0] };
      const d = tf - tA, yaw = YAW[0] + w0 * d, hh = heading(YAW[0] + (w0 * d) / 2);
      return { x: X[0] + v0 * d * hh[0], z: Z[0] + v0 * d * hh[2], yaw };
    }
    if (tf >= tB) {
      const j = N - 1;
      if (!Number.isFinite(tf)) return { x: X[j], z: Z[j], yaw: YAW[j] };
      const d = tf - tB, vE = V[j], wE = W[j], hh = heading(YAW[j] + (wE * d) / 2);
      return { x: X[j] + vE * d * hh[0], z: Z[j] + vE * d * hh[2], yaw: YAW[j] + wE * d };
    }
    const f = tf - tA, j = Math.floor(f), a = f - j;
    return { x: lerp(X[j], X[j + 1], a), z: lerp(Z[j], Z[j + 1], a), yaw: lerp(YAW[j], YAW[j + 1], a) };
  };
  /** Gait clock at fractional tick tf. */
  const phaseAt = (tf) => {
    if (tf <= tA) return PH[0] + rate0 * (tf - tA);
    if (tf >= tB) return PH[N - 1] + rateEnd * (tf - tB);
    const f = tf - tA, j = Math.floor(f);
    return lerp(PH[j], PH[j + 1], f - j);
  };
  /** First (fractional) tick at which the gait clock reaches P; ±Infinity if it never does. */
  const timeAtPhase = (P) => {
    if (P <= PH[0]) return P === PH[0] ? tA : rate0 > 0 ? tA - (PH[0] - P) / rate0 : -Infinity;
    if (P > PH[N - 1]) return rateEnd > 0 ? tB + (P - PH[N - 1]) / rateEnd : Infinity;
    let lo = 0, hi = N - 1; // first index with PH >= P
    while (lo < hi) { const m = (lo + hi) >> 1; if (PH[m] >= P) hi = m; else lo = m + 1; }
    return tA + lo - 1 + (P - PH[lo - 1]) / (PH[lo] - PH[lo - 1]);
  };
  /**
   * Causal prediction (what a live controller can know): from the state at integer tick tk, hold the command
   * given at tk and integrate forward with the same accel limits until the gait clock reaches goalPhase.
   * Returns the predicted pose there. If the clock stops first (walker settles), returns where it stops.
   */
  const predict = (tk, goalPhase) => {
    const j = Math.max(0, Math.min(N - 1, tk - tA));
    let x = X[j], z = Z[j], yaw = YAW[j], ph = PH[j], vv = V[j], ww = W[j];
    if (goalPhase <= ph) return { x, z, yaw };
    const [tv, tw] = target(tA + j);
    for (let n = 0; n < 20000; n++) {
      [vv, ww] = saturate(towards(vv, tv, aLin), towards(ww, tw, aYaw));
      let dP;
      if (vv === 0 && ww === 0 && tv === 0 && tw === 0) {
        if (planted(ph) >= restN) dP = 0;
        else dP = Math.min(settle / T, ph + frac(spec.gait.restPhase - ph) - ph);
      } else dP = Math.max(activity(vv, ww), settle) / T;
      if (dP <= 0) return { x, z, yaw };
      const a = ph + dP >= goalPhase ? (goalPhase - ph) / dP : 1; // fraction of this tick used
      const hh = heading(yaw + (ww * a) / 2);
      x += vv * a * hh[0]; z += vv * a * hh[2]; yaw += ww * a; ph += dP * a;
      if (a < 1) return { x, z, yaw };
    }
    return { x, z, yaw };
  };
  /** Gait clock rate over the last tick (causal). */
  const rateAt = (t) => { const j = Math.max(1, Math.min(N - 1, t - tA)); return PH[j] - PH[j - 1]; };
  return {
    poseAt, phaseAt, timeAtPhase, predict, rateAt, vTop, wTop,
    at: (t) => { const j = idx(t); return { x: X[j], z: Z[j], yaw: YAW[j], phase: PH[j], v: V[j], w: W[j], activity: ACT[j] }; },
  };
}

/**
 * Run the walking loop.
 * opts: { ticks, distance, startX, frames: false, commands }  (commands override the course's)
 * @returns {{frames, footholds, events, series, summary, ticks, cycleTicks}}
 */
export function simulate(spec, terrain, opts = {}) {
  const T = spec.gait.cycleTicks;
  const duty = spec.gait.duty;
  const warm = Math.ceil(T * 2);
  const commands = opts.commands ?? terrain.commands ?? null;
  const ticks = opts.ticks ?? courseTicks(spec, commands, opts.distance);
  const legs = spec.legs.map((l) => prepLeg(l, spec));
  const search = spec.gait.footholdSearch;
  const tEndPlan = ticks + Math.ceil((4 * T) / spec.gait.settleRate) + 10;
  const path = planPath(spec, legs, commands, -warm, tEndPlan, opts.startX ?? 0);
  const flatPose = (p) => ({ pos: [p.x, 0, p.z], yaw: p.yaw, pitch: 0, roll: 0 });

  // ---- foothold planner (memoised per leg & stance index) ----
  const fhCache = legs.map(() => new Map());
  let probeCount = 0;
  // A foothold for stance k is decided when the leg lifts off for the swing before it (end of stance k-1),
  // from the state known at that tick (causal; see planPath.predict). Before that, callers that need a
  // provisional guess (sway look-ahead) get one from the current state, which is not cached.
  const plan = (li, k, tDecide) => {
    const leg = legs[li];
    const P0 = k - leg.phase; // gait clock when stance k begins
    const mid = path.predict(tDecide, P0 + duty / 2);
    const w = bodyToWorld(flatPose(mid), leg.footLocal);
    const f = planFoothold(terrain, w[0], w[2], search, mid.yaw);
    f.k = k;
    f.yaw = mid.yaw;
    f.tStart = path.timeAtPhase(P0);
    f.tEnd = path.timeAtPhase(P0 + duty);
    f.tDecided = tDecide;
    return f;
  };
  const liftTick = (li, k) => Math.ceil(path.timeAtPhase(k - 1 - legs[li].phase + duty) - 1e-9);
  const foothold = (li, k, asOf = Infinity) => {
    let f = fhCache[li].get(k);
    if (f) return f;
    const tl = Math.max(-warm, liftTick(li, k));
    if (tl > asOf) return plan(li, k, asOf); // not decided yet: provisional, not cached
    f = plan(li, k, Number.isFinite(tl) ? tl : -warm);
    probeCount += f.probes;
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
  const series = { margin: [], bodyY: [], reach: [], footErr: [], stance: [], clearance: [], speed: [], torque: [], trackErr: [] };
  const sideGroups = [1, -1].map((s) => legs.map((l, i) => [l, i]).filter(([l]) => l.side === s).sort((a, b) => b[0].mount[0] - a[0].mount[0]));

  let bodyY = null, pitch = 0, roll = 0, shiftX = 0, shiftZ = 0;
  // actual hull (suspension follower, D1): height, pitch, roll and their rates
  const susp = spec.physics.suspension;
  const wn = 2 * Math.PI * susp.frequency, zeta = susp.damping;
  const SUB = 8, hSub = 1 / TPS / SUB;
  let act = null;
  // Backward-Euler substeps: stable for any frequency and damping ratio (overdamped settings just settle).
  const followDen = 1 + 2 * zeta * wn * hSub + wn * wn * hSub * hSub;
  const follow = (x, xv, target) => {
    for (let i = 0; i < SUB; i++) {
      xv = (xv + wn * wn * (target - x) * hSub) / followDen;
      x += xv * hSub;
    }
    return [x, xv];
  };
  const comShift = clamp(spec.gait.comShift ?? 0, 0, 1);
  const horizon = (1 - duty) * T + 2;
  let prevAngles = null;
  let jointSpeedMax = 0, reachMax = 0, footErrMax = 0, marginMin = Infinity, stanceMin = Infinity;
  let clearanceMin = Infinity, pitchMax = 0, vyMax = 0, torqueMax = 0, trackErrMax = 0, tiltErrMax = 0;
  let pathLength = 0, yawRateMax = 0;
  let solveTime = 0, solveTicks = 0;
  const comLocal = add([0, spec.body.size[1] / 2, 0], spec.body.comOffset);
  const [sx, , sz] = spec.body.size;
  const bottomSamples = [];
  for (let ix = 0; ix <= Math.ceil(sx); ix++)
    for (let iz = 0; iz <= Math.ceil(sz); iz++)
      bottomSamples.push([-sx / 2 + (ix / Math.ceil(sx)) * sx, 0, -sz / 2 + (iz / Math.ceil(sz)) * sz]);

  for (let t = -warm; t <= ticks; t++) {
    const t0 = now();
    const P = path.at(t);
    // 1. leg targets in world space
    const legState = legs.map((leg, li) => {
      const u = P.phase + leg.phase + EPS;
      const k = Math.floor(u);
      const phi = u - k;
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

    // 2. planned body pose follows the ground under the legs
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
    if (bodyY === null) bodyY = yTarget;
    else bodyY += (yTarget - bodyY) * kS;
    pitch += (pTarget - pitch) * kS;
    roll += (rTarget - roll) * kS;
    // optional sway: shift the hull toward the point that is safest for the support polygons
    // of the next swing window (coarse→fine grid search). This is what makes crawl gaits stable.
    if (comShift > 0) {
      const sets = [];
      for (let j = 0; j <= 3; j++) {
        const ph = P.phase + path.rateAt(t) * (j / 3) * horizon; // extrapolated from the current rate
        const pts = [];
        legs.forEach((leg, li) => {
          const u = ph + leg.phase + EPS;
          if (frac(u) < duty) {
            const f = foothold(li, Math.floor(u), t);
            if (!f.void) pts.push([f.x, f.z]);
          }
        });
        if (pts.length >= 3) sets.push(convexHull(pts));
      }
      if (sets.length) {
        const cw = bodyToWorld(flatPose(P), comLocal);
        const c0 = [cw[0], cw[2]];
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
    // actual hull: damped second-order follower of the planned height / pitch / roll
    if (act === null) act = { y: bodyY, vy: 0, p: pitch, vp: 0, r: roll, vr: 0 };
    else {
      [act.y, act.vy] = follow(act.y, act.vy, bodyY);
      [act.p, act.vp] = follow(act.p, act.vp, pitch);
      [act.r, act.vr] = follow(act.r, act.vr, roll);
    }
    const pose = { pos: [P.x + shiftX, act.y, P.z + shiftZ], yaw: P.yaw, pitch: act.p, roll: act.r };

    // 3. IK per leg (legs hang off the actual hull)
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
    const cycle = Math.floor(P.phase);
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
      // joint angles relative to the hull (hull yaw/pitch is not joint motion)
      angles.push(...jointAngles(leg, leg.mount, o.sol.base, o.sol.knee, o.sol.foot));
    });
    // foot clash between neighbours on the same side
    for (const group of sideGroups) {
      for (let g = 0; g + 1 < group.length; g++) {
        const a = legOut[group[g][1]].foot, b = legOut[group[g + 1][1]].foot;
        if (Math.hypot(a[0] - b[0], a[2] - b[2]) < 0.6)
          emit('foot-clash', t, `${group[g][0].id}/${group[g + 1][0].id}`, 'Neighbouring feet closer than 0.6 b', cycle);
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
    if (clearance < 0) emit('body-scrape', t, 'body', `Hull ${(-clearance).toFixed(2)} b into terrain`, cycle);
    // righting torque the hull needs to not tip (D6): mass × how far the CoM is outside the support polygon
    const torque = spec.mass * Math.max(0, -margin);
    const trackErr = act.y - bodyY;

    marginMin = Math.min(marginMin, margin);
    stanceMin = Math.min(stanceMin, stanceN);
    reachMax = Math.max(reachMax, reach);
    footErrMax = Math.max(footErrMax, ferr);
    clearanceMin = Math.min(clearanceMin, clearance);
    pitchMax = Math.max(pitchMax, Math.abs(act.p), Math.abs(act.r));
    vyMax = Math.max(vyMax, Math.abs(act.vy) / TPS);
    torqueMax = Math.max(torqueMax, torque);
    trackErrMax = Math.max(trackErrMax, Math.abs(trackErr));
    tiltErrMax = Math.max(tiltErrMax, Math.abs(act.p - pitch), Math.abs(act.r - roll));
    if (t < ticks) pathLength += Math.abs(path.at(t + 1).v);
    yawRateMax = Math.max(yawRateMax, Math.abs(P.w));

    series.margin.push(margin);
    series.bodyY.push(act.y);
    series.reach.push(reach);
    series.footErr.push(ferr);
    series.stance.push(stanceN);
    series.clearance.push(Number.isFinite(clearance) ? clearance : null);
    series.speed.push(Math.abs(P.v) * TPS);
    series.torque.push(torque);
    series.trackErr.push(trackErr);

    if (opts.frames !== false) frames.push({
      t,
      phase: P.phase,
      body: { pos: pose.pos, yaw: pose.yaw, pitch: act.p, roll: act.r, plannedY: bodyY },
      cmd: { ...commandAt(commands, t), v: P.v * TPS, yawRate: deg(P.w) * TPS, activity: P.activity },
      com: comW,
      hull,
      margin,
      torque,
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

  // stabilizer (D6): capped righting torque, drawing stress in proportion to the torque it applies
  const stab = spec.physics.stabilizer;
  const stabLoad = stab ? torqueMax / stab.maxTorque : null;
  const stabStress = stab ? Math.min(torqueMax, stab.maxTorque) * stab.stressPerTorque : 0;

  const start = path.at(0), end = path.at(ticks);
  const h0 = heading(start.yaw);
  const secs = Math.max(1, ticks) / TPS;
  return {
    ticks,
    cycleTicks: T,
    frames,
    footholds,
    events: events.sort((a, b) => a.t - b.t),
    series,
    summary: {
      speed: pathLength / secs, // achieved average ground speed (b/s, path length / time)
      topSpeed: path.vTop * TPS,
      distance: pathLength,
      netForward: (end.x - start.x) * h0[0] + (end.z - start.z) * h0[2], // along the starting heading
      netDisplacement: Math.hypot(end.x - start.x, end.z - start.z),
      yawTotalDeg: deg(end.yaw - start.yaw),
      yawRateMaxDegS: deg(yawRateMax) * TPS,
      maxYawRateDegS: deg(path.wTop) * TPS,
      endSpeed: Math.abs(end.v) * TPS,
      endYawRateDegS: Math.abs(deg(end.w)) * TPS,
      endPlanted: series.stance.length ? series.stance[series.stance.length - 1] : 0,
      marginMin,
      stanceMin,
      reachMax,
      footErrMax,
      clearanceMin,
      bob,
      vyMax: vyMax * TPS,
      pitchMaxDeg: deg(pitchMax),
      jointSpeedMaxDegS: deg(jointSpeedMax) * TPS,
      torqueMax,
      stabLoad,
      stabStress,
      trackErrMax,
      tiltErrMaxDeg: deg(tiltErrMax),
      eventCounts: counts,
      collisions: events.filter((e) => COLLISION_TYPES.includes(e.type)).length,
      refUsPerTick: (solveTime / Math.max(1, solveTicks)) * 1000,
      footholdProbesPerTick: probeCount / (ticks + warm),
    },
  };
}

export const COLLISION_TYPES = ['toe-stub', 'knee-clip', 'body-scrape', 'void-step', 'foot-clash', 'step-too-high'];
