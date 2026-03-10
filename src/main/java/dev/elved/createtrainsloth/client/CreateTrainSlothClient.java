package dev.elved.createtrainsloth.client;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.client.screen.StellwerkScreen;
import dev.elved.createtrainsloth.ponder.TrainSlothPonderPlugin;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(value = CreateTrainSlothMod.MOD_ID, dist = Dist.CLIENT)
public class CreateTrainSlothClient {

    public CreateTrainSlothClient(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterMenuScreens);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PonderIndex.addPlugin(new TrainSlothPonderPlugin()));
    }

    private void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TrainSlothRegistries.STELLWERK_MENU.get(), StellwerkScreen::new);
    }
}
