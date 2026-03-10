package dev.elved.createtrainsloth.ponder;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class TrainSlothPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return CreateTrainSlothMod.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        TrainSlothPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        TrainSlothPonderTags.register(helper);
    }
}
