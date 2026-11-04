package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.registry.ModBlocks;
import com.lbogerd.walkers.walker.Suspension;
import com.lbogerd.walkers.walker.WalkerControllerBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Follow-up 5 spike (decision D1): can spring impulses at four "hips" hold a Sable hull up at ride height?
 * <p>
 * Arena: 16×14×16 of air; each test lays a smooth-stone floor at relative y=0 (ground surface at y=1).
 * (Game-test structures are placed one block above the helper's origin, so we don't rely on the template
 * for the floor.) A 5×1×5 iron hull with the
 * Walker Controller on top is built one block above ride height, assembled into a sub-level and released.
 */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SuspensionGameTests {
    private SuspensionGameTests() {}

    static final double RIDE = 3.0;
    static final int HULL_Y = 5;              // hull blocks at y=5 → hull bottom at 5, ground surface at 1
    static final BlockPos CONTROLLER = new BlockPos(7, HULL_Y + 1, 7);

    record Rig(ServerSubLevel subLevel, BlockPos plotController) {}

    static Rig buildAndAssemble(GameTestHelper helper, boolean withSuspension) {
        return buildAndAssemble(helper, withSuspension, List.of());
    }

    /** {@code extra}: additional blocks (relative positions, gold) assembled into the hull, e.g. an off-centre load. */
    static Rig buildAndAssemble(GameTestHelper helper, boolean withSuspension, List<BlockPos> extra) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = 5; x <= 9; x++)
            for (int z = 5; z <= 9; z++) {
                BlockPos p = new BlockPos(x, HULL_Y, z);
                helper.setBlock(p, Blocks.IRON_BLOCK);
                blocks.add(helper.absolutePos(p));
            }
        for (BlockPos p : extra) {
            helper.setBlock(p, Blocks.GOLD_BLOCK);
            blocks.add(helper.absolutePos(p));
        }
        helper.setBlock(CONTROLLER, ModBlocks.WALKER_CONTROLLER.get());
        BlockPos anchor = helper.absolutePos(CONTROLLER);
        blocks.add(anchor);

        BlockPos min = helper.absolutePos(new BlockPos(5, HULL_Y, 5));
        BlockPos max = helper.absolutePos(new BlockPos(9, HULL_Y + 3, 9));
        BoundingBox3i bounds = new BoundingBox3i(
                Math.min(min.getX(), max.getX()), min.getY(), Math.min(min.getZ(), max.getZ()),
                Math.max(min.getX(), max.getX()), max.getY(), Math.max(min.getZ(), max.getZ()));
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
        BlockPos plotController = subLevel.getPlot().getCenterBlock();

        Rig rig = new Rig(subLevel, plotController);
        if (withSuspension) configureSuspension(rig);
        return rig;
    }

    /** Hips at the centres of the four corner blocks' bottom faces (controller centre is 1.5 above the hull bottom). */
    static void configureSuspension(Rig rig) {
        if (!(rig.subLevel().getLevel().getBlockEntity(rig.plotController()) instanceof WalkerControllerBlockEntity be)) {
            throw new IllegalStateException("Walker Controller block entity not found in the plot at " + rig.plotController());
        }
        List<Vector3d> hips = List.of(
                new Vector3d(-2, -1.5, -2), new Vector3d(2, -1.5, -2),
                new Vector3d(-2, -1.5, 2), new Vector3d(2, -1.5, 2));
        be.configure(new Suspension(RIDE, 1.5, 0.8, 0.5, 5.0), hips);
    }

    static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
    }

    /** World-space height of the hull's bottom centre, and how far the hull's up axis is from vertical. */
    static double[] measure(Rig rig) {
        BlockPos c = rig.plotController();
        Vector3d bottom = rig.subLevel().logicalPose().transformPosition(new Vector3d(c.getX() + 0.5, c.getY() - 1.0, c.getZ() + 0.5));
        Vector3d up = rig.subLevel().logicalPose().transformNormal(new Vector3d(0, 1, 0));
        double tiltDeg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, up.y))));
        return new double[] {bottom.y, tiltDeg};
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void suspensionHoldsHullAtRideHeight(GameTestHelper helper) {
        Rig rig = buildAndAssemble(helper, true);
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        helper.runAfterDelay(140, () -> {
            double[] m = measure(rig);
            double height = m[0] - groundY;
            Walkers.LOG.info("[suspension] hull bottom {} above ground (target {}), tilt {}°", height, RIDE, m[1]);
            if (Math.abs(height - RIDE) > 0.3) helper.fail("Hull bottom is %.2f above ground, expected %.1f ± 0.3".formatted(height, RIDE));
            else if (m[1] > 5) helper.fail("Hull tilted %.1f°, expected < 5°".formatted(m[1]));
            else helper.succeed();
        });
    }

    /** Control: the same hull without suspension must fall onto the floor, proving the springs do the work. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void hullWithoutSuspensionFalls(GameTestHelper helper) {
        Rig rig = buildAndAssemble(helper, false);
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        helper.runAfterDelay(140, () -> {
            double height = measure(rig)[0] - groundY;
            Walkers.LOG.info("[suspension] unsupported hull bottom {} above ground", height);
            if (height > 0.5) helper.fail("Unsupported hull still %.2f above ground".formatted(height));
            else helper.succeed();
        });
    }

    /** A heavy load on one corner: per-hip springs must keep the hull nearly level. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void offCentreLoadStaysLevel(GameTestHelper helper) {
        Rig rig = buildAndAssemble(helper, true, List.of(
                new BlockPos(9, HULL_Y + 1, 9), new BlockPos(8, HULL_Y + 1, 9), new BlockPos(9, HULL_Y + 1, 8),
                new BlockPos(9, HULL_Y + 2, 9)));
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        helper.runAfterDelay(140, () -> {
            double[] m = measure(rig);
            Walkers.LOG.info("[suspension] off-centre load: bottom {} above ground, tilt {}°", m[0] - groundY, m[1]);
            if (m[1] > 3) helper.fail("Off-centre load tilted the hull %.1f°, expected < 3°".formatted(m[1]));
            else if (Math.abs(m[0] - groundY - RIDE) > 0.4) helper.fail("Hull bottom %.2f above ground, expected %.1f ± 0.4".formatted(m[0] - groundY, RIDE));
            else helper.succeed();
        });
    }

    /**
     * A sideways shove: planted feet (anchored springs) must catch the hull and bring it back to its stance,
     * level and at height. Motion is measured from positions; Rapier's reported velocity on a resting body
     * includes the per-step gravity term.
     */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void sidewaysShoveIsCaughtAndRecovered(GameTestHelper helper) {
        Rig rig = buildAndAssemble(helper, true);
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        final Vector3d start = new Vector3d();
        final double[] peak = {0};
        final Vector3d last = new Vector3d();
        helper.runAfterDelay(60, () -> {
            start.set(rig.subLevel().logicalPose().position());
            double m = rig.subLevel().getMassTracker().getMass();
            dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel())
                    .applyLinearImpulse(new Vector3d(4.0 * m, 0, 0)); // ~4 m/s sideways
        });
        for (int t = 61; t <= 130; t++) {
            helper.runAfterDelay(t, () -> {
                Vector3d p = rig.subLevel().logicalPose().position();
                peak[0] = Math.max(peak[0], Math.hypot(p.x - start.x, p.z - start.z));
                last.set(p);
            });
        }
        helper.runAfterDelay(140, () -> {
            Vector3d p = rig.subLevel().logicalPose().position();
            double drift = Math.hypot(p.x - start.x, p.z - start.z);
            double moving = p.distance(last) * 2; // blocks/s over the last 10 ticks
            double[] mm = measure(rig);
            Walkers.LOG.info("[suspension] shove: peak {} b, final drift {} b, still moving {} b/s, tilt {}°, height {}",
                    peak[0], drift, moving, mm[1], mm[0] - groundY);
            if (peak[0] < 0.05) helper.fail("The shove didn't move the hull (peak %.3f b)".formatted(peak[0]));
            else if (drift > 0.25) helper.fail("Hull ended %.2f blocks from its stance, expected < 0.25".formatted(drift));
            else if (moving > 0.02) helper.fail("Hull still moving at %.3f b/s".formatted(moving));
            else if (mm[1] > 3) helper.fail("Hull tilted %.1f° after the shove".formatted(mm[1]));
            else if (Math.abs(mm[0] - groundY - RIDE) > 0.3) helper.fail("Hull height %.2f after the shove".formatted(mm[0] - groundY));
            else helper.succeed();
        });
    }

    /**
     * Ground that is itself a sub-level (another ship, a platform): Sable returns those ray hits in the other
     * sub-level's plot space, so the controller must project them to world space before measuring.
     * A 9×1×9 platform sub-level rests on the floor; the hull should ride 3 blocks above the platform's top.
     */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void suspensionStandsOnAnotherSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<BlockPos> platform = new ArrayList<>();
        for (int x = 3; x <= 11; x++)
            for (int z = 3; z <= 11; z++) {
                BlockPos p = new BlockPos(x, 1, z);
                helper.setBlock(p, Blocks.STONE);
                platform.add(helper.absolutePos(p));
            }
        BlockPos pMin = helper.absolutePos(new BlockPos(3, 1, 3)), pMax = helper.absolutePos(new BlockPos(11, 1, 11));
        SubLevelAssemblyHelper.assembleBlocks(level, helper.absolutePos(new BlockPos(7, 1, 7)), platform, new BoundingBox3i(
                Math.min(pMin.getX(), pMax.getX()), pMin.getY(), Math.min(pMin.getZ(), pMax.getZ()),
                Math.max(pMin.getX(), pMax.getX()), pMax.getY(), Math.max(pMin.getZ(), pMax.getZ())));
        Rig rig = buildAndAssemble(helper, true);
        double platformTop = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
        helper.runAfterDelay(140, () -> {
            double[] m = measure(rig);
            double height = m[0] - platformTop;
            Walkers.LOG.info("[suspension] on platform sub-level: hull bottom {} above platform (target {}), tilt {}°", height, RIDE, m[1]);
            if (Math.abs(height - RIDE) > 0.3) helper.fail("On a platform sub-level the hull is %.2f above it, expected %.1f ± 0.3".formatted(height, RIDE));
            else if (m[1] > 5) helper.fail("Hull tilted %.1f° on the platform".formatted(m[1]));
            else helper.succeed();
        });
    }
}
