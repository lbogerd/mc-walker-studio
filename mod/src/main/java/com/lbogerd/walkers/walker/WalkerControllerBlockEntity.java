package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.registry.ModBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the hull up with spring-damper "legs" (decision D1, spike for follow-up 5).
 * <p>
 * Each hip is an offset from this block's centre in the hull's local frame. Every physics tick, each hip
 * casts down along the hull's -Y. If ground is within reach, an upward spring force pulls the hip toward
 * {@code rideHeight} above it (plus a share of the hull's weight as feedforward), and horizontal velocity at
 * the hip is damped like a planted foot. Forces are applied as impulses at the hips, so uneven support
 * tilts the hull just like real legs would.
 */
public class WalkerControllerBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    private final List<Vector3d> hipOffsets = new ArrayList<>();
    private Suspension suspension = Suspension.DEFAULT;
    private final ForceTotal forces = new ForceTotal();
    /** World-space x/z where each hip's foot planted (null = not planted). Later the gait planner sets these. */
    private final List<Vector3d> anchors = new ArrayList<>();

    // last physics tick, for tests and debugging
    private int lastContacts;
    public static int debugTicks = 0; // >0: log that many physics ticks (tests/debugging)
    private double lastMaxCompression;

    /** Walk mode (D1 + D4): the walking loop drives the legs. Null = plain suspension with fixed hips. */
    private WalkDriver walk;

    public WalkerControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.WALKER_CONTROLLER_BE.get(), pos, state);
    }

    public void configure(Suspension suspension, List<Vector3d> hipOffsets) {
        this.suspension = suspension;
        this.hipOffsets.clear();
        hipOffsets.forEach(h -> this.hipOffsets.add(new Vector3d(h)));
        this.anchors.clear();
        setChanged();
    }

    /**
     * Switch to walk mode: the walking loop ({@code sim}, ported from the studio) plans footholds and the hull pose
     * from {@code specJson} (a walkers/*.json spec); planted legs push the hull toward that plan.
     *
     * @param originPlot plot position of the spec origin (hull bottom-face centre)
     * @param forward    the hull's forward (spec +x), normally this controller's facing
     */
    public void configureWalker(String specJson, Vector3d originPlot, net.minecraft.core.Direction forward, Suspension suspension) {
        this.walk = new WalkDriver(specJson, new WalkFrame(originPlot, forward), suspension);
        this.suspension = suspension;
        this.hipOffsets.clear();
        this.anchors.clear();
        setChanged();
    }

    public WalkDriver walk() { return walk; }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        if (walk != null && level != null) walk.tick(subLevel, level);
    }

    public Suspension suspension() { return suspension; }
    public List<Vector3d> hipOffsets() { return List.copyOf(hipOffsets); }
    public int lastContacts() { return lastContacts; }
    public double lastMaxCompression() { return lastMaxCompression; }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (walk != null) {
            if (level != null) walk.physicsTick(subLevel, handle, timeStep, level);
            return;
        }
        if (hipOffsets.isEmpty() || level == null) return;
        MassData mass = subLevel.getMassTracker();
        if (mass.getMass() <= 0) return;

        Pose3d pose = subLevel.logicalPose();
        Vec3 centre = getBlockPos().getCenter();
        Vector3d localUp = pose.transformNormalInverse(new Vector3d(0, 1, 0)); // world up in hull frame
        double gravity = DimensionPhysicsData.getGravity(level).length();
        double omega = suspension.omega();

        // First pass: find which hips touch ground, so the weight is shared among them.
        int n = hipOffsets.size();
        while (anchors.size() < n) anchors.add(null);
        double[] dist = new double[n];
        int contacts = 0;
        for (int i = 0; i < n; i++) {
            Vector3d o = hipOffsets.get(i);
            Vec3 hip = centre.add(o.x, o.y, o.z);
            dist[i] = castDown(subLevel, hip);
            if (dist[i] <= suspension.maxReach()) contacts++;
        }
        lastContacts = contacts;
        if (debugTicks > 0) com.lbogerd.walkers.Walkers.LOG.info("[phys] be={} sub={} pose={} dist={}",
                getBlockPos(), subLevel.getUniqueId(), pose.position(), java.util.Arrays.toString(dist));
        lastMaxCompression = 0;
        for (int i = 0; i < n; i++) if (dist[i] > suspension.maxReach()) anchors.set(i, null); // foot lifted
        if (contacts == 0) return;
        double weightShare = mass.getMass() * gravity / contacts;

        for (int i = 0; i < n; i++) {
            if (dist[i] > suspension.maxReach()) continue;
            Vector3d o = hipOffsets.get(i);
            Vector3d hipLocal = new Vector3d(centre.x + o.x, centre.y + o.y, centre.z + o.z);

            double normalMass = 1.0 / mass.getInverseNormalMass(hipLocal, OrientedBoundingBox3d.UP);
            double k = normalMass * omega * omega;
            double c = 2 * suspension.damping() * normalMass * omega;

            // NB: the 2-arg getVelocity overload writes into its argument; always pass a separate dest.
            Vector3d vWorld = Sable.HELPER.getVelocity(level, new Vector3d(hipLocal), new Vector3d()); // m/s at the hip
            Vector3d v = pose.transformNormalInverse(new Vector3d(vWorld));
            double vUp = v.y;

            double compression = suspension.rideHeight() - dist[i];
            lastMaxCompression = Math.max(lastMaxCompression, compression);
            double fUp = Math.max(0, k * compression - c * vUp + weightShare * Math.max(0, localUp.y));

            // Planted foot: a critically damped horizontal spring pulls the hip back over where the foot
            // touched down (world x/z), so the hull can't slide or creep while the foot is planted.
            Vector3d hipWorld = pose.transformPosition(new Vector3d(hipLocal));
            if (anchors.get(i) == null) anchors.set(i, new Vector3d(hipWorld.x, 0, hipWorld.z));
            Vector3d anchor = anchors.get(i);
            double kLat = k * suspension.lateral(), cLat = c * Math.sqrt(suspension.lateral());
            Vector3d fLatWorld = new Vector3d(
                    -kLat * (hipWorld.x - anchor.x) - cLat * vWorld.x, 0,
                    -kLat * (hipWorld.z - anchor.z) - cLat * vWorld.z);
            Vector3d fLat = pose.transformNormalInverse(fLatWorld);
            // The horizontal foot force acts at the hip, where a real leg attaches. (Applying it at
            // centre-of-mass height would hold the CoM over the feet and make any walker statically stable,
            // which defeats the Gyro Stabilizer, decision D6.)
            forces.applyImpulseAtPoint(mass, hipLocal, fLat.mul(timeStep, new Vector3d()));
            Vector3d force = new Vector3d(0, fUp, 0);
            forces.applyImpulseAtPoint(mass, hipLocal, force.mul(timeStep));
        }
        handle.applyForcesAndReset(forces);
        if (debugTicks > 0) debugTicks--;
    }

    /** Distance from the hip down (hull -Y) to the ground, or +inf if nothing within reach. */
    private double castDown(ServerSubLevel subLevel, Vec3 hipLocal) {
        Vec3 to = hipLocal.subtract(0, suspension.maxReach() + 1, 0);
        ClipContext ctx = new ClipContext(hipLocal, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        ((ClipContextExtension) ctx).sable$setIgnoredSubLevel(subLevel);
        BlockHitResult hit = level.clip(ctx);
        if (hit.getType() == HitResult.Type.MISS) return Double.POSITIVE_INFINITY;
        // A hit on another sub-level (a ship, a platform) comes back in *that* sub-level's plot space:
        // project it to world space first, then bring it into this hull's frame (as Offroad's wheels do).
        Vector3d hitWorld = JOMLConversion.toJOML(hit.getLocation());
        var hitSubLevel = Sable.HELPER.getContaining(level, hit.getLocation());
        if (hitSubLevel != null) hitWorld = hitSubLevel.logicalPose().transformPosition(hitWorld);
        Vector3d hitLocal = subLevel.logicalPose().transformPositionInverse(hitWorld);
        double d = hipLocal.y - hitLocal.y;
        return d > 1e-4 ? d : Double.POSITIVE_INFINITY;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag s = new CompoundTag();
        s.putDouble("RideHeight", suspension.rideHeight());
        s.putDouble("Frequency", suspension.frequency());
        s.putDouble("Damping", suspension.damping());
        s.putDouble("Lateral", suspension.lateral());
        s.putDouble("MaxReach", suspension.maxReach());
        tag.put("Suspension", s);
        ListTag hips = new ListTag();
        for (Vector3d h : hipOffsets) {
            CompoundTag t = new CompoundTag();
            t.putDouble("X", h.x);
            t.putDouble("Y", h.y);
            t.putDouble("Z", h.z);
            hips.add(t);
        }
        tag.put("Hips", hips);
        if (walk != null) {
            // The gait itself isn't saved: after a reload it restarts, standing, from wherever the hull is.
            CompoundTag w = new CompoundTag();
            w.putString("Spec", walk.specJson);
            w.putDouble("OX", walk.frame.originPlot.x);
            w.putDouble("OY", walk.frame.originPlot.y);
            w.putDouble("OZ", walk.frame.originPlot.z);
            w.putString("Forward", walk.frame.forward.getSerializedName());
            tag.put("Walk", w);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Suspension", Tag.TAG_COMPOUND)) {
            CompoundTag s = tag.getCompound("Suspension");
            suspension = new Suspension(s.getDouble("RideHeight"), s.getDouble("Frequency"), s.getDouble("Damping"),
                    s.getDouble("Lateral"), s.getDouble("MaxReach"));
        }
        hipOffsets.clear();
        for (Tag t : tag.getList("Hips", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            hipOffsets.add(new Vector3d(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z")));
        }
        walk = null;
        if (tag.contains("Walk", Tag.TAG_COMPOUND)) {
            CompoundTag w = tag.getCompound("Walk");
            var fwd = net.minecraft.core.Direction.byName(w.getString("Forward"));
            try {
                walk = new WalkDriver(w.getString("Spec"), new WalkFrame(new Vector3d(w.getDouble("OX"), w.getDouble("OY"), w.getDouble("OZ")),
                        fwd == null ? net.minecraft.core.Direction.NORTH : fwd), suspension);
            } catch (IllegalArgumentException e) {
                com.lbogerd.walkers.Walkers.LOG.warn("Walker Controller at {}: dropping invalid walker spec: {}", getBlockPos(), e.getMessage());
            }
        }
    }
}
