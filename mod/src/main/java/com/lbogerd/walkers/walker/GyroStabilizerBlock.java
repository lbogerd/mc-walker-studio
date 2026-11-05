package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.registry.ModBlocks;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gyro Stabilizer (decision D6): a Create stress consumer with a shaft through it. While it spins it
 * pushes the hull back toward upright, with a torque capped by its RPM. See {@link GyroStabilizerBlockEntity}.
 */
public class GyroStabilizerBlock extends RotatedPillarKineticBlock implements IBE<GyroStabilizerBlockEntity> {
    public GyroStabilizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Class<GyroStabilizerBlockEntity> getBlockEntityClass() {
        return GyroStabilizerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GyroStabilizerBlockEntity> getBlockEntityType() {
        return ModBlocks.GYRO_STABILIZER_BE.get();
    }
}
