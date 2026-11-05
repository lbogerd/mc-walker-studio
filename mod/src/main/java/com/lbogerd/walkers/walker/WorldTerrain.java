package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.sim.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * The real world as seen by the walking loop: surface heights in course y (relative to the course origin).
 * <ul>
 *   <li><b>Exact points.</b> {@link #heightAt} maps the course point itself to the world column under it, so a foot is
 *       always measured on the block it really stands on, whatever the course frame's offset or yaw. {@link #column}
 *       (used by the foothold search for edge checks) samples the course cell's centre, which is exact on a
 *       block-aligned, cardinal course frame and approximate otherwise.</li>
 *   <li><b>Below the hull.</b> Ground is the first block with a collision shape found scanning <i>down</i> from the
 *       {@link #setReference reference height} (the hull's bottom), so roofs, bridges and ceilings above the walker
 *       are not mistaken for ground.</li>
 *   <li>Sub-levels (including the walker itself) live in plot space and aren't seen. Walking on other ships is a
 *       later step.</li>
 * </ul>
 * Results are cached per world column until {@link #clear()} (call it once per tick, after updating the reference).
 */
public final class WorldTerrain implements Terrain {
    /** How far below the reference height to look for ground. */
    static final int SCAN_DOWN = 16;
    private final Level level;
    private final WalkFrame frame;
    private final Map<Long, Integer> cache = new HashMap<>();
    private double referenceY;

    public WorldTerrain(Level level, WalkFrame frame, double referenceY) {
        this.level = level;
        this.frame = frame;
        this.referenceY = referenceY;
    }

    /** World height to scan down from (the hull's bottom). Clears the cache if it moved to another block row. */
    public void setReference(double worldY) {
        if (Math.floor(worldY) != Math.floor(referenceY)) cache.clear();
        referenceY = worldY;
    }

    public void clear() { cache.clear(); }

    /** World y of the ground surface in world column (x, z), or {@link Integer#MIN_VALUE} if none within reach. */
    public int surfaceY(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Integer h = cache.get(key);
        if (h != null) return h;
        int found = Integer.MIN_VALUE;
        int top = (int) Math.floor(referenceY + 0.25); // allow ground a hair above a hull resting on it
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y >= top - SCAN_DOWN; y--) {
            p.set(x, y, z);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) { found = y + 1; break; }
        }
        cache.put(key, found);
        return found;
    }

    private int courseHeight(Vector3d world) {
        int s = surfaceY((int) Math.floor(world.x), (int) Math.floor(world.z));
        if (s == Integer.MIN_VALUE) return VOID;
        return Math.max(VOID, s - (int) Math.round(frame.courseOrigin.y));
    }

    @Override
    public int heightAt(double x, double z) {
        return courseHeight(frame.courseToWorld(new double[] {x, 0, z}));
    }

    @Override
    public int column(int ix, int iz) {
        return courseHeight(frame.courseToWorld(new double[] {ix + 0.5, 0, iz + 0.5}));
    }
}
