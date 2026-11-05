package com.lbogerd.walkers.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that com.lbogerd.walkers.sim reproduces the studio's src/sim: every fixture written by
 * {@code npm run fixtures} (tools/export-fixtures.mjs) is re-simulated in Java and compared field by field.
 *
 * <p>Tolerance: {@value #TOL} absolute on every number. The fixtures are rounded to 1e-9, and the port is
 * otherwise bit-exact (StrictMath = fdlibm like V8, and a port of V8's Math.hypot), so the observed deviation
 * is at the rounding level; the test prints it per fixture. Discrete values (stance flags, stance indices,
 * foothold heights, probe counts, events, warnings) must match exactly.
 */
class SimFixtureTest {
    static final double TOL = 1e-6;
    static final String DIR = "/sim-fixtures/";

    static JsonObject load(String name) {
        try (InputStream in = SimFixtureTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, "missing fixture " + name + " (run `npm run fixtures`)");
            InputStream s = name.endsWith(".gz") ? new GZIPInputStream(in) : in;
            try (Reader r = new InputStreamReader(s, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @TestFactory
    List<DynamicTest> fixtures() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement f : load("index.json").getAsJsonArray("files")) {
            String name = f.getAsString();
            if (name.equals("spec-cases.json.gz")) tests.add(DynamicTest.dynamicTest(name, SimFixtureTest::specCases));
            else tests.add(DynamicTest.dynamicTest(name.replace(".json.gz", ""), () -> walker(name)));
        }
        if (tests.size() < 2) fail("no fixtures in " + DIR);
        return tests;
    }

    // ---------------------------------------------------------------- comparison helpers

    /** Collects the first mismatch (in the order compared: frames by tick, then series, footholds, events, summary). */
    static final class Cmp {
        final String fixture;
        double maxDev = 0;
        Cmp(String fixture) { this.fixture = fixture; }

        static double num(JsonElement e) {
            if (e == null || e.isJsonNull()) return Double.NaN;
            if (e.getAsJsonPrimitive().isString()) return Double.parseDouble(e.getAsString()); // "Infinity", "-Infinity", "NaN"
            return e.getAsDouble();
        }

        void num(String where, double java, JsonElement js) {
            double v = num(js);
            if (Double.isNaN(v) && Double.isNaN(java)) return;
            if (!Double.isFinite(v) || !Double.isFinite(java)) {
                if (v != java) mismatch(where, java, js);
                return;
            }
            double d = Math.abs(java - v);
            maxDev = Math.max(maxDev, d);
            if (!(d <= TOL)) mismatch(where, java, js);
        }

        /** Malformed vectors in invalid specs: the JS keeps the bad value, the port pads with NaN. Both report the error. */
        void shapeOrError(String where, WalkerSpec spec, double[] java, JsonElement js, Runnable compare) {
            boolean ok = js != null && js.isJsonArray() && js.getAsJsonArray().size() == java.length;
            for (int i = 0; ok && i < java.length; i++) ok = js.getAsJsonArray().get(i).isJsonPrimitive();
            if (ok) compare.run();
            else if (spec.errors.isEmpty()) fail(fixture + ": " + where + " is malformed but the spec has no errors");
        }

        void vec(String where, String[] names, double[] java, JsonArray js, int offset) {
            for (int i = 0; i < java.length; i++) num(where + "." + names[i], java[i], js.get(offset + i));
        }

        void exact(String where, Object java, Object js) {
            if (!java.equals(js)) fail(fixture + ": first divergence at " + where + ": java=" + java + ", js=" + js);
        }

        void mismatch(String where, double java, JsonElement js) {
            fail(String.format("%s: first divergence at %s: java=%.12g, js=%s (|Δ| > %.0e)", fixture, where, java, js, TOL));
        }
    }

    static final String[] XYZ = {"x", "y", "z"};
    static final String[] POSE = {"x", "y", "z", "yaw", "pitch", "roll"};

    static double[] pose(SimMath.Pose p) {
        return new double[] {p.pos()[0], p.pos()[1], p.pos()[2], p.yaw(), p.pitch(), p.roll()};
    }

    // ---------------------------------------------------------------- walker × scenario fixtures

    static void walker(String file) {
        JsonObject fx = load(file);
        String id = fx.get("walker").getAsString(), scenario = fx.get("scenario").getAsString();
        Cmp c = new Cmp(id + " on " + scenario);
        WalkerSpec spec = SpecNormalizer.normalizeSpec(fx.get("raw"), id);
        compareSpec(c, "spec", spec, fx.getAsJsonObject("spec"));

        Scenarios.CourseTerrain terrain = Scenarios.makeTerrain(scenario, fx.get("seed").getAsInt());
        compareCommands(c, terrain.commands(), fx.get("commands"));
        Simulate.Result sim = Simulate.simulate(spec, terrain);
        c.exact("ticks", sim.ticks(), fx.get("ticks").getAsInt());

        // frames, densely tick by tick: the first diverging tick / leg / field is reported
        for (JsonElement fe : fx.getAsJsonArray("frames")) {
            JsonObject f = fe.getAsJsonObject();
            int t = f.get("t").getAsInt();
            Simulate.Frame jf = sim.frames().get(t);
            c.exact("frame index " + t, jf.t(), t);
            String at = "tick " + t;
            c.num(at + " phase", jf.phase(), f.get("phase"));
            c.vec(at + " actual hull", POSE, pose(jf.actual()), f.getAsJsonArray("actual"), 0);
            c.vec(at + " planned hull", POSE, pose(jf.planned()), f.getAsJsonArray("planned"), 0);
            JsonArray legs = f.getAsJsonArray("legs");
            c.exact(at + " leg count", jf.legs().size(), legs.size());
            for (int li = 0; li < legs.size(); li++) {
                JsonArray l = legs.get(li).getAsJsonArray();
                Simulate.LegFrame jl = jf.legs().get(li);
                String w = at + ", leg " + spec.legs.get(li).id + ", ";
                c.exact(w + "stance", jl.stance() ? 1 : 0, l.get(9).getAsInt());
                c.vec(w + "foot", XYZ, jl.foot(), l, 0);
                c.vec(w + "knee", XYZ, jl.knee(), l, 3);
                c.vec(w + "hip", XYZ, jl.hip(), l, 6);
                c.num(w + "err", jl.err(), l.get(10));
            }
        }

        // every per-tick series (covers the ticks past the frames)
        JsonObject series = fx.getAsJsonObject("series");
        Map<String, List<Double>> js = Map.of("margin", sim.series().margin, "bodyY", sim.series().bodyY, "reach", sim.series().reach,
                "footErr", sim.series().footErr, "stance", sim.series().stance, "clearance", sim.series().clearance,
                "speed", sim.series().speed, "torque", sim.series().torque, "trackErr", sim.series().trackErr);
        for (int t = 0; t <= sim.ticks(); t++) {
            for (var en : series.entrySet()) {
                List<Double> jv = js.get(en.getKey());
                assertNotNull(jv, "unknown series " + en.getKey());
                c.exact("series " + en.getKey() + " length", jv.size(), en.getValue().getAsJsonArray().size());
                c.num("tick " + t + " series " + en.getKey(), jv.get(t), en.getValue().getAsJsonArray().get(t));
            }
        }

        // footholds: [k, x, y, z, void, adjusted, probes, yaw, tStart, tEnd, tDecided]
        JsonArray fhs = fx.getAsJsonArray("footholds");
        for (int li = 0; li < fhs.size(); li++) {
            JsonArray l = fhs.get(li).getAsJsonArray();
            List<GaitController.Foothold> jl = sim.footholds().get(li);
            String leg = "footholds of leg " + spec.legs.get(li).id;
            for (int i = 0; i < Math.min(l.size(), jl.size()); i++) {
                JsonArray e = l.get(i).getAsJsonArray();
                GaitController.Foothold f = jl.get(i);
                String w = leg + " #" + i + " (stance " + e.get(0).getAsInt() + ") ";
                c.exact(w + "k", f.k, e.get(0).getAsInt());
                c.num(w + "x", f.x, e.get(1));
                c.exact(w + "y", (int) f.y, e.get(2).getAsInt());
                c.num(w + "z", f.z, e.get(3));
                c.exact(w + "void", f.isVoid ? 1 : 0, e.get(4).getAsInt());
                c.exact(w + "adjusted", f.adjusted ? 1 : 0, e.get(5).getAsInt());
                c.exact(w + "probes", f.probes, e.get(6).getAsInt());
                c.num(w + "yaw", f.yaw, e.get(7));
                c.num(w + "tStart", f.tStart, e.get(8));
                c.num(w + "tEnd", f.tEnd, e.get(9));
                c.num(w + "tDecided", f.tDecided, e.get(10));
            }
            c.exact(leg + " count", jl.size(), l.size());
        }

        // events, in order
        JsonArray ev = fx.getAsJsonArray("events");
        for (int i = 0; i < Math.min(ev.size(), sim.events().size()); i++) {
            JsonObject e = ev.get(i).getAsJsonObject();
            Simulate.Event je = sim.events().get(i);
            String js2 = e.get("type").getAsString() + " t=" + e.get("t").getAsInt() + " " + e.get("leg").getAsString() + ": " + e.get("detail").getAsString();
            c.exact("event #" + i, je.type() + " t=" + je.t() + " " + je.leg() + ": " + je.detail(), js2);
        }
        c.exact("event count", sim.events().size(), ev.size());

        // summary
        JsonObject sum = fx.getAsJsonObject("summary");
        Map<String, Double> jsum = sim.summary().numbers();
        for (var en : sum.entrySet()) {
            String k = en.getKey();
            if (k.equals("eventCounts")) {
                JsonObject ec = en.getValue().getAsJsonObject();
                c.exact("summary.eventCounts size", sim.summary().eventCounts.size(), ec.size());
                for (var e2 : ec.entrySet())
                    c.exact("summary.eventCounts." + e2.getKey(), sim.summary().eventCounts.get(e2.getKey()), e2.getValue().getAsInt());
                continue;
            }
            if (!jsum.containsKey(k)) fail("summary." + k + " is not ported");
            Double v = jsum.get(k);
            if (v == null) c.exact("summary." + k, "null", en.getValue().toString());
            else c.num("summary." + k, v, en.getValue());
        }
        c.num("stress", Evaluate.stress(spec), fx.getAsJsonObject("spec").get("stress"));
        System.out.printf("%-28s %-10s ticks %4d  max |Δ| %.1e%n", id, scenario, sim.ticks(), c.maxDev);
    }

    static void compareCommands(Cmp c, List<Scenarios.Segment> java, JsonElement js) {
        if (js == null || js.isJsonNull()) {
            c.exact("commands", String.valueOf(java), "null");
            return;
        }
        JsonArray a = js.getAsJsonArray();
        c.exact("command count", java.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            JsonObject s = a.get(i).getAsJsonObject();
            c.num("command " + i + " ticks", java.get(i).ticks(), s.get("ticks"));
            c.num("command " + i + " throttle", java.get(i).throttle(), s.get("throttle"));
            c.num("command " + i + " steer", java.get(i).steer(), s.get("steer"));
        }
    }

    // ---------------------------------------------------------------- normalizeSpec

    static void compareSpec(Cmp c, String at, WalkerSpec spec, JsonObject js) {
        c.exact(at + ".errors", spec.errors, strings(js.getAsJsonArray("errors")));
        c.exact(at + ".warnings", spec.warnings, strings(js.getAsJsonArray("warnings")));
        JsonArray legs = js.getAsJsonArray("legs");
        c.exact(at + ".legs count", spec.legs.size(), legs.size());
        for (int i = 0; i < legs.size(); i++) {
            JsonObject l = legs.get(i).getAsJsonObject();
            WalkerSpec.Leg jl = spec.legs.get(i);
            String w = at + ".legs[" + i + "] ";
            c.exact(w + "id", jl.id, l.get("id").getAsString());
            c.shapeOrError(w + "mount", spec, jl.mount, l.get("mount"), () -> c.vec(w + "mount", XYZ, jl.mount, l.getAsJsonArray("mount"), 0));
            c.shapeOrError(w + "neutral", spec, jl.neutral, l.get("neutral"),
                    () -> c.vec(w + "neutral", new String[] {"dx", "dz"}, jl.neutral, l.getAsJsonArray("neutral"), 0));
            JsonArray segs = l.getAsJsonArray("segments");
            c.exact(w + "segments length", jl.segments.length, segs.size());
            for (int s = 0; s < segs.size(); s++) c.num(w + "segments[" + s + "]", jl.segments[s], segs.get(s));
            c.exact(w + "knee", jl.knee, l.get("knee").getAsString());
            c.num(w + "phase", jl.phase, l.get("phase"));
            c.exact(w + "side", jl.side, l.get("side").getAsInt());
        }
        JsonObject g = js.getAsJsonObject("gait");
        c.exact(at + ".gait.preset", spec.gait.preset, g.get("preset").getAsString());
        c.num(at + ".gait.duty", spec.gait.duty, g.get("duty"));
        c.num(at + ".gait.cycleTicks", spec.gait.cycleTicks, g.get("cycleTicks"));
        c.num(at + ".gait.restPhase", spec.gait.restPhase, g.get("restPhase"));
        c.exact(at + ".gait.restPlanted", spec.gait.restPlanted, g.get("restPlanted").getAsInt());
        JsonElement fs = g.get("footholdSearch");
        if (fs.isJsonNull()) c.exact(at + ".gait.footholdSearch", String.valueOf(spec.gait.footholdSearch), "null");
        else {
            assertNotNull(spec.gait.footholdSearch, at + ".gait.footholdSearch");
            c.num(at + ".gait.footholdSearch.radius", spec.gait.footholdSearch.radius(), fs.getAsJsonObject().get("radius"));
        }
        nullable(c, at + ".gait.comShift", spec.gait.comShift, g.get("comShift"));
        nullable(c, at + ".gait.maxStepUp", spec.gait.maxStepUp, g.get("maxStepUp"));
        JsonObject b = js.getAsJsonObject("body");
        c.shapeOrError(at + ".body.size", spec, spec.body.size, b.get("size"), () -> c.vec(at + ".body.size", XYZ, spec.body.size, b.getAsJsonArray("size"), 0));
        c.shapeOrError(at + ".body.comOffset", spec, spec.body.comOffset, b.get("comOffset"),
                () -> c.vec(at + ".body.comOffset", XYZ, spec.body.comOffset, b.getAsJsonArray("comOffset"), 0));
        c.num(at + ".body.rideHeight", spec.body.rideHeight, b.get("rideHeight"));
        c.num(at + ".body.blocks", spec.body.blocks, b.get("blocks"));
        c.num(at + ".rpm", spec.drive.rpm, js.get("rpm"));
        c.num(at + ".turnRadius", spec.turnRadius, js.get("turnRadius"));
        c.num(at + ".speed", spec.speed, js.get("speed"));
        c.num(at + ".maxYawRate", spec.maxYawRate, js.get("maxYawRate"));
        c.num(at + ".mass", spec.mass, js.get("mass"));
        c.num(at + ".footholdProbesPerStep", spec.implementation.footholdProbesPerStep, js.get("footholdProbesPerStep"));
        c.exact(at + ".legRepresentation", spec.implementation.legRepresentation, js.get("legRepresentation").getAsString());
        JsonElement st = js.get("stabilizer");
        if (st.isJsonNull() || !st.isJsonObject()) {
            // a non-object stabilizer is an error case; the JS keeps the bad value, the port drops it
            if (st.isJsonNull()) c.exact(at + ".stabilizer", String.valueOf(spec.physics.stabilizer), "null");
        } else {
            assertNotNull(spec.physics.stabilizer, at + ".stabilizer");
            c.num(at + ".stabilizer.maxTorque", spec.physics.stabilizer.maxTorque(), st.getAsJsonObject().get("maxTorque"));
            c.num(at + ".stabilizer.stressPerTorque", spec.physics.stabilizer.stressPerTorque(), st.getAsJsonObject().get("stressPerTorque"));
        }
        JsonObject su = js.getAsJsonObject("suspension");
        c.num(at + ".suspension.frequency", spec.physics.suspension.frequency(), su.get("frequency"));
        c.num(at + ".suspension.damping", spec.physics.suspension.damping(), su.get("damping"));
        c.num(at + ".stress", Evaluate.stress(spec), js.get("stress"));
    }

    static void nullable(Cmp c, String at, Double java, JsonElement js) {
        if (js == null || js.isJsonNull()) c.exact(at, String.valueOf(java), "null");
        else {
            assertNotNull(java, at);
            c.num(at, java, js);
        }
    }

    static List<String> strings(JsonArray a) {
        List<String> l = new ArrayList<>();
        for (JsonElement e : a) l.add(e.getAsString());
        return l;
    }

    static void specCases() {
        JsonArray cases = load("spec-cases.json.gz").getAsJsonArray("cases");
        for (JsonElement ce : cases) {
            JsonObject cs = ce.getAsJsonObject();
            String name = cs.get("name").getAsString();
            if (cs.has("throws")) continue; // the JS itself cannot normalise it; nothing to mirror
            WalkerSpec spec = SpecNormalizer.normalizeSpec(cs.get("raw"), cs.get("id").getAsString());
            compareSpec(new Cmp("spec case \"" + name + "\""), "spec", spec, cs.getAsJsonObject("result"));
        }
        assertEquals(true, cases.size() > 10, "spec cases");
    }
}
