package dev.elved.createtrainsloth.ponder;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

public final class TrainSlothPonderTags {

    public static final ResourceLocation STELLWERK = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "stellwerk"
    );

    private TrainSlothPonderTags() {
    }

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(STELLWERK)
            .addToIndex()
            .item(TrainSlothRegistries.INTERLOCKING_BLOCK.get())
            .title("Stellwerk Dispatching")
            .description("Network-aware train dispatching, manual section locks, and route fallback control")
            .register();

        PonderTagRegistrationHelper<ItemLike> itemHelper =
            helper.withKeyFunction(item -> BuiltInRegistries.ITEM.getKey(item.asItem()));
        itemHelper.addToTag(STELLWERK)
            .add(TrainSlothRegistries.INTERLOCKING_BLOCK.get())
            .add(TrainSlothRegistries.STATION_HUB_BLOCK.get())
            .add(TrainSlothRegistries.STATION_LINK_ITEM.get());
    }
}
