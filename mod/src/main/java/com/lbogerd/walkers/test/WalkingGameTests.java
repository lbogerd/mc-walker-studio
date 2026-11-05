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
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 48; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = CX - 2; x <= CX + 2; x++)
            for (int z = CZ - 2; z <= CZ + 2; z++) {
                helper.setBlock(new BlockPos(x, HULL_Y, z), Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(new BlockPos(x, HULL_Y, z)));
            }
        BlockPos controller = new BlockPos(CX, HULL_Y + 1, CZ);
        helper.setBlock(controller, ModBlocks.WALKER_CONTROLLER.get());
        BlockPos anchor = helper.absolutePos(controller);
        blocks.add(anchor);
        BlockPos a = helper.absolutePos(new BlockPos(CX - 2, HULL_Y, CZ - 2)), b = helper.absolutePos(new BlockPos(CX + 2, HULL_Y + 1, CZ + 2));
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), anchor, blocks, new BoundingBox3i(
                Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ())));
        BlockPos c = sub.getPlot().getCenterBlock();
        var be = (WalkerControllerBlockEntity) sub.getLevel().getBlockEntity(c);
        Vector3d originPlot = new Vector3d(c.getX() + 0.5, c.getY() - 1, c.getZ() + 0.5); // hull bottom-face centre
        be.configureWalker(QUAD_CRAWL, originPlot, Direction.NORTH, new Suspension(3.0, 1.5, 0.8, 0.5, 5.0));
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
        Walker w = driven(helper, rpm, lever);
        trace(helper, w, "drive " + rpm + "rpm lever " + lever, 200);
        helper.runAfterDelay(200, () -> {
            Vector3d o = w.origin(), plan = w.drive().plannedOriginWorld();
            double walked = w.start().z - o.z, posErr = Math.hypot(o.x - plan.x, o.z - plan.z);
            Walkers.LOG.info("[walk] driven rpm={} lever={}: walked {} b, throttle {}, posErr {}, controller speed {} RPM, stress impact {} SU/RPM",
                    rpm, lever, walked, w.be().lastThrottle(), posErr, w.be().getSpeed(), w.be().calculateStressApplied());
            if (walked < minWalked || walked > maxWalked)
                helper.fail("At %d RPM, lever %d, the walker moved %.2f b; expected %.1f..%.1f".formatted(rpm, lever, walked, minWalked, maxWalked));
            else if (posErr > 0.6) helper.fail("Hull is %.2f b from the plan".formatted(posErr));
            else helper.succeed();
        });
    }

    /** Design RPM (32) and full lever: full speed, ~1.07 b/s × ~9 s. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void drivesForwardAtDesignRpm(GameTestHelper helper) { expectWalk(helper, 32, 15, 7, 11); }

    /** Half the design RPM: half the top speed (D7). */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void halfRpmWalksAtHalfSpeed(GameTestHelper helper) { expectWalk(helper, 16, 15, 3.5, 5.5); }

    /** Lever at 0: the walker stands still even though the shaft turns. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void leverAtZeroStandsStill(GameTestHelper helper) { expectWalk(helper, 32, 0, -0.2, 0.2); }

    /** A reversed shaft walks backward. */
    @GameTest(template = "track", timeoutTicks = 260)
    public static void reversedShaftWalksBackward(GameTestHelper helper) {
        expectWalk(helper, -32, 15, -11, -7);
    }

    /** The walker is a Create stress consumer: impact per RPM from its spec (legs × 4 + mass × 0.05 by default). */
    @GameTest(template = "track", timeoutTicks = 100)
    public static void walkerDrawsStressFromItsSpec(GameTestHelper helper) {
        Walker w = driven(helper, 32, 0);
        helper.runAfterDelay(20, () -> {
            double expected = WalkerControllerBlockEntity.stressImpactPerRpm(w.drive().spec);
            double impact = w.be().calculateStressApplied();
            Walkers.LOG.info("[walk] stress impact {} SU/RPM (spec {}), speed {} RPM, overstressed {}", impact, expected, w.be().getSpeed(), w.be().isOverStressed());
            if (Math.abs(impact - expected) > 1e-3 || expected <= 0) helper.fail("Stress impact %.2f, expected %.2f".formatted(impact, expected));
            else if (w.be().getSpeed() == 0) helper.fail("The controller isn't turning");
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
}
