package com.lbogerd.walkers.walker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The walker's brain. Placed on the hull; drives the hull through {@link WalkerControllerBlockEntity}. */
public class WalkerControllerBlock extends Block implements EntityBlock {
    public WalkerControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WalkerControllerBlockEntity(pos, state);
    }
}
