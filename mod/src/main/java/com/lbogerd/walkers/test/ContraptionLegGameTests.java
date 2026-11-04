package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.List;

/**
 * Follow-up 6 spike (decision D2): a Create contraption "leg" on a moving Sable hull.
 * <p>
 * The suspended hull from {@link SuspensionGameTests} is assembled first. Then, inside its plot, a Creative
 * Motor drives a Mechanical Bearing that swings a glued 3-block arm (the stand-in leg) about the hull's z axis,
 * the way a hip swings a leg forward and back.
 */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContraptionLegGameTests {
    private ContraptionLegGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 220)
    public static void contraptionLegRidesOnHull(GameTestHelper helper) {
        SuspensionGameTests.Rig rig = SuspensionGameTests.buildAndAssemble(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos c = rig.plotController();
        BlockPos motor = c.offset(0, 0, 1), bearing = c.offset(0, 0, 2), anchor = c.offset(0, 0, 3);
        double groundY = helper.absolutePos(new BlockPos(0, 1, 0)).getY();

        helper.runAfterDelay(5, () -> {
            // arm first, glued together; then bearing; then the motor that powers it
            List<BlockPos> arm = List.of(anchor, anchor.below(), anchor.below(2));
            arm.forEach(p -> level.setBlockAndUpdate(p, Blocks.ANDESITE.defaultBlockState()));
            level.addFreshEntity(new SuperGlueEntity(level, new AABB(anchor.below(2)).minmax(new AABB(anchor))));
            level.setBlockAndUpdate(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        });

        final float[] angleAt = new float[2];
        final ControlledContraptionEntity[] leg = new ControlledContraptionEntity[1];
        helper.runAfterDelay(60, () -> {
            List<ControlledContraptionEntity> found = level.getEntitiesOfClass(ControlledContraptionEntity.class, new AABB(anchor).inflate(4));
            if (found.isEmpty()) { helper.fail("Bearing did not assemble a contraption inside the hull's plot"); return; }
            leg[0] = found.getFirst();
            angleAt[0] = leg[0].getAngle(1);
        });
        helper.runAfterDelay(100, () -> {
            if (leg[0] == null) return;
            ControlledContraptionEntity e = leg[0];
            angleAt[1] = e.getAngle(1);
            KinematicContraption kc = (KinematicContraption) e;
            // Sable keeps the leg's kinematic body in the hull's plot space (it follows the hull through the
            // parent pose), positioned at the arm's rotating centre of mass, so it must stay within the arm's
            // reach (3 blocks) of the pivot.
            Vector3d physicsPos = new Vector3d(kc.sable$getPosition());
            Vector3d pivot = new Vector3d(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
            double trackErr = physicsPos.distance(pivot);
            Vector3d worldPos = rig.subLevel().logicalPose().transformPosition(new Vector3d(physicsPos));
            double[] m = SuspensionGameTests.measure(rig);
            Walkers.LOG.info("[leg] valid={} collides={} angle {}→{} trackErr={} world={} hull height {} tilt {}°",
                    kc.sable$isValid(), kc.sable$shouldCollide(), angleAt[0], angleAt[1], trackErr, worldPos, m[0] - groundY, m[1]);
            if (!kc.sable$isValid()) helper.fail("Leg contraption is not a valid Sable kinematic body");
            else if (!kc.sable$shouldCollide()) helper.fail("Leg contraption doesn't collide");
            else if (Math.abs(angleAt[1] - angleAt[0]) < 1) helper.fail("Leg didn't rotate (%.1f° → %.1f°)".formatted(angleAt[0], angleAt[1]));
            else if (trackErr > 3) helper.fail("Leg physics body is %.2f b from its pivot (arm is 3 long)".formatted(trackErr));
            else if (worldPos.distance(rig.subLevel().logicalPose().position()) > 6) helper.fail("Leg isn't next to the hull in world space: " + worldPos);
            else if (Math.abs(m[0] - groundY - SuspensionGameTests.RIDE) > 0.5) helper.fail("Hull height %.2f with the leg attached".formatted(m[0] - groundY));
            else if (m[1] > 10) helper.fail("Hull tilted %.1f° with the leg attached".formatted(m[1]));
            else helper.succeed();
        });
    }

    /**
     * The point of contraption legs (D2) is that they collide. A free block (its own sub-level) rests on a
     * pillar inside the arm's swing circle; the swinging leg must knock it off.
     */
    @GameTest(template = "arena", timeoutTicks = 220)
    public static void contraptionLegKnocksFreeBlock(GameTestHelper helper) {
        freeBlockScenario(helper, true);
    }

    /** Control: same pillar and block, no leg. The block must stay put, so the leg is what moved it above. */
    @GameTest(template = "arena", timeoutTicks = 220)
    public static void freeBlockStaysWithoutLeg(GameTestHelper helper) {
        freeBlockScenario(helper, false);
    }

    static void freeBlockScenario(GameTestHelper helper, boolean withLeg) {
        SuspensionGameTests.Rig rig = SuspensionGameTests.buildAndAssemble(helper, true);
        ServerLevel level = helper.getLevel();
        BlockPos c = rig.plotController();
        BlockPos motor = c.offset(0, 0, 1), bearing = c.offset(0, 0, 2), anchor = c.offset(0, 0, 3);

        // Pivot ends up near relative (7.5, 5.5, 10.5) once the hull settles at ride height. The arm reaches 3
        // blocks, so a block centred 2 blocks to +x of the pivot sits in its path.
        BlockPos pillar = new BlockPos(9, 4, 10), target = pillar.above();
        for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(9, y, 10), Blocks.STONE);
        helper.setBlock(target, Blocks.GOLD_BLOCK);
        BlockPos targetAbs = helper.absolutePos(target);
        ServerSubLevel block = SubLevelAssemblyHelper.assembleBlocks(level, targetAbs, List.of(targetAbs),
                new BoundingBox3i(targetAbs.getX(), targetAbs.getY(), targetAbs.getZ(), targetAbs.getX(), targetAbs.getY(), targetAbs.getZ()));
        final Vector3d start = new Vector3d();

        helper.runAfterDelay(4, () -> start.set(block.logicalPose().position()));
        if (withLeg) helper.runAfterDelay(5, () -> {
            List<BlockPos> arm = List.of(anchor, anchor.below(), anchor.below(2));
            arm.forEach(p -> level.setBlockAndUpdate(p, Blocks.ANDESITE.defaultBlockState()));
            level.addFreshEntity(new SuperGlueEntity(level, new AABB(anchor.below(2)).minmax(new AABB(anchor))));
            level.setBlockAndUpdate(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        });
        helper.runAfterDelay(150, () -> {
            Vector3d now = block.logicalPose().position();
            double moved = now.distance(start);
            Walkers.LOG.info("[leg] withLeg={} free block moved {} b ({} → {})", withLeg, moved, start, now);
            if (withLeg && moved < 0.5) helper.fail("The swinging leg didn't move the free block (moved %.2f b)".formatted(moved));
            else if (!withLeg && moved > 0.1) helper.fail("Without a leg the free block still moved %.2f b".formatted(moved));
            else helper.succeed();
        });
    }
}
