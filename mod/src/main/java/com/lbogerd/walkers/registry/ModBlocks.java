package com.lbogerd.walkers.registry;

import com.lbogerd.walkers.Walkers;
import com.lbogerd.walkers.leg.LegActuatorBlock;
import com.lbogerd.walkers.leg.LegJointBlock;
import com.lbogerd.walkers.walker.GyroStabilizerBlock;
import com.lbogerd.walkers.walker.GyroStabilizerBlockEntity;
import com.lbogerd.walkers.walker.WalkerControllerBlock;
import com.lbogerd.walkers.walker.WalkerControllerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
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
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Walkers.MOD_ID);

    public static final DeferredBlock<WalkerControllerBlock> WALKER_CONTROLLER = BLOCKS.register("walker_controller",
            () -> new WalkerControllerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE).strength(2.5f).sound(SoundType.WOOD)));

    // Leg parts (decision D3). Metal, a little softer than iron so they're quick to rebuild.
    public static final DeferredBlock<LegActuatorBlock> LEG_ACTUATOR = BLOCKS.register("leg_actuator",
            () -> new LegActuatorBlock(metal(MapColor.COLOR_ORANGE)));
    public static final DeferredBlock<RotatedPillarBlock> LEG_STRUT = BLOCKS.register("leg_strut",
            () -> new RotatedPillarBlock(metal(MapColor.METAL)));
    public static final DeferredBlock<LegJointBlock> LEG_JOINT = BLOCKS.register("leg_joint",
            () -> new LegJointBlock(metal(MapColor.COLOR_YELLOW)));
    public static final DeferredBlock<Block> FOOT_PAD = BLOCKS.register("foot_pad",
            () -> new Block(metal(MapColor.COLOR_GRAY)));

    // Gyro Stabilizer (decision D6): a Create stress consumer that rights the hull.
    public static final DeferredBlock<GyroStabilizerBlock> GYRO_STABILIZER = BLOCKS.register("gyro_stabilizer",
            () -> new GyroStabilizerBlock(metal(MapColor.COLOR_LIGHT_BLUE).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> GYRO_STABILIZER_ITEM = ITEMS.registerSimpleBlockItem(GYRO_STABILIZER);
    public static final DeferredHolder<Item, BlockItem> WALKER_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(WALKER_CONTROLLER);
    public static final DeferredHolder<Item, BlockItem> LEG_ACTUATOR_ITEM = ITEMS.registerSimpleBlockItem(LEG_ACTUATOR);
    public static final DeferredHolder<Item, BlockItem> LEG_STRUT_ITEM = ITEMS.registerSimpleBlockItem(LEG_STRUT);
    public static final DeferredHolder<Item, BlockItem> LEG_JOINT_ITEM = ITEMS.registerSimpleBlockItem(LEG_JOINT);
    public static final DeferredHolder<Item, BlockItem> FOOT_PAD_ITEM = ITEMS.registerSimpleBlockItem(FOOT_PAD);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WalkerControllerBlockEntity>> WALKER_CONTROLLER_BE =
            BLOCK_ENTITIES.register("walker_controller", () -> BlockEntityType.Builder
                    .of(WalkerControllerBlockEntity::new, WALKER_CONTROLLER.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GyroStabilizerBlockEntity>> GYRO_STABILIZER_BE =
            BLOCK_ENTITIES.register("gyro_stabilizer", () -> BlockEntityType.Builder
                    .of((pos, state) -> new GyroStabilizerBlockEntity(ModBlocks.GYRO_STABILIZER_BE.get(), pos, state), GYRO_STABILIZER.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("walkers",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.walkers"))
                    .icon(() -> LEG_ACTUATOR_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(WALKER_CONTROLLER_ITEM.get());
                        output.accept(LEG_ACTUATOR_ITEM.get());
                        output.accept(LEG_STRUT_ITEM.get());
                        output.accept(LEG_JOINT_ITEM.get());
                        output.accept(FOOT_PAD_ITEM.get());
                        output.accept(GYRO_STABILIZER_ITEM.get());
                    })
                    .build());

    private static BlockBehaviour.Properties metal(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).strength(2.0f, 6.0f)
                .sound(SoundType.METAL);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
