// Tiny vector helpers. Vectors are plain [x, y, z] arrays (Minecraft axes: y is up).
export const TPS = 20; // Minecraft server ticks per second

export const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const frac = (x) => x - Math.floor(x);
export const smoothstep = (t) => {
  t = clamp(t, 0, 1);
  return t * t * (3 - 2 * t);
};
export const deg = (r) => (r * 180) / Math.PI;
export const rad = (d) => (d * Math.PI) / 180;

export const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
export const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
export const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
export const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
export const len = (a) => Math.hypot(a[0], a[1], a[2]);
export const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
export const norm = (a) => {
  const l = len(a);
  return l > 1e-9 ? [a[0] / l, a[1] / l, a[2] / l] : [0, 0, 0];
};
export const vlerp = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];

// Rotation about the x axis (roll) and z axis (pitch; +pitch lifts the +x "nose").
export const rotX = (v, a) => {
  const c = Math.cos(a), s = Math.sin(a);
  return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c];
};
export const rotZ = (v, a) => {
  const c = Math.cos(a), s = Math.sin(a);
  return [v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]];
};

// Body pose: { pos:[x,y,z], pitch, roll }. Local origin = centre of the body's bottom face.
export const bodyToWorld = (pose, p) => add(pose.pos, rotZ(rotX(p, pose.roll), pose.pitch));
export const worldToBody = (pose, p) => rotX(rotZ(sub(p, pose.pos), -pose.pitch), -pose.roll);

// ---- 2D helpers (x, z ground plane) ----
export function convexHull(points) {
  const pts = points.map((p) => [p[0], p[1]]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (pts.length < 3) return pts;
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower = [], upper = [];
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop();
    lower.push(p);
  }
  for (let i = pts.length - 1; i >= 0; i--) {
    const p = pts[i];
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop();
    upper.push(p);
  }
  upper.pop();
  lower.pop();
  return lower.concat(upper); // counter-clockwise
}

function distToSegment(p, a, b) {
  const dx = b[0] - a[0], dz = b[1] - a[1];
  const l2 = dx * dx + dz * dz;
  const t = l2 > 0 ? clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / l2, 0, 1) : 0;
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dz));
}

/**
 * Signed distance from point p to the support polygon spanned by `points`.
 * Positive = inside (statically stable, value is the margin), negative = outside.
 * Fewer than 3 contacts can never be statically stable, so the result is <= 0.
 */
export function supportMargin(p, points) {
  if (points.length === 0) return { margin: -5, hull: [] };
  const hull = convexHull(points);
  return { margin: hullMargin(p, hull), hull };
}

/** Same as supportMargin but for an already computed (CCW) hull. */
export function hullMargin(p, hull) {
  if (hull.length === 0) return -5;
  if (hull.length === 1) return -Math.hypot(p[0] - hull[0][0], p[1] - hull[0][1]);
  let minD = Infinity;
  let inside = hull.length >= 3;
  for (let i = 0; i < hull.length; i++) {
    const a = hull[i], b = hull[(i + 1) % hull.length];
    minD = Math.min(minD, distToSegment(p, a, b));
    if (inside && (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) < 0) inside = false;
  }
  return inside ? minD : -minD;
}
