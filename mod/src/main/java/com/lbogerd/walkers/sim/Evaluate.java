// Mirrors the stress helpers of src/sim/evaluate.js (stress, totalStress, stabilizerHolds).
// The metric table, targets and the server cost model stay JS-only (studio concerns, not the mod's).
package com.lbogerd.walkers.sim;

public final class Evaluate {
    private Evaluate() {}

    /** Create stress impact of the leg drive: rpm × (legs × stressPerLegPerRpm + mass × stressPerBlockPerRpm). */
    public static double stress(WalkerSpec spec) {
        WalkerSpec.Drive d = spec.drive;
        return d.rpm * (spec.legs.size() * d.stressPerLegPerRpm + spec.mass * d.stressPerBlockPerRpm);
    }

    /** Drive stress plus the stress the Gyro Stabilizer draws at its peak on this run. */
    public static double totalStress(WalkerSpec spec, Simulate.Result sim) {
        return stress(spec) + (sim == null ? 0 : sim.summary().stabStress);
    }

    /** True when a Gyro Stabilizer is fitted and the peak righting torque is within its maxTorque. */
    public static boolean stabilizerHolds(WalkerSpec spec, Simulate.Result sim) {
        return spec.physics.stabilizer != null && sim.summary().stabLoad != null && sim.summary().stabLoad <= 1 + 1e-9;
    }
}
