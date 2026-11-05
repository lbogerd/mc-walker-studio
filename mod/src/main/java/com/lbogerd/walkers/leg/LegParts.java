package com.lbogerd.walkers.leg;

import com.lbogerd.walkers.registry.ModBlocks;
import net.minecraft.world.level.block.state.BlockState;

/** What role a block plays in a block-built leg. */
public enum LegParts {
    ACTUATOR, STRUT, JOINT, FOOT, NONE;

    public static LegParts of(BlockState state) {
        if (state.is(ModBlocks.LEG_ACTUATOR.get())) return ACTUATOR;
        if (state.is(ModBlocks.LEG_STRUT.get())) return STRUT;
        if (state.is(ModBlocks.LEG_JOINT.get())) return JOINT;
        if (state.is(ModBlocks.FOOT_PAD.get())) return FOOT;
        return NONE;
    }

    /** Struts, joints and foot pads: the blocks that move with a leg (actuators stay on the hull). */
    public boolean isLegBody() {
        return this == STRUT || this == JOINT || this == FOOT;
    }
}
