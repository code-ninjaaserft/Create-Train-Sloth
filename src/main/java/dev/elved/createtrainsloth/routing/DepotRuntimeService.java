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

    private static final long LINE_BALANCE_INTERVAL_TICKS = 20L;

    private final LineRegistry lineRegistry;
    private final LineManager lineManager;
    private final LinePlanningService linePlanningService;
    private final StationHubRegistry stationHubRegistry;
    private final DebugOverlay debugOverlay;
    private final Set<UUID> recallInProgress = new HashSet<>();
    private long lastLineBalanceTick = Long.MIN_VALUE;

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
        runDepotControl(level, trains, false);
    }

    public int triggerManualBalance(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableDepotControl.get()) {
            return -1;
        }
        return runDepotControl(level, trains, true);
    }

    public boolean isRecallInProgress(UUID trainId) {
        return trainId != null && recallInProgress.contains(trainId);
    }

    private int runDepotControl(Level level, List<Train> trains, boolean forceLineBalanceCheck) {
        if (!TrainSlothConfig.ROUTING.enableDepotControl.get()) {
            return 0;
        }
        if (level == null || trains == null || trains.isEmpty()) {
            return 0;
        }

        List<StationHub> depotHubs = resolveDepotHubs();
        if (depotHubs.isEmpty()) {
            return 0;
        }

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));
        Map<UUID, Train> trainById = new HashMap<>();
        for (Train train : ordered) {
            trainById.put(train.id, train);
        }

        processRecallArrivals(trainById, depotHubs);
        int actions = 0;
        Set<UUID> reservedDepotStations = collectReservedDepotStationIds(ordered, depotHubs);
        if (shouldRunLineBalanceCheck(level, forceLineBalanceCheck)) {
            List<Train> availableDepotTrains = collectAvailableDepotTrains(ordered, depotHubs);
            actions += evaluateLineBalances(level, ordered, availableDepotTrains, depotHubs, reservedDepotStations);
            lastLineBalanceTick = level.getGameTime();
        }
        actions += routeUnassignedTrainsToDepot(ordered, depotHubs, reservedDepotStations);
        return actions;
    }

    private boolean shouldRunLineBalanceCheck(Level level, boolean force) {
        if (force) {
            return true;
        }
        if (level == null) {
            return false;
        }
        if (lastLineBalanceTick == Long.MIN_VALUE) {
            return true;
        }
        return level.getGameTime() - lastLineBalanceTick >= LINE_BALANCE_INTERVAL_TICKS;
    }

    private int evaluateLineBalances(
        Level level,
        List<Train> trains,
        List<Train> availableDepotTrains,
        List<StationHub> depotHubs,
        Set<UUID> reservedDepotStations
    ) {
        int actions = 0;
        List<TrainLine> lines = new ArrayList<>(lineRegistry.allLines());
        lines.sort(Comparator.comparing(line -> line.id().value()));

        for (TrainLine line : lines) {
            LineId lineId = line.id();
            List<StationHub> lineDepotHubs = resolveLineDepotHubs(line, depotHubs);
            if (lineDepotHubs.isEmpty()) {
                continue;
            }
            int currentTrainCount = lineManager.countAssignedTrains(lineId);
            TrainServiceClass serviceClass = resolveServiceClassForLine(line, lineId);
            int recommendedTrainCount = linePlanningService.recommendedTrainCount(line, line.stationCount(), serviceClass);
            int targetTrainCount = line.settings().resolveTargetTrainCount(recommendedTrainCount);

            while (currentTrainCount < targetTrainCount) {
                Train depotTrain = takeFirstAvailableDepotTrain(availableDepotTrains, lineDepotHubs);
                if (depotTrain == null) {
                    break;
                }
                lineRegistry.assignTrain(depotTrain.id, lineId, serviceClass);
                currentTrainCount++;
                actions++;
                recordDepotAction(
                    depotTrain,
                    "DEPLOY line=" + lineId.value()
                        + " svc=" + serviceClass.name()
                        + " current=" + currentTrainCount
                        + "/" + targetTrainCount
                        + " recommended=" + recommendedTrainCount
                );
            }

            int excess = currentTrainCount - targetTrainCount;
            if (excess <= 0) {
                continue;
            }

            int alreadyAtDepot = unassignExcessTrainsAlreadyAtDepot(lineId, lineDepotHubs, trains, excess);
            if (alreadyAtDepot > 0) {
                excess -= alreadyAtDepot;
                actions += alreadyAtDepot;
            }
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

                DiscoveredPath pathToDepot = findPathToDepot(candidate, lineDepotHubs, reservedDepotStations);
                if (pathToDepot == null || pathToDepot.destination == null) {
                    recordDepotAction(candidate, "RECALL_SKIPPED line=" + lineId.value() + " reason=no_path_to_depot");
                    continue;
                }
                if (candidate.navigation.startNavigation(pathToDepot) < 0D) {
                    recordDepotAction(candidate, "RECALL_SKIPPED line=" + lineId.value() + " reason=start_navigation_failed");
                    continue;
                }

                recallInProgress.add(candidate.id);
                excess--;
                actions++;
                recordDepotAction(
                    candidate,
                    "RECALL line=" + lineId.value() + " destination=" + pathToDepot.destination.name
                );
            }
        }
        return actions;
    }

    private int unassignExcessTrainsAlreadyAtDepot(
        LineId lineId,
        List<StationHub> lineDepotHubs,
        List<Train> trains,
        int maxToUnassign
    ) {
        if (lineId == null || lineDepotHubs == null || lineDepotHubs.isEmpty() || trains == null || trains.isEmpty() || maxToUnassign <= 0) {
            return 0;
        }

        int unassigned = 0;
        for (Train train : trains) {
            if (unassigned >= maxToUnassign) {
                break;
            }
            if (train == null || train.derailed || train.runtime == null || train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }
            if (train.navigation.destination != null || recallInProgress.contains(train.id)) {
                continue;
            }

            Optional<LineId> assignedLine = lineRegistry.assignmentOf(train.id).map(assignment -> assignment.lineId());
            if (assignedLine.isEmpty() || !assignedLine.get().equals(lineId)) {
                continue;
            }

            GlobalStation currentStation = train.getCurrentStation();
            if (currentStation == null || !matchesAnyHub(currentStation, lineDepotHubs)) {
                continue;
            }

            lineRegistry.unassignTrain(train.id);
            unassigned++;
            recordDepotAction(train, "EXCESS_UNASSIGN_AT_DEPOT line=" + lineId.value() + " depot=" + currentStation.name);
        }

        return unassigned;
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

    private Train takeFirstAvailableDepotTrain(List<Train> availableDepotTrains, List<StationHub> allowedDepotHubs) {
        if (availableDepotTrains == null || availableDepotTrains.isEmpty()) {
            return null;
        }
        for (int i = 0; i < availableDepotTrains.size(); i++) {
            Train train = availableDepotTrains.get(i);
            if (train == null) {
                continue;
            }
            if (!matchesAnyHub(train.getCurrentStation(), allowedDepotHubs)) {
                continue;
            }
            availableDepotTrains.remove(i);
            return train;
        }
        return null;
    }

    private List<Train> collectRecallCandidatesForLine(LineId lineId, List<Train> trains) {
        List<Train> candidates = new ArrayList<>();
        for (Train train : trains) {
            if (train == null || train.derailed || train.graph == null) {
                continue;
            }
            Optional<LineId> assignedLine = lineRegistry.assignmentOf(train.id).map(assignment -> assignment.lineId());
            if (assignedLine.isEmpty() || !assignedLine.get().equals(lineId)) {
                continue;
            }
            if (train.runtime == null || train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
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
        return findPathToDepot(train, depotHubs, null);
    }

    private DiscoveredPath findPathToDepot(Train train, List<StationHub> depotHubs, Set<UUID> reservedDepotStations) {
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
                if (isDepotStationUnavailableFor(train, station, reservedDepotStations)) {
                    continue;
                }
                candidates.add(station);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        DiscoveredPath path = train.navigation.findPathTo(candidates, TrainSlothConfig.ROUTING.maxSearchCost.get());
        if (path != null && path.destination != null && reservedDepotStations != null) {
            reservedDepotStations.add(path.destination.id);
        }
        return path;
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

    private TrainServiceClass resolveServiceClassForLine(TrainLine line, LineId lineId) {
        if (line == null) {
            return dominantServiceClass(lineId);
        }

        return line.settings().resolveServiceClass();
    }

    private List<StationHub> resolveLineDepotHubs(TrainLine line, List<StationHub> depotHubs) {
        if (line == null || depotHubs == null || depotHubs.isEmpty()) {
            return List.of();
        }
        if (!line.settings().hasDepotHubRestrictions()) {
            return depotHubs;
        }

        List<StationHub> allowed = new ArrayList<>();
        for (StationHub hub : depotHubs) {
            if (line.settings().allowsDepotHubId(hub.id().value())) {
                allowed.add(hub);
            }
        }
        // Fallback: keep balancing active even if saved restriction ids no longer resolve.
        return allowed.isEmpty() ? depotHubs : allowed;
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

    private Set<UUID> collectReservedDepotStationIds(List<Train> trains, List<StationHub> depotHubs) {
        if (trains == null || trains.isEmpty() || depotHubs == null || depotHubs.isEmpty()) {
            return new HashSet<>();
        }

        Set<UUID> reserved = new HashSet<>();
        for (Train train : trains) {
            if (train == null) {
                continue;
            }
            GlobalStation destination = train.navigation.destination;
            if (destination != null && matchesAnyHub(destination, depotHubs)) {
                reserved.add(destination.id);
            }
        }
        return reserved;
    }

    private boolean isDepotStationUnavailableFor(Train self, GlobalStation station, Set<UUID> reservedDepotStations) {
        if (station == null) {
            return true;
        }
        if (reservedDepotStations != null && station.id != null && reservedDepotStations.contains(station.id)) {
            return true;
        }

        Train present = station.getPresentTrain();
        if (present != null && (self == null || !present.id.equals(self.id))) {
            return true;
        }

        Train imminent = station.getImminentTrain();
        return imminent != null && (self == null || !imminent.id.equals(self.id));
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

    private int routeUnassignedTrainsToDepot(List<Train> trains, List<StationHub> depotHubs, Set<UUID> reservedDepotStations) {
        if (depotHubs == null || depotHubs.isEmpty()) {
            return 0;
        }

        int routed = 0;
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

            DiscoveredPath pathToDepot = findPathToDepot(train, depotHubs, reservedDepotStations);
            if (pathToDepot == null || pathToDepot.destination == null) {
                continue;
            }
            if (train.navigation.startNavigation(pathToDepot) < 0D) {
                continue;
            }
            routed++;
            recordDepotAction(train, "UNASSIGNED_TO_DEPOT destination=" + pathToDepot.destination.name);
        }
        return routed;
    }
}
