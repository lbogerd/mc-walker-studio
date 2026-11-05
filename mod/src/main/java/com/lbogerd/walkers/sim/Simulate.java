// Mirrors simulate() in src/sim/simulate.js (the batch run: step 4 measurements, events, series, summary),
// plus defaultTicks, courseTicks and COLLISION_TYPES.
//
// Batch simulate = a GaitController fed a known command timeline one tick at a time. Afterwards the path is
// run out past the course (as the JS plans it) so the footholds' stance times match the JS.
package com.lbogerd.walkers.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lbogerd.walkers.sim.SimMath.Pose;
import static com.lbogerd.walkers.sim.SimMath.TPS;
import static com.lbogerd.walkers.sim.SimMath.bodyToWorld;
import static com.lbogerd.walkers.sim.SimMath.clamp;
import static com.lbogerd.walkers.sim.SimMath.deg;
import static com.lbogerd.walkers.sim.SimMath.dist;
import static com.lbogerd.walkers.sim.SimMath.heading;
import static com.lbogerd.walkers.sim.SimMath.hypot;
import static com.lbogerd.walkers.sim.SimMath.supportMargin;
import static com.lbogerd.walkers.sim.SimMath.toFixed;

public final class Simulate {
    private Simulate() {}

    public static final List<String> COLLISION_TYPES =
            List.of("toe-stub", "knee-clip", "body-scrape", "void-step", "foot-clash", "step-too-high");

    /** Default run length: long enough to cover distance blocks and at least 4 gait cycles. */
    public static int defaultTicks(WalkerSpec spec, double distance) {
        double T = spec.gait.cycleTicks;
        double v = spec.gait.stride / T;
        return (int) clamp(Math.ceil(Math.max(4 * T, distance / Math.max(v, 1e-3))), 40, 4000);
    }

    /** Run length for a course: its command timeline if it has one, else defaultTicks. */
    public static int courseTicks(WalkerSpec spec, List<Scenarios.Segment> commands, double distance) {
        double n = commands != null ? Scenarios.commandTicks(commands) : Double.POSITIVE_INFINITY;
        return Double.isFinite(n) ? (int) n : defaultTicks(spec, distance);
    }

    public static final class Options {
        /** Run length; null = courseTicks. */
        public Integer ticks = null;
        public double distance = 60;
        public double startX = 0;
        /** Keep per-tick frames. */
        public boolean frames = true;
        /** Overrides the course's commands when non-null. */
        public List<Scenarios.Segment> commands = null;
    }

    public record Event(String type, int t, String leg, String detail) {}

    public record LegFrame(double[] hip, double[] base, double[] knee, double[] foot, boolean stance, double err, double[] next) {}

    public record Frame(int t, double phase, Pose actual, Pose planned, Scenarios.Command cmd, double v, double yawRate,
                        double activity, double[] com, List<double[]> hull, double margin, double torque, List<LegFrame> legs) {}

    /** Per-tick series, ticks 0..ticks. clearance is NaN where the JS stores null. */
    public static final class Series {
        public final List<Double> margin = new ArrayList<>(), bodyY = new ArrayList<>(), reach = new ArrayList<>(),
                footErr = new ArrayList<>(), stance = new ArrayList<>(), clearance = new ArrayList<>(),
                speed = new ArrayList<>(), torque = new ArrayList<>(), trackErr = new ArrayList<>();
    }

    public static final class Summary {
        public double speed, topSpeed, distance, netForward, netDisplacement, yawTotalDeg, yawRateMaxDegS, maxYawRateDegS,
                endSpeed, endYawRateDegS, endPlanted, marginMin, stanceMin, reachMax, footErrMax, clearanceMin, bob, vyMax, hullAccelMax,
                pitchMaxDeg, jointSpeedMaxDegS, torqueMax, stabStress, trackErrMax, tiltErrMaxDeg, collisions,
                refUsPerTick, footholdProbesPerTick;
        /** null without a stabilizer. */
        public Double stabLoad;
        public Map<String, Integer> eventCounts;

        /** The numeric fields by their JS names (stabLoad may be null). */
        public Map<String, Double> numbers() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("speed", speed); m.put("topSpeed", topSpeed); m.put("distance", distance); m.put("netForward", netForward);
            m.put("netDisplacement", netDisplacement); m.put("yawTotalDeg", yawTotalDeg); m.put("yawRateMaxDegS", yawRateMaxDegS);
            m.put("maxYawRateDegS", maxYawRateDegS); m.put("endSpeed", endSpeed); m.put("endYawRateDegS", endYawRateDegS);
            m.put("endPlanted", endPlanted); m.put("marginMin", marginMin); m.put("stanceMin", stanceMin); m.put("reachMax", reachMax);
            m.put("footErrMax", footErrMax); m.put("clearanceMin", clearanceMin); m.put("bob", bob); m.put("vyMax", vyMax); m.put("hullAccelMax", hullAccelMax);
            m.put("pitchMaxDeg", pitchMaxDeg); m.put("jointSpeedMaxDegS", jointSpeedMaxDegS); m.put("torqueMax", torqueMax);
            m.put("stabLoad", stabLoad); m.put("stabStress", stabStress); m.put("trackErrMax", trackErrMax);
            m.put("tiltErrMaxDeg", tiltErrMaxDeg); m.put("collisions", collisions); m.put("refUsPerTick", refUsPerTick);
            m.put("footholdProbesPerTick", footholdProbesPerTick);
            return m;
        }
    }

    public record Result(int ticks, double cycleTicks, List<Frame> frames, List<List<GaitController.Foothold>> footholds,
                         List<Event> events, Series series, Summary summary) {}

    public static Result simulate(WalkerSpec spec, Scenarios.CourseTerrain course) {
        return simulate(spec, course, course.commands(), new Options());
    }

    public static Result simulate(WalkerSpec spec, Scenarios.CourseTerrain course, Options opts) {
        return simulate(spec, course, opts.commands != null ? opts.commands : course.commands(), opts);
    }

    /**
     * Run the walking loop over a known command timeline (null = full throttle forward).
     */
    public static Result simulate(WalkerSpec spec, Terrain terrain, List<Scenarios.Segment> commandsIn, Options opts) {
        final List<Scenarios.Segment> commands = opts.commands != null ? opts.commands : commandsIn;
        double T = spec.gait.cycleTicks;
        int ticks = opts.ticks != null ? opts.ticks : courseTicks(spec, commands, opts.distance);
        int tEndPlan = ticks + (int) Math.ceil((4 * T) / spec.gait.settleRate) + 10;

        long t0 = System.nanoTime();
        int warmGuess = (int) Math.ceil(T * 2);
        GaitController ctl = new GaitController(spec, terrain, Scenarios.commandAt(commands, Math.min(-1, -warmGuess)), opts.startX);
        long warmNanos = System.nanoTime() - t0;
        int warm = ctl.warm;
        List<GaitController.SimLeg> legs = ctl.legs;
        PathPlanner path = ctl.path();

        List<Event> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Frame> frames = new ArrayList<>();
        Series series = new Series();
        // neighbours on each side, front first
        List<List<Integer>> sideGroups = new ArrayList<>();
        for (int s : new int[] {1, -1}) {
            List<Integer> g = new ArrayList<>();
            for (int i = 0; i < legs.size(); i++) if (legs.get(i).side == s) g.add(i);
            g.sort((a, b) -> SpecNormalizer.jsCompare(legs.get(b).mount[0] - legs.get(a).mount[0]));
            sideGroups.add(g);
        }

        double[] prevAngles = null;
        double jointSpeedMax = 0, reachMax = 0, footErrMax = 0, marginMin = Double.POSITIVE_INFINITY, stanceMin = Double.POSITIVE_INFINITY;
        double clearanceMin = Double.POSITIVE_INFINITY, pitchMax = 0, vyMax = 0, torqueMax = 0, trackErrMax = 0, tiltErrMax = 0, hullAccelMax = 0;
        double pathLength = 0, yawRateMax = 0;
        long solveNanos = warmNanos;
        int solveTicks = warm;
        double[] comLocal = SimMath.add(new double[] {0, spec.body.size[1] / 2, 0}, spec.body.comOffset);
        double sx = spec.body.size[0], sz = spec.body.size[2];
        List<double[]> bottomSamples = new ArrayList<>();
        for (int ix = 0; ix <= Math.ceil(sx); ix++)
            for (int iz = 0; iz <= Math.ceil(sz); iz++)
                bottomSamples.add(new double[] {-sx / 2 + (ix / Math.ceil(sx)) * sx, 0, -sz / 2 + (iz / Math.ceil(sz)) * sz});

        for (int t = 0; t <= ticks; t++) {
            Scenarios.Command cmd = Scenarios.commandAt(commands, t);
            long ts = System.nanoTime();
            GaitController.TickState state = ctl.tick(cmd);
            solveNanos += System.nanoTime() - ts;
            solveTicks++;
            PathPlanner.State P = state.path();
            Pose pose = state.actual();
            Pose planned = state.planned();
            double bodyY = planned.pos()[1];

            // 4. measurements
            List<double[]> support = new ArrayList<>();
            double reach = 0, ferr = 0;
            int stanceN = 0;
            double[] angles = new double[legs.size() * 3];
            int cycle = (int) Math.floor(P.phase());
            for (int li = 0; li < legs.size(); li++) {
                GaitController.SimLeg leg = legs.get(li);
                GaitController.LegOut o = state.legs().get(li);
                GaitController.LegState st = o.st();
                double err = dist(o.foot(), st.target());
                reach = Math.max(reach, o.sol().util());
                ferr = Math.max(ferr, st.stance() ? err : 0);
                if (st.stance() && !st.fh().isVoid) {
                    stanceN++;
                    support.add(new double[] {o.foot()[0], o.foot()[2]});
                }
                if (st.stance() && st.fh().isVoid) emit(events, seen, "void-step", t, leg.id, "Foot planted over the void (no ground)", st.k());
                if (o.sol().status().equals("over") && err > 0.05 && !(st.stance() && st.fh().isVoid))
                    emit(events, seen, "overreach", t, leg.id, "Target " + toFixed(err, 2) + " b beyond reach", st.k());
                if (o.sol().status().equals("under") && err > 0.05)
                    emit(events, seen, "overcompress", t, leg.id, "Target " + toFixed(err, 2) + " b inside min reach", st.k());
                if (!st.stance() && st.s() > 0.08 && st.s() < 0.92) {
                    int h = terrain.heightAt(o.foot()[0], o.foot()[2]);
                    if (h > Terrain.VOID && o.foot()[1] < h - 0.05)
                        emit(events, seen, "toe-stub", t, leg.id, "Swinging foot passes through a block", st.k());
                }
                int kh = terrain.heightAt(o.knee()[0], o.knee()[2]);
                if (kh > Terrain.VOID && o.knee()[1] < kh - 0.05) emit(events, seen, "knee-clip", t, leg.id, "Knee inside terrain", st.k());
                if (!st.stance() && st.s() < 0.05 && st.next() != null && spec.gait.maxStepUp != null) {
                    GaitController.Foothold A = st.fh(), B = st.next();
                    if (!A.isVoid && !B.isVoid && B.y - A.y > spec.gait.maxStepUp)
                        emit(events, seen, "step-too-high", t, leg.id, "Step of " + SimMath.jsString(B.y - A.y)
                                + " b exceeds maxStepUp " + SimMath.jsString(spec.gait.maxStepUp), st.k());
                }
                // joint angles relative to the hull (hull yaw/pitch is not joint motion)
                double[] ja = GaitController.jointAngles(leg, leg.mount, o.sol().base(), o.sol().knee(), o.sol().foot());
                System.arraycopy(ja, 0, angles, li * 3, 3);
            }
            // foot clash between neighbours on the same side
            for (List<Integer> group : sideGroups) {
                for (int g = 0; g + 1 < group.size(); g++) {
                    double[] a = state.legs().get(group.get(g)).foot(), b = state.legs().get(group.get(g + 1)).foot();
                    if (hypot(a[0] - b[0], a[2] - b[2]) < 0.6)
                        emit(events, seen, "foot-clash", t, legs.get(group.get(g)).id + "/" + legs.get(group.get(g + 1)).id,
                                "Neighbouring feet closer than 0.6 b", cycle);
                }
            }
            if (prevAngles != null) {
                for (int i = 0; i < angles.length; i++) {
                    double d = Math.abs(angles[i] - prevAngles[i]);
                    if (d > Math.PI) d = 2 * Math.PI - d;
                    jointSpeedMax = Math.max(jointSpeedMax, d);
                }
            }
            prevAngles = angles;

            double[] comW = bodyToWorld(pose, comLocal);
            SimMath.Support sup = supportMargin(new double[] {comW[0], comW[2]}, support);
            double margin = sup.margin();
            double clearance = Double.POSITIVE_INFINITY;
            for (double[] p : bottomSamples) {
                double[] w = bodyToWorld(pose, p);
                int h = terrain.heightAt(w[0], w[2]);
                if (h > Terrain.VOID) clearance = Math.min(clearance, w[1] - h);
            }
            if (clearance < 0) emit(events, seen, "body-scrape", t, "body", "Hull " + toFixed(-clearance, 2) + " b into terrain", cycle);
            // righting torque the hull needs to not tip (D6): mass × how far the CoM is outside the support polygon
            double torque = spec.mass * Math.max(0, -margin);
            double trackErr = pose.pos()[1] - bodyY;

            marginMin = Math.min(marginMin, margin);
            stanceMin = Math.min(stanceMin, stanceN);
            reachMax = Math.max(reachMax, reach);
            footErrMax = Math.max(footErrMax, ferr);
            clearanceMin = Math.min(clearanceMin, clearance);
            pitchMax = Math.max(pitchMax, Math.max(Math.abs(pose.pitch()), Math.abs(pose.roll())));
            vyMax = Math.max(vyMax, Math.abs(state.actualVy()) / TPS);
            hullAccelMax = Math.max(hullAccelMax, state.hullAccel());
            torqueMax = Math.max(torqueMax, torque);
            trackErrMax = Math.max(trackErrMax, Math.abs(trackErr));
            tiltErrMax = Math.max(tiltErrMax, Math.max(Math.abs(pose.pitch() - planned.pitch()), Math.abs(pose.roll() - planned.roll())));
            if (t < ticks) pathLength += Math.abs(path.at(t + 1).v());
            yawRateMax = Math.max(yawRateMax, Math.abs(P.w()));

            series.margin.add(margin);
            series.bodyY.add(pose.pos()[1]);
            series.reach.add(reach);
            series.footErr.add(ferr);
            series.stance.add((double) stanceN);
            series.clearance.add(Double.isFinite(clearance) ? clearance : Double.NaN);
            series.speed.add(Math.abs(P.v()) * TPS);
            series.torque.add(torque);
            series.trackErr.add(trackErr);

            if (opts.frames) {
                List<LegFrame> lf = new ArrayList<>(legs.size());
                for (GaitController.LegOut o : state.legs()) {
                    GaitController.Foothold nx = o.st().next();
                    lf.add(new LegFrame(o.hip(), o.base(), o.knee(), o.foot(), o.st().stance(), dist(o.foot(), o.st().target()),
                            nx != null && !nx.isVoid ? nx.pos() : null));
                }
                frames.add(new Frame(t, P.phase(), pose, planned, cmd, P.v() * TPS, deg(P.w()) * TPS, P.activity(), comW,
                        sup.hull(), margin, torque, Collections.unmodifiableList(lf)));
            }
        }

        // run the path out past the course (the JS plans it to tEndPlan) for the footholds' stance times
        for (int t = path.lastTick(); t < tEndPlan; t++) path.step(Scenarios.commandAt(commands, t));
        ctl.refreshStanceTimes();

        // bob = spread of body height after removing the one-cycle moving average (terrain trend)
        int win = (int) Math.max(1, SimMath.jsRound(T));
        List<Double> ys = series.bodyY;
        double hpMax = Double.NEGATIVE_INFINITY, hpMin = Double.POSITIVE_INFINITY;
        boolean anyHp = false;
        double acc = 0;
        for (int i = 0; i < ys.size(); i++) {
            acc += ys.get(i);
            if (i >= win) acc -= ys.get(i - win);
            if (i >= win) {
                double hp = ys.get(i - win / 2) - acc / win;
                anyHp = true;
                hpMax = Math.max(hpMax, hp);
                hpMin = Math.min(hpMin, hp);
            }
        }
        double bob = anyHp ? hpMax - hpMin : 0;

        List<List<GaitController.Foothold>> footholds = new ArrayList<>();
        for (int li = 0; li < legs.size(); li++) {
            List<GaitController.Foothold> l = new ArrayList<>();
            for (GaitController.Foothold f : ctl.decidedFootholds(li)) if (f.tStart <= ticks && f.tEnd >= 0) l.add(f);
            l.sort((a, b) -> Integer.compare(a.k, b.k));
            footholds.add(Collections.unmodifiableList(l));
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Event e : events) counts.merge(e.type(), 1, Integer::sum);

        // stabilizer (D6): capped righting torque, drawing stress in proportion to the torque it applies
        WalkerSpec.Stabilizer stab = spec.physics.stabilizer;
        Double stabLoad = stab != null ? torqueMax / stab.maxTorque() : null;
        double stabStress = stab != null ? Math.min(torqueMax, stab.maxTorque()) * stab.stressPerTorque() : 0;

        PathPlanner.State start = path.at(0), end = path.at(ticks);
        double[] h0 = heading(start.yaw());
        double secs = Math.max(1, ticks) / (double) TPS;
        events.sort((a, b) -> Integer.compare(a.t(), b.t())); // stable, like Array.prototype.sort

        Summary s = new Summary();
        s.speed = pathLength / secs; // achieved average ground speed (b/s, path length / time)
        s.topSpeed = path.vTop() * TPS;
        s.distance = pathLength;
        s.netForward = (end.x() - start.x()) * h0[0] + (end.z() - start.z()) * h0[2]; // along the starting heading
        s.netDisplacement = hypot(end.x() - start.x(), end.z() - start.z());
        s.yawTotalDeg = deg(end.yaw() - start.yaw());
        s.yawRateMaxDegS = deg(yawRateMax) * TPS;
        s.maxYawRateDegS = deg(path.wTop()) * TPS;
        s.endSpeed = Math.abs(end.v()) * TPS;
        s.endYawRateDegS = Math.abs(deg(end.w())) * TPS;
        s.endPlanted = series.stance.isEmpty() ? 0 : series.stance.get(series.stance.size() - 1);
        s.marginMin = marginMin;
        s.stanceMin = stanceMin;
        s.reachMax = reachMax;
        s.footErrMax = footErrMax;
        s.clearanceMin = clearanceMin;
        s.bob = bob;
        s.vyMax = vyMax * TPS;
        s.hullAccelMax = hullAccelMax;
        s.pitchMaxDeg = deg(pitchMax);
        s.jointSpeedMaxDegS = deg(jointSpeedMax) * TPS;
        s.torqueMax = torqueMax;
        s.stabLoad = stabLoad;
        s.stabStress = stabStress;
        s.trackErrMax = trackErrMax;
        s.tiltErrMaxDeg = deg(tiltErrMax);
        s.eventCounts = counts;
        s.collisions = events.stream().filter(e -> COLLISION_TYPES.contains(e.type())).count();
        s.refUsPerTick = (solveNanos / 1000.0) / Math.max(1, solveTicks);
        s.footholdProbesPerTick = (double) ctl.probeCount() / (ticks + warm);

        return new Result(ticks, T, Collections.unmodifiableList(frames), Collections.unmodifiableList(footholds),
                Collections.unmodifiableList(events), series, s);
    }

    private static void emit(List<Event> events, Set<String> seen, String type, int t, String legId, String detail, int key) {
        String k = type + "|" + legId + "|" + key;
        if (!seen.add(k)) return;
        events.add(new Event(type, t, legId, detail));
    }
}
