package com.lbogerd.walkers.assembly;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * Result of {@link LegScanner#scan}.
 *
 * @param frame      hull axes (null only when there is no controller)
 * @param hullBlocks blocks that become the hull sub-level, in world positions, Leg Actuators excluded
 * @param actuators  Leg Actuator positions; they also go on the hull sub-level, like Mechanical Bearings
 * @param legs       measured legs, sorted left side first, front to back (only legs that measured cleanly)
 * @param errors     validation errors; when non-empty the walker should not assemble
 * @param warnings   things that measured but may surprise (inward knee, tilted coxa, ...)
 */
public record LegScan(
        HullFrame frame,
        Set<BlockPos> hullBlocks,
        Set<BlockPos> actuators,
        List<MeasuredLeg> legs,
        List<LegScanError> errors,
        List<String> warnings) {

    public boolean ok() {
        return errors.isEmpty();
    }

    /** Blocks for the hull sub-level: hull blocks plus actuators. */
    public Set<BlockPos> subLevelBlocks() {
        var all = new java.util.HashSet<>(hullBlocks);
        all.addAll(actuators);
        return all;
    }

    public MeasuredLeg leg(String id) {
        return legs.stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
    }

    /** Hull bottom height above the foot points as built (mean over legs), i.e. the studio's rideHeight. */
    public double builtRideHeight() {
        return legs.isEmpty() ? 0 : -legs.stream().mapToDouble(l -> l.foot().y).average().orElse(0);
    }
}
