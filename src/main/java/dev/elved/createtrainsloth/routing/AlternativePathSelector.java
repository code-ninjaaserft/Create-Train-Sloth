package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.interlocking.StellwerkControlModeService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class AlternativePathSelector {

    private final LineManager lineManager;
    private final ScheduleAlternativeResolver scheduleAlternativeResolver;
    private final ScheduleDestinationResolver scheduleDestinationResolver;
    private final PlatformAssignmentService platformAssignmentService;
    private final StellwerkControlModeService stellwerkControlModeService;
    private final DebugOverlay debugOverlay;
    private final Map<UUID, TrainRouteState> stateByTrain = new HashMap<>();

    public AlternativePathSelector(
        LineManager lineManager,
        RoutePreferenceResolver routePreferenceResolver,
        ReservationAwarenessService reservationAwarenessService,
        ScheduleAlternativeResolver scheduleAlternativeResolver,
        PlatformAssignmentService platformAssignmentService,
        InterlockingControlService interlockingControlService,
        StellwerkControlModeService stellwerkControlModeService,
        DebugOverlay debugOverlay,
        StationHubRegistry stationHubRegistry
    ) {
        this.lineManager = lineManager;
        this.scheduleAlternativeResolver = scheduleAlternativeResolver;
        this.scheduleDestinationResolver = new ScheduleDestinationResolver(scheduleAlternativeResolver, stationHubRegistry, lineManager);
        this.platformAssignmentService = platformAssignmentService;
        this.stellwerkControlModeService = stellwerkControlModeService;
        this.debugOverlay = debugOverlay;
    }

    public void preRailwayTick(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableAlternativeRouting.get()
            || !TrainSlothConfig.ROUTING.enablePreDepartureAlternativeSelection.get()
            || !TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            return;
        }
        if (trains == null || trains.isEmpty()) {
            return;
        }

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            if (train == null || !stellwerkControlModeService.isStellwerkEnabled(train.id)) {
                continue;
            }
            if (!isPreDepartureCandidate(train)) {
                continue;
            }

            Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = scheduleDestinationResolver.resolve(train);
            if (destinationContext.isEmpty()) {
                continue;
            }

            Optional<PlatformAssignmentService.PlannedPlatformAssignment> plannedAssignment =
                platformAssignmentService.assignmentForTrain(train.id);
            if (plannedAssignment.isEmpty()) {
                continue;
            }

            GlobalStation assignedStation = stationById(destinationContext.get(), plannedAssignment.get().stationId());
            if (assignedStation == null || assignedStation.id.equals(destinationContext.get().primaryDestination().id)) {
                continue;
            }

            if (!prepareScheduleForDestination(train, destinationContext.get(), assignedStation)) {
                continue;
            }

            stateByTrain.computeIfAbsent(train.id, ignored -> new TrainRouteState()).currentSignature = assignedStation.id.toString();
            if (debugOverlay != null) {
                LineId lineId = lineManager.lineForTrain(train).map(TrainLine::id).orElse(new LineId("unassigned"));
                debugOverlay.recordRouteSwitch(
                    train.id,
                    lineId,
                    "pre_departure|" + assignedStation.name + "|reason=planned_assignment",
                    -900
                );
            }
        }
    }

    public void postRailwayTick(Level level, List<Train> trains) {
        if (trains == null || trains.isEmpty()) {
            stateByTrain.clear();
            return;
        }

        Set<UUID> activeTrainIds = new HashSet<>();
        for (Train train : trains) {
            if (train != null) {
                activeTrainIds.add(train.id);
            }
        }
        stateByTrain.keySet().removeIf(trainId -> !activeTrainIds.contains(trainId));
    }

    private boolean isPreDepartureCandidate(Train train) {
        if (train.graph == null || train.derailed) {
            return false;
        }
        if (train.navigation.destination != null) {
            return false;
        }
        if (train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused) {
            return false;
        }
        if (train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
            return false;
        }
        return train.getCurrentStation() != null;
    }

    private boolean prepareScheduleForDestination(
        Train train,
        ScheduleDestinationResolver.DestinationContext destinationContext,
        GlobalStation targetStation
    ) {
        if (train == null || destinationContext == null || targetStation == null || targetStation.id == null) {
            return false;
        }

        Integer alternativeEntry = destinationContext.alternativeEntryByStation().get(targetStation.id);
        if (alternativeEntry != null) {
            scheduleAlternativeResolver.restoreMainDestinationOverrideNow(train);
            return scheduleAlternativeResolver.activateAlternativeEntry(train, alternativeEntry);
        }

        if (targetStation.id.equals(destinationContext.primaryDestination().id)) {
            scheduleAlternativeResolver.restoreMainDestinationOverrideNow(train);
            return true;
        }

        if (targetStation.name == null || targetStation.name.isBlank()) {
            return false;
        }

        return scheduleAlternativeResolver.activateMainDestinationOverride(train, targetStation.name);
    }

    private GlobalStation stationById(ScheduleDestinationResolver.DestinationContext destinationContext, UUID stationId) {
        if (destinationContext == null || stationId == null) {
            return null;
        }
        for (GlobalStation station : destinationContext.candidateStations()) {
            if (station.id.equals(stationId)) {
                return station;
            }
        }
        return null;
    }

    public static String pathSignature(DiscoveredPath path) {
        if (path == null || path.destination == null) {
            return "none";
        }
        return path.destination.id
            + "|"
            + (path.distance < 0 ? 'B' : 'F')
            + "|branches="
            + path.path.size()
            + "|d="
            + Mth.floor(path.distance)
            + "|c="
            + Mth.floor(path.cost);
    }

    public static class TrainRouteState {
        private String currentSignature;
        private long lastSwitchTick = Long.MIN_VALUE;

        public String currentSignature() {
            return currentSignature;
        }

        public long lastSwitchTick() {
            return lastSwitchTick;
        }
    }
}
