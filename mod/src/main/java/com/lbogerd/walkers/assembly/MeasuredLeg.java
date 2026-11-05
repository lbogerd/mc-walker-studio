package com.lbogerd.walkers.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * One block-built leg, measured in hull coordinates ({@link HullFrame}) with the conventions of
 * {@code walkers/*.json} (see {@code docs/walker-spec.md} and {@code mod/README.md}, "Building legs").
 *
 * @param id               e.g. {@code FL}, {@code HR}: F/M/H by rank front to back on its side, then L/R/C
 * @param mount            hip: the Leg Actuator's centre
 * @param foot             the foot point: the Foot Pad's centre
 * @param neutral          {@code [dx, dz]} of the foot point from the mount
 * @param segments         lengths between consecutive pivots (actuator → joints → foot pad, block centres):
 *                         {@code [upper, lower]} or {@code [coxa, upper, lower]}
 * @param knee             studio knee hint: forward | back | up | out | down
 * @param kneeFromShape    true when the hint was read from the bend of the built leg, false when the leg is
 *                         straight at the knee and the Leg Joint's facing was used
 * @param side             -1 left, +1 right, 0 on the centre line
 * @param mirrorOf         for a right leg that mirrors a left leg exactly: that leg's id, else null
 * @param actuator         world position of the Leg Actuator (it stays on the hull)
 * @param chain            world positions of the leg's moving blocks in order, actuator excluded, foot pad last
 * @param pivots           world positions of the pivots: actuator, joints..., foot pad
 * @param segmentBlocks    the chain split per segment: struts between pivot i and i+1, plus the joint or foot
 *                         pad that ends it (so each segment is the rigid body hinged at its start)
 */
public record MeasuredLeg(
        String id,
        Vec3 mount,
        Vec3 foot,
        double[] neutral,
        double[] segments,
        String knee,
        boolean kneeFromShape,
        int side,
        String mirrorOf,
        BlockPos actuator,
        List<BlockPos> chain,
        List<BlockPos> pivots,
        List<List<BlockPos>> segmentBlocks) {

    MeasuredLeg withMirrorOf(String other) {
        return new MeasuredLeg(id, mount, foot, neutral, segments, knee, kneeFromShape, side, other, actuator, chain, pivots, segmentBlocks);
    }

    MeasuredLeg withId(String newId) {
        return new MeasuredLeg(newId, mount, foot, neutral, segments, knee, kneeFromShape, side, mirrorOf, actuator, chain, pivots, segmentBlocks);
    }
}
