// Mirrors the object returned by normalizeSpec in src/sim/spec.js (see SpecNormalizer for the code).
// The raw JSON format is documented in docs/walker-spec.md.
package com.lbogerd.walkers.sim;

import com.google.gson.JsonObject;

import java.util.List;

/** A normalised walker spec: defaults filled in, mirrored legs expanded, phases and timing derived. */
public final class WalkerSpec {
    public String id;
    public String name;
    public String description;
    public Body body;
    public List<Leg> legs;
    public Gait gait;
    public Drive drive;
    public Physics physics;
    public Implementation implementation;
    /** Body blocks plus leg blocks (hips, joints, struts, feet). */
    public double mass;
    /** Top speed, blocks per second (throttle 1). */
    public double speed;
    /** Largest horizontal distance from the hull origin to a neutral foot position (≥ 0.5). */
    public double turnRadius;
    /** Max yaw rate turning in place, rad/s: speed / turnRadius. */
    public double maxYawRate;
    public List<String> errors;
    public List<String> warnings;
    public JsonObject raw;

    public static final class Body {
        public double[] size;
        public double rideHeight;
        public double[] comOffset;
        public double blocks;
    }

    public static final class Leg {
        public String id;
        /** Hip position relative to the body's bottom centre. */
        public double[] mount;
        /** [dx, dz] neutral foot offset from the mount. */
        public double[] neutral;
        /** [upper, lower] or [coxa, upper, lower]. */
        public double[] segments;
        public String knee;
        /** Gait phase offset, 0–1. */
        public double phase;
        /** +1 for mount z ≥ 0, -1 otherwise. */
        public int side;

        Leg copy() {
            Leg l = new Leg();
            l.id = id;
            l.mount = mount;
            l.neutral = neutral;
            l.segments = segments;
            l.knee = knee;
            l.phase = phase;
            l.side = side;
            return l;
        }
    }

    /** gait.footholdSearch (null = off). */
    public record FootholdSearch(double radius, double step, double edgeClearance) {}

    public static final class Gait {
        public String preset;
        public double duty;
        public double stride;
        public double stepHeight;
        public String swing;
        public String bodyAdapt;
        public double bodySmoothing;
        /** null when unset (the JS uses ?? 0). */
        public Double comShift;
        public FootholdSearch footholdSearch;
        /** null when unset. */
        public Double maxStepUp;
        public double cycleTicks;
        public double accel;
        public double settleRate;
        /** Gait-clock phase of the rest stance (middle of the widest window with the most feet planted). */
        public double restPhase;
        /** Number of feet planted in the rest stance. */
        public int restPlanted;
    }

    public static final class Drive {
        public double rpm;
        public double revsPerCycle;
        public double stressPerLegPerRpm;
        public double stressPerBlockPerRpm;
    }

    /** physics.stabilizer (decision D6). */
    public record Stabilizer(double maxTorque, double stressPerTorque) {}

    /** physics.suspension (decision D1): the actual hull follows the planned one as a damped spring. */
    public record Suspension(double frequency, double damping) {}

    public static final class Physics {
        /** null = no Gyro Stabilizer. */
        public Stabilizer stabilizer;
        public Suspension suspension;
    }

    public static final class Implementation {
        public String legRepresentation;
        public double footholdProbesPerStep;
        public double groundChecksPerFootPerTick;
        public double syncEveryTicks;
    }
}
