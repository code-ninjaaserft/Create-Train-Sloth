package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class AlternativePathSelector {
    private static final TrainLine FALLBACK_LINE = new TrainLine(new LineId("fallback"), "Fallback");

    private final LineManager lineManager;
    private final RoutePreferenceResolver routePreferenceResolver;
    private final ReservationAwarenessService reservationAwarenessService;
    private final ScheduleAlternativeResolver scheduleAlternativeResolver;
    private final ScheduleDestinationResolver scheduleDestinationResolver;
    private final PlatformAssignmentService platformAssignmentService;
    private final InterlockingControlService interlockingControlService;
    private final DebugOverlay debugOverlay;
    private final Map<UUID, TrainRouteState> stateByTrain = new HashMap<>();

    public AlternativePathSelector(
        LineManager lineManager,
        RoutePreferenceResolver routePreferenceResolver,
        ReservationAwarenessService reservationAwarenessService,
        ScheduleAlternativeResolver scheduleAlternativeResolver,
        PlatformAssignmentService platformAssignmentService,
        InterlockingControlService interlockingControlService,
        DebugOverlay debugOverlay,
        StationHubRegistry stationHubRegistry
    ) {
        this.lineManager = lineManager;
        this.routePreferenceResolver = routePreferenceResolver;
        this.reservationAwarenessService = reservationAwarenessService;
        this.scheduleAlternativeResolver = scheduleAlternativeResolver;
        this.scheduleDestinationResolver = new ScheduleDestinationResolver(scheduleAlternativeResolver, stationHubRegistry);
        this.platformAssignmentService = platformAssignmentService;
        this.interlockingControlService = interlockingControlService;
        this.debugOverlay = debugOverlay;
    }

    public void preRailwayTick(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableAlternativeRouting.get()
            || !TrainSlothConfig.ROUTING.enablePreDepartureAlternativeSelection.get()
            || !TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            return;
        }
        if (!interlockingControlService.isOverrideActive(level)) {
            return;
        }

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            if (!isPreDepartureCandidate(train)) {
                continue;
            }

            Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = scheduleDestinationResolver.resolve(train);
            if (destinationContext.isEmpty()) {
                continue;
            }

            Optional<PlatformAssignmentService.PlannedPlatformAssignment> plannedAssignment =
                platformAssignmentService.assignmentForTrain(train.id);

            DepartureAlternative selection = selectDepartureAlternative(
                train,
                destinationContext.get(),
                plannedAssignment.orElse(null)
            );
            if (selection == null) {
                continue;
            }
            if (selection.entryIndex() != null && selection.entryIndex() == train.runtime.currentEntry) {
                continue;
            }

            if (!prepareScheduleForDestination(train, destinationContext.get(), selection.station())) {
                continue;
            }

            Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
            LineId debugLine = optionalLine.map(TrainLine::id).orElse(new LineId("unassigned"));

            String signature = "pre_departure|" + selection.station().name + "|reason=" + selection.reason();
            debugOverlay.recordRouteSwitch(train.id, debugLine, signature, -900);
            logVerbose(
                train,
                debugLine,
                "pre-departure selection -> station=" + selection.station().name
                    + " entry=" + (selection.entryIndex() == null ? "<override>" : selection.entryIndex())
                    + " reason=" + selection.reason()
            );
        }
    }

    public void postRailwayTick(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableAlternativeRouting.get()) {
            return;
        }
        if (!interlockingControlService.isOverrideActive(level)) {
            return;
        }

        long gameTick = level.getGameTime();
        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            TrainLine line = lineManager.lineForTrain(train).orElse(FALLBACK_LINE);
            Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = scheduleDestinationResolver.resolve(train);
            if (destinationContext.isEmpty()) {
                continue;
            }
            Optional<PlatformAssignmentService.PlannedPlatformAssignment> plannedAssignment =
                platformAssignmentService.assignmentForTrain(train.id);

            GlobalStation primaryDestination = destinationContext.get().primaryDestination();
            DiscoveredPath primaryPath = train.navigation.findPathTo(primaryDestination, -1);
            if (primaryPath == null) {
                continue;
            }

            if (!isRerouteCandidate(train, line, plannedAssignment, primaryDestination)) {
                continue;
            }

            int adaptiveReplanThreshold = resolveAdaptiveReplanThresholdTicks(train, line);
            boolean blocked = train.navigation.waitingForSignal != null
                && train.navigation.ticksWaitingForSignal >= adaptiveReplanThreshold;
            blocked |= reservationAwarenessService.isPrimaryPathBlocked(train, primaryPath);
            boolean primaryStationBlocked = isStationHardBlockedByOtherTrain(train, primaryDestination);
            blocked |= primaryStationBlocked;

            boolean proactiveAssignedSwitch = TrainSlothConfig.ROUTING.enableProactivePlatformPlanning.get()
                && plannedAssignment.isPresent()
                && !plannedAssignment.get().stationId().equals(primaryDestination.id)
                && train.navigation.distanceToDestination > 2D;

            if (!blocked && !proactiveAssignedSwitch) {
                continue;
            }

            List<DiscoveredPath> candidates = collectCandidates(train, destinationContext.get(), primaryPath);
            candidates = enforceScheduleAlternativeTargets(candidates, destinationContext.get());
            if (candidates.size() <= 1) {
                continue;
            }

            if (primaryStationBlocked) {
                DiscoveredPath forcedAlternative = selectBestNonPrimaryCandidate(train, candidates, primaryDestination.id);
                if (forcedAlternative != null) {
                    if (forcedAlternative.destination == null) {
                        continue;
                    }
                    if (!prepareScheduleForDestination(train, destinationContext.get(), forcedAlternative.destination)) {
                        continue;
                    }

                    double result = train.navigation.startNavigation(forcedAlternative);
                    if (result >= 0) {
                        String signature = pathSignature(forcedAlternative);
                        Integer alternativeEntry = destinationContext.get()
                            .alternativeEntryByStation()
                            .get(forcedAlternative.destination.id);
                        debugOverlay.recordRouteSwitch(train.id, line.id(), signature, -1200);
                        logVerbose(
                            train,
                            line,
                            "forced station-block reroute -> " + signature
                                + " altEntry=" + (alternativeEntry == null ? "-" : alternativeEntry)
                        );
                    }
                    continue;
                }
            }

            TrainRouteState routeState = stateByTrain.computeIfAbsent(train.id, ignored -> new TrainRouteState());
            if (proactiveAssignedSwitch && plannedAssignment.isPresent()) {
                DiscoveredPath forcedPath = selectPathForAssignedStation(candidates, plannedAssignment.get().stationId());
                if (forcedPath != null) {
                    if (forcedPath.destination == null) {
                        continue;
                    }
                    if (!prepareScheduleForDestination(train, destinationContext.get(), forcedPath.destination)) {
                        continue;
                    }

                    double result = train.navigation.startNavigation(forcedPath);
                    if (result >= 0) {
                        String signature = pathSignature(forcedPath);
                        Integer alternativeEntry = destinationContext.get()
                            .alternativeEntryByStation()
                            .get(forcedPath.destination.id);
                        routeState.currentSignature = signature;
                        routeState.lastSwitchTick = gameTick;
                        debugOverlay.recordRouteSwitch(train.id, line.id(), signature, -999);
                        logVerbose(
                            train,
                            line,
                            "platform-plan switch -> " + signature
                                + " assigned=" + plannedAssignment.get().stationName()
                                + " service=" + plannedAssignment.get().serviceClass().name()
                                + " altEntry=" + (alternativeEntry == null ? "-" : alternativeEntry)
                        );
                    }
                    continue;
                }
            }

            RoutePreferenceResolver.RouteResolution resolution = routePreferenceResolver.resolve(
                train,
                line,
                primaryPath,
                candidates,
                plannedAssignment.orElse(null),
                routeState,
                gameTick
            );

            if (!resolution.shouldSwitch() || resolution.selectedPath() == null) {
                continue;
            }

            if (resolution.selectedPath().destination == null) {
                continue;
            }
            if (!prepareScheduleForDestination(train, destinationContext.get(), resolution.selectedPath().destination)) {
                continue;
            }

            double result = train.navigation.startNavigation(resolution.selectedPath());
            if (result >= 0) {
                Integer alternativeEntry = destinationContext.get()
                    .alternativeEntryByStation()
                    .get(resolution.selectedPath().destination.id);
                routeState.currentSignature = resolution.signature();
                routeState.lastSwitchTick = gameTick;
                debugOverlay.recordRouteSwitch(train.id, line.id(), resolution.signature(), resolution.score());
                logVerbose(
                    train,
                    line,
                    "route switch -> " + resolution.signature() + " score=" + resolution.score()
                        + " candidates=" + candidates.size()
                        + " altEntry=" + (alternativeEntry == null ? "-" : alternativeEntry)
                        + " proactive=" + proactiveAssignedSwitch
                        + " filter=" + destinationContext.get().sourceFilter()
                );
            }
        }
    }

    private List<DiscoveredPath> collectCandidates(
        Train train,
        ScheduleDestinationResolver.DestinationContext destinationContext,
        DiscoveredPath primaryPath
    ) {
        int maxCandidates = Math.max(2, TrainSlothConfig.ROUTING.maxCandidatePaths.get());
        Map<String, DiscoveredPath> candidatesBySignature = new LinkedHashMap<>();

        addCandidate(candidatesBySignature, primaryPath);

        DiscoveredPath dynamicPrimary = train.navigation.findPathTo(
            destinationContext.primaryDestination(),
            TrainSlothConfig.ROUTING.maxSearchCost.get()
        );
        addCandidate(candidatesBySignature, dynamicPrimary);

        for (GlobalStation station : destinationContext.candidateStations()) {
            if (candidatesBySignature.size() >= maxCandidates) {
                break;
            }
            if (station.id.equals(destinationContext.primaryDestination().id)) {
                continue;
            }

            DiscoveredPath candidatePath = train.navigation.findPathTo(station, TrainSlothConfig.ROUTING.maxSearchCost.get());
            addCandidate(candidatesBySignature, candidatePath);
        }

        return List.copyOf(candidatesBySignature.values());
    }

    private List<DiscoveredPath> enforceScheduleAlternativeTargets(
        List<DiscoveredPath> candidates,
        ScheduleDestinationResolver.DestinationContext destinationContext
    ) {
        if (!TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            return candidates;
        }
        if (candidates.isEmpty() || destinationContext.alternativeEntryByStation().isEmpty()) {
            return candidates;
        }

        UUID primaryStation = destinationContext.primaryDestination().id;
        List<DiscoveredPath> filtered = new ArrayList<>();
        for (DiscoveredPath candidate : candidates) {
            if (candidate == null || candidate.destination == null) {
                continue;
            }
            UUID destinationId = candidate.destination.id;
            if (destinationId.equals(primaryStation) || destinationContext.alternativeEntryByStation().containsKey(destinationId)) {
                filtered.add(candidate);
            }
        }

        if (filtered.isEmpty()) {
            return candidates;
        }
        return List.copyOf(filtered);
    }

    private DiscoveredPath selectPathForAssignedStation(List<DiscoveredPath> candidates, UUID stationId) {
        if (stationId == null || candidates == null || candidates.isEmpty()) {
            return null;
        }

        DiscoveredPath best = null;
        for (DiscoveredPath candidate : candidates) {
            if (candidate == null || candidate.destination == null || !stationId.equals(candidate.destination.id)) {
                continue;
            }
            if (best == null || candidate.cost < best.cost) {
                best = candidate;
            }
        }

        return best;
    }

    private void addCandidate(Map<String, DiscoveredPath> candidatesBySignature, DiscoveredPath candidatePath) {
        if (candidatePath == null) {
            return;
        }
        String signature = pathSignature(candidatePath);
        candidatesBySignature.putIfAbsent(signature, candidatePath);
    }

    private boolean isRerouteCandidate(
        Train train,
        TrainLine line,
        Optional<PlatformAssignmentService.PlannedPlatformAssignment> plannedAssignment,
        GlobalStation primaryDestination
    ) {
        if (train.graph == null || train.derailed) {
            return false;
        }

        if (train.navigation.destination == null) {
            return false;
        }

        if (train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused) {
            return false;
        }

        if (isStationHardBlockedByOtherTrain(train, primaryDestination)) {
            return true;
        }

        int adaptiveThreshold = resolveAdaptiveReplanThresholdTicks(train, line);
        if (train.navigation.waitingForSignal != null
            && train.navigation.ticksWaitingForSignal >= adaptiveThreshold) {
            return true;
        }

        return TrainSlothConfig.ROUTING.enableProactivePlatformPlanning.get()
            && plannedAssignment.isPresent()
            && !plannedAssignment.get().stationId().equals(primaryDestination.id);
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

    private DepartureAlternative selectDepartureAlternative(
        Train train,
        ScheduleDestinationResolver.DestinationContext destinationContext,
        PlatformAssignmentService.PlannedPlatformAssignment plannedAssignment
    ) {
        GlobalStation primary = destinationContext.primaryDestination();
        Map<UUID, Integer> alternativeEntryByStation = destinationContext.alternativeEntryByStation();

        if (plannedAssignment != null) {
            UUID stationId = plannedAssignment.stationId();
            Integer entryIndex = alternativeEntryByStation.get(stationId);
            GlobalStation assignedStation = stationById(destinationContext, stationId);
            if (assignedStation != null
                && !stationId.equals(primary.id)
                && !isStationHardBlockedByOtherTrain(train, assignedStation)) {
                return new DepartureAlternative(assignedStation, entryIndex, Integer.MIN_VALUE / 4, "planned_assignment");
            }
        }

        DiscoveredPath primaryPath = train.navigation.findPathTo(primary, TrainSlothConfig.ROUTING.maxSearchCost.get());
        boolean primaryBlocked = primaryPath == null
            || isStationHardBlockedByOtherTrain(train, primary)
            || reservationAwarenessService.isPrimaryPathBlocked(train, primaryPath);

        if (!primaryBlocked) {
            return null;
        }

        DepartureAlternative best = null;
        for (GlobalStation station : destinationContext.candidateStations()) {
            if (station == null || station.id.equals(primary.id)) {
                continue;
            }

            DiscoveredPath candidatePath = train.navigation.findPathTo(station, TrainSlothConfig.ROUTING.maxSearchCost.get());
            if (candidatePath == null) {
                continue;
            }

            int score = scoreDepartureCandidate(train, station, candidatePath);
            if (best == null || score < best.score()) {
                best = new DepartureAlternative(station, alternativeEntryByStation.get(station.id), score, "primary_blocked");
            }
        }

        if (best == null) {
            return null;
        }

        return best;
    }

    private DiscoveredPath selectBestNonPrimaryCandidate(Train train, List<DiscoveredPath> candidates, UUID primaryStationId) {
        DiscoveredPath best = null;
        int bestScore = Integer.MAX_VALUE;

        for (DiscoveredPath candidate : candidates) {
            if (candidate == null || candidate.destination == null) {
                continue;
            }
            if (candidate.destination.id.equals(primaryStationId)) {
                continue;
            }
            if (isStationHardBlockedByOtherTrain(train, candidate.destination)) {
                continue;
            }

            int score = scoreDepartureCandidate(train, candidate.destination, candidate);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        return best;
    }

    private int scoreDepartureCandidate(Train train, GlobalStation station, DiscoveredPath path) {
        if (path == null) {
            return Integer.MAX_VALUE / 4;
        }

        int score = (int) Math.round(path.cost + Math.abs(path.distance));
        score += reservationAwarenessService.countOccupiedSignals(train, path) * 900;
        score += reservationAwarenessService.estimateConflictComplexity(path) * 80;
        if (isStationHardBlockedByOtherTrain(train, station)) {
            score += 2800;
        }
        return score;
    }

    private boolean isStationHardBlockedByOtherTrain(Train self, GlobalStation station) {
        if (station == null) {
            return true;
        }

        Train present = station.getPresentTrain();
        if (present != null && !present.id.equals(self.id)) {
            return true;
        }

        Train imminent = station.getImminentTrain();
        return imminent != null && !imminent.id.equals(self.id);
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
        for (GlobalStation station : destinationContext.candidateStations()) {
            if (station.id.equals(stationId)) {
                return station;
            }
        }
        return null;
    }

    private int resolveAdaptiveReplanThresholdTicks(Train train, TrainLine line) {
        int configured = Math.max(0, line.settings().resolveRouteReplanWaitTicks());
        double distance = Math.max(0D, train.navigation.distanceToDestination);
        if (distance <= 16D) {
            return Math.min(configured, 20);
        }
        if (distance <= 40D) {
            return Math.min(configured, 40);
        }
        return configured;
    }

    public static String pathSignature(DiscoveredPath path) {
        if (path == null) {
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

    private void logVerbose(Train train, TrainLine line, String detail) {
        logVerbose(train, line.id(), detail);
    }

    private void logVerbose(Train train, LineId lineId, String detail) {
        if (!TrainSlothConfig.DEBUG.verboseLogs.get()) {
            return;
        }
        CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth][Routing] train={} line={} {}", train.id, lineId, detail);
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

    private record DepartureAlternative(GlobalStation station, Integer entryIndex, int score, String reason) {
    }
}
