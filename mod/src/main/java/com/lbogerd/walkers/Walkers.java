package com.lbogerd.walkers;

import com.lbogerd.walkers.registry.ModBlocks;
import com.lbogerd.walkers.walker.GyroStabilizerBlockEntity;
import com.mojang.logging.LogUtils;
import com.simibubi.create.api.stress.BlockStressValues;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Walkers.MOD_ID)
public final class Walkers {
    public static final String MOD_ID = "walkers";
    public static final Logger LOG = LogUtils.getLogger();

    public Walkers(IEventBus modBus) {
        ModBlocks.register(modBus);
        modBus.addListener(FMLCommonSetupEvent.class, e -> e.enqueueWork(() ->
                // Create stress impact: SU per RPM (Gyro Stabilizer, decision D6)
                BlockStressValues.IMPACTS.register(ModBlocks.GYRO_STABILIZER.get(), () -> GyroStabilizerBlockEntity.STRESS_IMPACT)));
        ModList mods = ModList.get();
        LOG.info("Walkers loading with create {}, sable {}, aeronautics {}",
                version(mods, "create"), version(mods, "sable"), version(mods, "aeronautics"));
    }

    static String version(ModList mods, String id) {
        return mods.getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse("MISSING");
    }
}
