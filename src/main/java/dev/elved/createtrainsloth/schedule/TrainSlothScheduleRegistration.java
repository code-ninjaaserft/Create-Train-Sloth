package dev.elved.createtrainsloth.schedule;

import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import java.util.function.Supplier;
import net.createmod.catnip.data.Pair;
import net.minecraft.resources.ResourceLocation;

public final class TrainSlothScheduleRegistration {

    private TrainSlothScheduleRegistration() {
    }

    public static void registerCreateScheduleInstructions() {
        registerInstruction(AlternativeDestinationInstruction.ID, AlternativeDestinationInstruction::new);
        registerInstruction(HubDestinationInstruction.ID, HubDestinationInstruction::new);
        registerInstruction(StellwerkControlInstruction.ID, StellwerkControlInstruction::new);
    }

    private static void registerInstruction(ResourceLocation id, Supplier<? extends ScheduleInstruction> factory) {
        boolean alreadyRegistered = Schedule.INSTRUCTION_TYPES.stream()
            .map(Pair::getFirst)
            .anyMatch(id::equals);
        if (!alreadyRegistered) {
            Schedule.INSTRUCTION_TYPES.add(Pair.of(id, factory));
        }
    }
}
