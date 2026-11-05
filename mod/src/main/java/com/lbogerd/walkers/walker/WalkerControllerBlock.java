package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.registry.ModBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * The walker's brain. Placed on the hull; drives the hull through {@link WalkerControllerBlockEntity}.
 * {@link #FACING} is the hull's forward direction (+x in walker specs): the direction the player looks
 * when placing it. It is a Create stress consumer (decision D7): a shaft goes into its <b>back</b> face; the
 * shaft's RPM sets the walker's top speed and its direction sets forward or reverse.
 */
public class WalkerControllerBlock extends HorizontalKineticBlock implements IBE<WalkerControllerBlockEntity> {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public WalkerControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Class<WalkerControllerBlockEntity> getBlockEntityClass() {
        return WalkerControllerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends WalkerControllerBlockEntity> getBlockEntityType() {
        return ModBlocks.WALKER_CONTROLLER_BE.get();
    }
}
