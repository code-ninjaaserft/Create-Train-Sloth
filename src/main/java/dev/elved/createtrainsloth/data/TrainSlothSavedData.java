package dev.elved.createtrainsloth.data;

import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public class TrainSlothSavedData extends SavedData {

    private static final String DATA_NAME = "create_train_sloth";

    private final Map<LineId, TrainLine> lines = new LinkedHashMap<>();
    private final Map<UUID, LineId> assignments = new LinkedHashMap<>();

    public static SavedData.Factory<TrainSlothSavedData> factory() {
        return new SavedData.Factory<>(TrainSlothSavedData::new, TrainSlothSavedData::load);
    }

    public static TrainSlothSavedData load(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    private static TrainSlothSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainSlothSavedData data = new TrainSlothSavedData();

        ListTag lineTags = tag.getList("Lines", Tag.TAG_COMPOUND);
        for (Tag lineTag : lineTags) {
            TrainLine line = TrainLine.read((CompoundTag) lineTag);
            data.lines.put(line.id(), line);
        }

        ListTag assignmentTags = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (Tag assignmentTag : assignmentTags) {
            CompoundTag assignment = (CompoundTag) assignmentTag;
            UUID trainId = assignment.getUUID("TrainId");
            LineId lineId = new LineId(assignment.getString("LineId"));
            data.assignments.put(trainId, lineId);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag lineTags = new ListTag();
        for (TrainLine line : lines.values()) {
            lineTags.add(line.write());
        }
        tag.put("Lines", lineTags);

        ListTag assignmentTags = new ListTag();
        for (Map.Entry<UUID, LineId> assignment : assignments.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("TrainId", assignment.getKey());
            entry.putString("LineId", assignment.getValue().value());
            assignmentTags.add(entry);
        }
        tag.put("Assignments", assignmentTags);

        return tag;
    }

    public Map<LineId, TrainLine> lines() {
        return lines;
    }

    public Map<UUID, LineId> assignments() {
        return assignments;
    }
}
