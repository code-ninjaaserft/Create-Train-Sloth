package dev.elved.createtrainsloth.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class AlternativeDestinationInstruction extends DestinationInstruction {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "alternative_destination"
    );

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public List<Component> getTitleAs(String type) {
        return ImmutableList.of(
            Component.translatable("create_train_sloth.schedule." + type + ".alternative_destination")
                .withStyle(ChatFormatting.GOLD),
            Component.translatable("generic.in_quotes", Component.literal(getLabelText()))
        );
    }
}
