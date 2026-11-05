// Mirrors the per-tick part of simulate() in src/sim/simulate.js (steps 1–3: leg targets and footholds,
// planned body pose and sway, suspension follower, IK) plus its helpers prepLeg, legAt, edgeDistance,
// planFoothold, swingPoint, solveLeg and jointAngles. Measurements (step 4) live in Simulate.
//
// This is the live controller: it advances one tick at a time from the command given on that tick and knows
// nothing about later commands. Batch Simulate.simulate is this controller fed a known command timeline.
package com.lbogerd.walkers.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lbogerd.walkers.sim.SimMath.Pose;
import static com.lbogerd.walkers.sim.SimMath.TPS;
import static com.lbogerd.walkers.sim.SimMath.add;
import static com.lbogerd.walkers.sim.SimMath.bodyToWorld;
import static com.lbogerd.walkers.sim.SimMath.clamp;
import static com.lbogerd.walkers.sim.SimMath.convexHull;
import static com.lbogerd.walkers.sim.SimMath.dot;
import static com.lbogerd.walkers.sim.SimMath.hullMargin;
import static com.lbogerd.walkers.sim.SimMath.hypot;
import static com.lbogerd.walkers.sim.SimMath.len;
import static com.lbogerd.walkers.sim.SimMath.lerp;
import static com.lbogerd.walkers.sim.SimMath.norm;
import static com.lbogerd.walkers.sim.SimMath.scale;
import static com.lbogerd.walkers.sim.SimMath.smoothstep;
import static com.lbogerd.walkers.sim.SimMath.sub;
import static com.lbogerd.walkers.sim.SimMath.worldToBody;

public final class GaitController {
    /**
     * The gait clock is integrated tick by tick, so exact touchdown instants (Φ + phase = integer) can land a
     * hair below the integer. Nudge before flooring so constant-speed runs match t / cycleTicks.
     */
    public static final double EPS = 1e-9;

    // ---------------------------------------------------------------- helpers (module level in the JS)

    /** A leg prepared for the simulation (prepLeg in the JS). */
    public static final class SimLeg {
        public final WalkerSpec.Leg leg;
        public final String id;
        public final double[] mount;
        public final double phase;
        public final int side;
        public final double coxa, femur, tibia, duty;
        /** Knee pole hint in the hull frame. */
        public final double[] hint;
        /** Neutral foot position on the hull's bottom plane (hull frame). */
        public final double[] footLocal;
        public final double[] outward;

        SimLeg(WalkerSpec.Leg leg, WalkerSpec spec) {
            this.leg = leg;
            double[] segs = leg.segments;
            id = leg.id;
            mount = leg.mount;
            phase = leg.phase;
            side = leg.side;
            coxa = segs.length == 3 ? segs[0] : 0;
            femur = segs[segs.length - 2];
            tibia = segs[segs.length - 1];
            duty = spec.gait.duty;
            hint = switch (leg.knee) {
                case "forward" -> new double[] {1, 0, 0};
                case "back" -> new double[] {-1, 0, 0};
                case "up" -> new double[] {0, 1, 0};
                case "down" -> new double[] {0, -1, 0};
                case "out" -> norm(new double[] {0, 0.8, side * 0.6});
                default -> throw new IllegalArgumentException(leg.id + ": unknown knee hint " + leg.knee);
            };
            footLocal = new double[] {leg.mount[0] + leg.neutral[0], 0, leg.mount[2] + leg.neutral[1]};
            outward = new double[] {0, 0, side};
        }
    }

    /** Where a leg is in its cycle at gait clock P: stance index k, phase within the cycle, planted or not. */
    public record LegPhase(int k, double phi, boolean stance) {}

    public static LegPhase legAt(SimLeg leg, double P) {
        double u = P + leg.phase + EPS;
        double k = Math.floor(u);
        double phi = u - k;
        return new LegPhase((int) k, phi, phi < leg.duty);
    }

    /** A foothold: where a leg plants for stance k. Times are fractional ticks. */
    public static final class Foothold {
        public double x, y, z;
        /** No ground at the chosen point. */
        public boolean isVoid;
        /** The foothold search moved the foot away from its nominal point. */
        public boolean adjusted;
        public int probes;
        /** Stance index. */
        public int k;
        /** Predicted hull yaw at mid-stance (the search grid is aligned with it). */
        public double yaw;
        /** When the stance begins / ends. Estimates from the known history; see {@link #refreshStanceTimes}. */
        public double tStart, tEnd;
        /** Tick on which the foothold was decided (the lift-off tick of the swing before it). */
        public double tDecided;

        public double[] pos() { return new double[] {x, y, z}; }
    }

    static double edgeDistance(Terrain terrain, double x, double z, int h) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double d = 1;
        if (terrain.column(ix - 1, iz) != h) d = Math.min(d, fx);
        if (terrain.column(ix + 1, iz) != h) d = Math.min(d, 1 - fx);
        if (terrain.column(ix, iz - 1) != h) d = Math.min(d, fz);
        if (terrain.column(ix, iz + 1) != h) d = Math.min(d, 1 - fz);
        return d;
    }

    /** Search offsets are in the hull's frame (dx along its heading), rotated into the world by yaw. */
    static Foothold planFoothold(Terrain terrain, double x, double z, WalkerSpec.FootholdSearch search, double yaw) {
        double cy = StrictMath.cos(yaw), sy = StrictMath.sin(yaw);
        int h0 = terrain.heightAt(x, z);
        Foothold f = new Foothold();
        if (search == null) {
            f.x = x; f.y = h0; f.z = z; f.isVoid = h0 <= Terrain.VOID; f.adjusted = false; f.probes = 1;
            return f;
        }
        double r = search.radius(), step = search.step(), edgeClear = search.edgeClearance();
        boolean found = false;
        double bx = 0, by = 0, bz = 0, bscore = 0;
        int probes = 0;
        for (double dz = -r / 2; dz <= r / 2 + 1e-9; dz += r / 2) {
            for (double dx = -r; dx <= r + 1e-9; dx += step) {
                probes++;
                double cx = x + dx * cy + dz * sy, cz = z - dx * sy + dz * cy;
                int h = terrain.heightAt(cx, cz);
                if (h <= Terrain.VOID) continue;
                double e = edgeDistance(terrain, cx, cz, h);
                double score = hypot(dx, dz) + (e < edgeClear ? (edgeClear - e) * 8 : 0);
                if (!found || score < bscore) { found = true; bx = cx; by = h; bz = cz; bscore = score; }
            }
        }
        f.probes = probes;
        if (!found) { f.x = x; f.y = h0; f.z = z; f.isVoid = true; f.adjusted = false; return f; }
        f.x = bx; f.y = by; f.z = bz; f.isVoid = false;
        f.adjusted = Math.abs(bx - x) + Math.abs(bz - z) > 1e-6;
        return f;
    }

    record SwingPoint(double[] pos, double hx) {}

    /** Swing trajectory from A to B at normalised swing time s ∈ [0,1]. */
    static SwingPoint swingPoint(Foothold A, Foothold B, double s, WalkerSpec.Gait gait, Terrain terrain) {
        double hx, lift;
        if (gait.swing.equals("box")) {
            hx = smoothstep((s - 0.25) / 0.5);
            lift = smoothstep(s / 0.25) * smoothstep((1 - s) / 0.25);
        } else {
            hx = smoothstep(s);
            lift = StrictMath.sin(Math.PI * s);
        }
        // apex clears the highest column between the two footholds by stepHeight
        double top = Math.max(A.isVoid ? Double.NEGATIVE_INFINITY : A.y, B.isVoid ? Double.NEGATIVE_INFINITY : B.y);
        int n = (int) Math.max(2, Math.ceil(hypot(B.x - A.x, B.z - A.z) * 2));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int h = terrain.heightAt(lerp(A.x, B.x, t), lerp(A.z, B.z, t));
            if (h > Terrain.VOID) top = Math.max(top, h);
        }
        if (!Double.isFinite(top)) top = 0;
        double apex = top + gait.stepHeight;
        double base = lerp(A.y, B.y, hx);
        return new SwingPoint(new double[] {lerp(A.x, B.x, hx), lerp(base, apex, lift), lerp(A.z, B.z, hx)}, hx);
    }

    /** IK result in the hull frame. status: "ok", "over" (out of reach) or "under" (inside min reach). */
    public record LegSolution(double[] base, double[] knee, double[] foot, String status, double util) {}

    static LegSolution solveLeg(SimLeg leg, double[] targetL) {
        double[] hip = leg.mount;
        double[] base = hip;
        if (leg.coxa > 0) {
            double[] d = sub(targetL, hip);
            double[] hd = norm(new double[] {d[0], 0, d[2]});
            if (len(hd) < 0.5) hd = leg.outward;
            base = add(hip, scale(hd, leg.coxa));
        }
        double a = leg.femur, b = leg.tibia;
        double[] toT = sub(targetL, base);
        double d = len(toT);
        double[] dir = d > 1e-6 ? scale(toT, 1 / d) : new double[] {0, -1, 0};
        double maxR = a + b - 1e-4, minR = Math.abs(a - b) + 1e-4;
        String status = "ok";
        double r = d;
        if (d > maxR) { r = maxR; status = "over"; }
        else if (d < minR) { r = minR; status = "under"; }
        double[] foot = add(base, scale(dir, r));
        double cosA = clamp((a * a + r * r - b * b) / (2 * a * r), -1, 1);
        double along = a * cosA;
        double perp = Math.sqrt(Math.max(0, a * a - along * along));
        double[] pole = sub(leg.hint, scale(dir, dot(leg.hint, dir)));
        if (len(pole) < 1e-3) pole = sub(new double[] {0, 1, 0}, scale(dir, dot(new double[] {0, 1, 0}, dir)));
        if (len(pole) < 1e-3) pole = new double[] {1, 0, 0};
        pole = norm(pole);
        double[] knee = add(base, add(scale(dir, along), scale(pole, perp)));
        return new LegSolution(base, knee, foot, status, d / (a + b));
    }

    /**
     * Hip yaw is the coxa direction (3-segment legs) or the orientation of the leg plane (2-segment legs);
     * then hip pitch and knee angle. All relative to the hull.
     */
    static double[] jointAngles(SimLeg leg, double[] hip, double[] base, double[] knee, double[] foot) {
        double yaw;
        if (leg.coxa > 0) yaw = StrictMath.atan2(base[2] - hip[2], base[0] - hip[0]);
        else {
            double[] a = sub(foot, base), b = sub(knee, base);
            yaw = StrictMath.atan2(a[0] * b[1] - a[1] * b[0], a[1] * b[2] - a[2] * b[1]); // plane normal (a × b), z over x
        }
        double[] femurV = sub(knee, base);
        double[] tibiaV = sub(foot, knee);
        double pitch = StrictMath.atan2(femurV[1], hypot(femurV[0], femurV[2]));
        double kneeA = StrictMath.acos(clamp(dot(norm(femurV), norm(tibiaV)), -1, 1));
        return new double[] {yaw, pitch, kneeA};
    }

    // ---------------------------------------------------------------- per-tick output

    /** Leg target for this tick (step 1). */
    public record LegState(boolean stance, double[] target, Double ground, Foothold fh, Foothold next, int k, Double s) {}

    /** One leg after IK, in world space. */
    public record LegOut(LegState st, LegSolution sol, double[] hip, double[] base, double[] knee, double[] foot) {}

    /** Everything the controller decided on one tick. */
    public record TickState(
            int t,
            /** Planned path state (x, z, yaw, gait clock, v, ω) at this tick. */
            PathPlanner.State path,
            /** The command given on this tick. */
            Scenarios.Command command,
            /** Planned hull pose: path + sway, planned height, pitch and roll. */
            Pose planned,
            /** Actual hull pose: the suspension follower of the planned one (D1). */
            Pose actual,
            /** Vertical speed of the actual hull, b/s. */
            double actualVy,
            /** Peak horizontal acceleration (b/s²) this tick of a hull on this suspension following the plan (path + sway). */
            double hullAccel,
            List<LegOut> legs) {}

    // ---------------------------------------------------------------- state

    public final WalkerSpec spec;
    public final Terrain terrain;
    public final List<SimLeg> legs;
    /** Warm-up ticks run before tick 0 (ceil(2 · cycleTicks)). */
    public final int warm;
    private final PathPlanner path;
    private final WalkerSpec.FootholdSearch search;
    private final double duty, T;
    private final List<Map<Integer, Foothold>> fhCache = new ArrayList<>();
    private int probeCount = 0;

    private Double bodyY = null;
    private double pitch = 0, roll = 0, shiftX = 0, shiftZ = 0;
    // actual hull (suspension follower, D1): height, pitch, roll and their rates
    private final double wn, zeta, hSub, followDen;
    private static final int SUB = 8;
    private boolean actInit = false;
    private double actY, actVy, actP, actVp, actR, actVr;
    private final double comShift, horizon, kS;
    private final double[] comLocal;

    // Horizontal demand (mirrors simulate.js): planned path + sway run through the suspension follower, with the
    // plan interpolated across the sub-steps. Diagnostic only.
    private boolean hfInit = false;
    private double hfx, hfz, hfvx, hfvz, hpx, hpz;

    private int t; // next tick to run
    private TickState last;

    /**
     * Start a walker standing (or moving) in steady state on {@code initial}, hull origin at (startX, 0) facing +x,
     * and run the warm-up ticks so the first {@link #tick} is tick 0.
     */
    public GaitController(WalkerSpec spec, Terrain terrain, Scenarios.Command initial, double startX) {
        if (!spec.errors.isEmpty()) throw new IllegalArgumentException("Invalid walker spec: " + String.join("; ", spec.errors));
        this.spec = spec;
        this.terrain = terrain;
        T = spec.gait.cycleTicks;
        duty = spec.gait.duty;
        warm = (int) Math.ceil(T * 2);
        List<SimLeg> ls = new ArrayList<>();
        for (WalkerSpec.Leg l : spec.legs) ls.add(new SimLeg(l, spec));
        legs = Collections.unmodifiableList(ls);
        for (int i = 0; i < legs.size(); i++) fhCache.add(new HashMap<>());
        search = spec.gait.footholdSearch;
        path = new PathPlanner(spec, legs, initial, -warm, startX);

        WalkerSpec.Suspension susp = spec.physics.suspension;
        wn = 2 * Math.PI * susp.frequency();
        zeta = susp.damping();
        hSub = 1.0 / TPS / SUB;
        // Backward-Euler substeps: stable for any frequency and damping ratio (overdamped settings just settle).
        followDen = 1 + 2 * zeta * wn * hSub + wn * wn * hSub * hSub;
        comShift = clamp(spec.gait.comShift == null ? 0 : spec.gait.comShift, 0, 1);
        horizon = (1 - duty) * T + 2;
        kS = clamp(spec.gait.bodySmoothing, 0.01, 1);
        comLocal = add(new double[] {0, spec.body.size[1] / 2, 0}, spec.body.comOffset);

        t = -warm;
        while (t < 0) advance(initial);
    }

    /** Standing start at the origin. */
    public GaitController(WalkerSpec spec, Terrain terrain) {
        this(spec, terrain, Scenarios.Command.STOP, 0);
    }

    /** The tick the next call to {@link #tick} runs. */
    public int nextTick() { return t; }

    /** The planned path (hull x/z/yaw and gait clock history, causal predictions). */
    public PathPlanner path() { return path; }

    /** The result of the latest tick (a warm-up tick right after construction). */
    public TickState last() { return last; }

    /** Foothold probes done so far (cached decisions only, like the JS). */
    public int probeCount() { return probeCount; }

    /** Every decided foothold of leg li so far, by stance index. */
    public Collection<Foothold> decidedFootholds(int li) { return Collections.unmodifiableCollection(fhCache.get(li).values()); }

    /** Run one tick with the command the driver gives on it. */
    public TickState tick(Scenarios.Command command) {
        return advance(command);
    }

    private TickState advance(Scenarios.Command command) {
        if (t >= 0) path.step(command); // integrates tick t+1 from tick t's command; tick t's state is unchanged
        TickState s = run(t, command);
        last = s;
        t++;
        return s;
    }

    /**
     * Recompute every decided foothold's tStart/tEnd from the path known now. When a foothold is decided its
     * stance usually lies in the future, so these start as estimates (extrapolated at the current gait rate).
     */
    public void refreshStanceTimes() {
        for (int li = 0; li < legs.size(); li++) {
            for (Foothold f : fhCache.get(li).values()) {
                double P0 = f.k - legs.get(li).phase;
                f.tStart = path.timeAtPhase(P0);
                f.tEnd = path.timeAtPhase(P0 + duty);
            }
        }
    }

    // ---- foothold planner (memoised per leg & stance index) ----
    // A foothold for stance k is decided when the leg lifts off for the swing before it (end of stance k-1),
    // from the state known at that tick (causal; see PathPlanner.predict). Before that, callers that need a
    // provisional guess (sway look-ahead) get one from the current state, which is not cached.

    private static Pose flatPose(PathPlanner.Pose2 p) { return new Pose(new double[] {p.x(), 0, p.z()}, p.yaw(), 0, 0); }

    private Foothold plan(int li, int k, double tDecide) {
        SimLeg leg = legs.get(li);
        double P0 = k - leg.phase; // gait clock when stance k begins
        PathPlanner.Pose2 mid = path.predict(tDecide, P0 + duty / 2);
        double[] w = bodyToWorld(flatPose(mid), leg.footLocal);
        Foothold f = planFoothold(terrain, w[0], w[2], search, mid.yaw());
        f.k = k;
        f.yaw = mid.yaw();
        f.tStart = path.timeAtPhase(P0);
        f.tEnd = path.timeAtPhase(P0 + duty);
        f.tDecided = tDecide;
        return f;
    }

    /**
     * Lift-off tick of stance k-1: the first tick on which this leg is past stance k-1 (in its swing or later).
     * Uses exactly the main loop's test (legAt), so a foothold is never decided after the tick that needs it.
     */
    private double liftTick(int li, int k) {
        SimLeg leg = legs.get(li);
        return path.firstTick(P -> {
            LegPhase s = legAt(leg, P);
            return s.k() > k - 1 || (s.k() == k - 1 && !s.stance());
        });
    }

    private Foothold foothold(int li, int k, double asOf) {
        Foothold f = fhCache.get(li).get(k);
        if (f != null) return f;
        double tl = Math.max(-warm, liftTick(li, k));
        if (tl > asOf) return plan(li, k, asOf); // not decided yet: provisional, not cached
        f = plan(li, k, Double.isFinite(tl) ? tl : -warm);
        probeCount += f.probes;
        fhCache.get(li).put(k, f);
        return f;
    }

    private double[] follow(double x, double xv, double target) {
        for (int i = 0; i < SUB; i++) {
            xv = (xv + wn * wn * (target - x) * hSub) / followDen;
            x += xv * hSub;
        }
        return new double[] {x, xv};
    }

    private TickState run(int t, Scenarios.Command command) {
        PathPlanner.State P = path.at(t);
        // 1. leg targets in world space
        List<LegState> legState = new ArrayList<>(legs.size());
        for (int li = 0; li < legs.size(); li++) {
            SimLeg leg = legs.get(li);
            LegPhase lp = legAt(leg, P.phase());
            int k = lp.k();
            Foothold A = foothold(li, k, Double.POSITIVE_INFINITY);
            if (lp.stance()) {
                legState.add(new LegState(true, new double[] {A.x, A.y, A.z}, A.isVoid ? null : A.y, A, null, k, null));
                continue;
            }
            Foothold B = foothold(li, k + 1, Double.POSITIVE_INFINITY);
            double s = (lp.phi() - duty) / (1 - duty);
            SwingPoint sp = swingPoint(A, B, s, spec.gait, terrain);
            double ga = A.isVoid ? B.y : A.y, gb = B.isVoid ? A.y : B.y;
            legState.add(new LegState(false, sp.pos(), A.isVoid && B.isVoid ? null : lerp(ga, gb, sp.hx()), A, B, k, s));
        }

        // 2. planned body pose follows the ground under the legs
        List<Integer> grounded = new ArrayList<>();
        double gSum = 0;
        for (int i = 0; i < legState.size(); i++) {
            if (legState.get(i).ground() != null) {
                grounded.add(i);
                gSum += legState.get(i).ground();
            }
        }
        double gMean = !grounded.isEmpty() ? gSum / grounded.size() : 0;
        double yTarget = gMean + spec.body.rideHeight;
        double pTarget = 0, rTarget = 0;
        if (spec.gait.bodyAdapt.equals("pitch") && grounded.size() >= 2) {
            pTarget = clamp(fit(grounded, legState, gMean, 0), -0.45, 0.45);
            rTarget = -clamp(fit(grounded, legState, gMean, 2), -0.3, 0.3);
        }
        if (bodyY == null) bodyY = yTarget;
        else bodyY += (yTarget - bodyY) * kS;
        pitch += (pTarget - pitch) * kS;
        roll += (rTarget - roll) * kS;
        // optional sway: shift the hull toward the point that is safest for the support polygons
        // of the next swing window (coarse→fine grid search). This is what makes crawl gaits stable.
        if (comShift > 0) sway(t, P);
        // actual hull: damped second-order follower of the planned height / pitch / roll
        if (!actInit) {
            actInit = true;
            actY = bodyY; actVy = 0; actP = pitch; actVp = 0; actR = roll; actVr = 0;
        } else {
            double[] r;
            r = follow(actY, actVy, bodyY); actY = r[0]; actVy = r[1];
            r = follow(actP, actVp, pitch); actP = r[0]; actVp = r[1];
            r = follow(actR, actVr, roll); actR = r[0]; actVr = r[1];
        }
        Pose pose = new Pose(new double[] {P.x() + shiftX, actY, P.z() + shiftZ}, P.yaw(), actP, actR);
        // the plan moves once per tick: interpolate it across the substeps so a steady walk isn't a staircase
        double tx = P.x() + shiftX, tz = P.z() + shiftZ;
        if (!hfInit) { hfx = tx; hfz = tz; hpx = tx; hpz = tz; hfInit = true; }
        double hullAccel = 0;
        for (int i = 1; i <= SUB; i++) {
            double gx = hpx + ((tx - hpx) * i) / SUB, gz = hpz + ((tz - hpz) * i) / SUB;
            double nvx = (hfvx + wn * wn * (gx - hfx) * hSub) / followDen;
            double nvz = (hfvz + wn * wn * (gz - hfz) * hSub) / followDen;
            hullAccel = Math.max(hullAccel, SimMath.hypot(nvx - hfvx, nvz - hfvz) / hSub);
            hfvx = nvx; hfvz = nvz;
            hfx += hfvx * hSub; hfz += hfvz * hSub;
        }
        hpx = tx; hpz = tz;
        Pose planned = new Pose(new double[] {P.x() + shiftX, bodyY, P.z() + shiftZ}, P.yaw(), pitch, roll);

        // 3. IK per leg (legs hang off the actual hull)
        List<LegOut> legOut = new ArrayList<>(legs.size());
        for (int li = 0; li < legs.size(); li++) {
            SimLeg leg = legs.get(li);
            LegState st = legState.get(li);
            double[] tl = worldToBody(pose, st.target());
            LegSolution sol = solveLeg(leg, tl);
            legOut.add(new LegOut(st, sol,
                    bodyToWorld(pose, leg.mount),
                    bodyToWorld(pose, sol.base()),
                    bodyToWorld(pose, sol.knee()),
                    bodyToWorld(pose, sol.foot())));
        }
        return new TickState(t, P, command, planned, pose, actVy, hullAccel, Collections.unmodifiableList(legOut));
    }

    private double fit(List<Integer> grounded, List<LegState> legState, double gMean, int axis) {
        int m = grounded.size();
        double[] xs = new double[m];
        double sum = 0;
        for (int i = 0; i < m; i++) { xs[i] = legs.get(grounded.get(i)).footLocal[axis]; sum += xs[i]; }
        double mx = sum / m;
        double num = 0, den = 0;
        for (int i = 0; i < m; i++) {
            double g = legState.get(grounded.get(i)).ground();
            double d = xs[i] - mx;
            num += d * (g - gMean);
            den += d * d; // JS: (xs[i] - mx) ** 2, which fdlibm/V8 pow computes as x * x
        }
        return den > 1e-6 ? StrictMath.atan(num / den) : 0;
    }

    private void sway(int t, PathPlanner.State P) {
        List<List<double[]>> sets = new ArrayList<>();
        for (int j = 0; j <= 3; j++) {
            double ph = P.phase() + path.rateAt(t) * (j / 3.0) * horizon; // extrapolated from the current rate
            List<double[]> pts = new ArrayList<>();
            for (int li = 0; li < legs.size(); li++) {
                LegPhase s = legAt(legs.get(li), ph);
                if (s.stance()) {
                    Foothold f = foothold(li, s.k(), t);
                    if (!f.isVoid) pts.add(new double[] {f.x, f.z});
                }
            }
            if (pts.size() >= 3) sets.add(convexHull(pts));
        }
        if (sets.isEmpty()) return;
        double[] cw = bodyToWorld(new Pose(new double[] {P.x(), 0, P.z()}, P.yaw(), 0, 0), comLocal);
        double c0x = cw[0], c0z = cw[2];
        double bestX = 0, bestZ = 0;
        double bestS = swayScore(sets, c0x, c0z, 0, 0);
        double[][] passes = {{0.5, 2, 0, 0}, {0.125, 0.375, Double.NaN, Double.NaN}};
        for (double[] pass : passes) {
            double stepSize = pass[0], span = pass[1];
            double ox = Double.isNaN(pass[2]) ? bestX : pass[2], oz = Double.isNaN(pass[3]) ? bestZ : pass[3];
            for (double dx = -span; dx <= span + 1e-9; dx += stepSize)
                for (double dz = -span; dz <= span + 1e-9; dz += stepSize) {
                    double sc = swayScore(sets, c0x, c0z, ox + dx, oz + dz);
                    if (sc > bestS) { bestS = sc; bestX = ox + dx; bestZ = oz + dz; }
                }
        }
        shiftX += (bestX * comShift - shiftX) * kS;
        shiftZ += (bestZ * comShift - shiftZ) * kS;
    }

    private static double swayScore(List<List<double[]>> sets, double c0x, double c0z, double dx, double dz) {
        double[] p = {c0x + dx, c0z + dz};
        double m = Double.POSITIVE_INFINITY;
        for (List<double[]> h : sets) m = Math.min(m, hullMargin(p, h));
        return m - 0.02 * hypot(dx, dz);
    }
}
