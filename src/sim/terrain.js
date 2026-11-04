// Block-grid terrain test courses. Each column (ix, iz) has an integer surface height:
// a foot standing on column (ix, iz) rests at y = height. Walkers start at x = 0 heading +x.
//
// A course may also define a driver command timeline (`commands`): piecewise-constant segments
// { ticks, throttle, steer }, played in order from tick 0.
//   throttle ∈ [-1, 1]: fraction of top speed (negative = reverse)
//   steer    ∈ [-1, 1]: fraction of the max yaw rate (positive = turn left, toward -z)
// The first segment also sets the state before tick 0 (warm-up): a course that starts with zero
// commands starts standing still, one that starts with throttle 1 starts already at full speed.
// After the last segment its command is held. Courses without `commands` drive full throttle
// forward for the default run length.
export const VOID = -8; // "no ground here" (a trench / drop into the void)

export const DEFAULT_COMMANDS = [{ ticks: Infinity, throttle: 1, steer: 0 }];

/** Total run length of a command timeline in ticks (Infinity if open-ended). */
export function commandTicks(commands) {
  return (commands ?? DEFAULT_COMMANDS).reduce((a, c) => a + (c.ticks ?? Infinity), 0);
}

/** Command { throttle, steer } in effect at tick t. */
export function commandAt(commands, t) {
  const cs = commands?.length ? commands : DEFAULT_COMMANDS;
  let end = 0;
  for (const c of cs) {
    end += c.ticks ?? Infinity;
    if (t < end) return { throttle: c.throttle ?? 0, steer: c.steer ?? 0 };
  }
  const c = cs[cs.length - 1];
  return { throttle: c.throttle ?? 0, steer: c.steer ?? 0 };
}

function hash(ix, iz, seed) {
  let h = (ix * 374761393 + iz * 668265263 + seed * 1442695041) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function valueNoise(x, z, cell, seed) {
  const gx = Math.floor(x / cell), gz = Math.floor(z / cell);
  const fx = x / cell - gx, fz = z / cell - gz;
  const s = (t) => t * t * (3 - 2 * t);
  const a = hash(gx, gz, seed), b = hash(gx + 1, gz, seed);
  const c = hash(gx, gz + 1, seed), d = hash(gx + 1, gz + 1, seed);
  const u = s(fx), v = s(fz);
  return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
}

export const SCENARIOS = {
  flat: {
    surface: 'grass',
    label: 'Flat',
    short: 'Flat',
    description: 'Baseline: steady-state gait, bob, speed and cost with no terrain influence.',
    column: () => 0,
  },
  stairs: {
    surface: 'stone',
    label: 'Stairs',
    short: 'Stairs',
    description: 'Five 1-block steps up (3 blocks deep), a plateau, then five steps down.',
    column: (ix) => {
      if (ix < 10) return 0;
      if (ix < 25) return Math.floor((ix - 10) / 3) + 1;
      if (ix < 35) return 5;
      if (ix < 50) return 5 - (Math.floor((ix - 35) / 3) + 1);
      return 0;
    },
  },
  ledge: {
    surface: 'stone',
    label: '2-block ledge',
    short: 'Ledge',
    description: 'A sudden 2-block wall up at x=14 and a 2-block drop at x=30. Probes max step-up.',
    column: (ix) => (ix >= 14 && ix < 30 ? 2 : 0),
  },
  hill: {
    surface: 'grass',
    label: 'Steep hill',
    short: 'Hill',
    description: '1 block of rise every 2 blocks (≈27°) up to +8, then back down.',
    column: (ix) => {
      if (ix < 10) return 0;
      if (ix < 26) return Math.floor((ix - 10) / 2) + 1;
      if (ix < 32) return 8;
      if (ix < 48) return 8 - (Math.floor((ix - 32) / 2) + 1);
      return 0;
    },
  },
  gap: {
    surface: 'grass',
    label: 'Trenches',
    short: 'Gap',
    description: 'A 2-wide and a 3-wide trench across the path. Without foothold search feet fall in.',
    column: (ix) => ((ix >= 12 && ix < 14) || (ix >= 26 && ix < 29) ? VOID : 0),
  },
  rough: {
    surface: 'grass',
    label: 'Rough',
    short: 'Rough',
    description: 'Seeded 0–2 block lumpy ground (like a plains/forest edge) from x=8 onward.',
    column: (ix, iz, seed) => (ix < 8 ? 0 : Math.round(valueNoise(ix, iz, 3, seed) * 2.4 - 0.2)),
  },
  turn: {
    surface: 'grass',
    label: 'Turn in place',
    short: 'Turn',
    description: 'Flat. Stand, spin left at full steer and zero throttle for 12 s, then stop and settle.',
    column: () => 0,
    commands: [
      { ticks: 10, throttle: 0, steer: 0 },
      { ticks: 240, throttle: 0, steer: 1 },
      { ticks: 80, throttle: 0, steer: 0 },
    ],
  },
  startstop: {
    surface: 'grass',
    label: 'Start-stop',
    short: 'Stop',
    description: 'Flat. Accelerate from standing, cruise, brake to a stop, stand, start again and stop.',
    column: () => 0,
    commands: [
      { ticks: 10, throttle: 0, steer: 0 },
      { ticks: 150, throttle: 1, steer: 0 },
      { ticks: 100, throttle: 0, steer: 0 },
      { ticks: 120, throttle: 1, steer: 0 },
      { ticks: 100, throttle: 0, steer: 0 },
    ],
  },
  reverse: {
    surface: 'grass',
    label: 'Reverse + curve',
    short: 'Rev',
    description: 'Flat. Back up at full reverse, stop, then drive forward on a left-hand curve (steer 0.4).',
    column: () => 0,
    commands: [
      { ticks: 10, throttle: 0, steer: 0 },
      { ticks: 120, throttle: -1, steer: 0 },
      { ticks: 40, throttle: 0, steer: 0 },
      { ticks: 200, throttle: 1, steer: 0.4 },
      { ticks: 60, throttle: 0, steer: 0 },
    ],
  },
};

export function makeTerrain(name = 'flat', seed = 7) {
  const sc = SCENARIOS[name];
  if (!sc) throw new Error(`Unknown scenario "${name}". Known: ${Object.keys(SCENARIOS).join(', ')}`);
  const cache = new Map();
  const column = (ix, iz) => {
    const key = ix * 100003 + iz;
    let h = cache.get(key);
    if (h === undefined) {
      h = Math.max(VOID, sc.column(ix, iz, seed));
      cache.set(key, h);
    }
    return h;
  };
  return {
    name,
    label: sc.label,
    surface: sc.surface,
    description: sc.description,
    commands: sc.commands ?? null,
    seed,
    column,
    heightAt: (x, z) => column(Math.floor(x), Math.floor(z)),
  };
}
