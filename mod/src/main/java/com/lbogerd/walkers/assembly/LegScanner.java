package com.lbogerd.walkers.assembly;

import com.lbogerd.walkers.assembly.LegScanError.Kind;
import com.lbogerd.walkers.leg.LegActuatorBlock;
import com.lbogerd.walkers.leg.LegJointBlock;
import com.lbogerd.walkers.leg.LegParts;
import com.lbogerd.walkers.walker.WalkerControllerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns block-built legs (decision D3) into the leg data of {@code walkers/*.json}. Works on any
 * {@link BlockGetter}, so it runs in the world before anything is assembled.
 * <p>
 * Conventions (also in {@code mod/README.md}, "Building legs"):
 * <ul>
 *   <li>Hull axes: see {@link HullFrame}. Forward is the Walker Controller's facing; the origin is the centre
 *       of the bottom face of the hull blocks' bounding box (hull blocks = walker blocks that are not leg parts;
 *       actuators are left out of the box).</li>
 *   <li>A leg starts at a Leg Actuator. The block in front of it (its {@code facing}) is the first block of the
 *       chain. The chain is face-connected Leg Struts and Leg Joints, ending in exactly one Foot Pad.</li>
 *   <li>Pivots are block centres: actuator, each joint in chain order, the foot pad. A segment's length is the
 *       straight distance between its two pivots, whatever the struts between them look like (a segment is one
 *       rigid body, so an L-shaped or staircase run of struts is just a bent link). Straight runs give integer
 *       lengths: actuator, 2 struts, joint = 3.</li>
 *   <li>1 joint = 2 segments [upper, lower]; 2 joints = 3 segments [coxa, upper, lower].</li>
 *   <li>mount = actuator centre; neutral = foot pad centre minus mount, x and z. The foot point is the pad's
 *       centre, so in the world the pad's centre sits half a block above the ground the studio sees.</li>
 *   <li>Knee hint: take the knee joint (the last joint) and its offset from the line base → foot, where base is
 *       the actuator (2 segments) or the first joint (3 segments). If the offset is over a quarter block, the
 *       hint is the studio hint whose pole (as the studio's IK projects it) points closest to that offset.
 *       If the leg is straight there, the Leg Joint's facing is used the same way.</li>
 * </ul>
 */
public final class LegScanner {
    private LegScanner() {}

    /** Knee offsets below this (blocks) count as a straight leg. */
    static final double STRAIGHT = 0.25;
    /** Studio knee hints, in tie-break order: "out" first, because once projected onto a leg that slopes
     *  outward it's often identical to "up" (both poles point up-and-out), and "out" says more. */
    static final String[] KNEE_HINTS = {"out", "forward", "back", "up", "down"};

    /**
     * Measures every leg of a walker.
     *
     * @param level        where the walker is built
     * @param controller   the Walker Controller
     * @param walkerBlocks every block of the walker (hull, actuators, leg parts), e.g. from {@link #collect}
     */
    public static LegScan scan(BlockGetter level, BlockPos controller, Collection<BlockPos> walkerBlocks) {
        List<LegScanError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BlockState controllerState = level.getBlockState(controller);
        if (!(controllerState.getBlock() instanceof WalkerControllerBlock)) {
            errors.add(new LegScanError(Kind.NO_CONTROLLER, controller, "No Walker Controller here"));
            return new LegScan(null, Set.of(), Set.of(), List.of(), errors, warnings);
        }
        Set<BlockPos> all = new HashSet<>();
        for (BlockPos p : walkerBlocks) all.add(p.immutable());
        all.add(controller.immutable());

        Set<BlockPos> legBody = new HashSet<>(), actuators = new HashSet<>(), hull = new HashSet<>();
        for (BlockPos p : all) {
            LegParts part = LegParts.of(level.getBlockState(p));
            if (part.isLegBody()) legBody.add(p);
            else if (part == LegParts.ACTUATOR) actuators.add(p);
            else hull.add(p);
        }
        HullFrame frame = HullFrame.of(controller.immutable(), controllerState.getValue(WalkerControllerBlock.FACING), hull);

        // Connected groups of leg parts; each must hang off exactly one actuator.
        Map<BlockPos, Integer> groupOf = new HashMap<>();
        List<List<BlockPos>> groups = new ArrayList<>();
        for (BlockPos start : sorted(legBody)) {
            if (groupOf.containsKey(start)) continue;
            List<BlockPos> group = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
            groupOf.put(start, groups.size());
            while (!queue.isEmpty()) {
                BlockPos p = queue.poll();
                group.add(p);
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (legBody.contains(n) && !groupOf.containsKey(n)) {
                        groupOf.put(n, groups.size());
                        queue.add(n);
                    }
                }
            }
            groups.add(group);
        }
        Map<Integer, List<BlockPos>> actuatorsOfGroup = new HashMap<>();
        for (BlockPos a : sorted(actuators)) {
            BlockPos front = a.relative(level.getBlockState(a).getValue(LegActuatorBlock.FACING));
            Integer g = groupOf.get(front);
            if (g == null) errors.add(new LegScanError(Kind.NO_CHAIN, a,
                    "Leg Actuator has no Leg Strut, Leg Joint or Foot Pad in front of it"));
            else actuatorsOfGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(a);
        }

        List<MeasuredLeg> legs = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<BlockPos> group = groups.get(g);
            List<BlockPos> owners = actuatorsOfGroup.getOrDefault(g, List.of());
            if (owners.isEmpty()) {
                errors.add(new LegScanError(Kind.LOOSE_PARTS, group.get(0),
                        group.size() + " leg block(s) not attached to the front of any Leg Actuator"));
                continue;
            }
            if (owners.size() > 1) {
                errors.add(new LegScanError(Kind.LEGS_TOUCH, owners.get(1), "The legs of the actuators at "
                        + String.join(", ", owners.stream().map(BlockPos::toShortString).toList())
                        + " touch, so they would move as one"));
                continue;
            }
            BlockPos actuator = owners.get(0);
            if (!checkContacts(level, all, legBody, actuator, group, errors)) continue;
            MeasuredLeg leg = walk(level, frame, legBody, actuator, group, errors, warnings);
            if (leg != null) legs.add(leg);
        }
        return new LegScan(frame, hull, actuators, name(legs), errors, warnings);
    }

    /** A leg may only touch its own actuator (through the chain's first block) and the ground under its foot pad. */
    private static boolean checkContacts(BlockGetter level, Set<BlockPos> all, Set<BlockPos> legBody, BlockPos actuator,
                                         List<BlockPos> group, List<LegScanError> errors) {
        Direction facing = level.getBlockState(actuator).getValue(LegActuatorBlock.FACING);
        BlockPos front = actuator.relative(facing);
        BlockPos hullHit = null, worldHit = null;
        int hullCount = 0, worldCount = 0;
        for (BlockPos p : group) {
            boolean foot = LegParts.of(level.getBlockState(p)) == LegParts.FOOT;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (legBody.contains(n)) continue;
                if (p.equals(front) && n.equals(actuator)) continue;
                if (all.contains(n)) {
                    hullCount++;
                    if (hullHit == null) hullHit = p;
                } else if (!(foot && d == Direction.DOWN) && blocks(level, n)) {
                    worldCount++;
                    if (worldHit == null) worldHit = p;
                }
            }
        }
        if (hullHit != null) errors.add(new LegScanError(Kind.TOUCHES_HULL, hullHit,
                "Leg block touches the hull or another actuator (" + hullCount + " contact(s)); a leg may only touch its own actuator"));
        if (worldHit != null) errors.add(new LegScanError(Kind.TOUCHES_WORLD, worldHit,
                "Leg block touches a block that isn't part of the walker (" + worldCount + " contact(s)); only the bottom of a Foot Pad may"));
        return hullHit == null && worldHit == null;
    }

    private static boolean blocks(BlockGetter level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static MeasuredLeg walk(BlockGetter level, HullFrame frame, Set<BlockPos> legBody, BlockPos actuator,
                                    List<BlockPos> group, List<LegScanError> errors, List<String> warnings) {
        BlockPos cur = actuator.relative(level.getBlockState(actuator).getValue(LegActuatorBlock.FACING));
        List<BlockPos> chain = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos prev = null;
        while (true) {
            chain.add(cur);
            seen.add(cur);
            List<BlockPos> next = new ArrayList<>();
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (legBody.contains(n) && !n.equals(prev)) next.add(n);
            }
            boolean isFoot = LegParts.of(level.getBlockState(cur)) == LegParts.FOOT;
            if (isFoot && !next.isEmpty()) {
                errors.add(new LegScanError(Kind.FOOT_NOT_AT_END, cur, "Foot Pad must be the last block of the leg"));
                return null;
            }
            if (isFoot) break;
            if (next.isEmpty()) {
                errors.add(new LegScanError(Kind.NO_FOOT, cur, "Leg ends without a Foot Pad"));
                return null;
            }
            if (next.size() > 1 || seen.contains(next.get(0))) {
                errors.add(new LegScanError(Kind.BRANCH, cur, "Leg forks or loops here; a leg must be a single chain"));
                return null;
            }
            prev = cur;
            cur = next.get(0);
        }
        if (chain.size() != group.size()) { // can't happen with the checks above, but keep the partition honest
            errors.add(new LegScanError(Kind.BRANCH, actuator, "Leg has blocks off its main chain"));
            return null;
        }

        List<BlockPos> pivots = new ArrayList<>(List.of(actuator));
        List<List<BlockPos>> segmentBlocks = new ArrayList<>();
        List<BlockPos> run = new ArrayList<>();
        for (BlockPos p : chain) {
            run.add(p);
            LegParts part = LegParts.of(level.getBlockState(p));
            if (part == LegParts.JOINT || part == LegParts.FOOT) {
                pivots.add(p);
                segmentBlocks.add(List.copyOf(run));
                run.clear();
            }
        }
        int nSeg = pivots.size() - 1;
        if (nSeg < 2) {
            errors.add(new LegScanError(Kind.TOO_FEW_SEGMENTS, actuator, "Leg needs a Leg Joint (knee): 2 segments, or 3 with a coxa"));
            return null;
        }
        if (nSeg > 3) {
            errors.add(new LegScanError(Kind.TOO_MANY_SEGMENTS, pivots.get(3),
                    "Leg has " + nSeg + " segments; at most 3 (coxa, upper, lower), i.e. 2 Leg Joints"));
            return null;
        }

        List<Vec3> pts = pivots.stream().map(frame::centre).toList();
        double[] segments = new double[nSeg];
        for (int i = 0; i < nSeg; i++) segments[i] = round(pts.get(i).distanceTo(pts.get(i + 1)));
        Vec3 mount = pts.get(0), foot = pts.get(nSeg);
        int side = Math.abs(mount.z) < 1e-6 ? 0 : (mount.z > 0 ? 1 : -1);

        // Knee hint.
        Vec3 base = pts.get(nSeg - 2), knee = pts.get(nSeg - 1);
        Vec3 line = foot.subtract(base);
        Vec3 dir = line.length() > 1e-6 ? line.normalize() : new Vec3(0, -1, 0);
        Vec3 off = knee.subtract(base);
        Vec3 bend = off.subtract(dir.scale(off.dot(dir)));
        boolean fromShape = bend.length() > STRAIGHT;
        Vec3 hintDir = fromShape ? bend
                : Vec3.atLowerCornerOf(level.getBlockState(pivots.get(nSeg - 1)).getValue(LegJointBlock.FACING).getNormal());
        if (!fromShape) hintDir = frame.toHullDir(hintDir);
        String[] best = {null};
        double bestScore = bestHint(hintDir, dir, side == 0 ? 1 : side, best);
        if (bestScore < 0.5) warnings.add("Leg at " + actuator.toShortString() + ": knee bends "
                + (fromShape ? "(as built)" : "(Leg Joint facing)") + " in a direction no studio hint matches; using \""
                + best[0] + "\" (match " + round(bestScore) + ")");
        if (nSeg == 3) {
            Vec3 coxa = pts.get(1).subtract(pts.get(0));
            if (Math.abs(coxa.y) > 0.5 * coxa.length()) warnings.add("Leg at " + actuator.toShortString()
                    + ": the coxa (actuator → first joint) is steep; the studio swings the coxa horizontally");
        }
        double[] neutral = {round(foot.x - mount.x), round(foot.z - mount.z)};
        return new MeasuredLeg(null, round(mount), round(foot), neutral, segments, best[0], fromShape, side, null,
                actuator, List.copyOf(chain), List.copyOf(pivots), List.copyOf(segmentBlocks));
    }

    /** The studio hint whose pole (hint minus its component along the leg line, as in simulate.js) best matches. */
    static double bestHint(Vec3 want, Vec3 dir, int side, String[] out) {
        Vec3 w = want.length() > 1e-9 ? want.normalize() : new Vec3(0, 1, 0);
        double best = -2;
        for (String h : KNEE_HINTS) {
            Vec3 v = switch (h) {
                case "forward" -> new Vec3(1, 0, 0);
                case "back" -> new Vec3(-1, 0, 0);
                case "up" -> new Vec3(0, 1, 0);
                case "down" -> new Vec3(0, -1, 0);
                default -> new Vec3(0, 0.8, side * 0.6); // "out"
            };
            Vec3 pole = v.subtract(dir.scale(v.dot(dir)));
            if (pole.length() < 1e-3) continue;
            double score = pole.normalize().dot(w);
            if (score > best + 1e-9) {
                best = score;
                out[0] = h;
            }
        }
        return best;
    }

    /**
     * Ids: per side (L = -z, R = +z, C = on the centre line), legs ranked front to back get F, H (2 legs),
     * F, M, H (3), F, M1, M2, ..., H (more), or nothing (1 leg), then the side letter: FL, HR, ML, L.
     * A right leg that is the exact mirror of the left leg with the same rank gets {@code mirrorOf}.
     */
    private static List<MeasuredLeg> name(List<MeasuredLeg> legs) {
        List<MeasuredLeg> out = new ArrayList<>();
        Map<Integer, String> letter = Map.of(-1, "L", 1, "R", 0, "C");
        for (int side : new int[] {-1, 1, 0}) {
            List<MeasuredLeg> s = new ArrayList<>(legs.stream().filter(l -> l.side() == side).toList());
            s.sort(Comparator.comparingDouble((MeasuredLeg l) -> -l.mount().x).thenComparingDouble(l -> Math.abs(l.mount().z)));
            for (int i = 0; i < s.size(); i++) {
                String rank = s.size() == 1 ? "" : i == 0 ? "F" : i == s.size() - 1 ? "H" : s.size() == 3 ? "M" : "M" + i;
                out.add(s.get(i).withId(rank + letter.get(side)));
            }
        }
        for (int i = 0; i < out.size(); i++) {
            MeasuredLeg r = out.get(i);
            if (r.side() != 1) continue;
            String leftId = r.id().substring(0, r.id().length() - 1) + "L";
            for (MeasuredLeg l : out)
                if (l.id().equals(leftId) && mirrors(l, r)) out.set(i, r.withMirrorOf(leftId));
        }
        return out;
    }

    static boolean mirrors(MeasuredLeg l, MeasuredLeg r) {
        if (!l.knee().equals(r.knee()) || l.segments().length != r.segments().length) return false;
        for (int i = 0; i < l.segments().length; i++) if (!near(l.segments()[i], r.segments()[i])) return false;
        return near(l.mount().x, r.mount().x) && near(l.mount().y, r.mount().y) && near(l.mount().z, -r.mount().z)
                && near(l.neutral()[0], r.neutral()[0]) && near(l.neutral()[1], -r.neutral()[1]);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-3;
    }

    /**
     * Collects a walker's blocks by flood fill (face neighbours) from its controller, for tests and as a
     * starting point for assembly. Hull blocks and actuators spread into any non-air, non-liquid block; leg
     * parts only spread into other leg parts, so feet standing on the ground don't pull the ground in. A hull
     * resting on the ground would, so build the hull clear of other blocks. Stops after {@code max} blocks.
     */
    public static Set<BlockPos> collect(BlockGetter level, BlockPos controller, int max) {
        Set<BlockPos> found = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(controller.immutable()));
        found.add(controller.immutable());
        while (!queue.isEmpty() && found.size() < max) {
            BlockPos p = queue.poll();
            boolean fromLeg = LegParts.of(level.getBlockState(p)).isLegBody();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (found.contains(n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.isAir() || s.getBlock() instanceof LiquidBlock) continue;
                if (fromLeg && !LegParts.of(s).isLegBody()) continue;
                found.add(n);
                queue.add(n);
                if (found.size() >= max) break;
            }
        }
        return found;
    }

    private static List<BlockPos> sorted(Collection<BlockPos> c) {
        List<BlockPos> l = new ArrayList<>(c);
        l.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return l;
    }

    static double round(double v) {
        double r = Math.round(v * 1000) / 1000.0;
        return r == 0 ? 0 : r; // no -0.0 in JSON
    }

    static Vec3 round(Vec3 v) {
        return new Vec3(round(v.x), round(v.y), round(v.z));
    }
}
