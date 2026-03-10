package dev.elved.createtrainsloth.data;

import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.TrainLineAssignment;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
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
    private final Map<UUID, TrainLineAssignment> assignments = new LinkedHashMap<>();
    private final Map<StationHubId, StationHub> stationHubs = new LinkedHashMap<>();

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
            TrainServiceClass serviceClass = TrainServiceClass.fromStringOrDefault(
                assignment.getString("ServiceClass"),
                TrainServiceClass.RE
            );
            data.assignments.put(trainId, new TrainLineAssignment(trainId, lineId, serviceClass));
        }

        ListTag stationHubTags = tag.getList("StationHubs", Tag.TAG_COMPOUND);
        for (Tag stationHubTag : stationHubTags) {
            StationHub stationHub = StationHub.read((CompoundTag) stationHubTag);
            data.stationHubs.put(stationHub.id(), stationHub);
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
        for (Map.Entry<UUID, TrainLineAssignment> assignment : assignments.entrySet()) {
            CompoundTag entry = new CompoundTag();
            TrainLineAssignment value = assignment.getValue();
            entry.putUUID("TrainId", value.trainId());
            entry.putString("LineId", value.lineId().value());
            entry.putString("ServiceClass", value.serviceClass().name());
            assignmentTags.add(entry);
        }
        tag.put("Assignments", assignmentTags);

        ListTag stationHubTags = new ListTag();
        for (StationHub stationHub : stationHubs.values()) {
            stationHubTags.add(stationHub.write());
        }
        tag.put("StationHubs", stationHubTags);

        return tag;
    }

    public Map<LineId, TrainLine> lines() {
        return lines;
    }

    public Map<UUID, TrainLineAssignment> assignments() {
        return assignments;
    }

    public Map<StationHubId, StationHub> stationHubs() {
        return stationHubs;
    }
}
