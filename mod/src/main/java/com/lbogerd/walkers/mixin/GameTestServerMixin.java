package com.lbogerd.walkers.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla places game tests at a random x/z up to ±15 million blocks. Sable's physics (Rapier) loses
 * block-level precision that far out, so hulls fall through the floor. Run tests near the origin instead.
 * Only affects the dedicated game-test server ({@code ./gradlew runGameTestServer}).
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {
    @Redirect(method = "startTests",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextIntBetweenInclusive(II)I"))
    private int walkers$testsNearOrigin(RandomSource random, int min, int max) {
        return 0;
    }
}
