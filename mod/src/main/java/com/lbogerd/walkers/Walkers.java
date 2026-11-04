package com.lbogerd.walkers;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Walkers.MOD_ID)
public final class Walkers {
    public static final String MOD_ID = "walkers";
    public static final Logger LOG = LogUtils.getLogger();

    public Walkers(IEventBus modBus) {
        ModList mods = ModList.get();
        LOG.info("Walkers loading with create {}, sable {}, aeronautics {}",
                version(mods, "create"), version(mods, "sable"), version(mods, "aeronautics"));
    }

    static String version(ModList mods, String id) {
        return mods.getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse("MISSING");
    }
}
