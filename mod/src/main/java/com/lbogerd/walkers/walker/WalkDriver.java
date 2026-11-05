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
import net.minecraft.core.BlockPos;
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
 * and its horizontal force is limited to {@link #FRICTION} × its vertical force. Swinging legs don't push. A stance
 * leg also only counts if its foothold is within the leg's reach of the <i>actual</i> hip and its ground block still
 * exists. So the hull is only supported where feet really are, and an unstable gait needs the Gyro Stabilizer (D6).
 * <p>
 * <b>Time scale (D7).</b> The shaft drives the legs, so the gait clock runs at |RPM| ÷ the spec's design RPM: each
 * server tick advances the walking loop that many (fractional, accumulated) design ticks. Twice the RPM halves the
 * gait cycle and doubles the top speed; no rotation freezes the legs where they are. Physics targets interpolate
 * between the last two gait ticks.
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
    private double timeScale = 1, clock = 0; // design ticks per server tick; accumulated fraction
    private int gaitTicks = 0;
    private final double[] legLength;
    /** Most design ticks run per server tick (8× the design RPM). */
    public static final double MAX_TIME_SCALE = 8;
    static final int TRIM_EVERY = 200, KEEP_TICKS = 400;

    public WalkDriver(String specJson, WalkFrame frame, Suspension suspension) {
        this.specJson = specJson;
        this.spec = SpecNormalizer.normalizeSpec(specJson, "walker");
        if (!spec.errors.isEmpty()) throw new IllegalArgumentException("Invalid walker spec: " + String.join("; ", spec.errors));
        this.frame = frame;
        this.suspension = suspension;
        legLength = spec.legs.stream().mapToDouble(l -> java.util.Arrays.stream(l.segments).sum()).toArray();
    }

    public void setCommand(Scenarios.Command command) { this.command = command; }
    /** Gait time scale = |shaft RPM| ÷ design RPM (D7); 1 runs the walking loop at its design speed. */
    public void setTimeScale(double scale) { this.timeScale = Math.max(0, Math.min(MAX_TIME_SCALE, scale)); }
    public double timeScale() { return timeScale; }
    /** Walking-loop ticks run so far (design ticks). */
    public int gaitTicks() { return gaitTicks; }
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

    /**
     * One server tick: on the first one, fix the course frame to where the hull stands; then advance the gait by the
     * time scale (possibly several, or zero, design ticks).
     */
    public void tick(ServerSubLevel subLevel, Level level) {
        if (gait == null) start(subLevel, level);
        terrain.setReference(subLevel.logicalPose().transformPosition(new Vector3d(frame.originPlot)).y);
        terrain.clear();
        clock += timeScale;
        while (clock >= 1) {
            clock -= 1;
            previous = last;
            last = gait.tick(command);
            if (++gaitTicks % TRIM_EVERY == 0) gait.trimHistory(KEEP_TICKS);
        }
    }

    private void start(ServerSubLevel subLevel, Level level) {
        Pose3d pose = subLevel.logicalPose();
        Vector3d originWorld = pose.transformPosition(new Vector3d(frame.originPlot));
        Vector3d fwd = pose.transformNormal(frame.localDirToPlot(1, 0, 0));
        frame.courseYaw = WalkFrame.yawOf(fwd);
        frame.courseOrigin.set(originWorld.x, Math.floor(originWorld.y), originWorld.z);
        terrain = new WorldTerrain(level, frame, originWorld.y);
        // course y = 0 at the ground under the hull origin (scanning down from the hull's bottom)
        int ground = terrain.surfaceY((int) Math.floor(originWorld.x), (int) Math.floor(originWorld.z));
        frame.courseOrigin.y = ground == Integer.MIN_VALUE ? Math.floor(originWorld.y - spec.body.rideHeight) : ground;
        terrain.clear();
        gait = new GaitController(spec, terrain, Scenarios.Command.STOP, 0);
        last = previous = null;
        // the controller's warm-up ends standing at tick 0; take that as the first target
        last = gait.tick(command);
        gaitTicks = 1;
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
        boolean[] support = new boolean[legs.size()];
        int planted = 0;
        for (int i = 0; i < legs.size(); i++) {
            GaitController.LegOut o = legs.get(i);
            if (!isPlanted(o)) continue;
            Vector3d foot = frame.courseToWorld(o.st().fh().pos());
            Vector3d hip = pose.transformPosition(frame.localToPlot(gait.legs.get(i).mount));
            BlockPos under = BlockPos.containing(foot.x, foot.y - 0.01, foot.z);
            boolean reachable = hip.distance(foot) <= legLength[i] + 0.05;
            boolean ground = !level.getBlockState(under).getCollisionShape(level, under).isEmpty();
            if (reachable && ground) { support[i] = true; planted++; }
        }
        lastPlanted = planted;
        if (planted == 0) return;
        double weightShare = mass.getMass() * gravity / planted;

        // targets between the last two gait ticks (the clock is `clock` of the way to the next one)
        SimMath.Pose plan = last.planned(), prevPlan = previous == null ? plan : previous.planned();
        double a = clock;
        for (int i = 0; i < legs.size(); i++) {
            if (!support[i]) continue;
            double[] mount = gait.legs.get(i).mount;
            Vector3d hipPlot = frame.localToPlot(mount);
            Vector3d hipWorld = pose.transformPosition(new Vector3d(hipPlot));
            Vector3d tNow = frame.courseToWorld(SimMath.bodyToWorld(plan, mount));
            Vector3d tPrev = frame.courseToWorld(SimMath.bodyToWorld(prevPlan, mount));
            Vector3d target = new Vector3d(tPrev).lerp(tNow, a);
            Vector3d targetVel = new Vector3d(tNow).sub(tPrev).mul(20 * timeScale); // planned hip velocity [b/s]
            // NB: the 2-arg getVelocity overload writes into its argument; always pass a separate dest.
            Vector3d vWorld = Sable.HELPER.getVelocity(level, new Vector3d(hipPlot), new Vector3d());

            double normalMass = 1.0 / mass.getInverseNormalMass(hipPlot, OrientedBoundingBox3d.UP);
            double k = normalMass * omega * omega, c = 2 * suspension.damping() * normalMass * omega;
            Vector3d f = new Vector3d(target).sub(hipWorld).mul(k)
                    .add(new Vector3d(targetVel).sub(vWorld).mul(c))
                    .add(0, weightShare, 0);
            f.y = Math.max(0, f.y);                               // legs push, they don't pull
            double fh = Math.hypot(f.x, f.z), cap = FRICTION * f.y; // friction cone
            if (fh > cap) { double sc = cap / fh; f.x *= sc; f.z *= sc; }
            forces.applyImpulseAtPoint(mass, hipPlot, pose.transformNormalInverse(f).mul(timeStep));
        }
        handle.applyForcesAndReset(forces);
    }

    /** Planned stance on non-void ground (the plan's view; {@link #physicsTick} also checks reach and the real block). */
    public static boolean isPlanted(GaitController.LegOut o) {
        return o.st().stance() && o.st().fh() != null && !o.st().fh().isVoid;
    }
}
