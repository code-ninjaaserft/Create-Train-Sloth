package dev.elved.createtrainsloth.ponder;

import dev.elved.createtrainsloth.ponder.scenes.StellwerkScenes;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

public final class TrainSlothPonderScenes {

    private TrainSlothPonderScenes() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<ItemLike> itemHelper =
            helper.withKeyFunction(item -> BuiltInRegistries.ITEM.getKey(item.asItem()));

        itemHelper.forComponents(TrainSlothRegistries.INTERLOCKING_BLOCK.get())
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_signal/placement"),
                StellwerkScenes::introduction,
                TrainSlothPonderTags.STELLWERK
            )
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_signal/signaling"),
                StellwerkScenes::schematicView
            )
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_signal/redstone"),
                StellwerkScenes::manualLocking
            )
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_station/schedule"),
                StellwerkScenes::automaticRouting
            )
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_track/placement"),
                StellwerkScenes::alternativeRouting
            )
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "train_track/chunks"),
                StellwerkScenes::multiTrainDispatch
            );

        itemHelper.forComponents(TrainSlothRegistries.STATION_HUB_BLOCK.get(), TrainSlothRegistries.STATION_LINK_ITEM.get())
            .addStoryBoard(
                ResourceLocation.fromNamespaceAndPath("create", "redstone_link/transmitter"),
                StellwerkScenes::stationHubLinking,
                TrainSlothPonderTags.STELLWERK
            );
    }
}
