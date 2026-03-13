package dev.elved.createtrainsloth.routing;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

public class ReservationAwarenessService {

    private static final int UNKNOWN_STATION_RELEASE_FALLBACK_TICKS = 20 * 30;
    private static final int UNKNOWN_CONDITION_WAIT_FALLBACK_TICKS = 20 * 15;
    private static final int DERAIL_STATION_RELEASE_TICKS = 20 * 120;
    private static final int APPROACH_CLAIM_MAX_ETA_TICKS = 20 * 60 * 6;

    public boolean isPrimaryPathBlocked(Train train, DiscoveredPath path) {
        if (path == null) {
            return true;
        }
        return countOccupiedSignals(train, path) > 0;
    }

    public int countOccupiedSignals(Train train, DiscoveredPath path) {
        if (path == null || train.graph == null) {
            return 0;
        }

        int occupiedSignals = 0;
        if (train.navigation.waitingForSignal != null && train.navigation.waitingForSignal.getFirst() != null) {
            SignalBoundary signal = train.graph.getPoint(EdgePointType.SIGNAL, train.navigation.waitingForSignal.getFirst());
            if (signal != null && isSignalOccupied(signal)) {
                occupiedSignals++;
            }
        }

        if (!train.reservedSignalBlocks.isEmpty()) {
            occupiedSignals++;
        }

        return occupiedSignals;
    }

    public int estimateConflictComplexity(DiscoveredPath path) {
        if (path == null) {
            return Integer.MAX_VALUE / 8;
        }
        // With current public API we cannot reliably inspect each hidden branch edge.
        // We use path branch-count as a stable conflict proxy until Create adds richer hooks.
        return path.path.size();
    }

    public int estimateStationReleaseTicks(Train requester, GlobalStation station) {
        if (station == null) {
            return Integer.MAX_VALUE / 8;
        }

        Train present = station.getPresentTrain();
        if (present != null && (requester == null || !present.id.equals(requester.id))) {
            return Math.max(1, estimateTicksUntilTrainClearsStation(present, station));
        }

        Train imminent = station.getImminentTrain();
        if (imminent != null && (requester == null || !imminent.id.equals(requester.id))) {
            int eta = estimateArrivalTicksToStation(imminent, station);
            int dwell = estimateMinimumStationOccupancyTicks();
            return Math.max(1, eta + dwell);
        }

        int approachingClaim = estimateApproachingClaimTicks(requester, station);
        if (approachingClaim > 0) {
            return approachingClaim;
        }

        return 0;
    }

    private int estimateApproachingClaimTicks(Train requester, GlobalStation station) {
        if (station == null || station.id == null || Create.RAILWAYS == null || Create.RAILWAYS.trains == null) {
            return 0;
        }

        int bestReleaseTicks = Integer.MAX_VALUE;
        for (Train candidate : Create.RAILWAYS.trains.values()) {
            if (candidate == null) {
                continue;
            }
            if (requester != null && requester.id.equals(candidate.id)) {
                continue;
            }
            if (candidate.derailed || candidate.runtime == null || candidate.runtime.paused) {
                continue;
            }

            GlobalStation destination = candidate.navigation.destination;
            if (destination == null || destination.id == null || !destination.id.equals(station.id)) {
                continue;
            }

            int eta = estimateArrivalTicksToStation(candidate, station);
            if (eta <= 0 || eta > APPROACH_CLAIM_MAX_ETA_TICKS) {
                continue;
            }

            int release = eta + estimateMinimumStationOccupancyTicks();
            if (release < bestReleaseTicks) {
                bestReleaseTicks = release;
            }
        }

        return bestReleaseTicks == Integer.MAX_VALUE ? 0 : Math.max(1, bestReleaseTicks);
    }

    private int estimateTicksUntilTrainClearsStation(Train occupyingTrain, GlobalStation station) {
        if (occupyingTrain == null || station == null) {
            return UNKNOWN_STATION_RELEASE_FALLBACK_TICKS;
        }

        if (occupyingTrain.derailed) {
            return DERAIL_STATION_RELEASE_TICKS;
        }

        GlobalStation currentStation = occupyingTrain.getCurrentStation();
        boolean currentlyAtStation = currentStation != null && currentStation.id.equals(station.id);
        if (!currentlyAtStation) {
            return estimateArrivalTicksToStation(occupyingTrain, station) + estimateMinimumStationOccupancyTicks();
        }

        if (occupyingTrain.runtime == null || occupyingTrain.runtime.getSchedule() == null || occupyingTrain.runtime.paused) {
            return UNKNOWN_STATION_RELEASE_FALLBACK_TICKS;
        }

        ScheduleRuntime runtime = occupyingTrain.runtime;
        if (runtime.state == ScheduleRuntime.State.POST_TRANSIT) {
            int remaining = estimateRemainingWaitConditionTicks(runtime);
            return Math.max(5, remaining + 5);
        }

        if (runtime.state == ScheduleRuntime.State.PRE_TRANSIT) {
            return 20;
        }

        return 40;
    }

    private int estimateArrivalTicksToStation(Train train, GlobalStation station) {
        if (train == null || station == null) {
            return UNKNOWN_STATION_RELEASE_FALLBACK_TICKS;
        }

        if (train.navigation.destination != null && train.navigation.destination.id.equals(station.id)) {
            double speed = Math.abs(train.speed);
            if (speed >= 0.01D && train.navigation.distanceToDestination > 0D) {
                int eta = (int) Math.round(train.navigation.distanceToDestination / Math.max(0.02D, speed) * 2D);
                return Mth.clamp(eta, 20, 20 * 60 * 30);
            }
        }

        if (train.runtime != null && train.runtime.predictionTicks != null && !train.runtime.predictionTicks.isEmpty()) {
            int index = train.runtime.currentEntry;
            if (index >= 0 && index < train.runtime.predictionTicks.size()) {
                int predicted = train.runtime.predictionTicks.get(index);
                if (predicted > 0) {
                    return predicted;
                }
            }
        }

        return UNKNOWN_STATION_RELEASE_FALLBACK_TICKS;
    }

    private int estimateMinimumStationOccupancyTicks() {
        int configured = TrainSlothConfig.DISPATCH.minimumDwellTicks.get() + TrainSlothConfig.DISPATCH.dwellExtensionTicks.get();
        return Math.max(20 * 8, configured);
    }

    private int estimateRemainingWaitConditionTicks(ScheduleRuntime runtime) {
        Schedule schedule = runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return 0;
        }

        int entryIndex = runtime.currentEntry;
        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return 0;
        }

        ScheduleEntry entry = schedule.entries.get(entryIndex);
        if (entry == null || entry.conditions == null || entry.conditions.isEmpty() || !entry.instruction.supportsConditions()) {
            return 0;
        }

        int best = Integer.MAX_VALUE;
        for (int i = 0; i < entry.conditions.size(); i++) {
            List<ScheduleWaitCondition> column = entry.conditions.get(i);
            int progress = runtime.conditionProgress != null && i < runtime.conditionProgress.size()
                ? runtime.conditionProgress.get(i)
                : 0;

            if (progress >= column.size()) {
                return 0;
            }

            CompoundTag columnContext = runtime.conditionContext != null && i < runtime.conditionContext.size()
                ? runtime.conditionContext.get(i)
                : new CompoundTag();

            int columnRemaining = 0;
            for (int j = progress; j < column.size(); j++) {
                ScheduleWaitCondition condition = column.get(j);
                CompoundTag conditionContext = j == progress ? columnContext : new CompoundTag();
                columnRemaining += estimateConditionTicks(condition, conditionContext);
            }
            best = Math.min(best, columnRemaining);
        }

        if (best == Integer.MAX_VALUE) {
            return UNKNOWN_CONDITION_WAIT_FALLBACK_TICKS;
        }
        return Math.max(0, best);
    }

    private int estimateConditionTicks(ScheduleWaitCondition condition, CompoundTag context) {
        if (condition instanceof TimedWaitCondition timedWaitCondition) {
            int totalTicks = timedWaitCondition.totalWaitTicks();
            int elapsedTicks = context == null ? 0 : Math.max(0, context.getInt("Time"));
            return Math.max(0, totalTicks - elapsedTicks);
        }

        return UNKNOWN_CONDITION_WAIT_FALLBACK_TICKS;
    }

    private boolean isSignalOccupied(SignalBoundary signal) {
        UUID primary = signal.groups.getFirst();
        UUID secondary = signal.groups.getSecond();
        return isGroupOccupied(primary) || isGroupOccupied(secondary);
    }

    private boolean isGroupOccupied(UUID groupId) {
        if (groupId == null) {
            return false;
        }
        SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
        return group != null && group.isOccupiedUnless((SignalBoundary) null);
    }
}
