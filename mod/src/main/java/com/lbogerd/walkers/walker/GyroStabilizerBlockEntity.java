package com.lbogerd.walkers.walker;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Gyro Stabilizer physics (decision D6). Every physics tick on a hull it applies a righting torque that turns
 * the hull's up axis back toward world up: a PD controller (2 Hz, critically damped) scaled by the hull's
 * inertia, acting only on pitch and roll (yaw is left to the walker). The torque is capped at
 * {@code TORQUE_PER_RPM × |RPM|} and is zero when the block isn't spinning or the network is overstressed.
 * <p>
 * Stress follows from Create's model: the block's stress impact ({@link #STRESS_IMPACT} SU per RPM) times its
 * RPM. Spinning faster buys more righting torque and costs more stress, which matches the studio's
 * {@code maxTorque × stressPerTorque}. The constants are placeholders until calibrated against the studio.
 */
public class GyroStabilizerBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {
    /** Max righting torque per RPM [N·m]. Placeholder; calibrate against the studio's stabilizer model. */
    public static final double TORQUE_PER_RPM = 100;
    /** Create stress impact [SU per RPM]. */
    public static final double STRESS_IMPACT = 8;
    static final double FREQUENCY = 2.0, DAMPING = 1.0;

    private final ForceTotal forces = new ForceTotal();
    private double lastTorque, lastTiltDeg;

    public GyroStabilizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Torque the gyro can deliver right now [N·m]. */
    public double maxTorque() {
        if (isOverStressed()) return 0;
        return TORQUE_PER_RPM * Math.abs(getSpeed());
    }

    public double lastTorque() { return lastTorque; }
    public double lastTiltDeg() { return lastTiltDeg; }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        double cap = maxTorque();
        MassData mass = subLevel.getMassTracker();
        if (cap <= 0 || mass.getMass() <= 0) { lastTorque = 0; return; }
        Pose3d pose = subLevel.logicalPose();

        // world up in the hull frame; rotate the hull's +Y toward it
        Vector3d up = pose.transformNormalInverse(new Vector3d(0, 1, 0)).normalize();
        Vector3d axis = new Vector3d(0, 1, 0).cross(up);          // |axis| = sin(tilt)
        double tilt = Math.atan2(axis.length(), up.y);
        lastTiltDeg = Math.toDegrees(tilt);
        Vector3d err = axis.lengthSquared() > 1e-12 ? axis.normalize(tilt) : new Vector3d();

        // angular velocity in the hull frame, without the yaw part (about world up)
        Vector3d w = pose.transformNormalInverse(handle.getAngularVelocity(new Vector3d()));
        w.sub(new Vector3d(up).mul(w.dot(up)));

        double wn = 2 * Math.PI * FREQUENCY;
        Vector3d alpha = err.mul(wn * wn).sub(w.mul(2 * DAMPING * wn));
        Vector3d torque = mass.getInertiaTensor().transform(alpha, new Vector3d());
        double t = torque.length();
        if (t > cap) torque.mul(cap / t);
        lastTorque = torque.length();

        forces.applyTorqueImpulse(torque.mul(timeStep));
        handle.applyForcesAndReset(forces);
    }
}
