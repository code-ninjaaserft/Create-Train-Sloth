package dev.elved.createtrainsloth.registry;

import com.simibubi.create.AllCreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

public final class TrainSlothCreativeTabIntegration {

    private TrainSlothCreativeTabIntegration() {
    }

    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())) {
            event.accept(TrainSlothRegistries.INTERLOCKING_BLOCK_ITEM.get());
            event.accept(TrainSlothRegistries.LINE_MANAGER_COMPUTER_BLOCK_ITEM.get());
            event.accept(TrainSlothRegistries.STATION_HUB_BLOCK_ITEM.get());
            event.accept(TrainSlothRegistries.STATION_LINK_ITEM.get());
        }
    }
}
