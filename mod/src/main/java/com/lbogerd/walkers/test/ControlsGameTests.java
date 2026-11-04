package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.control.SimulatedControls;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Follow-up 4 / decision D5: our code links against Simulated's control blocks at runtime. */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ControlsGameTests {
    private ControlsGameTests() {}

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException("Block not registered: " + id));
    }

    @GameTest(template = "arena")
    public static void readsSimulatedControls(GameTestHelper helper) {
        BlockPos lever = new BlockPos(3, 1, 3), wheel = new BlockPos(6, 1, 3);
        helper.setBlock(lever, block("simulated:throttle_lever"));
        helper.setBlock(wheel, block("simulated:steering_wheel"));
        helper.runAfterDelay(2, () -> {
            var leverBe = helper.getBlockEntity(lever);
            if (leverBe instanceof dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity t) t.setSignal(6);
            float throttle = SimulatedControls.throttle(leverBe);
            float steer = SimulatedControls.steer(helper.getBlockEntity(wheel));
            if (Math.abs(throttle - 6 / 15f) > 1e-6) helper.fail("throttle " + throttle + ", expected 0.4");
            else if (steer != 0f) helper.fail("steer " + steer + ", expected 0 for an untouched wheel");
            else helper.succeed();
        });
    }
}
