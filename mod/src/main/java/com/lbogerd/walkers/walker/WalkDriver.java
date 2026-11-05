package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.sim.GaitController;
import com.lbogerd.walkers.sim.Scenarios;
import com.lbogerd.walkers.sim.SimMath;
import com.lbogerd.walkers.sim.SpecNormalizer;
import com.lbogerd.walkers.sim.WalkerSpec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.List;

/**
 * Drives a hull with the walking loop (decisions D1 + D4): the gait planner ({@link GaitController}, the Java port
 * of the studio's {@code src/sim}) decides footholds and the planned hull pose every server tick; every physics
 * tick, each <b>planted</b> leg pushes its hip toward where the plan puts it.
 * <p>
 * Per planted leg: a spring-damper toward the planned hip position (gains from the hull's effective mass at the hip,
 * like the plain suspension) plus a share of the hull's weight. A leg can only push, not pull (vertical force ≥ 0),
 * and its horizontal force is limited to {@link #FRICTION} × its vertical force. Swinging legs don't push. So the
 * hull is only supported where feet really are, and an unstable gait needs the Gyro Stabilizer (D6).
 */
public final class WalkDriver {
    /** Friction-cone limit on a planted foot's horizontal force, as a fraction of its vertical force. */
    public static final double FRICTION = 1.0;

    public final WalkerSpec spec;
    public final String specJson;
    public final WalkFrame frame;
    private final Suspension suspension;
    private GaitController gait;
    private WorldTerrain terrain;
    private Scenarios.Command command = Scenarios.Command.STOP;
    private GaitController.TickState last, previous;
    private final ForceTotal forces = new ForceTotal();
    private int lastPlanted;

    public WalkDriver(String specJson, WalkFrame frame, Suspension suspension) {
        this.specJson = specJson;
        this.spec = SpecNormalizer.normalizeSpec(specJson, "walker");
        if (!spec.errors.isEmpty()) throw new IllegalArgumentException("Invalid walker spec: " + String.join("; ", spec.errors));
        this.frame = frame;
        this.suspension = suspension;
    }

    public void setCommand(Scenarios.Command command) { this.command = command; }
    public Scenarios.Command command() { return command; }
    public GaitController.TickState last() { return last; }
    public boolean started() { return gait != null; }
    public int lastPlanted() { return lastPlanted; }

    /** Planned hull origin in world space, or null before the first tick. */
    public Vector3d plannedOriginWorld() {
        return last == null ? null : frame.courseToWorld(last.planned().pos());
    }

    /** Planned world yaw of the hull's forward axis. */
    public double plannedYawWorld() {
        return last == null ? Double.NaN : frame.courseYaw + last.planned().yaw();
    }

    /** One server tick: on the first one, fix the course frame to where the hull stands; then advance the gait. */
    public void tick(ServerSubLevel subLevel, Level level) {
        if (gait == null) start(subLevel, level);
        terrain.clear();
        previous = last;
        last = gait.tick(command);
    }

    private void start(ServerSubLevel subLevel, Level level) {
        Pose3d pose = subLevel.logicalPose();
        Vector3d originWorld = pose.transformPosition(new Vector3d(frame.originPlot));
        Vector3d fwd = pose.transformNormal(frame.localDirToPlot(1, 0, 0));
        frame.courseYaw = WalkFrame.yawOf(fwd);
        // course y = 0 at the ground under the hull origin
        frame.courseOrigin.set(originWorld.x, Math.floor(originWorld.y), originWorld.z);
        terrain = new WorldTerrain(level, frame);
        int ground = terrain.column(0, 0);
        terrain.clear();
        frame.courseOrigin.y = Math.floor(originWorld.y) + (ground == WorldTerrain.VOID ? -spec.body.rideHeight : ground);
        terrain = new WorldTerrain(level, frame);
        gait = new GaitController(spec, terrain, Scenarios.Command.STOP, 0);
    }

    /** One physics step: planted legs push their hips toward the plan. */
    public void physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep, Level level) {
        if (last == null) return;
        MassData mass = subLevel.getMassTracker();
        if (mass.getMass() <= 0) return;
        Pose3d pose = subLevel.logicalPose();
        double gravity = DimensionPhysicsData.getGravity(level).length();
        double omega = suspension.omega();

        List<GaitController.LegOut> legs = last.legs();
        int planted = 0;
        for (GaitController.LegOut o : legs) if (isPlanted(o)) planted++;
        lastPlanted = planted;
        if (planted == 0) return;
        double weightShare = mass.getMass() * gravity / planted;

        SimMath.Pose plan = last.planned(), prevPlan = previous == null ? plan : previous.planned();
        for (int i = 0; i < legs.size(); i++) {
            GaitController.LegOut o = legs.get(i);
            if (!isPlanted(o)) continue;
            double[] mount = gait.legs.get(i).mount;
            Vector3d hipPlot = frame.localToPlot(mount);
            Vector3d hipWorld = pose.transformPosition(new Vector3d(hipPlot));
            Vector3d target = frame.courseToWorld(SimMath.bodyToWorld(plan, mount));
            Vector3d targetPrev = frame.courseToWorld(SimMath.bodyToWorld(prevPlan, mount));
            Vector3d targetVel = new Vector3d(target).sub(targetPrev).mul(20); // planned hip velocity [b/s]
            // NB: the 2-arg getVelocity overload writes into its argument; always pass a separate dest.
            Vector3d vWorld = Sable.HELPER.getVelocity(level, new Vector3d(hipPlot), new Vector3d());

            double normalMass = 1.0 / mass.getInverseNormalMass(hipPlot, OrientedBoundingBox3d.UP);
            double k = normalMass * omega * omega, c = 2 * suspension.damping() * normalMass * omega;
            Vector3d f = new Vector3d(target).sub(hipWorld).mul(k)
                    .add(new Vector3d(targetVel).sub(vWorld).mul(c))
                    .add(0, weightShare, 0);
            f.y = Math.max(0, f.y);                               // legs push, they don't pull
            double fh = Math.hypot(f.x, f.z), cap = FRICTION * f.y; // friction cone
            if (fh > cap) { double s = cap / fh; f.x *= s; f.z *= s; }
            forces.applyImpulseAtPoint(mass, hipPlot, pose.transformNormalInverse(f).mul(timeStep));
        }
        handle.applyForcesAndReset(forces);
    }

    public static boolean isPlanted(GaitController.LegOut o) {
        return o.st().stance() && o.st().fh() != null && !o.st().fh().isVoid;
    }
}
