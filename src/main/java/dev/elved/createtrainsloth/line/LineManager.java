package dev.elved.createtrainsloth.line;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LineManager {

    private final LineRegistry lineRegistry;
    private final StationHubRegistry stationHubRegistry;
    private final Map<LineId, LineRuntimeState> runtimeByLine = new HashMap<>();

    public LineManager(LineRegistry lineRegistry, StationHubRegistry stationHubRegistry) {
        this.lineRegistry = lineRegistry;
        this.stationHubRegistry = stationHubRegistry;
    }

    public Collection<TrainLine> allLines() {
        return lineRegistry.allLines();
    }

    public Optional<TrainLine> findLine(LineId id) {
        return lineRegistry.findLine(id);
    }

    public Optional<TrainLine> lineForTrain(UUID trainId) {
        Optional<TrainLineAssignment> assignment = lineRegistry.assignmentOf(trainId);
        if (assignment.isEmpty()) {
            return Optional.empty();
        }
        return lineRegistry.findLine(assignment.get().lineId());
    }

    public Optional<TrainLine> lineForTrain(Train train) {
        return lineForTrain(train.id);
    }

    public TrainServiceClass serviceClassForTrain(UUID trainId) {
        return lineRegistry.assignmentOf(trainId)
            .map(TrainLineAssignment::serviceClass)
            .orElse(TrainServiceClass.RE);
    }

    public List<Train> collectAssignedTrains(LineId lineId, Collection<Train> trains) {
        Set<UUID> assigned = Set.copyOf(lineRegistry.trainsForLine(lineId));
        if (assigned.isEmpty()) {
            return List.of();
        }

        List<Train> result = new ArrayList<>();
        for (Train train : trains) {
            if (assigned.contains(train.id)) {
                result.add(train);
            }
        }

        result.sort(Comparator.comparing(train -> train.id.toString()));
        return result;
    }

    public int countAssignedTrains(LineId lineId) {
        return lineRegistry.trainsForLine(lineId).size();
    }

    public LineRuntimeState runtimeState(LineId lineId) {
        return runtimeByLine.computeIfAbsent(lineId, id -> new LineRuntimeState());
    }

    public boolean isStationOnLine(TrainLine line, GlobalStation station) {
        if (line.matchesStation(station)) {
            return true;
        }
        if (stationHubRegistry == null || line == null || station == null) {
            return false;
        }

        for (String routePoint : line.stationNames()) {
            Optional<StationHub> hub = stationHubRegistry.findHubForScheduleFilter(routePoint);
            if (hub.isPresent() && hub.get().matchesStation(station)) {
                return true;
            }
        }
        return false;
    }
}
