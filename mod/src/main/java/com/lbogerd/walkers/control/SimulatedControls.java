package com.lbogerd.walkers.control;

import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reads Simulated's control blocks (decision D5).
 * Steering Wheel: angle within ±angleInput degrees → steer in [-1, 1].
 * Throttle Lever: state 0–15 → throttle in [0, 1]. Reverse comes from the drive shaft's direction (D7).
 */
public final class SimulatedControls {
    private SimulatedControls() {}

    /** Steer fraction in [-1, 1], or NaN if {@code be} isn't a Steering Wheel. */
    public static float steer(BlockEntity be) {
        if (!(be instanceof SteeringWheelBlockEntity wheel)) return Float.NaN;
        float max = Math.max(1, wheel.angleInput.getValue());
        return Math.max(-1f, Math.min(1f, wheel.getAngle() / max));
    }

    /** Throttle fraction in [0, 1], or NaN if {@code be} isn't a Throttle Lever. */
    public static float throttle(BlockEntity be) {
        if (!(be instanceof ThrottleLeverBlockEntity lever)) return Float.NaN;
        return lever.getState() / 15f;
    }
}
