// Mirrors planPath in src/sim/simulate.js (pass 1: driver commands → hull path and gait clock).
//
// The JS integrates a whole known command timeline up front. This port is incremental so a live controller
// can feed it one command per tick: the constructor lays down the warm-up history (ticks tA..0, steady state
// on the initial command, exactly like the JS), and step(command) appends the state for the next tick. Every
// query below only reads ticks that already exist, so a planner fed the same commands produces the same
// arrays as the JS, tick for tick.
//
//  vTop  = stride / cycleTicks                 (b/tick, top speed at the drive RPM, D7)
//  ωTop  = vTop / turnRadius                   (rad/tick, turning in place at full steer)
//  target (v, ω) = (throttle · vTop, steer · ωTop), then scaled down together so the fastest foot
//         speed max_i |(v + ω·z_i, −ω·x_i)| stays ≤ vTop (outer legs set the limit when curving)
//  v, ω move toward the target by at most accel/20² b/tick² and (accel/turnRadius)/20² rad/tick²
//  activity a = max(|v|/vTop, |ω|/ωTop, footSpeedMax/vTop) ∈ [0, 1]
//  dΦ/dt = max(a, settleRate) / cycleTicks while moving; when stopped (v = ω = 0, no command) Φ runs on
//         at settleRate until the rest stance is reached (all feet planted) and then freezes.
package com.lbogerd.walkers.sim;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoublePredicate;

import static com.lbogerd.walkers.sim.SimMath.TPS;
import static com.lbogerd.walkers.sim.SimMath.clamp;
import static com.lbogerd.walkers.sim.SimMath.frac;
import static com.lbogerd.walkers.sim.SimMath.heading;
import static com.lbogerd.walkers.sim.SimMath.lerp;

public final class PathPlanner {
    /** Planned hull state at an integer tick. v in b/tick, w in rad/tick. */
    public record State(int t, double x, double z, double yaw, double phase, double v, double w, double activity) {}

    /** Hull pose on the ground plane. */
    public record Pose2(double x, double z, double yaw) {}

    private final double T, duty, vTop, wTop, settle, restPhase;
    private double aLin, aYaw;
    private final double aLin0, aYaw0;
    private final int restN;
    private final double[][] feet;
    private final double[] legPhase;
    /** First tick held: the start of the warm-up (-warm), or later once {@link #trimBefore} dropped old history. */
    public int tA;
    private final double v0, w0, rate0;

    private int n; // number of ticks held: tA .. tA + n - 1
    private double[] X, Z, YAW, PH, V, W, ACT, TV, TW; // TV/TW: saturated command target given at each tick

    PathPlanner(WalkerSpec spec, List<GaitController.SimLeg> legs, Scenarios.Command initial, int tA, double x0) {
        T = spec.gait.cycleTicks;
        duty = spec.gait.duty;
        vTop = spec.gait.stride / T;
        double R = spec.turnRadius;
        wTop = vTop / R;
        aLin = aLin0 = spec.gait.accel / (TPS * TPS);
        aYaw = aYaw0 = aLin / R;
        settle = spec.gait.settleRate;
        restPhase = spec.gait.restPhase;
        restN = spec.gait.restPlanted; // feet planted in the rest stance
        feet = new double[legs.size()][];
        legPhase = new double[legs.size()];
        for (int i = 0; i < legs.size(); i++) {
            feet[i] = new double[] {legs.get(i).footLocal[0], legs.get(i).footLocal[2]};
            legPhase[i] = legs.get(i).phase;
        }
        this.tA = tA;

        int cap = Math.max(64, 2 * (1 - tA));
        X = new double[cap]; Z = new double[cap]; YAW = new double[cap]; PH = new double[cap];
        V = new double[cap]; W = new double[cap]; ACT = new double[cap]; TV = new double[cap]; TW = new double[cap];
        int i0 = -tA; // index of tick 0
        n = i0 + 1;
        // before tick 0 the walker is in steady state on the first command
        double[] vw0 = target(initial);
        v0 = vw0[0];
        w0 = vw0[1];
        boolean moving0 = v0 != 0 || w0 != 0;
        rate0 = moving0 ? Math.max(activity(v0, w0), settle) / T : 0;
        X[i0] = x0; Z[i0] = 0; YAW[i0] = 0; PH[i0] = moving0 ? 0 : restPhase;
        V[i0] = v0; W[i0] = w0; ACT[i0] = moving0 ? activity(v0, w0) : 0;
        for (int i = i0 - 1; i >= 0; i--) {
            YAW[i] = YAW[i + 1] - w0;
            double[] h = heading(YAW[i] + w0 / 2);
            X[i] = X[i + 1] - v0 * h[0];
            Z[i] = Z[i + 1] - v0 * h[2];
            PH[i] = PH[i + 1] - rate0;
            V[i] = v0; W[i] = w0; ACT[i] = ACT[i0];
        }
        for (int i = 0; i < i0; i++) { TV[i] = v0; TW[i] = w0; } // warm-up ticks hold the first command
    }

    private double footSpeed(double v, double w) {
        double m = 0;
        for (double[] f : feet) m = Math.max(m, SimMath.hypot(v + w * f[1], w * f[0]));
        return m;
    }

    private double[] saturate(double v, double w) {
        double f = footSpeed(v, w);
        double k = f > vTop ? vTop / f : 1;
        return new double[] {v * k, w * k};
    }

    private double activity(double v, double w) {
        return clamp(Math.max(Math.max(Math.abs(v) / vTop, Math.abs(w) / wTop), footSpeed(v, w) / vTop), 0, 1);
    }

    private int planted(double P) {
        int a = 0;
        for (double ph : legPhase) if (frac(P + ph + GaitController.EPS) < duty) a++;
        return a;
    }

    private double[] target(Scenarios.Command c) {
        return saturate(clamp(c.throttle(), -1, 1) * vTop, clamp(c.steer(), -1, 1) * wTop);
    }

    private static double towards(double x, double goal, double step) {
        return Math.abs(goal - x) <= step ? goal : x + Math.signum(goal - x) * step;
    }

    /** Last tick whose state is known. */
    public int lastTick() { return tA + n - 1; }

    /**
     * Feed the command given on tick lastTick() (which must be ≥ 0: the warm-up is fixed) and integrate the
     * state of the next tick from it.
     */
    public void step(Scenarios.Command c) {
        int i = n - 1;
        if (tA + i < 0) throw new IllegalStateException("warm-up ticks hold the initial command");
        if (n + 1 > X.length) grow();
        double[] tvw = target(c);
        double tv = tvw[0], tw = tvw[1];
        TV[i] = tv;
        TW[i] = tw;
        double[] vw = saturate(towards(V[i], tv, aLin), towards(W[i], tw, aYaw));
        double v = vw[0], w = vw[1];
        double[] h = heading(YAW[i] + w / 2);
        YAW[i + 1] = YAW[i] + w;
        X[i + 1] = X[i] + v * h[0];
        Z[i + 1] = Z[i] + v * h[2];
        double dP;
        if (v == 0 && w == 0 && tv == 0 && tw == 0) {
            // stopped: run on to the rest stance (never past the middle of the rest window), then freeze
            if (planted(PH[i]) >= restN) dP = 0;
            else {
                double goal = PH[i] + frac(restPhase - PH[i]);
                dP = Math.min(settle / T, goal - PH[i]);
            }
        } else dP = Math.max(activity(v, w), settle) / T;
        PH[i + 1] = PH[i] + dP;
        V[i + 1] = v; W[i + 1] = w; ACT[i + 1] = activity(v, w);
        n++;
    }

    /**
     * Live use only (Java, no JS counterpart): forget ticks before {@code t} so a long-running controller's history
     * stays bounded. Batch runs never trim, so they stay identical to src/sim. Queries before the new first tick
     * are no longer answered from history (callers must only ask about recent ticks).
     */
    public void trimBefore(int t) {
        int drop = Math.min(t - tA, n - 2);
        if (drop <= 0) return;
        for (double[] a : new double[][] {X, Z, YAW, PH, V, W, ACT, TV, TW}) System.arraycopy(a, drop, a, 0, n - drop);
        n -= drop;
        tA += drop;
    }

    /**
     * Live use only (no JS counterpart): scale the acceleration limits. When the gait clock runs {@code s} design
     * ticks per real tick (shaft RPM, D7), pass 1/s² so the hull's acceleration in real time stays gait.accel.
     */
    public void setAccelScale(double f) {
        aLin = aLin0 * f;
        aYaw = aYaw0 * f;
    }

    /** Number of ticks currently held. */
    public int heldTicks() { return n; }

    private void grow() {
        int cap = X.length * 2;
        X = Arrays.copyOf(X, cap); Z = Arrays.copyOf(Z, cap); YAW = Arrays.copyOf(YAW, cap); PH = Arrays.copyOf(PH, cap);
        V = Arrays.copyOf(V, cap); W = Arrays.copyOf(W, cap); ACT = Arrays.copyOf(ACT, cap);
        TV = Arrays.copyOf(TV, cap); TW = Arrays.copyOf(TW, cap);
    }

    /** Planned state at integer tick t (tA ≤ t ≤ lastTick()). */
    public State at(int t) {
        int j = t - tA;
        if (j < 0 || j >= n) throw new IndexOutOfBoundsException("tick " + t + " not planned (have " + tA + ".." + lastTick() + ")");
        return new State(t, X[j], Z[j], YAW[j], PH[j], V[j], W[j], ACT[j]);
    }

    /** Top speed in b/tick. */
    public double vTop() { return vTop; }

    /** Max yaw rate (turning in place) in rad/tick. */
    public double wTop() { return wTop; }

    /**
     * First (fractional) tick at which the gait clock reaches P; ±∞ if it never does. Beyond the last known
     * tick it extrapolates at the latest rate (the JS plans a run-out past the course for this).
     */
    public double timeAtPhase(double P) {
        int tB = lastTick();
        double rateEnd = n > 1 ? PH[n - 1] - PH[n - 2] : 0;
        if (P <= PH[0]) return P == PH[0] ? tA : rate0 > 0 ? tA - (PH[0] - P) / rate0 : Double.NEGATIVE_INFINITY;
        if (P > PH[n - 1]) return rateEnd > 0 ? tB + (P - PH[n - 1]) / rateEnd : Double.POSITIVE_INFINITY;
        int lo = 0, hi = n - 1; // first index with PH >= P
        while (lo < hi) {
            int m = (lo + hi) >> 1;
            if (PH[m] >= P) hi = m;
            else lo = m + 1;
        }
        return tA + lo - 1 + (P - PH[lo - 1]) / (PH[lo] - PH[lo - 1]);
    }

    /**
     * First integer tick whose gait clock satisfies test (monotone in Φ: false, then true from some tick on);
     * tA if it already holds at the start, +∞ if it does not hold yet.
     */
    public double firstTick(DoublePredicate test) {
        if (test.test(PH[0])) return tA;
        if (!test.test(PH[n - 1])) return Double.POSITIVE_INFINITY;
        int lo = 1, hi = n - 1;
        while (lo < hi) {
            int m = (lo + hi) >> 1;
            if (test.test(PH[m])) hi = m;
            else lo = m + 1;
        }
        return tA + lo;
    }

    /**
     * Causal prediction (what a live controller can know): from the state at integer tick tk, hold the command
     * given at tk and integrate forward with the same accel limits until the gait clock reaches goalPhase.
     * Returns the predicted pose there. If the clock stops first (walker settles), returns where it stops.
     */
    public Pose2 predict(double tk, double goalPhase) {
        int j = (int) Math.max(0, Math.min(n - 1, tk - tA));
        double x = X[j], z = Z[j], yaw = YAW[j], ph = PH[j], vv = V[j], ww = W[j];
        if (goalPhase <= ph) return new Pose2(x, z, yaw);
        if (tA + j >= 0 && j == n - 1) throw new IllegalStateException("command for tick " + (tA + j) + " not given yet");
        double tv = TV[j], tw = TW[j];
        for (int it = 0; it < 20000; it++) {
            double[] vw = saturate(towards(vv, tv, aLin), towards(ww, tw, aYaw));
            vv = vw[0];
            ww = vw[1];
            double dP;
            if (vv == 0 && ww == 0 && tv == 0 && tw == 0) {
                if (planted(ph) >= restN) dP = 0;
                else dP = Math.min(settle / T, ph + frac(restPhase - ph) - ph);
            } else dP = Math.max(activity(vv, ww), settle) / T;
            if (dP <= 0) return new Pose2(x, z, yaw);
            double a = ph + dP >= goalPhase ? (goalPhase - ph) / dP : 1; // fraction of this tick used
            double[] hh = heading(yaw + (ww * a) / 2);
            x += vv * a * hh[0];
            z += vv * a * hh[2];
            yaw += ww * a;
            ph += dP * a;
            if (a < 1) return new Pose2(x, z, yaw);
        }
        return new Pose2(x, z, yaw);
    }

    /** Gait clock rate over the last tick (causal). */
    public double rateAt(int t) {
        int j = Math.max(1, Math.min(n - 1, t - tA));
        return PH[j] - PH[j - 1];
    }

    /** Gait clock at fractional tick tf (interpolated; extrapolated outside the known range). */
    public double phaseAt(double tf) {
        int tB = lastTick();
        double rateEnd = n > 1 ? PH[n - 1] - PH[n - 2] : 0;
        if (tf <= tA) return PH[0] + rate0 * (tf - tA);
        if (tf >= tB) return PH[n - 1] + rateEnd * (tf - tB);
        double f = tf - tA;
        int j = (int) Math.floor(f);
        return lerp(PH[j], PH[j + 1], f - j);
    }
}
