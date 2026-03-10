package dev.elved.createtrainsloth.registry;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.InterlockingBlock;
import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TrainSlothRegistries {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateTrainSlothMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateTrainSlothMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CreateTrainSlothMod.MOD_ID);

    public static final DeferredBlock<InterlockingBlock> INTERLOCKING_BLOCK = BLOCKS.registerBlock(
        "interlocking_block",
        InterlockingBlock::new,
        BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(5.0F, 6.0F)
    );

    public static final DeferredItem<BlockItem> INTERLOCKING_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem(INTERLOCKING_BLOCK, new Item.Properties());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InterlockingBlockEntity>> INTERLOCKING_BLOCK_ENTITY =
        BLOCK_ENTITY_TYPES.register(
            "interlocking_block",
            () -> BlockEntityType.Builder.of(InterlockingBlockEntity::new, INTERLOCKING_BLOCK.get()).build(null)
        );

    private TrainSlothRegistries() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
