package dev.elved.createtrainsloth.registry;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.InterlockingBlock;
import dev.elved.createtrainsloth.block.LineManagerComputerBlock;
import dev.elved.createtrainsloth.block.StationLinkBlock;
import dev.elved.createtrainsloth.block.StationHubBlock;
import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
import dev.elved.createtrainsloth.block.entity.LineManagerComputerBlockEntity;
import dev.elved.createtrainsloth.block.entity.StationLinkBlockEntity;
import dev.elved.createtrainsloth.block.entity.StationHubBlockEntity;
import dev.elved.createtrainsloth.item.StationLinkBlockItem;
import dev.elved.createtrainsloth.menu.LineManagerComputerMenu;
import dev.elved.createtrainsloth.menu.StationHubMenu;
import dev.elved.createtrainsloth.menu.StellwerkMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TrainSlothRegistries {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateTrainSlothMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateTrainSlothMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CreateTrainSlothMod.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(BuiltInRegistries.MENU, CreateTrainSlothMod.MOD_ID);

    public static final DeferredBlock<InterlockingBlock> INTERLOCKING_BLOCK = BLOCKS.registerBlock(
        "interlocking_block",
        InterlockingBlock::new,
        BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(5.0F, 6.0F)
    );

    public static final DeferredItem<BlockItem> INTERLOCKING_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(INTERLOCKING_BLOCK, new Item.Properties());

    public static final DeferredBlock<LineManagerComputerBlock> LINE_MANAGER_COMPUTER_BLOCK = BLOCKS.registerBlock(
        "line_manager_computer",
        LineManagerComputerBlock::new,
        BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(5.0F, 6.0F)
    );

    public static final DeferredItem<BlockItem> LINE_MANAGER_COMPUTER_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(LINE_MANAGER_COMPUTER_BLOCK, new Item.Properties());

    public static final DeferredBlock<StationHubBlock> STATION_HUB_BLOCK = BLOCKS.registerBlock(
        "station_hub_block",
        StationHubBlock::new,
        BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK).strength(4.0F, 6.0F)
    );

    public static final DeferredItem<BlockItem> STATION_HUB_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(STATION_HUB_BLOCK, new Item.Properties());

    public static final DeferredBlock<StationLinkBlock> STATION_LINK_BLOCK = BLOCKS.registerBlock(
        "station_link",
        StationLinkBlock::new,
        BlockBehaviour.Properties.ofFullCopy(Blocks.ANDESITE)
            .strength(2.0F, 4.0F)
            .noOcclusion()
    );

    public static final DeferredItem<StationLinkBlockItem> STATION_LINK_ITEM =
        ITEMS.register(
            "station_link",
            () -> new StationLinkBlockItem(STATION_LINK_BLOCK.get(), new Item.Properties().stacksTo(1))
        );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InterlockingBlockEntity>> INTERLOCKING_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register(
            "interlocking_block",
            () -> BlockEntityType.Builder.of(InterlockingBlockEntity::new, INTERLOCKING_BLOCK.get()).build(null)
        );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LineManagerComputerBlockEntity>> LINE_MANAGER_COMPUTER_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register(
            "line_manager_computer",
            () -> BlockEntityType.Builder.of(LineManagerComputerBlockEntity::new, LINE_MANAGER_COMPUTER_BLOCK.get()).build(null)
        );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StationHubBlockEntity>> STATION_HUB_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register(
            "station_hub_block",
            () -> BlockEntityType.Builder.of(StationHubBlockEntity::new, STATION_HUB_BLOCK.get()).build(null)
        );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StationLinkBlockEntity>> STATION_LINK_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register(
            "station_link",
            () -> BlockEntityType.Builder.of(StationLinkBlockEntity::new, STATION_LINK_BLOCK.get()).build(null)
        );

    public static final DeferredHolder<MenuType<?>, MenuType<StellwerkMenu>> STELLWERK_MENU =
        MENU_TYPES.register(
            "stellwerk_menu",
            () -> IMenuTypeExtension.create(StellwerkMenu::new)
        );

    public static final DeferredHolder<MenuType<?>, MenuType<LineManagerComputerMenu>> LINE_MANAGER_COMPUTER_MENU =
        MENU_TYPES.register(
            "line_manager_computer_menu",
            () -> IMenuTypeExtension.create(LineManagerComputerMenu::new)
        );

    public static final DeferredHolder<MenuType<?>, MenuType<StationHubMenu>> STATION_HUB_MENU =
        MENU_TYPES.register(
            "station_hub_menu",
            () -> IMenuTypeExtension.create(StationHubMenu::new)
        );

    private TrainSlothRegistries() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }
}
