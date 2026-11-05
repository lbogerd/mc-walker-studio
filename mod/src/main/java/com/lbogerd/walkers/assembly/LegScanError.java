package com.lbogerd.walkers.assembly;

import net.minecraft.core.BlockPos;

/** Why a walker's legs can't be measured. {@code pos} is the world position to point the player at. */
public record LegScanError(Kind kind, BlockPos pos, String message) {
    public enum Kind {
        /** The given controller position doesn't hold a Walker Controller. */
        NO_CONTROLLER,
        /** A Leg Actuator with no strut, joint or foot pad in front of it. */
        NO_CHAIN,
        /** A chain that ends without a Foot Pad. */
        NO_FOOT,
        /** A Foot Pad in the middle of a chain. */
        FOOT_NOT_AT_END,
        /** A chain that forks or loops back on itself. */
        BRANCH,
        /** No Leg Joint: a leg needs 2 segments (one knee) or 3 (coxa + knee). */
        TOO_FEW_SEGMENTS,
        /** More than 2 Leg Joints (more than 3 segments). */
        TOO_MANY_SEGMENTS,
        /** Two legs' blocks touch, so they would move as one. */
        LEGS_TOUCH,
        /** A leg block touches a hull block (other than its own actuator's front face). */
        TOUCHES_HULL,
        /** A leg block touches a solid block that isn't part of the walker (the Foot Pad's bottom face may). */
        TOUCHES_WORLD,
        /** Struts, joints or foot pads not attached to any actuator. */
        LOOSE_PARTS,
    }

    @Override
    public String toString() {
        return kind + " at " + pos.toShortString() + ": " + message;
    }
}
