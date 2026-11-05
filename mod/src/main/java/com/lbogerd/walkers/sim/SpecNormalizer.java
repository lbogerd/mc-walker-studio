// Mirrors src/sim/spec.js: walker spec normalisation (defaults, mirroring, gait presets and phases,
// restWindows, RPM/cycle derivation, turnRadius, stabilizer/suspension), with the same errors and warnings.
//
// The JS works on plain objects with spread defaults ({ ...defaults, ...raw.x }). This port does the same on
// Gson trees, then reads typed values with JavaScript's coercions (null → 0 in arithmetic, ?? for defaults).
// Only JSON numbers count as numbers (isNum); config/targets.json is not ported (targets stay JS-only).
package com.lbogerd.walkers.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lbogerd.walkers.sim.SimMath.TPS;
import static com.lbogerd.walkers.sim.SimMath.jsString;
import static com.lbogerd.walkers.sim.SimMath.toFixed;

public final class SpecNormalizer {
    private SpecNormalizer() {}

    public static final Map<String, String> GAIT_PRESETS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alternating", "Two groups in anti-phase (trot for 4 legs, tripod for 6, plain walking for 2).");
        m.put("wave", "One leg at a time, rear→front, left side then right. Slow but always ≥ n−1 feet down.");
        m.put("ripple", "Each side runs a rear→front wave; the two sides are half a cycle apart.");
        m.put("pace", "All legs on one side move together.");
        m.put("bound", "Front half and rear half move together.");
        m.put("custom", "Every leg supplies its own \"phase\" (0–1).");
        GAIT_PRESETS = Collections.unmodifiableMap(m);
    }

    public static final List<String> KNEE_HINTS = List.of("forward", "back", "up", "out", "down");
    public static final List<String> LEG_REPRESENTATIONS = List.of("renderOnly", "contraption", "subLevel");

    static String mirrorId(String id) {
        if (id.endsWith("L")) return id.substring(0, id.length() - 1) + "R";
        if (id.endsWith("R")) return id.substring(0, id.length() - 1) + "L";
        if (id.startsWith("L")) return "R" + id.substring(1);
        if (id.startsWith("R")) return "L" + id.substring(1);
        return id + "'";
    }

    // ---- JavaScript value semantics on Gson trees ----

    static boolean nullish(JsonElement e) { return e == null || e.isJsonNull(); }

    static JsonElement or(JsonElement e, JsonElement dflt) { return nullish(e) ? dflt : e; } // e ?? dflt

    static boolean isNum(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && Double.isFinite(e.getAsDouble());
    }

    static boolean isVec(JsonElement e, int n) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) return false;
        for (JsonElement x : e.getAsJsonArray()) if (!isNum(x)) return false;
        return true;
    }

    /** JS ToNumber for JSON values: null → 0, booleans → 0/1, numeric strings parse, anything else NaN. */
    static double num(JsonElement e) {
        if (e == null) return Double.NaN; // undefined
        if (e.isJsonNull()) return 0;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            String s = p.getAsString().trim();
            if (s.isEmpty()) return 0;
            try { return Double.parseDouble(s); } catch (NumberFormatException ex) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /** Number or null (for fields the JS reads with ?? or != null). */
    static Double numOrNull(JsonElement e) { return nullish(e) ? null : num(e); }

    static boolean truthy(JsonElement e) {
        if (nullish(e)) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) { double d = p.getAsDouble(); return d != 0 && !Double.isNaN(d); }
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    static String str(JsonElement e) {
        if (e == null) return "undefined";
        if (e.isJsonNull()) return "null";
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            return p.isNumber() ? jsString(p.getAsDouble()) : p.getAsString();
        }
        return e.toString();
    }

    /** { ...defaults, ...(raw || {}) } */
    static JsonObject spread(JsonObject defaults, JsonElement raw) {
        JsonObject o = defaults.deepCopy();
        if (raw != null && raw.isJsonObject()) for (var en : raw.getAsJsonObject().entrySet()) o.add(en.getKey(), en.getValue());
        return o;
    }

    static double[] vec(JsonElement e, int n) {
        double[] v = new double[n];
        JsonArray a = e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        for (int i = 0; i < n; i++) v[i] = i < a.size() ? num(a.get(i)) : Double.NaN;
        return v;
    }

    static double[] arr(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new double[0];
        JsonArray a = e.getAsJsonArray();
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = num(a.get(i));
        return v;
    }

    static JsonArray jarr(double... xs) {
        JsonArray a = new JsonArray();
        for (double x : xs) a.add(x);
        return a;
    }

    public static WalkerSpec normalizeSpec(String json, String id) {
        return normalizeSpec(JsonParser.parseString(json), id);
    }

    public static WalkerSpec normalizeSpec(JsonElement rawEl, String id) {
        if (rawEl == null || !rawEl.isJsonObject()) throw new IllegalArgumentException("Walker spec must be a JSON object");
        JsonObject raw = rawEl.getAsJsonObject();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        JsonObject bodyDefaults = new JsonObject();
        bodyDefaults.add("size", jarr(7, 2, 5));
        bodyDefaults.addProperty("rideHeight", 3);
        bodyDefaults.add("comOffset", jarr(0, 0, 0));
        bodyDefaults.add("blocks", JsonNull.INSTANCE);
        JsonObject bodyJ = spread(bodyDefaults, raw.get("body"));
        if (!isVec(bodyJ.get("size"), 3)) errors.add("body.size must be [x, y, z] in blocks");
        if (!isVec(bodyJ.get("comOffset"), 3)) errors.add("body.comOffset must be [x, y, z]");
        WalkerSpec.Body body = new WalkerSpec.Body();
        body.size = vec(bodyJ.get("size"), 3);
        body.comOffset = vec(bodyJ.get("comOffset"), 3);
        body.rideHeight = num(bodyJ.get("rideHeight"));
        body.blocks = isNum(bodyJ.get("blocks")) ? num(bodyJ.get("blocks")) : body.size[0] * body.size[1] * body.size[2];

        JsonObject legDefaultsD = new JsonObject();
        legDefaultsD.add("segments", jarr(3, 3));
        legDefaultsD.addProperty("knee", "back");
        JsonObject legDefaults = spread(legDefaultsD, raw.get("legDefaults"));

        // Raw (JSON) view of every leg for validation, typed view for the simulation.
        record RawLeg(JsonElement mount, JsonElement neutral, JsonElement segments, JsonElement knee) {}
        List<WalkerSpec.Leg> legs = new ArrayList<>();
        List<RawLeg> rawLegs = new ArrayList<>();
        JsonElement rawLegsEl = raw.get("legs");
        JsonArray rawLegArr = truthy(rawLegsEl) && rawLegsEl.isJsonArray() ? rawLegsEl.getAsJsonArray() : new JsonArray();
        List<Double> phases = new ArrayList<>(); // null = not given
        for (int i = 0; i < rawLegArr.size(); i++) {
            JsonObject l = rawLegArr.get(i).isJsonObject() ? rawLegArr.get(i).getAsJsonObject() : new JsonObject();
            JsonElement mountE = or(l.get("mount"), jarr(0, 0, 0));
            JsonElement neutralE = or(l.get("neutral"), jarr(0, 0));
            JsonElement segE = or(l.get("segments"), legDefaults.get("segments"));
            JsonElement kneeE = or(l.get("knee"), legDefaults.get("knee"));
            WalkerSpec.Leg leg = new WalkerSpec.Leg();
            leg.id = nullish(l.get("id")) ? "leg" + i : str(l.get("id"));
            leg.mount = vec(mountE, 3);
            leg.neutral = vec(neutralE, 2);
            leg.segments = arr(segE);
            leg.knee = str(kneeE);
            legs.add(leg);
            rawLegs.add(new RawLeg(mountE, neutralE, segE, kneeE));
            phases.add(isNum(l.get("phase")) ? num(l.get("phase")) : null);
            if (truthy(l.get("mirror"))) {
                WalkerSpec.Leg m = leg.copy();
                m.id = nullish(l.get("mirrorId")) ? mirrorId(leg.id) : str(l.get("mirrorId"));
                m.mount = new double[] {leg.mount[0], leg.mount[1], -leg.mount[2]};
                m.neutral = new double[] {leg.neutral[0], -leg.neutral[1]};
                legs.add(m);
                // the JS mirrors the already-defaulted arrays, so validation sees the mirrored values
                JsonElement mm = isVec(mountE, 3) ? jarr(m.mount) : mountE;
                JsonElement mn = isVec(neutralE, 2) ? jarr(m.neutral) : neutralE;
                rawLegs.add(new RawLeg(mm, mn, segE, kneeE));
                phases.add(isNum(l.get("mirrorPhase")) ? num(l.get("mirrorPhase")) : null);
            }
        }
        if (legs.isEmpty()) errors.add("Walker needs at least one leg");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < legs.size(); i++) {
            WalkerSpec.Leg leg = legs.get(i);
            RawLeg rl = rawLegs.get(i);
            if (ids.contains(leg.id)) errors.add("Duplicate leg id \"" + leg.id + "\"");
            ids.add(leg.id);
            if (!isVec(rl.mount(), 3)) errors.add(leg.id + ": mount must be [x, y, z] relative to body bottom centre");
            if (!isVec(rl.neutral(), 2)) errors.add(leg.id + ": neutral must be [dx, dz] foot offset from the mount");
            boolean segOk = rl.segments() != null && rl.segments().isJsonArray()
                    && (rl.segments().getAsJsonArray().size() == 2 || rl.segments().getAsJsonArray().size() == 3);
            if (segOk) for (JsonElement s : rl.segments().getAsJsonArray()) if (!(isNum(s) && s.getAsDouble() > 0)) segOk = false;
            if (!segOk)
                errors.add(leg.id + ": segments must be 2 values [upper, lower] or 3 values [coxa, upper, lower], all > 0");
            boolean kneeOk = rl.knee() != null && rl.knee().isJsonPrimitive() && rl.knee().getAsJsonPrimitive().isString()
                    && KNEE_HINTS.contains(rl.knee().getAsString());
            if (!kneeOk) errors.add(leg.id + ": knee must be one of " + String.join(", ", KNEE_HINTS));
        }

        JsonObject gaitD = new JsonObject();
        gaitD.addProperty("preset", "alternating");
        gaitD.addProperty("duty", 0.5);
        gaitD.addProperty("stride", 3);
        gaitD.addProperty("stepHeight", 1);
        gaitD.addProperty("swing", "arc");
        gaitD.addProperty("bodyAdapt", "level");
        gaitD.addProperty("bodySmoothing", 0.25);
        gaitD.addProperty("comShift", 0);
        gaitD.add("footholdSearch", JsonNull.INSTANCE);
        gaitD.add("maxStepUp", JsonNull.INSTANCE);
        gaitD.add("cycleTicks", JsonNull.INSTANCE);
        gaitD.addProperty("accel", 2);
        gaitD.addProperty("settleRate", 0.5);
        JsonObject gaitJ = spread(gaitD, raw.get("gait"));
        WalkerSpec.Gait gait = new WalkerSpec.Gait();
        gait.preset = str(gaitJ.get("preset"));
        gait.duty = num(gaitJ.get("duty"));
        gait.stride = num(gaitJ.get("stride"));
        gait.stepHeight = num(gaitJ.get("stepHeight"));
        gait.swing = str(gaitJ.get("swing"));
        gait.bodyAdapt = str(gaitJ.get("bodyAdapt"));
        gait.bodySmoothing = num(gaitJ.get("bodySmoothing"));
        gait.comShift = numOrNull(gaitJ.get("comShift"));
        gait.maxStepUp = numOrNull(gaitJ.get("maxStepUp"));
        gait.accel = num(gaitJ.get("accel"));
        gait.settleRate = num(gaitJ.get("settleRate"));
        JsonElement fs = gaitJ.get("footholdSearch");
        if (truthy(fs)) {
            JsonObject o = fs.isJsonObject() ? fs.getAsJsonObject() : new JsonObject(); // true → { radius: 1 }
            gait.footholdSearch = new WalkerSpec.FootholdSearch(
                    nullish(o.get("radius")) ? 1 : num(o.get("radius")),
                    nullish(o.get("step")) ? 0.25 : num(o.get("step")),
                    nullish(o.get("edgeClearance")) ? 0.3 : num(o.get("edgeClearance")));
        }
        boolean presetOk = gaitJ.get("preset").isJsonPrimitive() && GAIT_PRESETS.containsKey(gait.preset);
        if (!presetOk) errors.add("gait.preset must be one of " + String.join(", ", GAIT_PRESETS.keySet()));
        if (!(gait.duty > 0 && gait.duty < 1)) errors.add("gait.duty must be between 0 and 1 (fraction of the cycle a foot is planted)");
        if (!List.of("arc", "box").contains(gait.swing) || !gaitJ.get("swing").isJsonPrimitive()) errors.add("gait.swing must be \"arc\" or \"box\"");
        if (!List.of("level", "pitch").contains(gait.bodyAdapt) || !gaitJ.get("bodyAdapt").isJsonPrimitive())
            errors.add("gait.bodyAdapt must be \"level\" or \"pitch\"");
        if (!(isNum(gaitJ.get("accel")) && gait.accel > 0))
            errors.add("gait.accel must be > 0 (b/s², how fast the walker speeds up and brakes)");
        if (!(isNum(gaitJ.get("settleRate")) && gait.settleRate > 0 && gait.settleRate <= 1))
            errors.add("gait.settleRate must be in (0, 1] (fraction of the full gait rate used when slow or settling)");

        JsonObject driveD = new JsonObject();
        driveD.add("rpm", JsonNull.INSTANCE);
        driveD.addProperty("revsPerCycle", 1);
        driveD.addProperty("stressPerLegPerRpm", 4);
        driveD.addProperty("stressPerBlockPerRpm", 0.05);
        JsonObject driveJ = spread(driveD, raw.get("drive"));
        WalkerSpec.Drive drive = new WalkerSpec.Drive();
        drive.revsPerCycle = num(driveJ.get("revsPerCycle"));
        drive.stressPerLegPerRpm = num(driveJ.get("stressPerLegPerRpm"));
        drive.stressPerBlockPerRpm = num(driveJ.get("stressPerBlockPerRpm"));
        double cycleTicks;
        JsonElement rpmE = driveJ.get("rpm"), ctE = gaitJ.get("cycleTicks");
        if (isNum(rpmE) && rpmE.getAsDouble() > 0) {
            drive.rpm = rpmE.getAsDouble();
            cycleTicks = (1200 * drive.revsPerCycle) / drive.rpm; // 1 rev = 60/rpm s = 1200/rpm ticks
            if (isNum(ctE)) warnings.add("Both drive.rpm and gait.cycleTicks set; drive.rpm wins");
        } else {
            cycleTicks = isNum(ctE) ? ctE.getAsDouble() : 40;
            drive.rpm = (1200 * drive.revsPerCycle) / cycleTicks;
        }
        if (drive.rpm > 256) warnings.add("Drive needs " + toFixed(drive.rpm, 0) + " RPM; Create shafts top out at 256 RPM");
        if (cycleTicks < 8)
            warnings.add("Gait cycle of " + toFixed(cycleTicks, 1) + " ticks is very fast; swings will be only a few ticks long");
        gait.cycleTicks = cycleTicks;

        // ---- phase assignment ----
        List<WalkerSpec.Leg> right = new ArrayList<>(), left = new ArrayList<>(); // bySide[1], bySide[-1]
        for (WalkerSpec.Leg leg : legs) (sideOf(leg) > 0 ? right : left).add(leg);
        for (List<WalkerSpec.Leg> side : List.of(right, left)) side.sort((a, b) -> jsCompare(b.mount[0] - a.mount[0])); // front first
        int n = legs.size();
        for (int i = 0; i < n; i++) {
            WalkerSpec.Leg leg = legs.get(i);
            Double ph = phases.get(i);
            if (ph == null) {
                if (gait.preset.equals("custom")) warnings.add(leg.id + ": preset \"custom\" but no phase given; using 0");
                leg.phase = presetPhase(gait.preset, leg, right, left, n);
            } else leg.phase = ph;
        }
        for (WalkerSpec.Leg leg : legs) leg.side = sideOf(leg);
        if (gait.preset.equals("wave") && gait.duty < 1 - 1.0 / n - 1e-6)
            warnings.add("Wave gait with duty " + jsString(gait.duty) + " lifts more than one leg at a time; use duty ≥ " + toFixed(1 - 1.0 / n, 2));

        // ---- rest stance: phases of the gait clock where every foot is planted ----
        RestWindow rest = restWindows(legs, gait.duty);
        if (rest.planted() < n)
            warnings.add("No point in the gait cycle has all " + n + " feet planted (duty " + jsString(gait.duty)
                    + "); when stopped the walker freezes with " + (n - rest.planted()) + " foot/feet in the air");
        gait.restPhase = rest.phase();
        gait.restPlanted = rest.planted();

        // ---- physics: Gyro Stabilizer (D6) and leg suspension (D1) ----
        JsonElement rawPhysicsE = raw.get("physics");
        JsonObject rawPhysics = truthy(rawPhysicsE) && rawPhysicsE.isJsonObject() ? rawPhysicsE.getAsJsonObject() : new JsonObject();
        if (rawPhysics.has("support")) {
            errors.add("physics.support was removed (decision D6: balance comes from a Gyro Stabilizer). Migrate: "
                    + "\"support\": \"static\" → \"stabilizer\": null; \"support\": \"kinematic\" → \"stabilizer\": { \"maxTorque\": <mass·b>, \"stressPerTorque\": <SU per mass·b> }. "
                    + "Size maxTorque from the \"Peak righting torque\" metric. See docs/walker-spec.md.");
        }
        WalkerSpec.Physics physics = new WalkerSpec.Physics();
        JsonElement stabE = rawPhysics.get("stabilizer");
        if (!nullish(stabE)) {
            if (!stabE.isJsonObject() && !stabE.isJsonArray())
                errors.add("physics.stabilizer must be null or { maxTorque, stressPerTorque }");
            else {
                JsonObject sd = new JsonObject();
                sd.add("maxTorque", JsonNull.INSTANCE);
                sd.addProperty("stressPerTorque", 2);
                JsonObject s = spread(sd, stabE);
                if (!(isNum(s.get("maxTorque")) && num(s.get("maxTorque")) > 0))
                    errors.add("physics.stabilizer.maxTorque must be > 0 (mass·b: block-masses × blocks of lever arm)");
                if (!(isNum(s.get("stressPerTorque")) && num(s.get("stressPerTorque")) >= 0))
                    errors.add("physics.stabilizer.stressPerTorque must be ≥ 0 (SU per mass·b of torque)");
                physics.stabilizer = new WalkerSpec.Stabilizer(num(s.get("maxTorque")), num(s.get("stressPerTorque")));
            }
        }
        JsonObject suspD = new JsonObject();
        suspD.addProperty("frequency", 2);
        suspD.addProperty("damping", 0.7);
        JsonElement rawSusp = rawPhysics.get("suspension");
        JsonObject susp = spread(suspD, truthy(rawSusp) ? rawSusp : null);
        physics.suspension = new WalkerSpec.Suspension(num(susp.get("frequency")), num(susp.get("damping")));
        if (!(isNum(susp.get("frequency")) && physics.suspension.frequency() > 0 && physics.suspension.frequency() <= 10))
            errors.add("physics.suspension.frequency must be in (0, 10] Hz");
        if (!(isNum(susp.get("damping")) && physics.suspension.damping() >= 0))
            errors.add("physics.suspension.damping must be ≥ 0 (damping ratio; 1 = critically damped)");

        JsonObject implD = new JsonObject();
        implD.addProperty("legRepresentation", "contraption");
        implD.addProperty("footholdProbesPerStep", gait.footholdSearch != null ? 27 : 1);
        implD.addProperty("groundChecksPerFootPerTick", 1);
        implD.addProperty("syncEveryTicks", 1);
        JsonObject implJ = spread(implD, raw.get("implementation"));
        WalkerSpec.Implementation implementation = new WalkerSpec.Implementation();
        implementation.legRepresentation = str(implJ.get("legRepresentation"));
        implementation.footholdProbesPerStep = num(implJ.get("footholdProbesPerStep"));
        implementation.groundChecksPerFootPerTick = num(implJ.get("groundChecksPerFootPerTick"));
        implementation.syncEveryTicks = num(implJ.get("syncEveryTicks"));
        if (!LEG_REPRESENTATIONS.contains(implementation.legRepresentation) || !implJ.get("legRepresentation").isJsonPrimitive())
            errors.add("implementation.legRepresentation must be one of " + String.join(", ", LEG_REPRESENTATIONS));

        double turnRadius = 0.5;
        for (int i = 0; i < legs.size(); i++) {
            WalkerSpec.Leg l = legs.get(i);
            if (isVec(rawLegs.get(i).mount(), 3) && isVec(rawLegs.get(i).neutral(), 2))
                turnRadius = Math.max(turnRadius, SimMath.hypot(l.mount[0] + l.neutral[0], l.mount[2] + l.neutral[1]));
        }

        // bill of materials: leg blocks = hips + joints + struts + feet
        double struts = 0, joints = 0;
        for (WalkerSpec.Leg leg : legs) {
            for (double s : leg.segments) struts += Math.ceil(s - 1e-6);
            joints += leg.segments.length - 1;
        }
        double legBlocks = legs.size() + joints + struts + legs.size();

        WalkerSpec spec = new WalkerSpec();
        spec.id = id;
        spec.name = nullish(raw.get("name")) ? id : str(raw.get("name"));
        spec.description = nullish(raw.get("description")) ? "" : str(raw.get("description"));
        spec.body = body;
        spec.legs = legs;
        spec.gait = gait;
        spec.drive = drive;
        spec.physics = physics;
        spec.implementation = implementation;
        spec.mass = body.blocks + legBlocks;
        spec.speed = (gait.stride / cycleTicks) * TPS;
        spec.turnRadius = turnRadius;
        spec.maxYawRate = ((gait.stride / cycleTicks) * TPS) / turnRadius;
        spec.errors = errors;
        spec.warnings = warnings;
        spec.raw = raw;
        return spec;
    }

    private static int sideOf(WalkerSpec.Leg leg) { return leg.mount[2] >= 0 ? 1 : -1; }

    /** Sign of a JS comparator result (NaN counts as equal). */
    static int jsCompare(double d) { return d < 0 ? -1 : d > 0 ? 1 : 0; }

    private static double presetPhase(String preset, WalkerSpec.Leg leg, List<WalkerSpec.Leg> right, List<WalkerSpec.Leg> left, int n) {
        int s = sideOf(leg);
        List<WalkerSpec.Leg> side = s > 0 ? right : left;
        int r = side.indexOf(leg);
        int nSide = side.size();
        switch (preset) {
            case "alternating":
                return ((r + (s > 0 ? 1 : 0)) % 2) * 0.5;
            case "wave": {
                // left rear→front, then right rear→front
                List<WalkerSpec.Leg> order = new ArrayList<>(left);
                Collections.reverse(order);
                List<WalkerSpec.Leg> rr = new ArrayList<>(right);
                Collections.reverse(rr);
                order.addAll(rr);
                return (double) order.indexOf(leg) / n;
            }
            case "ripple":
                return ((double) (nSide - 1 - r) / Math.max(1, nSide) + (s > 0 ? 0.5 : 0)) % 1;
            case "pace":
                return s > 0 ? 0.5 : 0;
            case "bound":
                return r < nSide / 2.0 ? 0 : 0.5;
            default:
                return 0;
        }
    }

    public record RestWindow(int planted, double phase, double width) {}

    private static double fr(double x) { return ((x % 1) + 1) % 1; }

    private static int count(List<WalkerSpec.Leg> legs, double P, double duty) {
        int a = 0;
        for (WalkerSpec.Leg l : legs) if (fr(P + l.phase) < duty) a++;
        return a;
    }

    /**
     * The phase at which the most feet are planted, for standing still. Uses the exact touchdown (frac = 0)
     * and liftoff (frac = duty) phases of every leg, with the simulation's convention (planted while
     * frac(u + phase) < duty), and returns the middle of the widest interval with the most feet down.
     */
    public static RestWindow restWindows(List<WalkerSpec.Leg> legs, double duty) {
        Set<Double> set = new LinkedHashSet<>();
        for (WalkerSpec.Leg l : legs) {
            for (double x : new double[] {fr(-l.phase), fr(duty - l.phase)}) {
                double c = SimMath.jsRound(x * 1e9) / 1e9;
                set.add(c == 0 ? 0.0 : c); // SameValueZero: -0 and 0 are one entry
            }
        }
        List<Double> cuts = new ArrayList<>(set);
        cuts.sort((a, b) -> jsCompare(a - b));
        if (cuts.isEmpty()) return new RestWindow(count(legs, 0, duty), 0, 1);
        int bestPlanted = -1;
        double bestPhase = 0, bestWidth = 0;
        for (int i = 0; i < cuts.size(); i++) {
            double a = cuts.get(i), b = i + 1 < cuts.size() ? cuts.get(i + 1) : cuts.get(0) + 1;
            double width = b - a;
            if (width <= 1e-9) continue;
            double mid = fr(a + width / 2);
            int planted = count(legs, mid, duty);
            if (planted > bestPlanted || (planted == bestPlanted && width > bestWidth)) {
                bestPlanted = planted;
                bestPhase = mid;
                bestWidth = width;
            }
        }
        return new RestWindow(bestPlanted, bestPhase, bestWidth);
    }
}
