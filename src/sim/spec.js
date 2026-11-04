// Walker spec normalisation: fills defaults, derives timing from Create RPM, assigns gait phases.
// The raw JSON format is documented in docs/walker-spec.md.
import { TPS } from './math.js';

export const GAIT_PRESETS = {
  alternating: 'Two groups in anti-phase (trot for 4 legs, tripod for 6, plain walking for 2).',
  wave: 'One leg at a time, rear→front, left side then right. Slow but always ≥ n−1 feet down.',
  ripple: 'Each side runs a rear→front wave; the two sides are half a cycle apart.',
  pace: 'All legs on one side move together.',
  bound: 'Front half and rear half move together.',
  custom: 'Every leg supplies its own "phase" (0–1).',
};

export const KNEE_HINTS = ['forward', 'back', 'up', 'out', 'down'];
export const LEG_REPRESENTATIONS = ['renderOnly', 'contraption', 'subLevel'];

const DEFAULT_PARTS = {
  body: 'Hull block (any)',
  hip: 'Leg Actuator',
  joint: 'Leg Joint',
  strut: 'Leg Strut',
  foot: 'Foot Pad',
};

function mirrorId(id) {
  if (/L$/.test(id)) return id.replace(/L$/, 'R');
  if (/R$/.test(id)) return id.replace(/R$/, 'L');
  if (/^L/.test(id)) return id.replace(/^L/, 'R');
  if (/^R/.test(id)) return id.replace(/^R/, 'L');
  return `${id}'`;
}

const isNum = (x) => typeof x === 'number' && Number.isFinite(x);
const isVec = (v, n) => Array.isArray(v) && v.length === n && v.every(isNum);

export function normalizeSpec(raw, { id = 'walker', sharedTargets = {} } = {}) {
  if (!raw || typeof raw !== 'object') throw new Error('Walker spec must be a JSON object');
  const errors = [];
  const warnings = [];

  const body = {
    size: [7, 2, 5],
    rideHeight: 3,
    comOffset: [0, 0, 0],
    blocks: null,
    color: '#8d9399',
    accent: '#c9a227',
    ...(raw.body || {}),
  };
  if (!isVec(body.size, 3)) errors.push('body.size must be [x, y, z] in blocks');
  if (!isVec(body.comOffset, 3)) errors.push('body.comOffset must be [x, y, z]');
  if (!isNum(body.blocks)) body.blocks = body.size[0] * body.size[1] * body.size[2];

  const legDefaults = { segments: [3, 3], knee: 'back', ...(raw.legDefaults || {}) };
  const legs = [];
  (raw.legs || []).forEach((l, i) => {
    const leg = {
      id: l.id ?? `leg${i}`,
      mount: l.mount ?? [0, 0, 0],
      neutral: l.neutral ?? [0, 0],
      segments: l.segments ?? legDefaults.segments,
      knee: l.knee ?? legDefaults.knee,
      phase: isNum(l.phase) ? l.phase : null,
    };
    legs.push(leg);
    if (l.mirror) {
      legs.push({
        ...leg,
        id: l.mirrorId ?? mirrorId(leg.id),
        mount: [leg.mount[0], leg.mount[1], -leg.mount[2]],
        neutral: [leg.neutral[0], -leg.neutral[1]],
        phase: isNum(l.mirrorPhase) ? l.mirrorPhase : null,
      });
    }
  });
  if (legs.length === 0) errors.push('Walker needs at least one leg');
  const ids = new Set();
  for (const leg of legs) {
    if (ids.has(leg.id)) errors.push(`Duplicate leg id "${leg.id}"`);
    ids.add(leg.id);
    if (!isVec(leg.mount, 3)) errors.push(`${leg.id}: mount must be [x, y, z] relative to body bottom centre`);
    if (!isVec(leg.neutral, 2)) errors.push(`${leg.id}: neutral must be [dx, dz] foot offset from the mount`);
    if (!Array.isArray(leg.segments) || ![2, 3].includes(leg.segments.length) || !leg.segments.every((s) => isNum(s) && s > 0))
      errors.push(`${leg.id}: segments must be 2 values [upper, lower] or 3 values [coxa, upper, lower], all > 0`);
    if (!KNEE_HINTS.includes(leg.knee)) errors.push(`${leg.id}: knee must be one of ${KNEE_HINTS.join(', ')}`);
  }

  const gait = {
    preset: 'alternating',
    duty: 0.5,
    stride: 3,
    stepHeight: 1,
    swing: 'arc',
    bodyAdapt: 'level',
    bodySmoothing: 0.25,
    comShift: 0,
    footholdSearch: null,
    maxStepUp: null,
    cycleTicks: null,
    accel: 2, // b/s²: how fast the commanded ground speed may change (also sets the yaw acceleration)
    settleRate: 0.5, // fraction of the full gait rate used while moving slowly or settling into a stance
    ...(raw.gait || {}),
  };
  if (!GAIT_PRESETS[gait.preset]) errors.push(`gait.preset must be one of ${Object.keys(GAIT_PRESETS).join(', ')}`);
  if (!(gait.duty > 0 && gait.duty < 1)) errors.push('gait.duty must be between 0 and 1 (fraction of the cycle a foot is planted)');
  if (!['arc', 'box'].includes(gait.swing)) errors.push('gait.swing must be "arc" or "box"');
  if (!['level', 'pitch'].includes(gait.bodyAdapt)) errors.push('gait.bodyAdapt must be "level" or "pitch"');
  if (gait.footholdSearch === true) gait.footholdSearch = { radius: 1 };
  if (!(isNum(gait.accel) && gait.accel > 0)) errors.push('gait.accel must be > 0 (b/s², how fast the walker speeds up and brakes)');
  if (!(isNum(gait.settleRate) && gait.settleRate > 0 && gait.settleRate <= 1))
    errors.push('gait.settleRate must be in (0, 1] (fraction of the full gait rate used when slow or settling)');

  const drive = { rpm: null, revsPerCycle: 1, stressPerLegPerRpm: 4, stressPerBlockPerRpm: 0.05, ...(raw.drive || {}) };
  let cycleTicks;
  if (isNum(drive.rpm) && drive.rpm > 0) {
    cycleTicks = (1200 * drive.revsPerCycle) / drive.rpm; // 1 rev = 60/rpm s = 1200/rpm ticks
    if (isNum(gait.cycleTicks)) warnings.push('Both drive.rpm and gait.cycleTicks set; drive.rpm wins');
  } else {
    cycleTicks = isNum(gait.cycleTicks) ? gait.cycleTicks : 40;
    drive.rpm = (1200 * drive.revsPerCycle) / cycleTicks;
  }
  if (drive.rpm > 256) warnings.push(`Drive needs ${drive.rpm.toFixed(0)} RPM; Create shafts top out at 256 RPM`);
  if (cycleTicks < 8) warnings.push(`Gait cycle of ${cycleTicks.toFixed(1)} ticks is very fast; swings will be only a few ticks long`);
  gait.cycleTicks = cycleTicks;

  // ---- phase assignment ----
  const sideOf = (leg) => (leg.mount[2] >= 0 ? 1 : -1);
  const bySide = { 1: [], '-1': [] };
  legs.forEach((leg) => bySide[sideOf(leg)].push(leg));
  for (const s of [1, -1]) bySide[s].sort((a, b) => b.mount[0] - a.mount[0]); // front first
  const rank = (leg) => bySide[sideOf(leg)].indexOf(leg);
  const n = legs.length;
  const presetPhase = (leg) => {
    const s = sideOf(leg);
    const r = rank(leg);
    const nSide = bySide[s].length;
    switch (gait.preset) {
      case 'alternating':
        return ((r + (s > 0 ? 1 : 0)) % 2) * 0.5;
      case 'wave': {
        // left rear→front, then right rear→front
        const order = [...bySide[-1]].reverse().concat([...bySide[1]].reverse());
        return order.indexOf(leg) / n;
      }
      case 'ripple':
        return ((nSide - 1 - r) / Math.max(1, nSide) + (s > 0 ? 0.5 : 0)) % 1;
      case 'pace':
        return s > 0 ? 0.5 : 0;
      case 'bound':
        return r < nSide / 2 ? 0 : 0.5;
      default:
        return 0;
    }
  };
  for (const leg of legs) {
    if (leg.phase === null) {
      if (gait.preset === 'custom') warnings.push(`${leg.id}: preset "custom" but no phase given; using 0`);
      leg.phase = presetPhase(leg);
    }
    leg.side = sideOf(leg);
  }
  if (gait.preset === 'wave' && gait.duty < 1 - 1 / n - 1e-6)
    warnings.push(`Wave gait with duty ${gait.duty} lifts more than one leg at a time; use duty ≥ ${(1 - 1 / n).toFixed(2)}`);

  // ---- rest stance: phases of the gait clock where every foot is planted ----
  const rest = restWindows(legs, gait.duty);
  if (rest.planted < n)
    warnings.push(`No point in the gait cycle has all ${n} feet planted (duty ${gait.duty}); when stopped the walker freezes with ${n - rest.planted} foot/feet in the air`);
  gait.restPhase = rest.phase;
  gait.restPlanted = rest.planted;

  // ---- physics: Gyro Stabilizer (D6) and leg suspension (D1) ----
  const rawPhysics = raw.physics || {};
  if ('support' in rawPhysics) {
    errors.push(
      'physics.support was removed (decision D6: balance comes from a Gyro Stabilizer). Migrate: ' +
      '"support": "static" → "stabilizer": null; "support": "kinematic" → "stabilizer": { "maxTorque": <mass·b>, "stressPerTorque": <SU per mass·b> }. ' +
      'Size maxTorque from the "Peak righting torque" metric. See docs/walker-spec.md.');
  }
  const physics = {
    stabilizer: rawPhysics.stabilizer ?? null,
    suspension: { frequency: 2, damping: 0.7, ...(rawPhysics.suspension || {}) },
  };
  if (physics.stabilizer !== null) {
    if (typeof physics.stabilizer !== 'object') errors.push('physics.stabilizer must be null or { maxTorque, stressPerTorque }');
    else {
      physics.stabilizer = { maxTorque: null, stressPerTorque: 2, ...physics.stabilizer };
      if (!(isNum(physics.stabilizer.maxTorque) && physics.stabilizer.maxTorque > 0))
        errors.push('physics.stabilizer.maxTorque must be > 0 (mass·b: block-masses × blocks of lever arm)');
      if (!(isNum(physics.stabilizer.stressPerTorque) && physics.stabilizer.stressPerTorque >= 0))
        errors.push('physics.stabilizer.stressPerTorque must be ≥ 0 (SU per mass·b of torque)');
    }
  }
  if (!(isNum(physics.suspension.frequency) && physics.suspension.frequency > 0 && physics.suspension.frequency <= 10))
    errors.push('physics.suspension.frequency must be in (0, 10] Hz');
  if (!(isNum(physics.suspension.damping) && physics.suspension.damping >= 0))
    errors.push('physics.suspension.damping must be ≥ 0 (damping ratio; 1 = critically damped)');

  const implementation = {
    legRepresentation: 'contraption',
    footholdProbesPerStep: gait.footholdSearch ? 27 : 1,
    groundChecksPerFootPerTick: 1,
    syncEveryTicks: 1,
    ...(raw.implementation || {}),
  };
  if (!LEG_REPRESENTATIONS.includes(implementation.legRepresentation))
    errors.push(`implementation.legRepresentation must be one of ${LEG_REPRESENTATIONS.join(', ')}`);

  const turnRadius = Math.max(0.5, ...legs.filter((l) => isVec(l.mount, 3) && isVec(l.neutral, 2))
    .map((l) => Math.hypot(l.mount[0] + l.neutral[0], l.mount[2] + l.neutral[1])));

  const parts = { ...DEFAULT_PARTS, ...(raw.parts || {}) };
  const bom = buildBom(body, legs, parts);
  const legBlocks = bom.filter((b) => b.kind !== 'body').reduce((s, b) => s + b.count, 0);

  return {
    id,
    name: raw.name ?? id,
    description: raw.description ?? '',
    author: raw.author ?? null,
    proposal: { summary: '', decisions: [], openQuestions: [], ...(raw.proposal || {}) },
    color: raw.color ?? null,
    body,
    legs,
    gait,
    drive,
    physics,
    implementation,
    parts,
    bom,
    mass: body.blocks + legBlocks,
    targets: { ...sharedTargets, ...(raw.targets || {}) },
    speed: (gait.stride / cycleTicks) * TPS, // top speed, blocks per second (throttle 1)
    // Turning: the fastest any foot may move over the ground is the top speed, so the max yaw rate
    // (turning in place) is topSpeed / turnRadius, where turnRadius is the largest horizontal distance
    // from the hull origin to a neutral foot position.
    turnRadius,
    maxYawRate: ((gait.stride / cycleTicks) * TPS) / turnRadius, // rad/s
    errors,
    warnings,
    raw,
  };
}

/**
 * Rest stance analysis of the gait clock Φ: leg i is planted while frac(Φ + phase_i) < duty.
 * Returns the number of feet planted in the best part of the cycle and the phase (0–1) in the middle
 * of the longest window where that many feet are planted. When the walker stops, the gait clock runs
 * on until it reaches such a window and then freezes, so it stands on all feet.
 */
export function restWindows(legs, duty, samples = 1000) {
  const count = (P) => legs.reduce((a, l) => a + (((P + (l.phase ?? 0)) % 1 + 1) % 1 < duty ? 1 : 0), 0);
  const c = [];
  let best = 0;
  for (let i = 0; i < samples; i++) { c.push(count(i / samples)); best = Math.max(best, c[i]); }
  // longest circular run with c == best
  let runStart = -1, bestLen = 0, bestStart = 0;
  for (let i = 0; i < 2 * samples; i++) {
    if (c[i % samples] === best) {
      if (runStart < 0) runStart = i;
      const L = i - runStart + 1;
      if (L > bestLen && L <= samples) { bestLen = L; bestStart = runStart; }
    } else runStart = -1;
  }
  return { planted: best, phase: ((bestStart + bestLen / 2) / samples) % 1, width: bestLen / samples };
}

function buildBom(body, legs, parts) {
  let struts = 0, joints = 0;
  for (const leg of legs) {
    struts += leg.segments.reduce((s, l) => s + Math.ceil(l - 1e-6), 0);
    joints += leg.segments.length - 1;
  }
  return [
    { kind: 'body', name: parts.body, count: body.blocks },
    { kind: 'hip', name: parts.hip, count: legs.length },
    { kind: 'joint', name: parts.joint, count: joints },
    { kind: 'strut', name: parts.strut, count: struts },
    { kind: 'foot', name: parts.foot, count: legs.length },
  ];
}
