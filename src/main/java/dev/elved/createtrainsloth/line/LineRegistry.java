package dev.elved.createtrainsloth.line;

import dev.elved.createtrainsloth.data.TrainSlothSavedData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class LineRegistry {

    private final TrainSlothSavedData savedData;

    public LineRegistry(TrainSlothSavedData savedData) {
        this.savedData = savedData;
    }

    public Collection<TrainLine> allLines() {
        return Collections.unmodifiableCollection(savedData.lines().values());
    }

    public Optional<TrainLine> findLine(LineId id) {
        return Optional.ofNullable(savedData.lines().get(id));
    }

    public boolean hasLine(LineId id) {
        return savedData.lines().containsKey(id);
    }

    public TrainLine createLine(LineId id, String displayName) {
        TrainLine line = new TrainLine(id, displayName);
        savedData.lines().put(id, line);
        savedData.setDirty();
        return line;
    }

    public boolean removeLine(LineId id) {
        TrainLine removed = savedData.lines().remove(id);
        if (removed == null) {
            return false;
        }

        List<UUID> staleAssignments = new ArrayList<>();
        for (Map.Entry<UUID, TrainLineAssignment> entry : savedData.assignments().entrySet()) {
            if (entry.getValue().lineId().equals(id)) {
                staleAssignments.add(entry.getKey());
            }
        }
        staleAssignments.forEach(savedData.assignments()::remove);

        savedData.setDirty();
        return true;
    }

    public void assignTrain(UUID trainId, LineId lineId) {
        assignTrain(trainId, lineId, TrainServiceClass.RE);
    }

    public void assignTrain(UUID trainId, LineId lineId, TrainServiceClass serviceClass) {
        TrainLineAssignment updated = new TrainLineAssignment(trainId, lineId, serviceClass);
        TrainLineAssignment previous = savedData.assignments().put(trainId, updated);
        if (previous == null || !previous.lineId().equals(lineId) || previous.serviceClass() != serviceClass) {
            savedData.setDirty();
        }
    }

    public void unassignTrain(UUID trainId) {
        if (savedData.assignments().remove(trainId) != null) {
            savedData.setDirty();
        }
    }

    public Optional<TrainLineAssignment> assignmentOf(UUID trainId) {
        return Optional.ofNullable(savedData.assignments().get(trainId));
    }

    public Map<UUID, TrainLineAssignment> assignments() {
        return Collections.unmodifiableMap(new HashMap<>(savedData.assignments()));
    }

    public List<UUID> trainsForLine(LineId lineId) {
        return savedData.assignments().entrySet().stream()
            .filter(entry -> entry.getValue().lineId().equals(lineId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    public void markDirty() {
        savedData.setDirty();
    }
}
