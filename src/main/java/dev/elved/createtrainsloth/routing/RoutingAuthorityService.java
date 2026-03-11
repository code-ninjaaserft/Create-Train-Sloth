package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.dispatch.DispatchController;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.ScheduleLineSyncService;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.Level;

public class RoutingAuthorityService {

    private static final String STAGE_ROUTER_CALLED = "ROUTER_CALLED";
    private static final String STAGE_RESPONSE_BUILT = "RESPONSE_BUILT";

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
    private final DebugOverlay debugOverlay;
    private long requestSequence = 0L;

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
        dispatchController.preRailwayTick(level, trains);
    }

    public void onLevelTickEnd(Level level, List<Train> trains) {
        dispatchController.postRailwayTick(level, trains);
        alternativePathSelector.postRailwayTick(level, trains);
        if (depotRuntimeService != null) {
            depotRuntimeService.tick(level, trains);
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

        recordStage(
            train,
            request.lineId(),
            request.correlationId(),
            STAGE_ROUTER_CALLED,
            "source=" + request.requestSource() + " destination=<resolved_from_schedule>"
        );

        Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = scheduleDestinationResolver.resolve(train);
        if (destinationContext.isEmpty()) {
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
        if (plannedAssignment.isPresent()) {
            GlobalStation plannedStation = resolveStationById(destinationContext, plannedAssignment.get().stationId());
            if (plannedStation != null
                && (currentStationId == null || !plannedStation.id.equals(currentStationId))) {
                DiscoveredPath plannedPath = train.navigation.findPathTo(
                    plannedStation,
                    TrainSlothConfig.ROUTING.maxSearchCost.get()
                );
                if (plannedPath != null && plannedPath.destination != null) {
                    return new RouteSelection(plannedPath, Integer.MIN_VALUE / 8);
                }
            }
        }

        List<GlobalStation> candidates = new ArrayList<>(destinationContext.candidateStations());
        candidates.sort(Comparator.comparing(station -> station.id.toString()));
        boolean hasAlternativeToCurrent = currentStationId != null
            && candidates.stream().anyMatch(candidate -> !candidate.id.equals(currentStationId));

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

            int score = scorePath(level, train, station, path, request.lineId());
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

    private int scorePath(Level level, Train train, GlobalStation station, DiscoveredPath path, @Nullable LineId lineId) {
        int score = (int) Math.round(path.cost + Math.abs(path.distance));

        int stationReleaseTicks = reservationAwarenessService.estimateStationReleaseTicks(train, station);
        if (stationReleaseTicks > 0) {
            score += 500 + Math.min(10_000, stationReleaseTicks * 6);
        }

        if (isStationOccupiedByOtherTrain(train, station)) {
            score += 1_200;
        }

        if (level != null && train.graph != null && interlockingControlService.isStationLocked(level, train.graph, station)) {
            score += 8_000;
        }

        if (lineId != null) {
            boolean stationMatchesLine = lineManager.findLine(lineId)
                .map(line -> line.matchesStation(station))
                .orElse(true);
            if (stationMatchesLine) {
                score -= 80;
            } else {
                score += 1_500;
            }
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
        return finalizeResponse(
            train,
            request.lineId(),
            TrainRoutingResponse.assigned(
                selection.path(),
                context.destinationHubId(),
                assignedPlatform,
                correlationId,
                "score=" + selection.score() + " source=" + request.requestSource()
            )
        );
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

    private record RouteSelection(DiscoveredPath path, int score) {
    }
}
