package dev.elved.createtrainsloth.dispatch;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LineRuntimeState;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.routing.PlatformAssignmentService;
import dev.elved.createtrainsloth.routing.TrainRoutingResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.Level;

public class DispatchController {

    private static final int DISPATCH_TOKEN_DURATION = 40;
    private static final int ENFORCED_MINIMUM_DWELL_TICKS = 220;

    private final LineManager lineManager;
    private final StationStateTracker stationStateTracker;
    private final HeadwayCalculator headwayCalculator;
    private final PlatformAssignmentService platformAssignmentService;
    private final DebugOverlay debugOverlay;
    private final Map<UUID, Integer> consecutiveHoldByTrain = new HashMap<>();

    public DispatchController(
        LineManager lineManager,
        StationStateTracker stationStateTracker,
        HeadwayCalculator headwayCalculator,
        PlatformAssignmentService platformAssignmentService,
        DebugOverlay debugOverlay
    ) {
        this.lineManager = lineManager;
        this.stationStateTracker = stationStateTracker;
        this.headwayCalculator = headwayCalculator;
        this.platformAssignmentService = platformAssignmentService;
        this.debugOverlay = debugOverlay;
    }

    public void preRailwayTick(Level level, Collection<Train> trains) {
        if (!TrainSlothConfig.DISPATCH.enableAutomaticDispatch.get()) {
            return;
        }

        long gameTick = level.getGameTime();
        Map<LineId, List<Train>> readyTrainsByLine = collectDispatchReadyTrains(trains);

        for (Map.Entry<LineId, List<Train>> entry : readyTrainsByLine.entrySet()) {
            LineId lineId = entry.getKey();
            List<Train> readyTrains = entry.getValue();
            Optional<TrainLine> optionalLine = lineManager.findLine(lineId);
            if (optionalLine.isEmpty()) {
                continue;
            }

            TrainLine line = optionalLine.get();
            LineRuntimeState runtimeState = lineManager.runtimeState(lineId);
            int assignedTrainCount = lineManager.countAssignedTrains(lineId);
            List<Train> activeLineTrains = lineManager.collectAssignedTrains(lineId, trains);
            int headwayTicks = headwayCalculator.calculateTargetHeadwayTicks(line, runtimeState, assignedTrainCount, activeLineTrains);

            boolean releasedThisTick = false;
            for (Train train : readyTrains) {
                if (runtimeState.hasPendingDispatch(gameTick)) {
                    if (!train.id.equals(runtimeState.pendingDispatchTrain())) {
                        hold(train, lineId, "pending dispatch token");
                        continue;
                    }
                }

                TrainServiceClass serviceClass = lineManager.serviceClassForTrain(train.id);
                DispatchDecision decision = evaluateDispatch(
                    train,
                    line,
                    runtimeState,
                    headwayTicks,
                    gameTick,
                    serviceClass
                );
                if (decision.hold()) {
                    hold(train, lineId, decision.reason());
                    continue;
                }

                if (!releasedThisTick && !runtimeState.hasPendingDispatch(gameTick)) {
                    if (needsStellwerkDirectedDeparture(train) && !prepareDeparturePath(level, train, line)) {
                        hold(train, lineId, "no stellwerk path");
                        continue;
                    }
                    runtimeState.setPendingDispatch(train.id, gameTick + DISPATCH_TOKEN_DURATION);
                    consecutiveHoldByTrain.remove(train.id);
                    releasedThisTick = true;
                    debugOverlay.recordRelease(train.id, lineId, gameTick, headwayTicks);
                    logVerbose("Release", train, lineId, "headway=" + headwayTicks);
                } else if (!train.id.equals(runtimeState.pendingDispatchTrain())) {
                    hold(train, lineId, "one train released this tick");
                }
            }
        }
    }

    public void postRailwayTick(Level level, Collection<Train> trains) {
        long gameTick = level.getGameTime();

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
            if (optionalLine.isEmpty()) {
                continue;
            }

            TrainLine line = optionalLine.get();
            LineRuntimeState runtimeState = lineManager.runtimeState(line.id());
            StationStateTracker.StationTransition transition = stationStateTracker.observe(train, gameTick);

            if (transition.arrivedStationId() != null && transition.arrivedStationName() != null) {
                if (matchesLineStation(line, transition.arrivedStationName())) {
                    runtimeState.recordArrival(train.id, transition.arrivedStationId(), gameTick);
                }
            }

            if (transition.departedStationId() != null && transition.departedStationName() != null) {
                if (matchesLineStation(line, transition.departedStationName())) {
                    runtimeState.recordDeparture(train.id, transition.departedStationId(), gameTick);
                    runtimeState.clearPendingDispatchIf(train.id);
                    consecutiveHoldByTrain.remove(train.id);
                    logVerbose("Depart", train, line.id(), transition.departedStationName());
                }
            }
        }
    }

    private Map<LineId, List<Train>> collectDispatchReadyTrains(Collection<Train> trains) {
        List<Train> sortedTrains = new ArrayList<>(trains);
        sortedTrains.sort(Comparator.comparing(train -> train.id.toString()));

        Map<LineId, List<Train>> readyByLine = new LinkedHashMap<>();
        for (Train train : sortedTrains) {
            Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
            if (optionalLine.isEmpty()) {
                continue;
            }

            TrainLine line = optionalLine.get();
            if (!isDispatchCandidate(train, line)) {
                continue;
            }

            readyByLine.computeIfAbsent(line.id(), ignored -> new ArrayList<>()).add(train);
        }

        return readyByLine;
    }

    private boolean isDispatchCandidate(Train train, TrainLine line) {
        if (train.derailed || train.graph == null) {
            return false;
        }

        if (train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused) {
            return false;
        }

        if (train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
            return false;
        }

        if (train.navigation.destination != null) {
            return false;
        }

        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation == null) {
            return false;
        }

        return lineManager.isStationOnLine(line, currentStation);
    }

    private DispatchDecision evaluateDispatch(
        Train train,
        TrainLine line,
        LineRuntimeState runtimeState,
        int headwayTicks,
        long gameTick,
        TrainServiceClass serviceClass
    ) {
        long arrivedAt = stationStateTracker.arrivalTick(train.id);
        if (arrivedAt == Long.MIN_VALUE) {
            arrivedAt = gameTick;
        }

        int minimumDwell = line.settings().resolveMinimumDwellTicks() + line.settings().resolveDwellExtensionTicks();
        minimumDwell = Math.max(ENFORCED_MINIMUM_DWELL_TICKS, minimumDwell);
        long dwellElapsed = Math.max(0, gameTick - arrivedAt);
        if (dwellElapsed < minimumDwell) {
            return DispatchDecision.hold("minimum dwell " + dwellElapsed + "/" + minimumDwell);
        }

        long lastDeparture = runtimeState.lastDepartureTick();
        if (lastDeparture != Long.MIN_VALUE) {
            long elapsed = gameTick - lastDeparture;
            double aggressiveness = line.settings().resolveResynchronizationAggressiveness();
            long lateness = gameTick - (lastDeparture + headwayTicks);
            int reduction = 0;
            if (lateness > 0 && aggressiveness > 0D) {
                reduction = (int) Math.min(headwayTicks / 2D, lateness * aggressiveness);
            }

            int requiredGap = Math.max(line.settings().resolveMinimumIntervalTicks(), headwayTicks - reduction);
            int priorityAdjustment = priorityGapAdjustment(serviceClass);
            int fairnessAdjustment = fairnessGapAdjustment(train.id);
            int minimumFloor = Math.max(20, line.settings().resolveMinimumIntervalTicks() / 2);
            int weightedRequiredGap = Math.max(minimumFloor, requiredGap - priorityAdjustment - fairnessAdjustment);
            if (elapsed < weightedRequiredGap) {
                return DispatchDecision.hold(
                    "headway "
                        + elapsed + "/" + weightedRequiredGap
                        + " service=" + serviceClass.name()
                        + " priorityAdj=" + priorityAdjustment
                        + " fairnessAdj=" + fairnessAdjustment
                );
            }
        }

        return DispatchDecision.allow();
    }

    private void hold(Train train, LineId lineId, String reason) {
        train.runtime.startCooldown();
        int holdStreak = consecutiveHoldByTrain.merge(train.id, 1, Integer::sum);
        debugOverlay.recordHold(train.id, lineId, reason + " holdStreak=" + holdStreak);
        logVerbose("Hold", train, lineId, reason);
    }

    private int priorityGapAdjustment(TrainServiceClass serviceClass) {
        TrainServiceClass resolved = serviceClass == null ? TrainServiceClass.RE : serviceClass;
        int delta = Math.max(0, resolved.priorityWeight() - TrainServiceClass.RE.priorityWeight());
        return Math.min(120, delta * 2);
    }

    private int fairnessGapAdjustment(UUID trainId) {
        int holdStreak = consecutiveHoldByTrain.getOrDefault(trainId, 0);
        return Math.min(220, holdStreak * 20);
    }

    private void logVerbose(String action, Train train, LineId lineId, String detail) {
        if (!TrainSlothConfig.DEBUG.verboseLogs.get()) {
            return;
        }
        CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth][Dispatch][{}] train={} line={} {}", action, train.id, lineId, detail);
    }

    private boolean matchesLineStation(TrainLine line, String stationName) {
        return line.matchesStationName(stationName);
    }

    private boolean needsStellwerkDirectedDeparture(Train train) {
        if (train.runtime == null) {
            return true;
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return true;
        }

        int entryIndex = train.runtime.currentEntry;
        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return true;
        }

        return !(schedule.entries.get(entryIndex).instruction instanceof DestinationInstruction);
    }

    private boolean prepareDeparturePath(Level level, Train train, TrainLine line) {
        if (train.graph == null) {
            return false;
        }

        GlobalStation currentStation = train.getCurrentStation();
        DiscoveredPath selectedPath = null;

        if (CreateTrainSlothMod.runtime().routingAuthorityService() != null) {
            TrainRoutingResponse routerResponse = CreateTrainSlothMod.runtime()
                .routingAuthorityService()
                .requestRouteForCurrentSchedule(level, train);
            if (routerResponse.successful()) {
                selectedPath = routerResponse.path();
            }
        }

        if (selectedPath == null && platformAssignmentService != null) {
            selectedPath = platformAssignmentService.assignmentForTrain(train.id)
                .map(assignment -> resolveStationById(train, assignment.stationId()))
                .filter(station -> station != null && (currentStation == null || !station.id.equals(currentStation.id)))
                .map(station -> train.navigation.findPathTo(station, TrainSlothConfig.ROUTING.maxSearchCost.get()))
                .orElse(null);
        }

        if (selectedPath == null) {
            List<GlobalStation> targets = new ArrayList<>();
            for (GlobalStation station : train.graph.getPoints(EdgePointType.STATION)) {
                if (!lineManager.isStationOnLine(line, station)) {
                    continue;
                }
                if (currentStation != null && station.id.equals(currentStation.id)) {
                    continue;
                }
                targets.add(station);
            }

            if (targets.isEmpty() && currentStation != null && lineManager.isStationOnLine(line, currentStation)) {
                targets.add(currentStation);
            }
            if (targets.isEmpty()) {
                return false;
            }

            selectedPath = train.navigation.findPathTo(new ArrayList<>(targets), TrainSlothConfig.ROUTING.maxSearchCost.get());
        }

        if (selectedPath == null || selectedPath.destination == null) {
            return false;
        }

        return train.navigation.startNavigation(selectedPath) >= 0D;
    }

    private GlobalStation resolveStationById(Train train, java.util.UUID stationId) {
        if (train == null || train.graph == null || stationId == null) {
            return null;
        }
        for (GlobalStation station : train.graph.getPoints(EdgePointType.STATION)) {
            if (stationId.equals(station.id)) {
                return station;
            }
        }
        return null;
    }

    private record DispatchDecision(boolean hold, String reason) {

        static DispatchDecision hold(String reason) {
            return new DispatchDecision(true, reason);
        }

        static DispatchDecision allow() {
            return new DispatchDecision(false, "");
        }
    }
}
