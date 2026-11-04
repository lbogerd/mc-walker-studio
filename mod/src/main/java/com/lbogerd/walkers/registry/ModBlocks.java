package com.lbogerd.walkers.registry;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.walker.WalkerControllerBlock;
import com.lbogerd.walkers.walker.WalkerControllerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Walkers.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Walkers.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Walkers.MOD_ID);

    public static final DeferredBlock<WalkerControllerBlock> WALKER_CONTROLLER = BLOCKS.register("walker_controller",
            () -> new WalkerControllerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE).strength(2.5f).sound(SoundType.WOOD)));

    public static final DeferredHolder<Item, BlockItem> WALKER_CONTROLLER_ITEM =
            ITEMS.registerSimpleBlockItem(WALKER_CONTROLLER);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WalkerControllerBlockEntity>> WALKER_CONTROLLER_BE =
            BLOCK_ENTITIES.register("walker_controller", () -> BlockEntityType.Builder
                    .of(WalkerControllerBlockEntity::new, WALKER_CONTROLLER.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
