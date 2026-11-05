package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.registry.ModBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.lbogerd.walkers.sim.Scenarios;
import com.lbogerd.walkers.walker.Suspension;
import com.lbogerd.walkers.walker.WalkDriver;
import com.lbogerd.walkers.walker.WalkFrame;
import com.lbogerd.walkers.walker.WalkerControllerBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Follow-up step 2: the Java walking loop drives a real Sable hull. A 5×5 hull with the Walker Controller
 * (facing north = forward) stands on a 16×48 track; the controller runs a statically stable quad crawl (wave gait
 * with hull sway) from a spec, and only planted legs push the hull. The tests compare the real hull to the plan.
 */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WalkingGameTests {
    private WalkingGameTests() {}

    /**
     * A crawling quad with legs under the hull's corners, in the studio's walker format. The sway is kept at 0.4:
     * with {@code comShift: 1} the plan asks for ~15 m/s² of sideways hull acceleration, more than planted feet can
     * give (friction μ = 1 × g = 11 m/s²), and the real hull can't follow it.
     */
    static final String QUAD_CRAWL = """
            { "name": "test-crawler",
              "body": { "size": [5, 2, 5], "rideHeight": 3, "blocks": 26 },
              "legDefaults": { "segments": [2.5, 2.5], "knee": "back" },
              "legs": [
                { "id": "FL", "mount": [2, 0, -2], "neutral": [0.3, -0.5], "mirror": true },
                { "id": "HL", "mount": [-2, 0, -2], "neutral": [-0.3, -0.5], "mirror": true } ],
              "gait": { "preset": "wave", "duty": 0.8, "stride": 2, "stepHeight": 1, "comShift": 0.4 },
              "drive": { "rpm": 32 } }
            """;

    record Walker(ServerSubLevel subLevel, WalkerControllerBlockEntity be, WalkFrame frame, Vector3d start) {
        WalkDriver drive() { return be.walk(); }
        Vector3d origin() { return subLevel.logicalPose().transformPosition(new Vector3d(frame.originPlot)); }
        double tiltDeg() {
            Vector3d up = subLevel.logicalPose().transformNormal(new Vector3d(0, 1, 0));
            return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, up.y))));
        }
        double yawWorld() { return WalkFrame.yawOf(subLevel.logicalPose().transformNormal(frame.localDirToPlot(1, 0, 0))); }
    }

    static final int HULL_Y = 5, CX = 7;
    /** Start a little past the middle so both forward (−z) and backward walks stay on the 48-long track. */
    static int CZ = 30;

    static Walker build(GameTestHelper helper) {
        return build(helper, QUAD_CRAWL, z -> 0, HULL_Y);
    }

    /**
     * @param groundTop ground surface height (relative y) for each z; columns are filled from y = 0 up to it
     * @param hullY     relative y of the hull's block layer
     */
    static Walker build(GameTestHelper helper, String spec, java.util.function.IntUnaryOperator groundTop, int hullY) {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 48; z++)
                for (int y = 0; y <= groundTop.applyAsInt(z); y++) helper.setBlock(new BlockPos(x, y, z), Blocks.SMOOTH_STONE);
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = CX - 2; x <= CX + 2; x++)
            for (int z = CZ - 2; z <= CZ + 2; z++) {
                helper.setBlock(new BlockPos(x, hullY, z), Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, hullY, z)));
            }
        BlockPos controller = new BlockPos(CX, hullY + 1, CZ);
        helper.setBlock(controller, ModBlocks.WALKER_CONTROLLER.get());
        BlockPos anchor = helper.absolutePos(controller);
        blocks.add(anchor);
        BlockPos a = helper.absolutePos(new BlockPos(CX - 2, hullY, CZ - 2)), b = helper.absolutePos(new BlockPos(CX + 2, hullY + 1, CZ + 2));
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), anchor, blocks, new BoundingBox3i(
                Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ())));
        BlockPos c = sub.getPlot().getCenterBlock();
        var be = (WalkerControllerBlockEntity) sub.getLevel().getBlockEntity(c);
        Vector3d originPlot = new Vector3d(c.getX() + 0.5, c.getY() - 1, c.getZ() + 0.5); // hull bottom-face centre
        be.configureWalker(spec, originPlot, Direction.NORTH, new Suspension(3.0, 1.5, 0.8, 0.5, 5.0));
        WalkFrame frame = be.walk().frame;
        return new Walker(sub, be, frame, sub.logicalPose().transformPosition(new Vector3d(originPlot)));
    }

    static void trace(GameTestHelper helper, Walker w, String label, int until) {
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        for (int t = 0; t <= until; t += 20) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                Vector3d o = w.origin();
                Vector3d plan = w.drive().plannedOriginWorld();
                Walkers.LOG.info("[walk {}] t={} actual=({}, {}, {}) planned={} yaw={}° plannedYaw={}° tilt={}° planted={}",
                        label, tick, "%.2f".formatted(o.x - w.start().x), "%.2f".formatted(o.y - groundY), "%.2f".formatted(o.z - w.start().z),
                        plan == null ? "-" : "(%.2f, %.2f, %.2f)".formatted(plan.x - w.start().x, plan.y - groundY, plan.z - w.start().z),
                        "%.1f".formatted(Math.toDegrees(w.yawWorld())), "%.1f".formatted(Math.toDegrees(w.drive().plannedYawWorld())),
                        "%.1f".formatted(w.tiltDeg()), w.drive().lastPlanted());
            });
        }
    }

    static void commands(GameTestHelper helper, Walker w, IntFunction<Scenarios.Command> schedule, int until) {
        for (int t = 0; t <= until; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> w.be().setManualCommand(schedule.apply(tick)));
        }
    }

    @GameTest(template = "track", timeoutTicks = 300)
    public static void walksForwardAlongThePlan(GameTestHelper helper) {
        Walker w = build(helper);
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        commands(helper, w, t -> t < 20 ? Scenarios.Command.STOP : new Scenarios.Command(1, 0), 240);
        trace(helper, w, "fwd", 240);
        helper.runAfterDelay(240, () -> {
            Vector3d o = w.origin(), plan = w.drive().plannedOriginWorld();
            double walked = w.start().z - o.z;            // forward is north (−z)
            double planned = w.start().z - plan.z;
            double heightErr = Math.abs((o.y - groundY) - 3.0);
            double posErr = Math.hypot(o.x - plan.x, o.z - plan.z);
            Walkers.LOG.info("[walk] walked {} b (planned {}), posErr {}, height err {}, tilt {}°",
                    walked, planned, posErr, heightErr, w.tiltDeg());
            if (planned < 5) helper.fail("The plan only covered %.2f b".formatted(planned));
            else if (walked < 0.8 * planned) helper.fail("Hull walked %.2f b of the planned %.2f b".formatted(walked, planned));
            else if (posErr > 0.6) helper.fail("Hull is %.2f b from the planned position".formatted(posErr));
            else if (heightErr > 0.5) helper.fail("Hull height off by %.2f b".formatted(heightErr));
            else if (w.tiltDeg() > 8) helper.fail("Hull tilted %.1f°".formatted(w.tiltDeg()));
            else helper.succeed();
        });
    }

    @GameTest(template = "track", timeoutTicks = 260)
    public static void turnsInPlace(GameTestHelper helper) {
        Walker w = build(helper);
        commands(helper, w, t -> t < 20 ? Scenarios.Command.STOP : new Scenarios.Command(0, 1), 200);
        trace(helper, w, "turn", 200);
        final double[] yaw0 = new double[1];
        helper.runAfterDelay(20, () -> yaw0[0] = w.yawWorld());
        helper.runAfterDelay(200, () -> {
            double turned = Math.toDegrees(angleDiff(w.yawWorld(), yaw0[0]));
            double plannedTurn = Math.toDegrees(angleDiff(w.drive().plannedYawWorld(), w.frame().courseYaw));
            double drift = Math.hypot(w.origin().x - w.start().x, w.origin().z - w.start().z);
            Walkers.LOG.info("[walk] turned {}° (planned {}°), drift {} b, tilt {}°", turned, plannedTurn, drift, w.tiltDeg());
            if (Math.abs(plannedTurn) < 30) helper.fail("The plan only turned %.1f°".formatted(plannedTurn));
            else if (Math.abs(turned - plannedTurn) > Math.max(8, 0.2 * Math.abs(plannedTurn))) helper.fail("Hull turned %.1f°, plan %.1f°".formatted(turned, plannedTurn));
            else if (drift > 1.0) helper.fail("Hull drifted %.2f b while turning in place".formatted(drift));
            else if (w.tiltDeg() > 8) helper.fail("Hull tilted %.1f°".formatted(w.tiltDeg()));
            else helper.succeed();
        });
    }

    @GameTest(template = "track", timeoutTicks = 260)
    public static void walksThenStopsAndHolds(GameTestHelper helper) {
        Walker w = build(helper);
        commands(helper, w, t -> t >= 20 && t < 100 ? new Scenarios.Command(1, 0) : Scenarios.Command.STOP, 220);
        trace(helper, w, "stop", 220);
        final Vector3d at = new Vector3d();
        helper.runAfterDelay(200, () -> at.set(w.origin()));
        helper.runAfterDelay(220, () -> {
            double moved = w.origin().distance(at), walked = w.start().z - w.origin().z;
            long planted = w.drive().last().legs().stream().filter(WalkDriver::isPlanted).count();
            Walkers.LOG.info("[walk] stopped after {} b; moved {} b in the last second; {} feet planted; tilt {}°", walked, moved, planted, w.tiltDeg());
            if (walked < 1) helper.fail("Hull didn't walk before stopping (%.2f b)".formatted(walked));
            else if (moved > 0.05) helper.fail("Hull still moving %.3f b/s after stopping".formatted(moved));
            else if (planted < 4) helper.fail("Only %d feet planted at rest".formatted(planted));
            else if (w.tiltDeg() > 5) helper.fail("Hull tilted %.1f° at rest".formatted(w.tiltDeg()));
            else helper.succeed();
        });
    }

    static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    // ------------------------------------------------------------------ driving with the hull's controls (D5, D7)

    /**
     * The walker driven by its own controls: a Creative Motor at {@code rpm} feeding the controller's back face
     * (south, since it faces north) and a Simulated Throttle Lever on the hull at {@code lever} (0–15).
     */
    static Walker driven(GameTestHelper helper, int rpm, int lever) {
        Walker w = build(helper);
        var level = helper.getLevel();
        BlockPos c = w.be().getBlockPos();
        level.setBlockAndUpdate(c.offset(0, 0, 1), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.NORTH));
        if (level.getBlockEntity(c.offset(0, 0, 1)) instanceof CreativeMotorBlockEntity m) m.generatedSpeed.setValue(rpm);
        level.setBlockAndUpdate(c.offset(1, 0, 0), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:throttle_lever")).defaultBlockState());
        helper.runAfterDelay(2, () -> {
            if (level.getBlockEntity(c.offset(1, 0, 0)) instanceof ThrottleLeverBlockEntity t) t.setSignal(lever);
        });
        return w;
    }

    static void expectWalk(GameTestHelper helper, int rpm, int lever, double minWalked, double maxWalked) {
        expectWalk(helper, rpm, lever, minWalked, maxWalked, 0.6);
    }

    static void expectWalk(GameTestHelper helper, int rpm, int lever, double minWalked, double maxWalked, double maxPosErr) {
        Walker w = driven(helper, rpm, lever);
        trace(helper, w, "drive " + rpm + "rpm lever " + lever, 200);
        final double[] ph0 = new double[1];
        helper.runAfterDelay(40, () -> ph0[0] = w.drive().gaitPhase());
        helper.runAfterDelay(200, () -> {
            // D7: the shaft drives the gait clock: at full lever it runs |RPM| / design RPM cycles per design cycle
            double rate = (w.drive().gaitPhase() - ph0[0]) / 160.0;
            double expectRate = lever == 0 ? 0 : Math.abs(rpm) / 32.0 / w.drive().spec.gait.cycleTicks;
            if (Math.abs(rate - expectRate) > 0.05 * expectRate + 1e-4) {
                helper.fail("Gait clock ran %.5f cycles/tick at %d RPM; expected %.5f".formatted(rate, rpm, expectRate));
                return;
            }
            Vector3d o = w.origin(), plan = w.drive().plannedOriginWorld();
            double walked = w.start().z - o.z, posErr = Math.hypot(o.x - plan.x, o.z - plan.z);
            Walkers.LOG.info("[walk] driven rpm={} lever={}: walked {} b, throttle {}, posErr {}, controller speed {} RPM, stress impact {} SU/RPM",
                    rpm, lever, walked, w.be().lastThrottle(), posErr, w.be().getSpeed(), w.be().calculateStressApplied());
            if (walked < minWalked || walked > maxWalked)
                helper.fail("At %d RPM, lever %d, the walker moved %.2f b; expected %.1f..%.1f".formatted(rpm, lever, walked, minWalked, maxWalked));
            else if (posErr > maxPosErr) helper.fail("Hull is %.2f b from the plan (allowed %.1f)".formatted(posErr, maxPosErr));
            else helper.succeed();
        });
    }

    /** Design RPM (32) and full lever: full speed, ~1.07 b/s × ~9 s. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void drivesForwardAtDesignRpm(GameTestHelper helper) { expectWalk(helper, 32, 15, 7, 11); }

    /** Half the design RPM: half the top speed (D7). */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void halfRpmWalksAtHalfSpeed(GameTestHelper helper) { expectWalk(helper, 16, 15, 3.5, 5.5); }

    /** Twice the design RPM: the gait cycle halves and the walker goes twice as fast (D7: RPM drives the legs). */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void doubleRpmWalksAtDoubleSpeed(GameTestHelper helper) { expectWalk(helper, 64, 15, 16, 22); }

    /** Lever at 0: the walker stands still even though the shaft turns. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void leverAtZeroStandsStill(GameTestHelper helper) { expectWalk(helper, 32, 0, -0.2, 0.2); }

    /** A reversed shaft walks backward. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void reversedShaftWalksBackward(GameTestHelper helper) {
        expectWalk(helper, -32, 15, -11, -7);
    }

    /**
     * The walker is a Create stress consumer: impact per RPM from its spec (legs × 4 + mass × 0.05 by default). Create's
     * network accounts for it (stress = impact × RPM), and the impact reaches clients (goggle tooltips).
     */
    @GameTest(template = "track", timeoutTicks = 100)
    public static void walkerDrawsStressFromItsSpec(GameTestHelper helper) {
        Walker w = driven(helper, 32, 0);
        helper.runAfterDelay(20, () -> {
            double expected = WalkerControllerBlockEntity.stressImpactPerRpm(w.drive().spec);
            double impact = w.be().calculateStressApplied();
            double network = w.be().networkStress();
            // what a client gets: the update tag read into a fresh block entity with no walker spec
            var registries = helper.getLevel().registryAccess();
            var copy = new WalkerControllerBlockEntity(w.be().getBlockPos(), w.be().getBlockState());
            copy.handleUpdateTag(w.be().getUpdateTag(registries), registries);
            double client = copy.calculateStressApplied();
            Walkers.LOG.info("[walk] stress impact {} SU/RPM (spec {}), network stress {} SU at {} RPM, client sees {}",
                    impact, expected, network, w.be().getSpeed(), client);
            if (Math.abs(impact - expected) > 1e-3 || expected <= 0) helper.fail("Stress impact %.2f, expected %.2f".formatted(impact, expected));
            else if (Math.abs(network - expected * Math.abs(w.be().getSpeed())) > 0.5) helper.fail("Network stress %.1f SU, expected %.1f".formatted(network, expected * Math.abs(w.be().getSpeed())));
            else if (Math.abs(client - expected) > 1e-3) helper.fail("A client would see a stress impact of %.2f".formatted(client));
            else helper.succeed();
        });
    }

    /**
     * Steering Wheel held at full lock with the lever at 0: the walker turns in place (D5). Positive wheel angle =
     * positive steer = turning left (yaw increases), as in the studio.
     */
    @GameTest(template = "track", timeoutTicks = 240)
    public static void steeringWheelTurnsTheWalker(GameTestHelper helper) {
        Walker w = driven(helper, 32, 0);
        var level = helper.getLevel();
        BlockPos wheel = w.be().getBlockPos().offset(-1, 0, 0);
        level.setBlockAndUpdate(wheel, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:steering_wheel")).defaultBlockState());
        final double[] yaw0 = new double[1];
        helper.runAfterDelay(10, () -> yaw0[0] = w.yawWorld());
        for (int t = 5; t <= 180; t++)
            helper.runAfterDelay(t, () -> {
                if (level.getBlockEntity(wheel) instanceof SteeringWheelBlockEntity sw) {
                    // what a player's input does: the wheel keeps turning toward targetAngleToUpdate (else back to 0)
                    sw.targetAngleToUpdate = sw.angleInput.getValue();
                }
            });
        helper.runAfterDelay(180, () -> {
            double turned = Math.toDegrees(angleDiff(w.yawWorld(), yaw0[0]));
            double drift = Math.hypot(w.origin().x - w.start().x, w.origin().z - w.start().z);
            Walkers.LOG.info("[walk] steering wheel: steer {}, turned {}°, drift {} b", w.be().lastSteer(), turned, drift);
            if (Math.abs(w.be().lastSteer()) < 0.5) helper.fail("Steering Wheel at full lock gave steer %.2f".formatted(w.be().lastSteer()));
            else if (Math.abs(turned) < 30) helper.fail("Walker turned only %.1f° with the wheel at full lock".formatted(turned));
            else if (Math.signum(turned) != Math.signum(w.be().lastSteer())) helper.fail("Steer %.2f turned the walker %.1f°: wrong way round".formatted(w.be().lastSteer(), turned));
            else if (drift > 1.5) helper.fail("Walker drifted %.2f b while turning in place".formatted(drift));
            else helper.succeed();
        });
    }

    // ------------------------------------------------------------------ support must be real (review round 1)

    static double heightAboveGround(GameTestHelper helper, Walker w) {
        return w.origin().y - helper.absolutePos(new BlockPos(0, 1, 0)).getY();
    }

    /** Remove the ground under a standing walker: its feet have nothing to push on, so it must fall. */
    @GameTest(template = "track", timeoutTicks = 200)
    public static void walkerFallsWhenItsGroundIsRemoved(GameTestHelper helper) {
        Walker w = build(helper);
        w.be().setManualCommand(Scenarios.Command.STOP);
        final double[] before = new double[1];
        helper.runAfterDelay(60, () -> {
            before[0] = heightAboveGround(helper, w);
            for (int x = CX - 5; x <= CX + 5; x++)
                for (int z = CZ - 5; z <= CZ + 5; z++)
                    for (int y = -3; y <= 0; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        });
        helper.runAfterDelay(120, () -> {
            double h = heightAboveGround(helper, w);
            Walkers.LOG.info("[walk] ground removed: height {} → {}, planted {}", before[0], h, w.drive().lastPlanted());
            if (h > before[0] - 1.5) helper.fail("Walker still held at %.2f (was %.2f) with no ground under its feet".formatted(h, before[0]));
            else if (w.drive().lastPlanted() > 0) helper.fail("%d legs still count as planted over a hole".formatted(w.drive().lastPlanted()));
            else helper.succeed();
        });
    }

    /** Shove the hull sideways beyond its legs' reach: the planted feet can't hold or pull it back, so it must fall. */
    @GameTest(template = "track", timeoutTicks = 200)
    public static void walkerShovedOutOfReachFalls(GameTestHelper helper) {
        Walker w = build(helper);
        w.be().setManualCommand(Scenarios.Command.STOP);
        final double[] x0 = new double[1];
        helper.runAfterDelay(60, () -> {
            x0[0] = w.origin().x;
            Vector3d pos = new Vector3d(w.subLevel().logicalPose().position()).add(7, 0, 0);
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(helper.getLevel()).physicsSystem().getPipeline()
                    .teleport(w.subLevel(), pos, new org.joml.Quaterniond(w.subLevel().logicalPose().orientation()));
        });
        helper.runAfterDelay(120, () -> {
            double h = heightAboveGround(helper, w), dx = w.origin().x - x0[0];
            Walkers.LOG.info("[walk] shoved 7 b: still {} b over, height {}, planted {}", dx, h, w.drive().lastPlanted());
            if (dx < 5) helper.fail("The walker was pulled back to %.2f b from its feet".formatted(dx));
            else if (h > 1.5) helper.fail("Walker still held %.2f b up with its feet out of reach".formatted(h));
            else helper.succeed();
        });
    }

    /** A ceiling over the walker is not ground: it walks under it at ride height. */
    @GameTest(template = "track", timeoutTicks = 200)
    public static void walksUnderACeiling(GameTestHelper helper) {
        for (int x = 0; x < 16; x++)
            for (int z = CZ - 14; z <= CZ + 3; z++) helper.setBlock(new BlockPos(x, HULL_Y + 5, z), Blocks.STONE);
        Walker w = build(helper);
        commands(helper, w, t -> t < 20 ? Scenarios.Command.STOP : new Scenarios.Command(1, 0), 150);
        helper.runAfterDelay(150, () -> {
            double h = heightAboveGround(helper, w), walked = w.start().z - w.origin().z;
            Walkers.LOG.info("[walk] under a ceiling: height {}, walked {} b", h, walked);
            if (Math.abs(h - 3.0) > 0.5) helper.fail("Under a ceiling the hull rides at %.2f, expected 3".formatted(h));
            else if (walked < 5) helper.fail("Walker only got %.2f b under the ceiling".formatted(walked));
            else helper.succeed();
        });
    }

    /**
     * Terrain is sampled at the exact point, not a course cell's centre. Course origin half a block off the grid:
     * course x = 0.1 lies in world column 0 (a 3-high pillar), while its cell centre (x = 0.5) lies in column 1 (floor).
     */
    @GameTest(template = "track")
    public static void terrainSamplesTheExactPoint(GameTestHelper helper) {
        for (int x = 0; x < 4; x++) helper.setBlock(new BlockPos(x, 0, 5), Blocks.STONE);
        for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(0, y, 5), Blocks.STONE);
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 5));
        WalkFrame frame = new WalkFrame(new Vector3d(), Direction.EAST);
        frame.courseOrigin.set(o.getX() + 0.5, o.getY(), o.getZ() + 0.5);
        frame.courseYaw = 0; // course +x = world +x
        var terrain = new com.lbogerd.walkers.walker.WorldTerrain(helper.getLevel(), frame, o.getY() + 6);
        int onPillar = terrain.heightAt(-0.1, 0), nextColumn = terrain.heightAt(0.6, 0);
        Walkers.LOG.info("[walk] terrain: course x=-0.1 → {}, x=0.6 → {}", onPillar, nextColumn);
        if (onPillar != 3) helper.fail("Course x=-0.1 (world column 0, the pillar) read %d, expected 3".formatted(onPillar));
        else if (nextColumn != 0) helper.fail("Course x=0.6 (world column 1, the floor) read %d, expected 0".formatted(nextColumn));
        else helper.succeed();
    }

    // ------------------------------------------------------------------ review round 2

    /**
     * D4 at any RPM: the hull's real acceleration never exceeds gait.accel (2 b/s²) while the shaft speed and the
     * throttle change, including together: start at 0.5× with throttle at the same tick, raise to 1.5×, reverse at 2×,
     * brake, restart. (Lowering the RPM is the one exception: the legs can't outrun the shaft, so the hull slows with it.)
     */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void accelerationStaysLimitedAtAnyRpm(GameTestHelper helper) {
        Walker w = build(helper);
        final double[] prev = {Double.NaN}, peak = {0}, top = {0};
        for (int t = 0; t <= 220; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                double scale = tick < 60 ? 0.5 : tick < 100 ? 1.5 : 2;
                double throttle = tick < 10 ? 0 : tick < 100 ? 1 : tick < 140 ? -1 : tick < 180 ? 0 : 1;
                w.be().setManualCommand(new Scenarios.Command(throttle, 0));
                w.be().setManualTimeScale(tick < 10 ? 1 : scale); // scale and throttle change on the same tick (10)
                double v = w.drive().plannedSpeed();
                if (!Double.isNaN(prev[0])) peak[0] = Math.max(peak[0], Math.abs(v - prev[0]) * 20);
                prev[0] = v;
                top[0] = Math.max(top[0], Math.abs(v));
            });
        }
        helper.runAfterDelay(220, () -> {
            double limit = w.drive().spec.gait.accel;
            Walkers.LOG.info("[walk] RPM and throttle changes: peak planned acceleration {} b/s² (limit {}), top speed {} b/s", peak[0], limit, top[0]);
            if (peak[0] > limit * 1.02) helper.fail("Planned acceleration reached %.3f b/s² (gait.accel %.1f)".formatted(peak[0], limit));
            else if (top[0] < 1.8 * w.drive().gaitTopSpeed()) helper.fail("2× RPM only reached %.2f b/s".formatted(top[0]));
            else helper.succeed();
        });
    }

    /**
     * Reach is checked with the leg's own IK, not just its length. Coxa legs (coxa 3 b, then 1.5 + 1.5): shoved so the
     * right-hand footholds sit right under their hips, they're within the legs' total length (2 b ≤ 6 b), but the coxa
     * puts the leg's base 3 b out, so the IK can't reach them and nothing holds the hull up.
     */
    static final String COXA_QUAD = """
            { "name": "test-coxa",
              "body": { "size": [5, 2, 5], "rideHeight": 2, "blocks": 26 },
              "legDefaults": { "segments": [3, 1.5, 1.5], "knee": "out" },
              "legs": [
                { "id": "FL", "mount": [2, 0, -2], "neutral": [0, -4], "mirror": true },
                { "id": "HL", "mount": [-2, 0, -2], "neutral": [0, -4], "mirror": true } ],
              "gait": { "preset": "wave", "duty": 0.8, "stride": 1.5, "stepHeight": 0.8 },
              "drive": { "rpm": 32 } }
            """;

    @GameTest(template = "track", timeoutTicks = 200)
    public static void unreachableByIkIsNotSupport(GameTestHelper helper) {
        Walker w = build(helper, COXA_QUAD, z -> 0, 4);
        w.be().setManualCommand(Scenarios.Command.STOP);
        final double[] before = new double[1];
        helper.runAfterDelay(60, () -> {
            before[0] = heightAboveGround(helper, w);
            // move the hull 4 b to its right (+x for a north-facing hull) so the right footholds are under the hips
            Vector3d pos = new Vector3d(w.subLevel().logicalPose().position()).add(4, 0, 0);
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(helper.getLevel()).physicsSystem().getPipeline()
                    .teleport(w.subLevel(), pos, new org.joml.Quaterniond(w.subLevel().logicalPose().orientation()));
        });
        helper.runAfterDelay(120, () -> {
            double h = heightAboveGround(helper, w);
            Walkers.LOG.info("[walk] coxa legs shoved under their footholds: height {} → {}, planted {}", before[0], h, w.drive().lastPlanted());
            if (Math.abs(before[0] - 2) > 0.4) helper.fail("Coxa walker didn't stand at ride height first (%.2f)".formatted(before[0]));
            else if (w.drive().lastPlanted() > 0) helper.fail("%d legs count as planted though their IK can't reach".formatted(w.drive().lastPlanted()));
            else if (h > before[0] - 1) helper.fail("Hull still held at %.2f by legs that can't reach".formatted(h));
            else helper.succeed();
        });
    }

    /**
     * A long descent: a 1-in-2 staircase down 10 blocks, more than the walking loop's VOID depth (8) below the start.
     * The course frame re-bases as the walker descends, so it keeps finding ground all the way down.
     */
    @GameTest(template = "descent", timeoutTicks = 300)
    public static void walksDownALongStaircase(GameTestHelper helper) {
        java.util.function.IntUnaryOperator ground = z -> Math.max(0, Math.min(12, (z - 6) / 2));
        Walker w = build(helper, QUAD_CRAWL, ground, ground.applyAsInt(CZ) + 4);
        // at 2× it covers the 24 b of stairs in ~250 ticks; check while it's still on the course (it ends at z = 0)
        for (int t = 0; t <= 250; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                w.be().setManualCommand(tick < 20 ? Scenarios.Command.STOP : new Scenarios.Command(1, 0));
                w.be().setManualTimeScale(2);
            });
        }
        trace(helper, w, "descent", 250);
        helper.runAfterDelay(250, () -> {
            Vector3d o = w.origin();
            double walked = w.start().z - o.z;
            int localZ = (int) Math.floor(o.z - helper.absolutePos(BlockPos.ZERO).getZ());
            double groundHere = helper.absolutePos(new BlockPos(0, ground.applyAsInt(localZ) + 1, 0)).getY();
            double h = o.y - groundHere, descended = w.start().y - o.y;
            Walkers.LOG.info("[walk] descent: walked {} b, descended {} b, height above local ground {}, rebases {}, planted {}",
                    walked, descended, h, w.drive().rebases(), w.drive().lastPlanted());
            if (descended < 8.5) helper.fail("Only descended %.2f b (walked %.2f b)".formatted(descended, walked));
            else if (Math.abs(h - 3) > 0.8) helper.fail("Hull rides %.2f above the stairs, expected 3".formatted(h));
            else if (w.drive().lastPlanted() < 3) helper.fail("Only %d feet planted at the bottom".formatted(w.drive().lastPlanted()));
            else if (w.drive().rebases() < 1) helper.fail("The course frame never re-based");
            else helper.succeed();
        });
    }
}
