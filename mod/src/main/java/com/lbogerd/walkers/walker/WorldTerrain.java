package com.lbogerd.walkers.walker;

import com.lbogerd.walkers.sim.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * The real world as seen by the walking loop: the surface height of course column (ix, iz), in course y.
 * Looks up the world block column under the course cell's centre and scans down from a few blocks above
 * the start height for the first block with a collision shape. Sub-levels (including the walker itself) live
 * in plot space, so they are not seen; walking on other ships is a later step.
 * <p>
 * Exact on a block grid when the course origin is block-aligned; otherwise a course cell maps to the world
 * column under its centre. Results are cached until {@link #clear()} (call it once per tick).
 */
public final class WorldTerrain implements Terrain {
    static final int SCAN_UP = 8, SCAN_DOWN = 12;
    private final Level level;
    private final WalkFrame frame;
    private final Map<Long, Integer> cache = new HashMap<>();

    public WorldTerrain(Level level, WalkFrame frame) {
        this.level = level;
        this.frame = frame;
    }

    public void clear() { cache.clear(); }

    @Override
    public int column(int ix, int iz) {
        long key = ((long) ix << 32) ^ (iz & 0xffffffffL);
        Integer h = cache.get(key);
        if (h != null) return h;
        Vector3d w = frame.courseToWorld(new double[] {ix + 0.5, 0, iz + 0.5});
        int x = (int) Math.floor(w.x), z = (int) Math.floor(w.z);
        int y0 = (int) Math.round(frame.courseOrigin.y);
        int found = VOID;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = y0 + SCAN_UP; y >= y0 - SCAN_DOWN; y--) {
            p.set(x, y, z);
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty()) { found = Math.max(VOID, y + 1 - y0); break; }
        }
        cache.put(key, found);
        return found;
    }
}
