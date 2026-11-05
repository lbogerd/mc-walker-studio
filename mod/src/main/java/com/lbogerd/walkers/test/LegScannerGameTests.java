package com.lbogerd.walkers.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.assembly.LegScan;
import com.lbogerd.walkers.assembly.LegScanError;
import com.lbogerd.walkers.assembly.LegScanner;
import com.lbogerd.walkers.assembly.MeasuredLeg;
import com.lbogerd.walkers.assembly.WalkerSpecJson;
import com.lbogerd.walkers.leg.LegActuatorBlock;
import com.lbogerd.walkers.leg.LegJointBlock;
import com.lbogerd.walkers.registry.ModBlocks;
import com.lbogerd.walkers.walker.WalkerControllerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decision D3: legs built from Leg Actuator → Leg Struts / Leg Joints → Foot Pad, measured by {@link LegScanner}.
 * Positions are relative to the test (arena template); the floor is smooth stone at y=0.
 * <p>
 * {@link #quadMeasuresLikeQuadStrider} and {@link #hexapodLegWithCoxa} also write the scanned walker as a studio
 * spec to {@code mod/run/gametest/walker-scans/<name>.json} (see {@code mod/README.md}, "Building legs").
 */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LegScannerGameTests {
    private LegScannerGameTests() {}

    /** Small helper for building walkers block by block in a test. */
    static final class Build {
        final GameTestHelper helper;
        final List<BlockPos> hull = new ArrayList<>();
        BlockPos controller;

        Build(GameTestHelper helper) {
            this.helper = helper;
        }

        Build hull(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) block(x, y, z);
            return this;
        }

        Build block(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            helper.setBlock(p, Blocks.IRON_BLOCK);
            hull.add(helper.absolutePos(p));
            return this;
        }

        Build controller(int x, int y, int z, Direction forward) {
            BlockPos p = new BlockPos(x, y, z);
            helper.setBlock(p, ModBlocks.WALKER_CONTROLLER.get().defaultBlockState().setValue(WalkerControllerBlock.FACING, forward));
            controller = helper.absolutePos(p);
            hull.remove(controller); // may replace a hull block
            hull.add(controller);
            return this;
        }

        Build actuator(int x, int y, int z, Direction facing) {
            helper.setBlock(new BlockPos(x, y, z), ModBlocks.LEG_ACTUATOR.get().defaultBlockState().setValue(LegActuatorBlock.FACING, facing));
            return this;
        }

        Build strut(int x, int y, int z) {
            helper.setBlock(new BlockPos(x, y, z), ModBlocks.LEG_STRUT.get().defaultBlockState());
            return this;
        }

        /** Vertical run of struts from y0 down to y1 (inclusive). */
        Build strutsDown(int x, int y0, int y1, int z) {
            for (int y = y0; y >= y1; y--) strut(x, y, z);
            return this;
        }

        Build joint(int x, int y, int z, Direction knee) {
            helper.setBlock(new BlockPos(x, y, z), ModBlocks.LEG_JOINT.get().defaultBlockState().setValue(LegJointBlock.FACING, knee));
            return this;
        }

        Build foot(int x, int y, int z) {
            helper.setBlock(new BlockPos(x, y, z), ModBlocks.FOOT_PAD.get());
            return this;
        }

        LegScan scan() {
            LegScan scan = LegScanner.scan(helper.getLevel(), controller, LegScanner.collect(helper.getLevel(), controller, 4096));
            Walkers.LOG.info("[legs] {} leg(s): {}; errors {}; warnings {}", scan.legs().size(),
                    scan.legs().stream().map(LegScannerGameTests::describe).toList(), scan.errors(), scan.warnings());
            return scan;
        }

        BlockPos abs(int x, int y, int z) {
            return helper.absolutePos(new BlockPos(x, y, z));
        }
    }

    static String describe(MeasuredLeg l) {
        return "%s mount %s neutral %s segments %s knee %s%s%s".formatted(l.id(), l.mount(), Arrays.toString(l.neutral()),
                Arrays.toString(l.segments()), l.knee(), l.kneeFromShape() ? " (shape)" : " (joint facing)",
                l.mirrorOf() != null ? " mirrors " + l.mirrorOf() : "");
    }

    // ---------- assertions ----------

    static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }

    static void near(GameTestHelper helper, String what, double actual, double expected) {
        check(helper, Math.abs(actual - expected) < 1e-3, "%s: %.3f, expected %.3f".formatted(what, actual, expected));
    }

    static void leg(GameTestHelper helper, LegScan scan, String id, Vec3 mount, double[] neutral, double[] segments, String knee) {
        MeasuredLeg l = scan.leg(id);
        check(helper, l != null, "No leg " + id + " in " + scan.legs().stream().map(MeasuredLeg::id).toList());
        check(helper, l.mount().distanceTo(mount) < 1e-3, id + " mount " + l.mount() + ", expected " + mount);
        check(helper, Arrays.equals(l.neutral(), neutral), id + " neutral " + Arrays.toString(l.neutral()) + ", expected " + Arrays.toString(neutral));
        check(helper, l.segments().length == segments.length, id + " segments " + Arrays.toString(l.segments()) + ", expected " + Arrays.toString(segments));
        for (int i = 0; i < segments.length; i++) near(helper, id + " segment " + i, l.segments()[i], segments[i]);
        check(helper, l.knee().equals(knee), id + " knee " + l.knee() + ", expected " + knee);
    }

    static void clean(GameTestHelper helper, LegScan scan) {
        check(helper, scan.ok(), "Unexpected scan errors: " + scan.errors());
    }

    static void expectOnly(GameTestHelper helper, LegScan scan, LegScanError.Kind kind) {
        check(helper, !scan.errors().isEmpty(), "Expected a " + kind + " error, got none");
        check(helper, scan.errors().stream().allMatch(e -> e.kind() == kind), "Expected only " + kind + ", got " + scan.errors());
        helper.succeed();
    }

    static Path writeSpec(String name, LegScan scan, WalkerSpecJson.Options options) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("walker-scans");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".json");
            Files.writeString(file, WalkerSpecJson.toSpecJson(scan, options));
            Walkers.LOG.info("[legs] wrote {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- builds ----------

    /**
     * An 8×3×6 hull (forward = east = +x) with four straight 2-segment legs hanging from actuators under its
     * corner columns: actuator, 2 struts, joint, 2 struts, foot pad on the floor. Front knees face back (west),
     * hind knees forward (east), as in walkers/quad-strider.json.
     * <pre>
     *   side view (+x forward →)        hull bottom-face centre at (8, 8, 8); hull x 4..11, z 5..10, y 8..10 (controller in the top layer)
     *   y=8  [hull hull hull ... hull]
     *   y=7   A (hind)          A (front)   Leg Actuator, facing down: mount [±3.5, -0.5, ±2.5]
     *   y=6   |                 |           Leg Strut
     *   y=5   |                 |
     *   y=4   J→ (knee fwd)  ←J (knee back) Leg Joint    upper = 7.5 - 4.5 = 3
     *   y=3   |                 |
     *   y=2   |                 |
     *   y=1   F                 F           Foot Pad     lower = 4.5 - 1.5 = 3, rideHeight as built 6.5
     * </pre>
     */
    static Build quad(GameTestHelper helper) {
        SuspensionGameTests.floor(helper);
        Build b = new Build(helper).hull(4, 11, 8, 10, 5, 10).controller(8, 10, 8, Direction.EAST);
        for (int x : new int[] {4, 11})
            for (int z : new int[] {5, 10}) {
                boolean front = x == 11;
                b.actuator(x, 7, z, Direction.DOWN).strutsDown(x, 6, 5, z)
                        .joint(x, 4, z, front ? Direction.WEST : Direction.EAST)
                        .strutsDown(x, 3, 2, z).foot(x, 1, z);
            }
        return b;
    }

    // ---------- measurement tests ----------

    @GameTest(template = "arena")
    public static void quadMeasuresLikeQuadStrider(GameTestHelper helper) {
        LegScan scan = quad(helper).scan();
        clean(helper, scan);
        check(helper, scan.legs().size() == 4, "Expected 4 legs, got " + scan.legs().size());
        double[] seg = {3, 3}, none = {0, 0};
        leg(helper, scan, "FL", new Vec3(3.5, -0.5, -2.5), none, seg, "back");
        leg(helper, scan, "HL", new Vec3(-3.5, -0.5, -2.5), none, seg, "forward");
        leg(helper, scan, "FR", new Vec3(3.5, -0.5, 2.5), none, seg, "back");
        leg(helper, scan, "HR", new Vec3(-3.5, -0.5, 2.5), none, seg, "forward");
        check(helper, !scan.leg("FL").kneeFromShape(), "Straight leg: knee should come from the joint's facing");
        // walkers/quad-strider.json: FL mount [3.5, 0, -2.5], segments [3, 3], knee back; HL [-3.5, 0, -2.5], forward.
        // Our hips sit in a block under the hull, so they're half a block lower.
        check(helper, scan.leg("FL").mount().distanceTo(new Vec3(3.5, 0, -2.5)) <= 0.5 + 1e-6, "FL mount too far from the Quad Strider's");
        check(helper, Arrays.equals(scan.frame().size(), new int[] {8, 3, 6}), "Hull size " + Arrays.toString(scan.frame().size()));
        near(helper, "ride height as built", scan.builtRideHeight(), 6.5);

        // The spec the studio loads. Straight legs are at full reach as built, so use the Quad Strider's
        // ride height and gait for a walk that means something.
        JsonObject extra = JsonParser.parseString("""
                { "gait": { "preset": "alternating", "duty": 0.55, "stride": 4, "stepHeight": 1.4, "swing": "arc",
                            "bodyAdapt": "pitch", "footholdSearch": { "radius": 1 } },
                  "drive": { "rpm": 48, "revsPerCycle": 1 },
                  "physics": { "stabilizer": { "maxTorque": 160, "stressPerTorque": 2 } } }""").getAsJsonObject();
        JsonObject spec = WalkerSpecJson.toSpec(scan, new WalkerSpecJson.Options("Scanned Quad", 4.5, extra));
        check(helper, spec.getAsJsonArray("legs").size() == 2, "Mirrored legs should be written once with mirror: true: " + spec.get("legs"));
        check(helper, spec.getAsJsonObject("body").get("blocks").getAsInt() == 8 * 3 * 6, "body.blocks " + spec.getAsJsonObject("body").get("blocks"));
        writeSpec("scanned-quad", scan, new WalkerSpecJson.Options("Scanned Quad", 4.5, extra));
        helper.succeed();
    }

    /**
     * A hexapod-style leg with a coxa, hull forward = south, built on both sides. Left side (east, +x world):
     * <pre>
     *   front view, looking along the hull's forward (+x world → is the hull's left, -z)
     *   y=5        s  J2 s          upper: J1 → J2 = √5 ≈ 2.236 (struts go up, then out)
     *   y=4  hull  s     s          lower: J2 → F = √17 ≈ 4.123
     *   y=3  hull A  J1  s          coxa:  A → J1 = 1
     *   y=2              s          knee "out": J2 sits up and out of the J1 → F line
     *   y=1              F
     *       x=9  10  11 12 13
     * </pre>
     */
    @GameTest(template = "arena")
    public static void hexapodLegWithCoxa(GameTestHelper helper) {
        SuspensionGameTests.floor(helper);
        Build b = new Build(helper).hull(5, 9, 3, 4, 4, 10).controller(7, 5, 7, Direction.SOUTH);
        // left (east)
        b.actuator(10, 3, 9, Direction.EAST).joint(11, 3, 9, Direction.UP).strut(11, 4, 9).strut(11, 5, 9)
                .joint(12, 5, 9, Direction.UP).strut(13, 5, 9).strutsDown(13, 4, 2, 9).foot(13, 1, 9);
        // right (west), mirrored
        b.actuator(4, 3, 9, Direction.WEST).joint(3, 3, 9, Direction.UP).strut(3, 4, 9).strut(3, 5, 9)
                .joint(2, 5, 9, Direction.UP).strut(1, 5, 9).strutsDown(1, 4, 2, 9).foot(1, 1, 9);
        LegScan scan = b.scan();
        clean(helper, scan);
        // origin: x (5+10)/2 = 7.5, z (4+11)/2 = 7.5, y 3; forward south → hull x = z - 7.5, hull z = 7.5 - x
        double[] seg = {1, Math.sqrt(5), Math.sqrt(17)};
        leg(helper, scan, "L", new Vec3(2, 0.5, -3), new double[] {0, -3}, seg, "out");
        leg(helper, scan, "R", new Vec3(2, 0.5, 3), new double[] {0, 3}, seg, "out");
        check(helper, scan.leg("L").kneeFromShape(), "Bent knee: hint should come from the shape");
        check(helper, "L".equals(scan.leg("R").mirrorOf()), "R should mirror L");
        check(helper, Arrays.equals(scan.frame().size(), new int[] {7, 3, 5}), "Hull size " + Arrays.toString(scan.frame().size()));
        check(helper, scan.warnings().isEmpty(), "Unexpected warnings " + scan.warnings());
        writeSpec("scanned-hexapod-pair", scan, WalkerSpecJson.Options.named("Scanned Hexapod Pair"));
        helper.succeed();
    }

    /**
     * L-shaped segments and a knee read from the shape: forward = north. The upper segment goes down 2 then
     * forward 1, so the knee sits one block in front of the hip → foot line: knee "forward", even though the
     * joint faces back. Each segment measures √10 ≈ 3.162.
     * <pre>
     *   side view (forward = north = ←)
     *   y=7       A
     *   y=6       s
     *   y=5    s  s
     *   y=4    J          (joint faces back/south: ignored, the leg is bent)
     *   y=3    s  s
     *   y=2       s
     *   y=1       F
     *       z=4  5
     * </pre>
     */
    @GameTest(template = "arena")
    public static void bentLegKneeFromShape(GameTestHelper helper) {
        SuspensionGameTests.floor(helper);
        Build b = new Build(helper).hull(5, 9, 8, 8, 5, 9).controller(7, 9, 7, Direction.NORTH);
        b.actuator(5, 7, 5, Direction.DOWN).strut(5, 6, 5).strut(5, 5, 5).strut(5, 5, 4).joint(5, 4, 4, Direction.SOUTH)
                .strut(5, 3, 4).strut(5, 3, 5).strut(5, 2, 5).foot(5, 1, 5);
        LegScan scan = b.scan();
        clean(helper, scan);
        // origin (7.5, 8, 7.5); forward north → hull x = 7.5 - z, hull z = x - 7.5
        leg(helper, scan, "L", new Vec3(2, -0.5, -2), new double[] {0, 0}, new double[] {Math.sqrt(10), Math.sqrt(10)}, "forward");
        check(helper, scan.leg("L").kneeFromShape(), "Knee should come from the shape");
        helper.succeed();
    }

    /** Mirror detection: front legs mirror each other; the hind right knee faces out instead of forward, so it doesn't. */
    @GameTest(template = "arena")
    public static void mirroredLegsGetMirroredIds(GameTestHelper helper) {
        Build b = quad(helper);
        // hind right knee faces out (south = the hull's right) instead of forward
        helper.setBlock(new BlockPos(4, 4, 10), ModBlocks.LEG_JOINT.get().defaultBlockState().setValue(LegJointBlock.FACING, Direction.SOUTH));
        LegScan scan = b.scan();
        clean(helper, scan);
        check(helper, scan.legs().stream().map(MeasuredLeg::id).toList().equals(List.of("FL", "HL", "FR", "HR")),
                "Ids " + scan.legs().stream().map(MeasuredLeg::id).toList());
        check(helper, "FL".equals(scan.leg("FR").mirrorOf()), "FR should mirror FL");
        check(helper, scan.leg("HR").mirrorOf() == null, "HR has a different knee and must not mirror HL");
        check(helper, scan.leg("HR").knee().equals("out"), "HR knee " + scan.leg("HR").knee());
        JsonObject spec = WalkerSpecJson.toSpec(scan, WalkerSpecJson.Options.named("Mirrors"));
        List<String> written = new ArrayList<>();
        spec.getAsJsonArray("legs").forEach(e -> written.add(e.getAsJsonObject().get("id").getAsString()
                + (e.getAsJsonObject().has("mirror") ? "+mirror" : "")));
        check(helper, written.equals(List.of("FL+mirror", "HL", "HR")), "Spec legs " + written);
        helper.succeed();
    }

    /** The partition: hull blocks, actuators, each leg's chain in order and its split into segments. */
    @GameTest(template = "arena")
    public static void partitionsHullAndLegs(GameTestHelper helper) {
        Build b = quad(helper);
        b.block(8, 11, 9); // a deck block next to the controller belongs to the hull
        LegScan scan = b.scan();
        clean(helper, scan);
        check(helper, scan.hullBlocks().equals(new HashSet<>(b.hull)), "Hull has " + scan.hullBlocks().size() + " blocks, expected " + b.hull.size());
        Set<BlockPos> actuators = Set.of(b.abs(4, 7, 5), b.abs(4, 7, 10), b.abs(11, 7, 5), b.abs(11, 7, 10));
        check(helper, scan.actuators().equals(actuators), "Actuators " + scan.actuators());
        check(helper, scan.subLevelBlocks().size() == b.hull.size() + 4, "Sub-level blocks " + scan.subLevelBlocks().size());
        MeasuredLeg fl = scan.leg("FL");
        check(helper, fl.actuator().equals(b.abs(11, 7, 5)), "FL actuator " + fl.actuator());
        List<BlockPos> chain = List.of(b.abs(11, 6, 5), b.abs(11, 5, 5), b.abs(11, 4, 5), b.abs(11, 3, 5), b.abs(11, 2, 5), b.abs(11, 1, 5));
        check(helper, fl.chain().equals(chain), "FL chain " + fl.chain());
        check(helper, fl.pivots().equals(List.of(b.abs(11, 7, 5), b.abs(11, 4, 5), b.abs(11, 1, 5))), "FL pivots " + fl.pivots());
        check(helper, fl.segmentBlocks().equals(List.of(chain.subList(0, 3), chain.subList(3, 6))), "FL segments " + fl.segmentBlocks());
        Set<BlockPos> legBlocks = new HashSet<>();
        for (MeasuredLeg l : scan.legs()) for (BlockPos p : l.chain()) check(helper, legBlocks.add(p), "Block in two legs: " + p);
        check(helper, legBlocks.size() == 24, "Leg blocks " + legBlocks.size());
        for (BlockPos p : legBlocks) check(helper, !scan.subLevelBlocks().contains(p), "Leg block in the hull: " + p);
        helper.succeed();
    }

    // ---------- validation errors ----------
    // A 5×1×5 hull at y=8 with an actuator under its (5, 5) corner, facing down.

    static Build small(GameTestHelper helper) {
        return new Build(helper).hull(5, 9, 8, 8, 5, 9).controller(7, 9, 7, Direction.EAST).actuator(5, 7, 5, Direction.DOWN);
    }

    @GameTest(template = "arena")
    public static void errorNoFoot(GameTestHelper helper) {
        LegScan scan = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).scan();
        expectOnly(helper, scan, LegScanError.Kind.NO_FOOT);
    }

    @GameTest(template = "arena")
    public static void errorBranch(GameTestHelper helper) {
        LegScan scan = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).strut(6, 4, 5).foot(5, 3, 5).scan();
        expectOnly(helper, scan, LegScanError.Kind.BRANCH);
    }

    @GameTest(template = "arena")
    public static void errorTooManySegments(GameTestHelper helper) {
        LegScan scan = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).joint(5, 4, 5, Direction.WEST)
                .joint(5, 3, 5, Direction.WEST).foot(5, 2, 5).scan();
        expectOnly(helper, scan, LegScanError.Kind.TOO_MANY_SEGMENTS);
    }

    @GameTest(template = "arena")
    public static void errorTooFewSegments(GameTestHelper helper) {
        LegScan scan = small(helper).strutsDown(5, 6, 4, 5).foot(5, 3, 5).scan();
        expectOnly(helper, scan, LegScanError.Kind.TOO_FEW_SEGMENTS);
    }

    @GameTest(template = "arena")
    public static void errorLegsTouch(GameTestHelper helper) {
        Build b = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).foot(5, 3, 5);
        b.actuator(5, 7, 6, Direction.DOWN).strut(5, 6, 6).joint(5, 5, 6, Direction.WEST).strut(5, 4, 6).foot(5, 3, 6);
        expectOnly(helper, b.scan(), LegScanError.Kind.LEGS_TOUCH);
    }

    @GameTest(template = "arena")
    public static void errorTouchesWorld(GameTestHelper helper) {
        Build b = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).foot(5, 3, 5);
        helper.setBlock(new BlockPos(4, 4, 5), Blocks.STONE); // a rock beside the lower strut
        expectOnly(helper, b.scan(), LegScanError.Kind.TOUCHES_WORLD);
    }

    /** The foot pad standing on the ground is fine; that's how you build a walker. */
    @GameTest(template = "arena")
    public static void footOnGroundIsAllowed(GameTestHelper helper) {
        Build b = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).foot(5, 3, 5);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        clean(helper, b.scan());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void errorTouchesHull(GameTestHelper helper) {
        Build b = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).foot(5, 3, 5);
        b.block(6, 7, 5).block(6, 6, 5); // hull hanging down beside the upper strut
        expectOnly(helper, b.scan(), LegScanError.Kind.TOUCHES_HULL);
    }

    @GameTest(template = "arena")
    public static void errorNoChain(GameTestHelper helper) {
        Build b = small(helper); // nothing in front of the actuator
        b.actuator(9, 7, 9, Direction.UP); // facing into the hull
        LegScan scan = b.scan();
        check(helper, scan.errors().size() == 2, "Expected 2 errors, got " + scan.errors());
        expectOnly(helper, scan, LegScanError.Kind.NO_CHAIN);
    }

    @GameTest(template = "arena")
    public static void errorFootNotAtEnd(GameTestHelper helper) {
        LegScan scan = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).foot(5, 4, 5).strut(5, 3, 5).scan();
        expectOnly(helper, scan, LegScanError.Kind.FOOT_NOT_AT_END);
    }

    @GameTest(template = "arena")
    public static void errorLooseParts(GameTestHelper helper) {
        Build b = small(helper).strut(5, 6, 5).joint(5, 5, 5, Direction.WEST).strut(5, 4, 5).foot(5, 3, 5);
        helper.setBlock(new BlockPos(8, 7, 8), ModBlocks.LEG_STRUT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        expectOnly(helper, b.scan(), LegScanError.Kind.LOOSE_PARTS);
    }

    @GameTest(template = "arena")
    public static void errorNoController(GameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 7, 7), Blocks.IRON_BLOCK);
        BlockPos p = helper.absolutePos(new BlockPos(7, 7, 7));
        expectOnly(helper, LegScanner.scan(helper.getLevel(), p, List.of(p)), LegScanError.Kind.NO_CONTROLLER);
    }
}
