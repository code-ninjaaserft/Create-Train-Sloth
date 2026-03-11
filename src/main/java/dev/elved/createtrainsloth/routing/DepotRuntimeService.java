package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LinePlanningService;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Level;

public class DepotRuntimeService {

    private final LineRegistry lineRegistry;
    private final LineManager lineManager;
    private final LinePlanningService linePlanningService;
    private final StationHubRegistry stationHubRegistry;
    private final DebugOverlay debugOverlay;
    private final Set<UUID> recallInProgress = new HashSet<>();

    public DepotRuntimeService(
        LineRegistry lineRegistry,
        LineManager lineManager,
        LinePlanningService linePlanningService,
        StationHubRegistry stationHubRegistry,
        DebugOverlay debugOverlay
    ) {
        this.lineRegistry = lineRegistry;
        this.lineManager = lineManager;
        this.linePlanningService = linePlanningService;
        this.stationHubRegistry = stationHubRegistry;
        this.debugOverlay = debugOverlay;
    }

    public void tick(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableDepotControl.get()) {
            return;
        }
        if (level == null || trains == null || trains.isEmpty()) {
            return;
        }

        List<StationHub> depotHubs = resolveDepotHubs();
        if (depotHubs.isEmpty()) {
            return;
        }

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));
        Map<UUID, Train> trainById = new HashMap<>();
        for (Train train : ordered) {
            trainById.put(train.id, train);
        }

        processRecallArrivals(trainById, depotHubs);
        List<Train> availableDepotTrains = collectAvailableDepotTrains(ordered, depotHubs);
        evaluateLineBalances(level, ordered, availableDepotTrains, depotHubs);
        routeUnassignedTrainsToDepot(ordered, depotHubs);
    }

    private void evaluateLineBalances(
        Level level,
        List<Train> trains,
        List<Train> availableDepotTrains,
        List<StationHub> depotHubs
    ) {
        List<TrainLine> lines = new ArrayList<>(lineRegistry.allLines());
        lines.sort(Comparator.comparing(line -> line.id().value()));

        for (TrainLine line : lines) {
            LineId lineId = line.id();
            int currentTrainCount = lineManager.countAssignedTrains(lineId);
            TrainServiceClass serviceClass = dominantServiceClass(lineId);
            int recommendedTrainCount = linePlanningService.recommendedTrainCount(line, line.stationCount(), serviceClass);

            while (currentTrainCount < recommendedTrainCount && !availableDepotTrains.isEmpty()) {
                Train depotTrain = availableDepotTrains.remove(0);
                lineRegistry.assignTrain(depotTrain.id, lineId, serviceClass);
                currentTrainCount++;
                recordDepotAction(
                    depotTrain,
                    "DEPLOY line=" + lineId.value() + " svc=" + serviceClass.name() + " current=" + currentTrainCount + "/" + recommendedTrainCount
                );
            }

            int excess = currentTrainCount - recommendedTrainCount;
            if (excess <= 0) {
                continue;
            }

            List<Train> recallCandidates = collectRecallCandidatesForLine(lineId, trains);
            for (Train candidate : recallCandidates) {
                if (excess <= 0) {
                    break;
                }
                if (recallInProgress.contains(candidate.id)) {
                    continue;
                }

                DiscoveredPath pathToDepot = findPathToDepot(candidate, depotHubs);
                if (pathToDepot == null || pathToDepot.destination == null) {
                    continue;
                }
                if (candidate.navigation.startNavigation(pathToDepot) < 0D) {
                    continue;
                }

                recallInProgress.add(candidate.id);
                excess--;
                recordDepotAction(
                    candidate,
                    "RECALL line=" + lineId.value() + " destination=" + pathToDepot.destination.name
                );
            }
        }
    }

    private void processRecallArrivals(Map<UUID, Train> trainById, List<StationHub> depotHubs) {
        List<UUID> completed = new ArrayList<>();
        for (UUID trainId : recallInProgress) {
            Train train = trainById.get(trainId);
            if (train == null) {
                completed.add(trainId);
                continue;
            }

            GlobalStation currentStation = train.getCurrentStation();
            if (currentStation == null || train.navigation.destination != null) {
                continue;
            }
            if (!matchesAnyHub(currentStation, depotHubs)) {
                continue;
            }

            lineRegistry.unassignTrain(trainId);
            completed.add(trainId);
            recordDepotAction(train, "RECALL_COMPLETE depot=" + currentStation.name);
        }
        completed.forEach(recallInProgress::remove);
    }

    private List<Train> collectAvailableDepotTrains(List<Train> trains, List<StationHub> depotHubs) {
        List<Train> available = new ArrayList<>();
        for (Train train : trains) {
            if (lineRegistry.assignmentOf(train.id).isPresent()) {
                continue;
            }

            GlobalStation currentStation = train.getCurrentStation();
            if (currentStation == null || !matchesAnyHub(currentStation, depotHubs)) {
                continue;
            }
            if (train.runtime == null || train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }
            if (train.navigation.destination != null) {
                continue;
            }
            available.add(train);
        }
        available.sort(Comparator.comparing(train -> train.id.toString()));
        return available;
    }

    private List<Train> collectRecallCandidatesForLine(LineId lineId, List<Train> trains) {
        List<Train> candidates = new ArrayList<>();
        for (Train train : trains) {
            Optional<LineId> assignedLine = lineRegistry.assignmentOf(train.id).map(assignment -> assignment.lineId());
            if (assignedLine.isEmpty() || !assignedLine.get().equals(lineId)) {
                continue;
            }
            if (train.runtime == null || train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }
            if (train.navigation.destination != null) {
                continue;
            }
            if (train.getCurrentStation() == null) {
                continue;
            }
            candidates.add(train);
        }
        candidates.sort(
            Comparator.comparingInt((Train train) -> lineManager.serviceClassForTrain(train.id).priorityWeight())
                .thenComparing(train -> train.id.toString())
        );
        return candidates;
    }

    private DiscoveredPath findPathToDepot(Train train, List<StationHub> depotHubs) {
        if (train == null || train.graph == null) {
            return null;
        }

        GlobalStation currentStation = train.getCurrentStation();
        ArrayList<GlobalStation> candidates = new ArrayList<>();
        for (GlobalStation station : train.graph.getPoints(EdgePointType.STATION)) {
            if (currentStation != null && currentStation.id.equals(station.id)) {
                continue;
            }
            if (matchesAnyHub(station, depotHubs)) {
                candidates.add(station);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        return train.navigation.findPathTo(candidates, TrainSlothConfig.ROUTING.maxSearchCost.get());
    }

    private TrainServiceClass dominantServiceClass(LineId lineId) {
        TrainServiceClass selected = TrainServiceClass.RE;
        for (UUID trainId : lineRegistry.trainsForLine(lineId)) {
            TrainServiceClass serviceClass = lineManager.serviceClassForTrain(trainId);
            if (serviceClass.priorityWeight() > selected.priorityWeight()) {
                selected = serviceClass;
            }
        }
        return selected;
    }

    private List<StationHub> resolveDepotHubs() {
        if (stationHubRegistry == null) {
            return List.of();
        }

        Map<String, StationHub> hubsById = new HashMap<>();
        for (StationHub hub : stationHubRegistry.allHubs()) {
            if (hub.isDepotHub()) {
                hubsById.put(hub.id().value(), hub);
            }
        }

        for (String rawId : TrainSlothConfig.ROUTING.depotHubIds.get()) {
            if (rawId == null || rawId.isBlank()) {
                continue;
            }

            String depotId = rawId.trim().toLowerCase(Locale.ROOT);
            try {
                stationHubRegistry.findHub(new StationHubId(depotId)).ifPresent(hub -> hubsById.put(hub.id().value(), hub));
            } catch (IllegalArgumentException ignored) {
            }
        }
        List<StationHub> hubs = new ArrayList<>(hubsById.values());
        hubs.sort(Comparator.comparing(hub -> hub.id().value()));
        return hubs;
    }

    private boolean matchesAnyHub(GlobalStation station, List<StationHub> hubs) {
        if (station == null || hubs == null || hubs.isEmpty()) {
            return false;
        }
        for (StationHub hub : hubs) {
            if (hub.matchesStation(station)) {
                return true;
            }
        }
        return false;
    }

    private void recordDepotAction(Train train, String detail) {
        if (train == null) {
            return;
        }

        if (debugOverlay != null) {
            debugOverlay.recordDepotAction(train.id, detail);
        }
        if (TrainSlothConfig.DEBUG.verboseLogs.get()) {
            CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth][Depot] train={} {}", train.id, detail);
        }
    }

    private void routeUnassignedTrainsToDepot(List<Train> trains, List<StationHub> depotHubs) {
        if (depotHubs == null || depotHubs.isEmpty()) {
            return;
        }

        for (Train train : trains) {
            if (train == null || train.graph == null) {
                continue;
            }
            if (lineRegistry.assignmentOf(train.id).isPresent()) {
                continue;
            }
            if (train.runtime == null || train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }
            if (train.navigation.destination != null) {
                continue;
            }

            GlobalStation current = train.getCurrentStation();
            if (current != null && matchesAnyHub(current, depotHubs)) {
                continue;
            }

            DiscoveredPath pathToDepot = findPathToDepot(train, depotHubs);
            if (pathToDepot == null || pathToDepot.destination == null) {
                continue;
            }
            if (train.navigation.startNavigation(pathToDepot) < 0D) {
                continue;
            }
            recordDepotAction(train, "UNASSIGNED_TO_DEPOT destination=" + pathToDepot.destination.name);
        }
    }
}
