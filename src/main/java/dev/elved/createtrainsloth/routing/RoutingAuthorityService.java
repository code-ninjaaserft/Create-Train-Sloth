package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.dispatch.DispatchController;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.interlocking.schematic.SectionIdHelper;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.ScheduleLineSyncService;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.data.Couple;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class RoutingAuthorityService {

    private static final String STAGE_ROUTER_CALLED = "ROUTER_CALLED";
    private static final String STAGE_RESPONSE_BUILT = "RESPONSE_BUILT";
    private static final long MISSION_HEALTHCHECK_INTERVAL_TICKS = 20L * 5L;
    private static final int DEFAULT_ARRIVAL_ESTIMATE_TICKS = 20 * 30;
    private static final int MIN_ARRIVAL_ESTIMATE_TICKS = 20;
    private static final int MAX_ARRIVAL_ESTIMATE_TICKS = 20 * 60 * 30;

    private final LineManager lineManager;
    private final ScheduleLineSyncService scheduleLineSyncService;
    private final DispatchController dispatchController;
    private final AlternativePathSelector alternativePathSelector;
    private final PlatformAssignmentService platformAssignmentService;
    private final ReservationAwarenessService reservationAwarenessService;
    private final ScheduleAlternativeResolver scheduleAlternativeResolver;
    private final ScheduleDestinationResolver scheduleDestinationResolver;
    private final InterlockingControlService interlockingControlService;
    private final DepotRuntimeService depotRuntimeService;
    private final TrainMissionService trainMissionService;
    private final DebugOverlay debugOverlay;
    private long requestSequence = 0L;
    private long lastMissionHealthcheckTick = Long.MIN_VALUE;

    public RoutingAuthorityService(
        LineManager lineManager,
        ScheduleLineSyncService scheduleLineSyncService,
        DispatchController dispatchController,
        AlternativePathSelector alternativePathSelector,
        PlatformAssignmentService platformAssignmentService,
        ReservationAwarenessService reservationAwarenessService,
        ScheduleAlternativeResolver scheduleAlternativeResolver,
        StationHubRegistry stationHubRegistry,
        InterlockingControlService interlockingControlService,
        DepotRuntimeService depotRuntimeService,
        TrainMissionService trainMissionService,
        DebugOverlay debugOverlay
    ) {
        this.lineManager = lineManager;
        this.scheduleLineSyncService = scheduleLineSyncService;
        this.dispatchController = dispatchController;
        this.alternativePathSelector = alternativePathSelector;
        this.platformAssignmentService = platformAssignmentService;
        this.reservationAwarenessService = reservationAwarenessService;
        this.scheduleAlternativeResolver = scheduleAlternativeResolver;
        this.scheduleDestinationResolver = new ScheduleDestinationResolver(
            scheduleAlternativeResolver,
            stationHubRegistry,
            lineManager
        );
        this.interlockingControlService = interlockingControlService;
        this.depotRuntimeService = depotRuntimeService;
        this.trainMissionService = trainMissionService;
        this.debugOverlay = debugOverlay;
    }

    public void onLevelTickStart(Level level, List<Train> trains) {
        interlockingControlService.captureTick(level, trains);
        scheduleAlternativeResolver.restoreMainDestinationOverridesAfterArrival(trains);
        scheduleAlternativeResolver.rebindMainConditionsAfterAlternativeArrival(trains);
        scheduleAlternativeResolver.advancePastAlternativeEntries(trains);
        platformAssignmentService.plan(level, trains);
        alternativePathSelector.preRailwayTick(level, trains);
    }

    public void onLevelTickMid(Level level, List<Train> trains) {
        scheduleLineSyncService.syncFromSchedules(trains);
        if (trainMissionService != null) {
            trainMissionService.tick(trains, lineManager::lineForTrain, debugOverlay);
        }
        runMissionHealthcheck(level, trains);
        dispatchController.preRailwayTick(level, trains);
    }

    public void onLevelTickEnd(Level level, List<Train> trains) {
        dispatchController.postRailwayTick(level, trains);
        alternativePathSelector.postRailwayTick(level, trains);
        if (depotRuntimeService != null) {
            depotRuntimeService.tick(level, trains);
        }
    }

    public int triggerManualMissionPing(Level level, List<Train> trains) {
        if (level == null || trains == null || trains.isEmpty()) {
            return 0;
        }

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));
        if (trainMissionService != null) {
            trainMissionService.tick(ordered, lineManager::lineForTrain, debugOverlay);
        }

        int started = 0;
        for (Train train : ordered) {
            if (train == null || train.derailed || train.graph == null) {
                continue;
            }
            if (train.runtime == null || train.runtime.paused) {
                continue;
            }
            if (isRecallInProgress(train.id)) {
                continue;
            }
            if (lineManager.lineForTrain(train).isEmpty()) {
                continue;
            }
            if (train.navigation.destination != null) {
                continue;
            }

            TrainRoutingResponse response = requestRouteForCurrentSchedule(level, train);
            if (!response.successful() || response.path() == null) {
                continue;
            }

            double result = train.navigation.startNavigation(response.path());
            if (result < 0D) {
                continue;
            }

            started++;
            if (debugOverlay != null) {
                debugOverlay.recordMission(
                    train.id,
                    "MISSION_MANUAL_TRIGGER route_started destination=" + response.assignedPlatform()
                );
            }
        }

        return started;
    }

    public int triggerManualRouterPing(Level level, List<Train> trains) {
        if (level == null || trains == null || trains.isEmpty() || depotRuntimeService == null) {
            return 0;
        }
        return depotRuntimeService.triggerManualBalance(level, trains);
    }

    private void runMissionHealthcheck(Level level, List<Train> trains) {
        if (level == null || trains == null || trains.isEmpty()) {
            return;
        }

        long gameTime = level.getGameTime();
        if (lastMissionHealthcheckTick != Long.MIN_VALUE
            && gameTime - lastMissionHealthcheckTick < MISSION_HEALTHCHECK_INTERVAL_TICKS) {
            return;
        }
        lastMissionHealthcheckTick = gameTime;

        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            if (train == null || train.derailed || train.graph == null) {
                continue;
            }
            if (train.runtime == null || train.runtime.paused) {
                continue;
            }
            if (isRecallInProgress(train.id)) {
                if (debugOverlay != null) {
                    debugOverlay.recordMission(train.id, "MISSION_HEALTHCHECK_SKIP reason=recall_in_progress");
                }
                continue;
            }

            Optional<TrainLine> assignedLine = lineManager.lineForTrain(train);
            if (assignedLine.isEmpty()) {
                if (debugOverlay != null) {
                    debugOverlay.clearMission(train.id);
                }
                continue;
            }

            boolean missionEmpty = trainMissionService == null || trainMissionService.peekMissionDestination(train.id).isEmpty();
            if (missionEmpty && debugOverlay != null) {
                String current = train.getCurrentStation() == null ? "<unknown>" : train.getCurrentStation().name;
                String lineId = assignedLine.map(line -> line.id().value()).orElse("<none>");
                debugOverlay.recordMission(
                    train.id,
                    "MISSION_EMPTY_REPORT tick=" + gameTime
                        + " line=" + lineId
                        + " current=" + current
                        + " navDestination=" + (train.navigation.destination == null ? "<none>" : train.navigation.destination.name)
                );
            }

            if (train.navigation.destination != null) {
                continue;
            }
            if (train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }

            TrainRoutingResponse response = requestRouteForCurrentSchedule(level, train);
            if (!response.successful() || response.path() == null) {
                if (debugOverlay != null) {
                    debugOverlay.recordMission(
                        train.id,
                        "MISSION_HEALTHCHECK_WAIT status=" + response.status()
                            + " reason=" + (response.reason() == null ? "-" : response.reason())
                    );
                }
                continue;
            }

            double navigationResult = train.navigation.startNavigation(response.path());
            if (navigationResult < 0D) {
                if (debugOverlay != null) {
                    debugOverlay.recordMission(
                        train.id,
                        "MISSION_HEALTHCHECK_START_FAILED result=" + navigationResult
                            + " platform=" + (response.assignedPlatform() == null ? "-" : response.assignedPlatform())
                    );
                }
                continue;
            }

            if (debugOverlay != null) {
                debugOverlay.recordMission(
                    train.id,
                    "MISSION_HEALTHCHECK_STARTED destination="
                        + (response.assignedPlatform() == null ? "-" : response.assignedPlatform())
                        + " status=" + response.status()
                );
            }
        }
    }

    public TrainRoutingResponse requestRoute(Level level, Train train, String destinationFilter) {
        TrainRoutingRequest request = buildRequestFromTrain(train, destinationFilter, "legacy_router_entry");
        return requestRoute(level, train, request);
    }

    public TrainRoutingResponse requestRouteForCurrentSchedule(Level level, Train train) {
        TrainRoutingRequest request = buildRequestFromTrain(train, "<schedule>", "schedule_runtime");
        if (train == null) {
            return finalizeResponse(
                null,
                request.lineId(),
                TrainRoutingResponse.invalidRequest(request.correlationId(), "train_missing", "Train was null in schedule request")
            );
        }
        if (isRecallInProgress(train.id)) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noDestination(
                    request.correlationId(),
                    null,
                    "recall_in_progress",
                    "Train is currently being recalled to depot"
                )
            );
        }

        recordStage(
            train,
            request.lineId(),
            request.correlationId(),
            STAGE_ROUTER_CALLED,
            "source=" + request.requestSource() + " destination=<resolved_from_schedule>"
        );

        Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = scheduleDestinationResolver.resolve(train);
        if (destinationContext.isEmpty()) {
            Optional<ScheduleDestinationResolver.DestinationContext> missionContext = resolveMissionDestination(train);
            if (missionContext.isPresent()) {
                TrainRoutingRequest missionRequest = TrainRoutingRequest.create(
                    request.correlationId(),
                    request.trainId(),
                    request.lineId(),
                    request.currentLocation(),
                    missionContext.get().sourceFilter(),
                    request.requestSource() + ":mission"
                );
                return routeToContext(level, train, missionRequest, missionContext.get());
            }
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noDestination(
                    request.correlationId(),
                    null,
                    "no_schedule_destination",
                    "No destination context could be resolved from current schedule state"
                )
            );
        }

        TrainRoutingRequest resolvedRequest = TrainRoutingRequest.create(
            request.correlationId(),
            request.trainId(),
            request.lineId(),
            request.currentLocation(),
            destinationContext.get().sourceFilter(),
            request.requestSource()
        );
        return routeToContext(level, train, resolvedRequest, destinationContext.get());
    }

    public TrainRoutingResponse requestRoute(Level level, Train train, TrainRoutingRequest request) {
        String correlationId = request == null ? nextCorrelationId(train) : request.correlationId();
        if (request == null) {
            return finalizeResponse(
                train,
                null,
                TrainRoutingResponse.invalidRequest(
                    correlationId,
                    "request_missing",
                    "Router called without TrainRoutingRequest"
                )
            );
        }

        recordStage(
            train,
            request.lineId(),
            correlationId,
            STAGE_ROUTER_CALLED,
            "source=" + request.requestSource() + " destination=" + request.requestedDestination()
        );

        if (train == null) {
            return finalizeResponse(
                null,
                request.lineId(),
                TrainRoutingResponse.invalidRequest(correlationId, "train_missing", "Train was null in router call")
            );
        }
        if (isRecallInProgress(train.id)) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noDestination(
                    correlationId,
                    null,
                    "recall_in_progress",
                    "Train is currently being recalled to depot"
                )
            );
        }

        if (train.graph == null) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.invalidRequest(correlationId, "train_graph_missing", "Train graph unavailable")
            );
        }

        if (request.requestedDestination() == null || request.requestedDestination().isBlank()) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.invalidRequest(correlationId, "destination_missing", "Requested destination filter is blank")
            );
        }

        Optional<ScheduleDestinationResolver.DestinationContext> destinationContext =
            scheduleDestinationResolver.resolveForFilter(train, request.requestedDestination());
        if (destinationContext.isEmpty()) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noDestination(
                    correlationId,
                    null,
                    "no_destination_match",
                    "No candidate station matched filter '" + request.requestedDestination() + "'"
                )
            );
        }

        return routeToContext(level, train, request, destinationContext.get());
    }

    private TrainRoutingRequest buildRequestFromTrain(Train train, String destinationFilter, String requestSource) {
        String correlationId = nextCorrelationId(train);
        if (train == null) {
            return TrainRoutingRequest.create(correlationId, new UUID(0L, 0L), null, null, destinationFilter, requestSource);
        }
        LineId lineId = lineManager.lineForTrain(train).map(line -> line.id()).orElse(null);
        String currentLocation = train.getCurrentStation() == null ? null : train.getCurrentStation().name;
        return TrainRoutingRequest.create(correlationId, train.id, lineId, currentLocation, destinationFilter, requestSource);
    }

    public boolean isRecallInProgress(UUID trainId) {
        return depotRuntimeService != null && depotRuntimeService.isRecallInProgress(trainId);
    }

    private RouteSelection selectBestPath(
        Level level,
        Train train,
        ScheduleDestinationResolver.DestinationContext destinationContext,
        TrainRoutingRequest request
    ) {
        GlobalStation currentStation = train.getCurrentStation();
        UUID currentStationId = currentStation == null ? null : currentStation.id;
        Optional<PlatformAssignmentService.PlannedPlatformAssignment> plannedAssignment =
            platformAssignmentService.assignmentForTrain(train.id);
        UUID plannedStationId = plannedAssignment
            .map(PlatformAssignmentService.PlannedPlatformAssignment::stationId)
            .orElse(null);

        List<GlobalStation> candidates = new ArrayList<>(destinationContext.candidateStations());
        candidates.sort(Comparator.comparing(station -> station.id.toString()));
        boolean hasAlternativeToCurrent = currentStationId != null
            && candidates.stream().anyMatch(candidate -> !candidate.id.equals(currentStationId));
        Map<String, String> nodeAliasToToken = buildNodeAliasToTokenMap(train);

        RouteSelection best = null;
        for (GlobalStation station : candidates) {
            if (currentStationId != null && hasAlternativeToCurrent && station.id.equals(currentStationId)) {
                continue;
            }
            if (request.currentLocation() != null
                && station.name != null
                && hasAlternativeToCurrent
                && station.name.equalsIgnoreCase(request.currentLocation())) {
                continue;
            }

            DiscoveredPath path = train.navigation.findPathTo(station, TrainSlothConfig.ROUTING.maxSearchCost.get());
            if (path == null || path.destination == null) {
                continue;
            }

            int score = scorePath(
                level,
                train,
                station,
                path,
                request.lineId(),
                plannedStationId,
                destinationContext.requiredNodes(),
                nodeAliasToToken
            );
            if (best == null || score < best.score()) {
                best = new RouteSelection(path, score);
            }
        }

        return best;
    }

    private GlobalStation resolveStationById(
        ScheduleDestinationResolver.DestinationContext destinationContext,
        UUID stationId
    ) {
        if (stationId == null) {
            return null;
        }
        for (GlobalStation station : destinationContext.candidateStations()) {
            if (stationId.equals(station.id)) {
                return station;
            }
        }
        return null;
    }

    private int scorePath(
        Level level,
        Train train,
        GlobalStation station,
        DiscoveredPath path,
        @Nullable LineId lineId,
        @Nullable UUID plannedStationId,
        List<String> requiredNodes,
        Map<String, String> nodeAliasToToken
    ) {
        int score = (int) Math.round(path.cost + Math.abs(path.distance));
        PlatformTiming platformTiming = evaluatePlatformTiming(train, station, path);
        int stationReleaseTicks = platformTiming.stationReleaseTicks();
        int waitAfterArrivalTicks = platformTiming.waitAfterArrivalTicks();
        if (waitAfterArrivalTicks > 0) {
            score += 520 + Math.min(10_000, waitAfterArrivalTicks * 8 + stationReleaseTicks * 3);
        } else if (stationReleaseTicks > 0) {
            score += Math.min(150, Math.max(20, stationReleaseTicks / 2));
        }

        boolean occupiedByOtherTrain = isStationOccupiedByOtherTrain(train, station);
        if (occupiedByOtherTrain) {
            score += 1_200;
        }

        boolean lockedByInterlocking = level != null
            && train.graph != null
            && interlockingControlService.isStationLocked(level, train.graph, station);
        if (lockedByInterlocking) {
            score += 8_000;
        }

        if (lineId != null) {
            boolean stationMatchesLine = lineManager.findLine(lineId)
                .map(line -> lineManager.isStationOnLine(line, station))
                .orElse(true);
            if (stationMatchesLine) {
                score -= 80;
            } else {
                score += 1_500;
            }
        }

        if (plannedStationId != null && plannedStationId.equals(station.id)) {
            if (lockedByInterlocking || occupiedByOtherTrain) {
                score += 900;
            } else if (waitAfterArrivalTicks > 0) {
                score += 320 + Math.min(2_400, waitAfterArrivalTicks * 4);
            } else if (stationReleaseTicks > 0) {
                score -= 140;
            } else {
                score -= 520;
            }
        }

        int missingRequiredNodes = countMissingRequiredNodes(path, requiredNodes, nodeAliasToToken);
        if (missingRequiredNodes > 0) {
            score += 24_000 + missingRequiredNodes * 24_000;
        }

        return score;
    }

    @Nullable
    private String unusablePathReason(Train train, DiscoveredPath path, @Nullable String currentLocation) {
        if (path == null || path.destination == null) {
            return "path_missing_or_destination_missing";
        }

        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation != null && currentStation.id.equals(path.destination.id)) {
            return "selected_destination_equals_current_station";
        }

        if (currentLocation != null && path.destination.name != null && path.destination.name.equalsIgnoreCase(currentLocation)) {
            return "selected_destination_equals_current_location_name";
        }

        if (Math.abs(path.distance) < 0.25D && path.cost <= 0D) {
            return "path_distance_too_small_for_movement";
        }

        return null;
    }

    private TrainRoutingResponse routeToContext(
        Level level,
        Train train,
        TrainRoutingRequest request,
        ScheduleDestinationResolver.DestinationContext context
    ) {
        String correlationId = request.correlationId();
        RouteSelection selection = selectBestPath(level, train, context, request);
        if (selection == null || selection.path() == null || selection.path().destination == null) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noRoute(
                    correlationId,
                    context.destinationHubId(),
                    "no_reachable_route",
                    "No reachable route to candidates for filter '" + request.requestedDestination() + "'"
                )
            );
        }

        Map<String, String> nodeAliasToToken = buildNodeAliasToTokenMap(train);
        int missingRequiredNodes = countMissingRequiredNodes(selection.path(), context.requiredNodes(), nodeAliasToToken);
        if (!context.requiredNodes().isEmpty() && missingRequiredNodes > 0) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.noRoute(
                    correlationId,
                    context.destinationHubId(),
                    "required_nodes_unreachable",
                    "requiredNodes=" + context.requiredNodes() + " missing=" + missingRequiredNodes
                )
            );
        }

        String unusableReason = unusablePathReason(train, selection.path(), request.currentLocation());
        if (unusableReason != null) {
            return finalizeResponse(
                train,
                request.lineId(),
                TrainRoutingResponse.unusableRoute(
                    correlationId,
                    context.destinationHubId(),
                    selection.path().destination.name,
                    "unusable_route",
                    unusableReason
                )
            );
        }

        String assignedPlatform = selection.path().destination.name;
        PlatformTiming selectedTiming = evaluatePlatformTiming(train, selection.path().destination, selection.path());
        long freeAtTick = level == null ? -1L : level.getGameTime() + selectedTiming.stationReleaseTicks();
        String timingDetail = "releaseTicks=" + selectedTiming.stationReleaseTicks()
            + " etaTicks=" + selectedTiming.arrivalTicks()
            + " waitTicks=" + selectedTiming.waitAfterArrivalTicks()
            + " freeOnArrival=" + selectedTiming.freeOnArrival()
            + (freeAtTick >= 0 ? " freeAtTick=" + freeAtTick : "");
        if (trainMissionService != null) {
            trainMissionService.recordRouteAssigned(train, assignedPlatform, debugOverlay);
        }
        return finalizeResponse(
            train,
            request.lineId(),
            TrainRoutingResponse.assigned(
                selection.path(),
                context.destinationHubId(),
                assignedPlatform,
                correlationId,
                "score=" + selection.score()
                    + " source=" + request.requestSource()
                    + " hub=" + (context.destinationHubId() == null ? "<none>" : context.destinationHubId())
                    + " candidates=" + context.candidateStations().size()
                    + " requiredNodes=" + context.requiredNodes().size()
                    + " missingRequiredNodes=" + missingRequiredNodes
                    + " " + timingDetail
            )
        );
    }

    private PlatformTiming evaluatePlatformTiming(Train train, GlobalStation station, @Nullable DiscoveredPath path) {
        int stationReleaseTicks = reservationAwarenessService.estimateStationReleaseTicks(train, station);
        int arrivalTicks = estimateArrivalTicks(train, path);
        int waitAfterArrivalTicks = Math.max(0, stationReleaseTicks - arrivalTicks);
        return new PlatformTiming(stationReleaseTicks, arrivalTicks, waitAfterArrivalTicks, waitAfterArrivalTicks == 0);
    }

    private int estimateArrivalTicks(Train train, @Nullable DiscoveredPath path) {
        if (train == null) {
            return DEFAULT_ARRIVAL_ESTIMATE_TICKS;
        }

        if (path != null && Math.abs(path.distance) > 0.01D) {
            double speed = Math.abs(train.speed);
            if (speed >= 0.01D) {
                int eta = (int) Math.round(Math.abs(path.distance) / Math.max(0.02D, speed) * 2D);
                return Mth.clamp(eta, MIN_ARRIVAL_ESTIMATE_TICKS, MAX_ARRIVAL_ESTIMATE_TICKS);
            }
        }

        if (train.runtime != null && train.runtime.predictionTicks != null && !train.runtime.predictionTicks.isEmpty()) {
            int index = train.runtime.currentEntry;
            if (index >= 0 && index < train.runtime.predictionTicks.size()) {
                int predicted = train.runtime.predictionTicks.get(index);
                if (predicted > 0) {
                    return Mth.clamp(predicted, MIN_ARRIVAL_ESTIMATE_TICKS, MAX_ARRIVAL_ESTIMATE_TICKS);
                }
            }
        }

        if (path != null && path.cost > 0D) {
            int eta = (int) Math.round(Math.max(1D, path.cost) * 2.2D);
            return Mth.clamp(eta, MIN_ARRIVAL_ESTIMATE_TICKS, MAX_ARRIVAL_ESTIMATE_TICKS);
        }

        return DEFAULT_ARRIVAL_ESTIMATE_TICKS;
    }

    private TrainRoutingResponse finalizeResponse(Train train, @Nullable LineId lineId, TrainRoutingResponse response) {
        if (train != null) {
            String detail = "status=" + response.status()
                + " reason=" + (response.reason() == null ? "-" : response.reason())
                + " platform=" + (response.assignedPlatform() == null ? "-" : response.assignedPlatform());
            recordStage(train, lineId, response.correlationId(), STAGE_RESPONSE_BUILT, detail);

            if (!response.successful()) {
                debugOverlay.recordRouterBreakpoint(
                    train.id,
                    lineId,
                    response.correlationId(),
                    STAGE_RESPONSE_BUILT,
                    response.reason() == null ? response.status() : response.reason()
                );
            }

            if (TrainSlothConfig.DEBUG.verboseLogs.get()) {
                CreateTrainSlothMod.LOGGER.info(
                    "[CreateTrainSloth][Router][{}] train={} line={} status={} reason={} details={}",
                    response.correlationId(),
                    train.id,
                    lineId == null ? "<none>" : lineId.value(),
                    response.status(),
                    response.reason() == null ? "-" : response.reason(),
                    response.details() == null ? "-" : response.details()
                );
            }
        }
        return response;
    }

    private void recordStage(Train train, @Nullable LineId lineId, String correlationId, String stage, String detail) {
        if (train == null || debugOverlay == null) {
            return;
        }
        debugOverlay.recordRouterStage(train.id, lineId, correlationId, stage, detail);
    }

    private synchronized String nextCorrelationId(Train train) {
        requestSequence++;
        String token = train == null ? "unknown" : train.id.toString().replace("-", "");
        if (token.length() > 6) {
            token = token.substring(0, 6);
        }
        return token + "-" + Long.toString(requestSequence, 36);
    }

    private boolean isStationOccupiedByOtherTrain(Train self, GlobalStation station) {
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

    private Optional<ScheduleDestinationResolver.DestinationContext> resolveMissionDestination(Train train) {
        if (trainMissionService == null) {
            return Optional.empty();
        }
        Optional<String> missionDestination = trainMissionService.peekMissionDestination(train.id);
        if (missionDestination.isEmpty()) {
            return Optional.empty();
        }
        return scheduleDestinationResolver.resolveForFilter(train, missionDestination.get());
    }

    private record RouteSelection(DiscoveredPath path, int score) {
    }

    private record PlatformTiming(int stationReleaseTicks, int arrivalTicks, int waitAfterArrivalTicks, boolean freeOnArrival) {
    }

    private Map<String, String> buildNodeAliasToTokenMap(Train train) {
        if (train == null || train.graph == null) {
            return Map.of();
        }

        List<TrackNodeLocation> nodes = new ArrayList<>(train.graph.getNodes());
        nodes.sort(
            Comparator.comparing((TrackNodeLocation location) -> location.getDimension().location().toString())
                .thenComparingDouble(TrackNodeLocation::getX)
                .thenComparingDouble(TrackNodeLocation::getY)
                .thenComparingDouble(TrackNodeLocation::getZ)
                .thenComparingInt(location -> location.yOffsetPixels)
        );

        Map<String, String> aliases = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            TrackNodeLocation location = nodes.get(i);
            String token = SectionIdHelper.nodeToken(location).toLowerCase();
            aliases.put(token, token);
            aliases.put("n" + i, token);
        }
        return aliases;
    }

    private int countMissingRequiredNodes(
        @Nullable DiscoveredPath path,
        List<String> requiredNodes,
        Map<String, String> nodeAliasToToken
    ) {
        if (requiredNodes == null || requiredNodes.isEmpty()) {
            return 0;
        }
        if (path == null || path.path == null || path.path.isEmpty()) {
            return requiredNodes.size();
        }

        Set<String> traversedNodeTokens = new LinkedHashSet<>();
        for (Couple<TrackNode> segment : path.path) {
            if (segment == null) {
                continue;
            }
            TrackNode first = segment.getFirst();
            TrackNode second = segment.getSecond();
            if (first != null) {
                traversedNodeTokens.add(SectionIdHelper.nodeToken(first.getLocation()).toLowerCase());
            }
            if (second != null) {
                traversedNodeTokens.add(SectionIdHelper.nodeToken(second.getLocation()).toLowerCase());
            }
        }

        int missing = 0;
        for (String rawRequired : requiredNodes) {
            String normalizedRequired = normalizeRequiredNodeToken(rawRequired);
            if (normalizedRequired.isBlank()) {
                continue;
            }
            String resolvedToken = nodeAliasToToken.getOrDefault(normalizedRequired, normalizedRequired);
            if (!traversedNodeTokens.contains(resolvedToken)) {
                missing++;
            }
        }

        return missing;
    }

    private String normalizeRequiredNodeToken(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim().toLowerCase();
        if (normalized.startsWith("nodeid:")) {
            return normalized.substring("nodeid:".length()).trim();
        }
        if (normalized.startsWith("node:")) {
            return normalized.substring("node:".length()).trim();
        }
        return normalized;
    }
}
