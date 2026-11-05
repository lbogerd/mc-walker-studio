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
 * <b>Time scale (D7).</b> The shaft drives the legs: with scale s = |RPM| ÷ the spec's design RPM, the gait cycle is
 * cycleTicks/s and the top speed and turn rate × s ({@link GaitController#setTimeScale}). The walking loop still runs
 * one tick per server tick, so the hull's acceleration stays gait.accel at any RPM (D4); a higher RPM only raises the
 * speed limit. A lower RPM lowers it at once (the legs can't outrun the shaft), and no rotation locks the legs.
 * <p>
 * <b>Elevation.</b> The walking loop works in course y relative to an origin; whenever the plan drifts more than
 * {@link #REBASE_AT} blocks from ride height, the origin moves with it ({@link GaitController#rebaseY}) before the
 * next tick, so long climbs and descents never reach {@link com.lbogerd.walkers.sim.Terrain#VOID} and every tick's
 * result is in a single frame.
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
    private double timeScale = 1;
    /** Planned hull poses of the last two gait ticks (kept separately so a rebase can shift them). */
    private SimMath.Pose lastPlan, prevPlan;
    static final int REBASE_AT = 4;
    private int rebases;
    /** How often the course origin was moved vertically (tests). */
    public int rebases() { return rebases; }
    private int gaitTicks = 0;
    /** Highest time scale (8× the design RPM). */
    public static final double MAX_TIME_SCALE = 8;
    static final int TRIM_EVERY = 200, KEEP_TICKS = 400;
    /** A stance foot counts as touching if the leg's IK, solved from the actual hull, gets within this of it [b]. */
    static final double REACH_TOLERANCE = 0.1;

    public WalkDriver(String specJson, WalkFrame frame, Suspension suspension) {
        this.specJson = specJson;
        this.spec = SpecNormalizer.normalizeSpec(specJson, "walker");
        if (!spec.errors.isEmpty()) throw new IllegalArgumentException("Invalid walker spec: " + String.join("; ", spec.errors));
        this.frame = frame;
        this.suspension = suspension;
    }

    public void setCommand(Scenarios.Command command) { this.command = command; }
    /** Gait time scale = |shaft RPM| ÷ design RPM (D7); 1 runs the walking loop at its design speed. */
    public void setTimeScale(double scale) { this.timeScale = Math.max(0, Math.min(MAX_TIME_SCALE, scale)); }
    public double timeScale() { return timeScale; }
    /** Walking-loop ticks run so far (one per server tick). */
    public int gaitTicks() { return gaitTicks; }
    /** The gait clock (cycles since the start). */
    public double gaitPhase() { return last == null ? 0 : last.path().phase(); }
    public Scenarios.Command command() { return command; }
    public GaitController.TickState last() { return last; }
    public boolean started() { return gait != null; }
    public int lastPlanted() { return lastPlanted; }

    /** Planned hull origin in world space, or null before the first tick. */
    public Vector3d plannedOriginWorld() {
        return lastPlan == null ? null : frame.courseToWorld(lastPlan.pos());
    }

    /** Planned world yaw of the hull's forward axis. */
    public double plannedYawWorld() {
        return lastPlan == null ? Double.NaN : frame.courseYaw + lastPlan.yaw();
    }

    /** The spec's top speed at design RPM [b/s]. */
    public double gaitTopSpeed() { return spec.gait.stride / spec.gait.cycleTicks * 20; }

    /** Planned signed ground speed [b/s] (the planner runs in real ticks). */
    public double plannedSpeed() {
        return last == null ? 0 : last.path().v() * 20;
    }

    /**
     * One server tick: on the first one, fix the course frame to where the hull stands; then advance the gait by the
     * time scale (possibly several, or zero, design ticks).
     */
    public void tick(ServerSubLevel subLevel, Level level) {
        if (gait == null) start(subLevel, level);
        terrain.setReference(subLevel.logicalPose().transformPosition(new Vector3d(frame.originPlot)).y);
        terrain.clear();
        rebaseIfNeeded(); // between ticks, so the tick below is entirely in the new frame
        gait.setTimeScale(timeScale);
        previous = last;
        last = gait.tick(command);
        prevPlan = lastPlan == null ? last.planned() : lastPlan;
        lastPlan = last.planned();
        if (++gaitTicks % TRIM_EVERY == 0) gait.trimHistory(KEEP_TICKS);
    }

    /** Keep course heights near 0: move the course origin with the plan when it climbs or descends far. */
    private void rebaseIfNeeded() {
        if (lastPlan == null) return;
        double off = lastPlan.pos()[1] - spec.body.rideHeight;
        if (Math.abs(off) <= REBASE_AT) return;
        int dy = (int) Math.round(off);
        rebases++;
        frame.courseOrigin.y += dy;
        gait.rebaseY(dy);
        terrain.clear();
        lastPlan = shiftY(lastPlan, -dy);
        prevPlan = shiftY(prevPlan, -dy);
    }

    private static SimMath.Pose shiftY(SimMath.Pose p, double dy) {
        return new SimMath.Pose(new double[] {p.pos()[0], p.pos()[1] + dy, p.pos()[2]}, p.yaw(), p.pitch(), p.roll());
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
        lastPlan = prevPlan = last.planned();
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
            BlockPos under = BlockPos.containing(foot.x, foot.y - 0.01, foot.z);
            boolean ground = !level.getBlockState(under).getCollisionShape(level, under).isEmpty();
            // reach: solve the leg's IK from the actual hull to the foothold, as the leg itself would
            double[] footLocal = frame.plotToLocal(pose.transformPositionInverse(new Vector3d(foot)));
            GaitController.LegSolution sol = GaitController.solveLeg(gait.legs.get(i), footLocal);
            double miss = SimMath.dist(sol.foot(), footLocal);
            if (ground && miss <= REACH_TOLERANCE) { support[i] = true; planted++; }
        }
        lastPlanted = planted;
        if (planted == 0) return;
        double weightShare = mass.getMass() * gravity / planted;

        SimMath.Pose plan = lastPlan, prevPlan = this.prevPlan;
        for (int i = 0; i < legs.size(); i++) {
            if (!support[i]) continue;
            double[] mount = gait.legs.get(i).mount;
            Vector3d hipPlot = frame.localToPlot(mount);
            Vector3d hipWorld = pose.transformPosition(new Vector3d(hipPlot));
            Vector3d tNow = frame.courseToWorld(SimMath.bodyToWorld(plan, mount));
            Vector3d tPrev = frame.courseToWorld(SimMath.bodyToWorld(prevPlan, mount));
            Vector3d target = tNow;
            Vector3d targetVel = new Vector3d(tNow).sub(tPrev).mul(20); // planned hip velocity [b/s]
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
