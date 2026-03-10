package dev.elved.createtrainsloth.interlocking.schematic;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public record StellwerkTrainView(
    UUID trainId,
    String trainName,
    int sectionIndex,
    String target,
    boolean waitingForSignal
) {
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("TrainId", trainId);
        tag.putString("TrainName", trainName);
        tag.putInt("Section", sectionIndex);
        tag.putString("Target", target);
        tag.putBoolean("WaitingSignal", waitingForSignal);
        return tag;
    }

    public static StellwerkTrainView fromTag(CompoundTag tag) {
        return new StellwerkTrainView(
            tag.getUUID("TrainId"),
            tag.getString("TrainName"),
            tag.getInt("Section"),
            tag.getString("Target"),
            tag.getBoolean("WaitingSignal")
        );
    }
}
