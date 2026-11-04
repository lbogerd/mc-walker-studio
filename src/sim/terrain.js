// Block-grid terrain test courses. Each column (ix, iz) has an integer surface height:
// a foot standing on column (ix, iz) rests at y = height. Walkers start at x = 0 heading +x.
export const VOID = -8; // "no ground here" (a trench / drop into the void)

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
    description: 'Baseline: steady-state gait, bob, speed and cost with no terrain influence.',
    column: () => 0,
  },
  stairs: {
    surface: 'stone',
    label: 'Stairs',
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
    description: 'A sudden 2-block wall up at x=14 and a 2-block drop at x=30. Probes max step-up.',
    column: (ix) => (ix >= 14 && ix < 30 ? 2 : 0),
  },
  hill: {
    surface: 'grass',
    label: 'Steep hill',
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
    description: 'A 2-wide and a 3-wide trench across the path. Without foothold search feet fall in.',
    column: (ix) => ((ix >= 12 && ix < 14) || (ix >= 26 && ix < 29) ? VOID : 0),
  },
  rough: {
    surface: 'grass',
    label: 'Rough',
    description: 'Seeded 0–2 block lumpy ground (like a plains/forest edge) from x=8 onward.',
    column: (ix, iz, seed) => (ix < 8 ? 0 : Math.round(valueNoise(ix, iz, 3, seed) * 2.4 - 0.2)),
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
    seed,
    column,
    heightAt: (x, z) => column(Math.floor(x), Math.floor(z)),
  };
}
