package com.lbogerd.walkers.walker;

/**
 * Spring-damper tuning for one planted foot (decision D1). Gains are derived per contact from the hull's
 * effective mass at that point, so the same frequency/damping works for any hull size, the same way
 * Offroad's wheel mounts scale by normal mass.
 *
 * @param rideHeight hip height above ground the springs hold the hull at [blocks]
 * @param frequency  natural frequency of the vertical spring [Hz]
 * @param damping    damping ratio (1 = critical)
 * @param lateral    horizontal stiffness of a planted foot relative to the vertical spring (foot grip; damping scales with its root)
 * @param maxReach   beyond this hip-to-ground distance the foot is considered off the ground [blocks]
 */
public record Suspension(double rideHeight, double frequency, double damping, double lateral, double maxReach) {
    public static final Suspension DEFAULT = new Suspension(3.0, 1.5, 0.8, 0.5, 5.0);

    public double omega() {
        return 2 * Math.PI * frequency;
    }
}
