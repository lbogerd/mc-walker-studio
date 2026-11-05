package com.lbogerd.walkers.assembly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a {@link LegScan} as a walker spec ({@code walkers/<id>.json}, see {@code docs/walker-spec.md}) that
 * the studio's {@code normalizeSpec} loads. Only what the blocks determine is written (body size, block count,
 * ride height as built, legs); gait, drive and physics come from {@code extra} or the studio's defaults.
 */
public final class WalkerSpecJson {
    private WalkerSpecJson() {}

    private static final Pattern NUMBER_ARRAY = Pattern.compile("\\[([-0-9.,\\s]+)]");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * @param name       the spec's {@code name}
     * @param rideHeight hull bottom above the foot points; null = as built ({@link LegScan#builtRideHeight()}).
     *                   Legs built straight are at full reach as built, so pass something lower for them.
     * @param extra      top-level fields copied over the generated ones (e.g. {@code gait}, {@code drive}); may be null
     */
    public record Options(String name, Double rideHeight, JsonObject extra) {
        public static Options named(String name) {
            return new Options(name, null, null);
        }
    }

    public static JsonObject toSpec(LegScan scan, Options options) {
        if (!scan.ok()) throw new IllegalArgumentException("Walker has leg errors: " + scan.errors());
        JsonObject spec = new JsonObject();
        spec.addProperty("name", options.name());
        spec.addProperty("description", "Measured in the world by the mod's LegScanner from a block-built walker.");
        JsonObject proposal = new JsonObject();
        proposal.addProperty("summary", "Scanned from blocks (decision D3). Body size, block count, ride height and legs are measured; everything else is a studio default.");
        JsonArray decisions = new JsonArray();
        decisions.add("Mount = Leg Actuator centre; segments = distances between pivot block centres (actuator, joints, foot pad); the foot point is the Foot Pad's centre.");
        decisions.add("rideHeight " + (options.rideHeight() == null ? "is as built" : "was set by hand (as built: " + LegScanner.round(scan.builtRideHeight()) + ")") + ".");
        proposal.add("decisions", decisions);
        proposal.add("openQuestions", new JsonArray());
        spec.add("proposal", proposal);

        JsonObject body = new JsonObject();
        HullFrame f = scan.frame();
        body.add("size", array(f.size()[0], f.size()[1], f.size()[2]));
        body.addProperty("rideHeight", LegScanner.round(options.rideHeight() != null ? options.rideHeight() : scan.builtRideHeight()));
        body.addProperty("blocks", scan.hullBlocks().size()); // hips, joints, struts and feet are counted by the studio's bill of materials
        spec.add("body", body);

        // legDefaults: the most common segments and knee.
        Map<String, Integer> segCount = new HashMap<>(), kneeCount = new HashMap<>();
        for (MeasuredLeg l : scan.legs()) {
            segCount.merge(Arrays.toString(l.segments()), 1, Integer::sum);
            kneeCount.merge(l.knee(), 1, Integer::sum);
        }
        double[] defSegs = scan.legs().stream()
                .max((a, b) -> segCount.get(Arrays.toString(a.segments())) - segCount.get(Arrays.toString(b.segments())))
                .map(MeasuredLeg::segments).orElse(new double[] {3, 3});
        String defKnee = scan.legs().stream().map(MeasuredLeg::knee) // first leg wins ties
                .reduce((a, b) -> kneeCount.get(b) > kneeCount.get(a) ? b : a).orElse("back");
        JsonObject legDefaults = new JsonObject();
        legDefaults.add("segments", array(defSegs));
        legDefaults.addProperty("knee", defKnee);
        spec.add("legDefaults", legDefaults);

        JsonArray legs = new JsonArray();
        List<MeasuredLeg> all = scan.legs();
        for (MeasuredLeg l : all) {
            if (l.mirrorOf() != null) continue; // written as "mirror": true on its left leg
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id());
            o.add("mount", array(l.mount()));
            o.add("neutral", array(l.neutral()));
            if (!Arrays.equals(l.segments(), defSegs)) o.add("segments", array(l.segments()));
            if (!l.knee().equals(defKnee)) o.addProperty("knee", l.knee());
            if (all.stream().anyMatch(r -> l.id().equals(r.mirrorOf()))) o.addProperty("mirror", true);
            legs.add(o);
        }
        spec.add("legs", legs);

        JsonObject impl = new JsonObject();
        impl.addProperty("legRepresentation", "contraption");
        spec.add("implementation", impl);

        if (options.extra() != null)
            for (Map.Entry<String, JsonElement> e : options.extra().entrySet()) spec.add(e.getKey(), e.getValue().deepCopy());
        return spec;
    }

    public static String toSpecJson(LegScan scan, Options options) {
        // Gson puts every array element on its own line; keep number arrays on one line like walkers/*.json.
        return NUMBER_ARRAY.matcher(GSON.toJson(toSpec(scan, options)))
                .replaceAll(m -> "[" + String.join(", ", m.group(1).trim().split("\\s*,\\s*")) + "]") + "\n";
    }

    private static JsonArray array(Vec3 v) {
        return array(v.x, v.y, v.z);
    }

    private static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) {
            if (v == Math.rint(v)) a.add((long) v);
            else a.add(v);
        }
        return a;
    }

    private static JsonArray array(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) a.add(v);
        return a;
    }
}
