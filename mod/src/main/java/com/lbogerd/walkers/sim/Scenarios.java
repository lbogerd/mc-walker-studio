// Mirrors src/sim/terrain.js: the studio's test courses, their driver command timelines and makeTerrain.
//
// A course may define a driver command timeline: piecewise-constant segments { ticks, throttle, steer },
// played in order from tick 0.
//   throttle ∈ [-1, 1]: fraction of top speed (negative = reverse)
//   steer    ∈ [-1, 1]: fraction of the max yaw rate (positive = turn left, toward -z)
// The first segment also sets the state before tick 0 (warm-up). After the last segment its command is held.
// Courses without commands drive full throttle forward for the default run length.
package com.lbogerd.walkers.sim;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Scenarios {
    private Scenarios() {}

    public static final int VOID = Terrain.VOID;

    /** Driver input for one tick. */
    public record Command(double throttle, double steer) {
        public static final Command STOP = new Command(0, 0);
    }

    /** One segment of a command timeline; ticks may be +∞ (open-ended). */
    public record Segment(double ticks, double throttle, double steer) {}

    public static final List<Segment> DEFAULT_COMMANDS = List.of(new Segment(Double.POSITIVE_INFINITY, 1, 0));

    /** Total run length of a command timeline in ticks (+∞ if open-ended). */
    public static double commandTicks(List<Segment> commands) {
        double a = 0;
        for (Segment c : commands == null ? DEFAULT_COMMANDS : commands) a += c.ticks();
        return a;
    }

    /** Command in effect at tick t. */
    public static Command commandAt(List<Segment> commands, double t) {
        List<Segment> cs = commands != null && !commands.isEmpty() ? commands : DEFAULT_COMMANDS;
        double end = 0;
        for (Segment c : cs) {
            end += c.ticks();
            if (t < end) return new Command(c.throttle(), c.steer());
        }
        Segment c = cs.get(cs.size() - 1);
        return new Command(c.throttle(), c.steer());
    }

    /** Column function of a course: (ix, iz, seed) → surface height. */
    @FunctionalInterface
    public interface ColumnFn {
        double column(int ix, int iz, int seed);
    }

    public record Scenario(String name, String surface, String label, String shortLabel, String description,
                           ColumnFn column, List<Segment> commands) {}

    static double hash(int ix, int iz, int seed) {
        // JS: (ix * 374761393 + iz * 668265263 + seed * 1442695041) | 0, computed exactly in doubles (< 2^53)
        int h = (int) ((long) ix * 374761393L + (long) iz * 668265263L + (long) seed * 1442695041L);
        h = (h ^ (h >>> 13)) * 1274126177;
        return Integer.toUnsignedLong(h ^ (h >>> 16)) / 4294967296.0;
    }

    static double valueNoise(double x, double z, double cell, int seed) {
        double gxd = Math.floor(x / cell), gzd = Math.floor(z / cell);
        int gx = (int) gxd, gz = (int) gzd;
        double fx = x / cell - gxd, fz = z / cell - gzd;
        double a = hash(gx, gz, seed), b = hash(gx + 1, gz, seed);
        double c = hash(gx, gz + 1, seed), d = hash(gx + 1, gz + 1, seed);
        double u = s(fx), v = s(fz);
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
    }

    private static double s(double t) { return t * t * (3 - 2 * t); }

    private static double floor(double x) { return Math.floor(x); }

    public static final Map<String, Scenario> SCENARIOS;

    static {
        Map<String, Scenario> m = new LinkedHashMap<>();
        m.put("flat", new Scenario("flat", "grass", "Flat", "Flat",
                "Baseline: steady-state gait, bob, speed and cost with no terrain influence.",
                (ix, iz, seed) -> 0, null));
        m.put("stairs", new Scenario("stairs", "stone", "Stairs", "Stairs",
                "Five 1-block steps up (3 blocks deep), a plateau, then five steps down.",
                (ix, iz, seed) -> {
                    if (ix < 10) return 0;
                    if (ix < 25) return floor((ix - 10) / 3.0) + 1;
                    if (ix < 35) return 5;
                    if (ix < 50) return 5 - (floor((ix - 35) / 3.0) + 1);
                    return 0;
                }, null));
        m.put("ledge", new Scenario("ledge", "stone", "2-block ledge", "Ledge",
                "A sudden 2-block wall up at x=14 and a 2-block drop at x=30. Probes max step-up.",
                (ix, iz, seed) -> ix >= 14 && ix < 30 ? 2 : 0, null));
        m.put("hill", new Scenario("hill", "grass", "Steep hill", "Hill",
                "1 block of rise every 2 blocks (≈27°) up to +8, then back down.",
                (ix, iz, seed) -> {
                    if (ix < 10) return 0;
                    if (ix < 26) return floor((ix - 10) / 2.0) + 1;
                    if (ix < 32) return 8;
                    if (ix < 48) return 8 - (floor((ix - 32) / 2.0) + 1);
                    return 0;
                }, null));
        m.put("gap", new Scenario("gap", "grass", "Trenches", "Gap",
                "A 2-wide and a 3-wide trench across the path. Without foothold search feet fall in.",
                (ix, iz, seed) -> (ix >= 12 && ix < 14) || (ix >= 26 && ix < 29) ? VOID : 0, null));
        m.put("rough", new Scenario("rough", "grass", "Rough", "Rough",
                "Seeded 0–2 block lumpy ground (like a plains/forest edge) from x=8 onward.",
                (ix, iz, seed) -> ix < 8 ? 0 : SimMath.jsRound(valueNoise(ix, iz, 3, seed) * 2.4 - 0.2), null));
        m.put("turn", new Scenario("turn", "grass", "Turn in place", "Turn",
                "Flat. Stand, spin left at full steer and zero throttle for 12 s, then stop and settle.",
                (ix, iz, seed) -> 0,
                List.of(new Segment(10, 0, 0), new Segment(240, 0, 1), new Segment(80, 0, 0))));
        m.put("startstop", new Scenario("startstop", "grass", "Start-stop", "Stop",
                "Flat. Accelerate from standing, cruise, brake to a stop, stand, start again and stop.",
                (ix, iz, seed) -> 0,
                List.of(new Segment(10, 0, 0), new Segment(150, 1, 0), new Segment(100, 0, 0),
                        new Segment(120, 1, 0), new Segment(100, 0, 0))));
        m.put("reverse", new Scenario("reverse", "grass", "Reverse + curve", "Rev",
                "Flat. Back up at full reverse, stop, then drive forward on a left-hand curve (steer 0.4).",
                (ix, iz, seed) -> 0,
                List.of(new Segment(10, 0, 0), new Segment(120, -1, 0), new Segment(40, 0, 0),
                        new Segment(200, 1, 0.4), new Segment(60, 0, 0))));
        SCENARIOS = Collections.unmodifiableMap(m);
    }

    /** A test course: its terrain plus its command timeline (null = default full throttle). */
    public static final class CourseTerrain implements Terrain {
        public final String name;
        public final int seed;
        public final Scenario scenario;
        private final Map<Long, Integer> cache = new HashMap<>();

        CourseTerrain(Scenario scenario, int seed) {
            this.name = scenario.name();
            this.scenario = scenario;
            this.seed = seed;
        }

        public List<Segment> commands() { return scenario.commands(); }

        @Override
        public int column(int ix, int iz) {
            long key = ((long) ix << 32) | (iz & 0xffffffffL);
            Integer h = cache.get(key);
            if (h == null) {
                h = (int) Math.max(VOID, scenario.column().column(ix, iz, seed));
                cache.put(key, h);
            }
            return h;
        }
    }

    public static CourseTerrain makeTerrain(String name) { return makeTerrain(name, 7); }

    public static CourseTerrain makeTerrain(String name, int seed) {
        Scenario sc = SCENARIOS.get(name);
        if (sc == null)
            throw new IllegalArgumentException("Unknown scenario \"" + name + "\". Known: " + String.join(", ", SCENARIOS.keySet()));
        return new CourseTerrain(sc, seed);
    }
}
