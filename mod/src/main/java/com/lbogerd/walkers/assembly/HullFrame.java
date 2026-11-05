package com.lbogerd.walkers.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The hull's axes, as in walker specs: origin at the centre of the hull's bottom face, {@code +x} = forward
 * (the Walker Controller's facing), {@code +y} = up, {@code +z} = right (so {@code -z} is left).
 *
 * @param controller world position of the Walker Controller
 * @param forward    the controller's horizontal facing
 * @param origin     world position of the bottom-face centre: the horizontal centre of the hull blocks'
 *                   bounding box, at the bottom of its lowest layer
 * @param size       bounding box of the hull blocks in hull axes: [along forward, height, sideways]
 */
public record HullFrame(BlockPos controller, Direction forward, Vec3 origin, int[] size) {
    public Direction right() {
        return forward.getClockWise();
    }

    /** A world position in hull coordinates. */
    public Vec3 toHull(Vec3 world) {
        return toHullDir(world.subtract(origin));
    }

    /** A world direction or offset in hull axes. */
    public Vec3 toHullDir(Vec3 d) {
        Direction r = right();
        return new Vec3(
                d.x * forward.getStepX() + d.z * forward.getStepZ(),
                d.y,
                d.x * r.getStepX() + d.z * r.getStepZ());
    }

    /** Centre of a block in hull coordinates. */
    public Vec3 centre(BlockPos pos) {
        return toHull(Vec3.atCenterOf(pos));
    }

    static HullFrame of(BlockPos controller, Direction forward, Iterable<BlockPos> hull) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : hull) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        Vec3 origin = new Vec3((minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0);
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        int[] size = forward.getAxis() == Direction.Axis.X ? new int[] {sx, sy, sz} : new int[] {sz, sy, sx};
        return new HullFrame(controller, forward, origin, size);
    }
}
