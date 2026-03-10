package dev.elved.createtrainsloth;

import com.mojang.logging.LogUtils;

import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.integration.create.CreateIntegrationHooks;
import dev.elved.createtrainsloth.registry.TrainSlothRegistries;
import dev.elved.createtrainsloth.schedule.TrainSlothScheduleRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CreateTrainSlothMod.MOD_ID)
public class CreateTrainSlothMod {

    public static final String MOD_ID = "create_train_sloth";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final TrainSlothRuntime RUNTIME = new TrainSlothRuntime();

    public CreateTrainSlothMod(IEventBus modEventBus, ModContainer modContainer) {
        TrainSlothRegistries.register(modEventBus);
        TrainSlothScheduleRegistration.registerCreateScheduleInstructions();
        modContainer.registerConfig(ModConfig.Type.COMMON, TrainSlothConfig.SPEC);
        NeoForge.EVENT_BUS.register(new CreateIntegrationHooks(RUNTIME));
    }

    public static TrainSlothRuntime runtime() {
        return RUNTIME;
    }
}
