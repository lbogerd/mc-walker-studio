package com.lbogerd.walkers.test;

import com.lbogerd.walkers.Walkers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Smoke tests for the dev environment. Run headless with ./gradlew runGameTestServer. */
@GameTestHolder(Walkers.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SetupGameTests {
    private SetupGameTests() {}

    @GameTest(template = "empty")
    public static void dependenciesLoaded(GameTestHelper helper) {
        for (String id : new String[] {"create", "sable", "aeronautics", "simulated"}) {
            if (!ModList.get().isLoaded(id)) {
                helper.fail("Required mod not loaded: " + id);
                return;
            }
        }
        helper.succeed();
    }
}
