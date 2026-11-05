// Mirrors src/sim/math.js. Keep the two in sync (see mod/README.md, "Walking loop port").
//
// Vectors are double[3] {x, y, z} (Minecraft axes: y is up). Transcendentals use StrictMath (fdlibm), which
// is what V8 uses too, and hypot reimplements V8's Math.hypot, so results match the JavaScript bit for bit.
package com.lbogerd.walkers.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SimMath {
    private SimMath() {}

    /** Minecraft server ticks per second. */
    public static final int TPS = 20;

    public static double clamp(double x, double a, double b) { return Math.min(b, Math.max(a, x)); }
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static double frac(double x) { return x - Math.floor(x); }
    public static double smoothstep(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
    public static double deg(double r) { return (r * 180) / Math.PI; }
    public static double rad(double d) { return (d * Math.PI) / 180; }

    public static double[] vec(double x, double y, double z) { return new double[] {x, y, z}; }
    public static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    public static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    public static double[] scale(double[] a, double s) { return new double[] {a[0] * s, a[1] * s, a[2] * s}; }
    public static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    public static double len(double[] a) { return hypot(a[0], a[1], a[2]); }
    public static double dist(double[] a, double[] b) { return hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]); }
    public static double[] norm(double[] a) {
        double l = len(a);
        return l > 1e-9 ? new double[] {a[0] / l, a[1] / l, a[2] / l} : new double[] {0, 0, 0};
    }
    public static double[] vlerp(double[] a, double[] b, double t) {
        return new double[] {lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)};
    }

    // Rotation about the x axis (roll), z axis (pitch; +pitch lifts the +x "nose") and y axis (yaw).
    public static double[] rotX(double[] v, double a) {
        double c = StrictMath.cos(a), s = StrictMath.sin(a);
        return new double[] {v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }
    public static double[] rotZ(double[] v, double a) {
        double c = StrictMath.cos(a), s = StrictMath.sin(a);
        return new double[] {v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
    }
    /** Right-hand rule about +y: +yaw turns the +x nose toward -z (left). */
    public static double[] rotY(double[] v, double a) {
        double c = StrictMath.cos(a), s = StrictMath.sin(a);
        return new double[] {v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }
    /** Heading of a hull with yaw ψ: [cos ψ, 0, -sin ψ]. */
    public static double[] heading(double yaw) { return new double[] {StrictMath.cos(yaw), 0, -StrictMath.sin(yaw)}; }

    /**
     * Body pose. Local origin = centre of the body's bottom face.
     * Local → world: roll (x), then pitch (z), then yaw (y), then translate.
     */
    public record Pose(double[] pos, double yaw, double pitch, double roll) {}

    public static double[] bodyToWorld(Pose pose, double[] p) {
        return add(pose.pos(), rotY(rotZ(rotX(p, pose.roll()), pose.pitch()), pose.yaw()));
    }
    public static double[] worldToBody(Pose pose, double[] p) {
        return rotX(rotZ(rotY(sub(p, pose.pos()), -pose.yaw()), -pose.pitch()), -pose.roll());
    }

    // ---- 2D helpers (x, z ground plane); points are double[2] ----

    /** Counter-clockwise convex hull (Andrew's monotone chain), same tie handling as the JS. */
    public static List<double[]> convexHull(List<double[]> points) {
        List<double[]> pts = new ArrayList<>(points.size());
        for (double[] p : points) pts.add(new double[] {p[0], p[1]});
        // JS comparator a[0] - b[0] || a[1] - b[1] (note: -0 and 0 compare equal, unlike Double.compare)
        pts.sort((a, b) -> {
            double d = a[0] - b[0];
            if (d != 0) return d < 0 ? -1 : 1;
            d = a[1] - b[1];
            return d < 0 ? -1 : d > 0 ? 1 : 0;
        });
        if (pts.size() < 3) return pts;
        List<double[]> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (double[] p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) lower.remove(lower.size() - 1);
            lower.add(p);
        }
        for (int i = pts.size() - 1; i >= 0; i--) {
            double[] p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) upper.remove(upper.size() - 1);
            upper.add(p);
        }
        upper.remove(upper.size() - 1);
        lower.remove(lower.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    static double distToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double l2 = dx * dx + dz * dz;
        double t = l2 > 0 ? clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / l2, 0, 1) : 0;
        return hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dz));
    }

    public record Support(double margin, List<double[]> hull) {}

    /**
     * Signed distance from point p to the support polygon spanned by points. Positive = inside (the margin),
     * negative = outside. Fewer than 3 contacts can never be statically stable, so the result is <= 0.
     */
    public static Support supportMargin(double[] p, List<double[]> points) {
        if (points.isEmpty()) return new Support(-5, List.of());
        List<double[]> hull = convexHull(points);
        return new Support(hullMargin(p, hull), hull);
    }

    /** Same as supportMargin but for an already computed (CCW) hull. */
    public static double hullMargin(double[] p, List<double[]> hull) {
        if (hull.isEmpty()) return -5;
        if (hull.size() == 1) return -hypot(p[0] - hull.get(0)[0], p[1] - hull.get(0)[1]);
        double minD = Double.POSITIVE_INFINITY;
        boolean inside = hull.size() >= 3;
        for (int i = 0; i < hull.size(); i++) {
            double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
            minD = Math.min(minD, distToSegment(p, a, b));
            if (inside && (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) < 0) inside = false;
        }
        return inside ? minD : -minD;
    }

    // ---- JavaScript number semantics ----

    /**
     * V8's Math.hypot: normalise by the largest magnitude, Kahan-sum the squares, sqrt, rescale. Not the same
     * rounding as java.lang.Math.hypot, so the port uses this everywhere the JS calls Math.hypot.
     */
    public static double hypot(double... v) {
        int n = v.length;
        if (n == 0) return 0;
        double[] abs = new double[n];
        boolean nan = false;
        double max = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(v[i])) nan = true;
            else {
                abs[i] = Math.abs(v[i]);
                if (abs[i] > max) max = abs[i];
            }
        }
        if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        if (nan) return Double.NaN;
        if (max == 0) return 0;
        double sum = 0, compensation = 0;
        for (int i = 0; i < n; i++) {
            double x = abs[i] / max;
            double summand = x * x - compensation;
            double preliminary = sum + summand;
            compensation = (preliminary - sum) - summand;
            sum = preliminary;
        }
        return Math.sqrt(sum) * max;
    }

    /** JS Math.round: floor(x + 0.5) semantics (halves round toward +∞). */
    public static double jsRound(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        return (double) Math.round(x);
    }

    /** JS Number.prototype.toFixed: exact binary value, ties away from zero. */
    public static String toFixed(double x, int digits) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
        if (Math.abs(x) >= 1e21) return jsString(x);
        String s = new BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString();
        return x < 0 ? "-" + s : s;
    }

    /** JS String(number) for the values that appear in messages (integers and ordinary decimals). */
    public static String jsString(double x) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
        if (x == 0) return "0";
        double ax = Math.abs(x);
        if (ax >= 1e-7 && ax < 1e21) {
            // Double.toString gives the shortest round-trip digits (JDK 19+), like JS; only the layout differs
            return new BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString();
        }
        String s = Double.toString(x).replace("E", "e"); // 1.0E-8 → 1e-8, 1.0E21 → 1e+21
        s = s.replace(".0e", "e");
        return s.contains("e-") ? s : s.replace("e", "e+");
    }

    static double[] copy(double[] v) { return Arrays.copyOf(v, v.length); }
}
