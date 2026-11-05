package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.registry.ModBlocks;
import com.lbogerd.walkers.walker.GyroStabilizerBlockEntity;
import com.lbogerd.walkers.walker.Suspension;
import com.lbogerd.walkers.walker.WalkerControllerBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/**
 * Gyro Stabilizer (decision D6). A hull standing on only two hips (both on the x axis) has nothing resisting
 * roll, like a biped. It starts with a 10° roll and carries 3 gold blocks off to one side, so holding it upright
 * needs torque all the time. Powered at 16 RPM the gyro must hold it upright; unpowered it must tip over
 * (control); at 1 RPM the torque cap is too low to hold it.
 */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GyroStabilizerGameTests {
    private GyroStabilizerGameTests() {}

    /** Builds the two-legged hull, optionally with a gyro (axis x) driven by a creative motor at {@code rpm}. */
    static SuspensionGameTests.Rig twoLeggedHull(GameTestHelper helper, Integer rpm) {
        SuspensionGameTests.Rig rig = SuspensionGameTests.buildAndAssemble(helper, false);
        ServerLevel level = helper.getLevel();
        BlockPos c = rig.plotController();
        // a sustained toppling load: 3 gold blocks 2 blocks to one side of the roll axis
        for (int dx = -1; dx <= 1; dx++) level.setBlockAndUpdate(c.offset(dx, 0, 2), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        if (rpm != null) {
            level.setBlockAndUpdate(c.offset(1, 0, 0), ModBlocks.GYRO_STABILIZER.get().defaultBlockState()
                    .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
            level.setBlockAndUpdate(c.offset(2, 0, 0), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(CreativeMotorBlock.FACING, Direction.WEST));
            if (level.getBlockEntity(c.offset(2, 0, 0)) instanceof CreativeMotorBlockEntity motor) motor.generatedSpeed.setValue(rpm);
        }
        // a 10° roll to start it falling
        Vector3d pos = new Vector3d(rig.subLevel().logicalPose().position());
        SubLevelContainer.getContainer(level).physicsSystem().getPipeline()
                .teleport(rig.subLevel(), pos, new Quaterniond().rotateX(Math.toRadians(10)));
        if (!(level.getBlockEntity(c) instanceof WalkerControllerBlockEntity be)) throw new IllegalStateException("no controller");
        be.configure(new Suspension(SuspensionGameTests.RIDE, 1.5, 0.8, 0.5, 5.0),
                List.of(new Vector3d(-2, -1.5, 0), new Vector3d(2, -1.5, 0)));
        return rig;
    }

    static void expectTilt(GameTestHelper helper, Integer rpm, boolean upright) {
        SuspensionGameTests.Rig rig = twoLeggedHull(helper, rpm);
        helper.runAfterDelay(140, () -> {
            double tilt = SuspensionGameTests.measure(rig)[1];
            double torque = rig.subLevel().getLevel().getBlockEntity(rig.plotController().offset(1, 0, 0))
                    instanceof GyroStabilizerBlockEntity g ? g.lastTorque() : 0;
            Walkers.LOG.info("[gyro] rpm={} tilt {}° (last torque {} N·m)", rpm, tilt, torque);
            if (upright && tilt > 5) helper.fail("Gyro at %s RPM let the hull tilt %.1f°".formatted(rpm, tilt));
            else if (!upright && tilt < 20) helper.fail("Expected the hull to tip (rpm %s), but it's only %.1f° over".formatted(rpm, tilt));
            else helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void gyroHoldsTwoLeggedHullUpright(GameTestHelper helper) { expectTilt(helper, 16, true); }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void twoLeggedHullTipsWithoutGyro(GameTestHelper helper) { expectTilt(helper, null, false); }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void slowGyroCannotHoldTheHull(GameTestHelper helper) { expectTilt(helper, 1, false); }

    /** Create sees the gyro as a stress consumer: SU = impact × RPM. */
    @GameTest(template = "arena")
    public static void gyroDrawsStress(GameTestHelper helper) {
        BlockPos gyro = new BlockPos(5, 1, 5), motor = new BlockPos(6, 1, 5);
        helper.setBlock(gyro, ModBlocks.GYRO_STABILIZER.get().defaultBlockState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X));
        helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.WEST));
        helper.runAfterDelay(10, () -> {
            double impact = BlockStressValues.getImpact(ModBlocks.GYRO_STABILIZER.get());
            var be = (GyroStabilizerBlockEntity) helper.getBlockEntity(gyro);
            double applied = be.calculateStressApplied() * Math.abs(be.getSpeed());
            Walkers.LOG.info("[gyro] impact {} SU/RPM, speed {} RPM, stress {} SU, max torque {} N·m", impact, be.getSpeed(), applied, be.maxTorque());
            if (impact != GyroStabilizerBlockEntity.STRESS_IMPACT) helper.fail("Stress impact %s, expected %s".formatted(impact, GyroStabilizerBlockEntity.STRESS_IMPACT));
            else if (be.getSpeed() == 0) helper.fail("Gyro isn't spinning");
            else if (be.maxTorque() <= 0) helper.fail("Spinning gyro reports no torque");
            else helper.succeed();
        });
    }
}
