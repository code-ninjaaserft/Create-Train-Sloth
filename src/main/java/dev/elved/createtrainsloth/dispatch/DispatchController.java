package dev.elved.createtrainsloth.dispatch;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LineRuntimeState;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.Level;

public class DispatchController {

    private static final int DISPATCH_TOKEN_DURATION = 40;

    private final LineManager lineManager;
    private final StationStateTracker stationStateTracker;
    private final HeadwayCalculator headwayCalculator;
    private final DebugOverlay debugOverlay;

    public DispatchController(
        LineManager lineManager,
        StationStateTracker stationStateTracker,
        HeadwayCalculator headwayCalculator,
        DebugOverlay debugOverlay
    ) {
        this.lineManager = lineManager;
        this.stationStateTracker = stationStateTracker;
        this.headwayCalculator = headwayCalculator;
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
            int headwayTicks = headwayCalculator.calculateTargetHeadwayTicks(line, runtimeState, assignedTrainCount);

            boolean releasedThisTick = false;
            for (Train train : readyTrains) {
                if (runtimeState.hasPendingDispatch(gameTick)) {
                    if (!train.id.equals(runtimeState.pendingDispatchTrain())) {
                        hold(train, lineId, "pending dispatch token");
                        continue;
                    }
                }

                DispatchDecision decision = evaluateDispatch(train, line, runtimeState, headwayTicks, gameTick);
                if (decision.hold()) {
                    hold(train, lineId, decision.reason());
                    continue;
                }

                if (!releasedThisTick && !runtimeState.hasPendingDispatch(gameTick)) {
                    runtimeState.setPendingDispatch(train.id, gameTick + DISPATCH_TOKEN_DURATION);
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
                if (line.stationNames().isEmpty() || line.stationNames().contains(transition.arrivedStationName().toLowerCase(Locale.ROOT))) {
                    runtimeState.recordArrival(train.id, transition.arrivedStationId(), gameTick);
                }
            }

            if (transition.departedStationId() != null && transition.departedStationName() != null) {
                if (line.stationNames().isEmpty() || line.stationNames().contains(transition.departedStationName().toLowerCase(Locale.ROOT))) {
                    runtimeState.recordDeparture(train.id, transition.departedStationId(), gameTick);
                    runtimeState.clearPendingDispatchIf(train.id);
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

    private DispatchDecision evaluateDispatch(Train train, TrainLine line, LineRuntimeState runtimeState, int headwayTicks, long gameTick) {
        long arrivedAt = stationStateTracker.arrivalTick(train.id);
        if (arrivedAt == Long.MIN_VALUE) {
            arrivedAt = gameTick;
        }

        int minimumDwell = line.settings().resolveMinimumDwellTicks() + line.settings().resolveDwellExtensionTicks();
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
            if (elapsed < requiredGap) {
                return DispatchDecision.hold("headway " + elapsed + "/" + requiredGap);
            }
        }

        return DispatchDecision.allow();
    }

    private void hold(Train train, LineId lineId, String reason) {
        train.runtime.startCooldown();
        debugOverlay.recordHold(train.id, lineId, reason);
        logVerbose("Hold", train, lineId, reason);
    }

    private void logVerbose(String action, Train train, LineId lineId, String detail) {
        if (!TrainSlothConfig.DEBUG.verboseLogs.get()) {
            return;
        }
        CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth][Dispatch][{}] train={} line={} {}", action, train.id, lineId, detail);
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
