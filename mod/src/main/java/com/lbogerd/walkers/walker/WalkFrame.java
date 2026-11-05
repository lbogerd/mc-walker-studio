package com.lbogerd.walkers.walker;

import net.minecraft.core.Direction;
import org.joml.Vector3d;

/**
 * The coordinate frames a walking hull needs.
 * <ul>
 *   <li><b>Spec-local</b> (as in walkers/*.json and {@code sim}): origin at the centre of the hull's bottom face,
 *       +x forward, +y up, +z to the right.</li>
 *   <li><b>Plot</b>: the hull sub-level's own block coordinates. Spec-local maps to plot through {@link #originPlot}
 *       and the Walker Controller's facing (= forward).</li>
 *   <li><b>Course</b>: the walking loop's frame ({@code sim.GaitController}): starts at the hull origin, facing +x,
 *       y = 0 at the ground under the start. Fixed in the world when walking starts: world = {@link #courseOrigin} +
 *       Ry({@link #courseYaw})·course, so +yaw turns left as in the studio.</li>
 * </ul>
 */
public final class WalkFrame {
    /** Plot-space position of the spec origin (hull bottom-face centre). */
    public final Vector3d originPlot;
    public final Direction forward;
    /** World position of course (0, 0, 0) and the world yaw of course +x. Set when walking starts. */
    public final Vector3d courseOrigin = new Vector3d();
    public double courseYaw;

    public WalkFrame(Vector3d originPlot, Direction forward) {
        if (forward.getAxis().isVertical()) throw new IllegalArgumentException("forward must be horizontal");
        this.originPlot = new Vector3d(originPlot);
        this.forward = forward;
    }

    /** Spec-local direction → plot direction. */
    public Vector3d localDirToPlot(double x, double y, double z) {
        Direction right = forward.getClockWise();
        return new Vector3d(
                x * forward.getStepX() + z * right.getStepX(),
                y,
                x * forward.getStepZ() + z * right.getStepZ());
    }

    /** Spec-local point → plot point. */
    public Vector3d localToPlot(double[] p) {
        return localDirToPlot(p[0], p[1], p[2]).add(originPlot);
    }

    /** Plot point → spec-local point (inverse of {@link #localToPlot}). */
    public double[] plotToLocal(Vector3d plot) {
        Direction right = forward.getClockWise();
        double dx = plot.x - originPlot.x, dy = plot.y - originPlot.y, dz = plot.z - originPlot.z;
        return new double[] {
                dx * forward.getStepX() + dz * forward.getStepZ(),
                dy,
                dx * right.getStepX() + dz * right.getStepZ()};
    }

    /** Course point → world point. */
    public Vector3d courseToWorld(double[] p) {
        return new Vector3d(p[0], p[1], p[2]).rotateY(courseYaw).add(courseOrigin);
    }

    /** World point → course point. */
    public Vector3d worldToCourse(Vector3d w) {
        return new Vector3d(w).sub(courseOrigin).rotateY(-courseYaw);
    }

    /** Yaw (studio convention: heading = (cos, 0, −sin)) of a world direction. */
    public static double yawOf(Vector3d dir) {
        return Math.atan2(-dir.z, dir.x);
    }
}
