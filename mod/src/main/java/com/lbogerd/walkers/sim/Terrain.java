// Mirrors the terrain object returned by makeTerrain in src/sim/terrain.js.
package com.lbogerd.walkers.sim;

/**
 * Block-grid ground the walking loop stands on. Each column (ix, iz) has an integer surface height: a foot
 * standing on column (ix, iz) rests at y = height. {@link Terrain#VOID} means "no ground here".
 *
 * <p>The studio's test courses implement this in {@link Scenarios}; the mod can back it with the real world
 * (e.g. the motion-blocking heightmap below the walker, clamped to VOID where there is nothing to stand on).
 */
public interface Terrain {
    /** "No ground here" (a trench / drop into the void). Columns never report less than this. */
    int VOID = -8;

    /** Surface height of block column (ix, iz). */
    int column(int ix, int iz);

    /** Surface height under the world point (x, z). */
    default int heightAt(double x, double z) {
        return column((int) Math.floor(x), (int) Math.floor(z));
    }
}
