package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class PlatformAssignmentService {

    private static final TrainLine FALLBACK_LINE = new TrainLine(new LineId("unassigned"), "Unassigned");

    private final LineManager lineManager;
    private final ScheduleDestinationResolver destinationResolver;
    private final ReservationAwarenessService reservationAwarenessService;
    private final Map<UUID, PlannedPlatformAssignment> assignmentByTrain = new HashMap<>();
    private final Map<UUID, List<PlannedPlatformAssignment>> assignmentsByStation = new HashMap<>();
    private final Map<UUID, Set<UUID>> liveClaimsByStation = new HashMap<>();

    public PlatformAssignmentService(
        LineManager lineManager,
        ScheduleAlternativeResolver scheduleAlternativeResolver,
        StationHubRegistry stationHubRegistry,
        ReservationAwarenessService reservationAwarenessService
    ) {
        this.lineManager = lineManager;
        this.destinationResolver = new ScheduleDestinationResolver(scheduleAlternativeResolver, stationHubRegistry, lineManager);
        this.reservationAwarenessService = reservationAwarenessService;
    }

    public void plan(Level level, List<Train> trains) {
        assignmentByTrain.clear();
        assignmentsByStation.clear();
        liveClaimsByStation.clear();
        if (!TrainSlothConfig.ROUTING.enableProactivePlatformPlanning.get()) {
            return;
        }

        long now = level.getGameTime();
        indexLiveClaims(trains);
        List<PlatformRequest> requests = collectRequests(trains, now);
        requests.sort(
            Comparator.comparingLong(PlatformRequest::arrivalTick)
                .thenComparing((PlatformRequest req) -> req.serviceClass().priorityWeight(), Comparator.reverseOrder())
                .thenComparing(req -> req.train().id.toString())
        );

        for (PlatformRequest request : requests) {
            StationChoice choice = chooseStation(request);
            if (choice == null) {
                continue;
            }

            PlannedPlatformAssignment assignment = new PlannedPlatformAssignment(
                request.train().id,
                choice.station().id,
                choice.station().name,
                request.arrivalTick(),
                request.releaseTick(),
                request.line().id(),
                request.serviceClass(),
                choice.primary(),
                choice.score()
            );

            assignmentByTrain.put(request.train().id, assignment);
            assignmentsByStation.computeIfAbsent(choice.station().id, ignored -> new ArrayList<>()).add(assignment);
        }
    }

    public Optional<PlannedPlatformAssignment> assignmentForTrain(UUID trainId) {
        return Optional.ofNullable(assignmentByTrain.get(trainId));
    }

    private List<PlatformRequest> collectRequests(List<Train> trains, long now) {
        List<PlatformRequest> requests = new ArrayList<>();
        for (Train train : trains) {
            Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
            TrainLine line = optionalLine.orElse(FALLBACK_LINE);
            if (train.graph == null || train.derailed || train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused) {
                continue;
            }

            Optional<ScheduleDestinationResolver.DestinationContext> destinationContext = destinationResolver.resolve(train);
            if (destinationContext.isEmpty()) {
                continue;
            }

            long arrivalTick = estimateArrivalTick(train, now);
            int reserveWindow = Math.max(40, line.settings().resolveMinimumDwellTicks() + line.settings().resolveSafetyBufferTicks());
            long releaseTick = arrivalTick + reserveWindow;
            TrainServiceClass serviceClass = lineManager.serviceClassForTrain(train.id);
            requests.add(new PlatformRequest(train, line, destinationContext.get(), serviceClass, now, arrivalTick, releaseTick));
        }

        return requests;
    }

    private long estimateArrivalTick(Train train, long now) {
        if (train.navigation.destination != null && train.navigation.distanceToDestination > 0) {
            double speed = Math.abs(train.speed);
            if (speed >= 0.01D) {
                int eta = (int) Math.round(train.navigation.distanceToDestination / Math.max(0.02D, speed) * 2D);
                return now + Mth.clamp(eta, 20, 20 * 60 * 30);
            }
        }

        if (train.runtime != null && train.runtime.predictionTicks != null && !train.runtime.predictionTicks.isEmpty()) {
            int index = train.runtime.currentEntry;
            if (index >= 0 && index < train.runtime.predictionTicks.size()) {
                int predicted = train.runtime.predictionTicks.get(index);
                if (predicted > 0) {
                    return now + predicted;
                }
            }
        }

        return now + 20 * 30;
    }

    private StationChoice chooseStation(PlatformRequest request) {
        StationChoice best = null;
        boolean hasNonOverlappingCandidate = false;
        for (GlobalStation station : request.destinationContext().candidateStations()) {
            if (!hasPlannedOverlap(request, station) && !hasLiveClaimByOtherTrain(request, station)) {
                hasNonOverlappingCandidate = true;
                break;
            }
        }

        for (GlobalStation station : request.destinationContext().candidateStations()) {
            int score = scoreStation(request, station, hasNonOverlappingCandidate);
            boolean primary = request.destinationContext().primaryDestination().id.equals(station.id);

            if (best == null || score < best.score() || (score == best.score() && primary && !best.primary())) {
                best = new StationChoice(station, primary, score);
            }
        }

        return best;
    }

    private int scoreStation(PlatformRequest request, GlobalStation station, boolean hasNonOverlappingCandidate) {
        int score = 0;
        boolean primary = request.destinationContext().primaryDestination().id.equals(station.id);
        boolean hasOverlap = false;
        boolean liveClaimed = hasLiveClaimByOtherTrain(request, station);

        if (primary) {
            score -= 350;
        } else {
            score += 120;
        }

        int releaseTicks = reservationAwarenessService.estimateStationReleaseTicks(request.train(), station);
        if (releaseTicks > 0) {
            long leadTimeUntilArrival = Math.max(0L, request.arrivalTick() - request.planningTick());
            long expectedWaitAfterArrival = Math.max(0L, releaseTicks - leadTimeUntilArrival);
            score += 480 + (int) Math.min(8_000L, expectedWaitAfterArrival * 6L + releaseTicks * 2L);
        }

        List<PlannedPlatformAssignment> stationAssignments = assignmentsByStation.get(station.id);
        if (stationAssignments != null) {
            for (PlannedPlatformAssignment existing : stationAssignments) {
                long overlap = overlapTicks(
                    request.arrivalTick(),
                    request.releaseTick(),
                    existing.arrivalTick(),
                    existing.releaseTick()
                );
                if (overlap <= 0) {
                    continue;
                }
                hasOverlap = true;

                int overlapPenalty = (int) Math.min(4000, overlap * 3);
                int priorityDelta = existing.serviceClass().priorityWeight() - request.serviceClass().priorityWeight();
                if (priorityDelta >= 0) {
                    overlapPenalty += 650 + priorityDelta * 8;
                } else {
                    overlapPenalty += Math.max(60, 250 + priorityDelta * 4);
                }

                score += overlapPenalty;
            }
        }

        if (hasNonOverlappingCandidate && hasOverlap) {
            // Strongly prefer free platforms inside the same candidate group.
            score += 8_000;
        }
        if (hasNonOverlappingCandidate && liveClaimed) {
            // Prevent two trains from converging to one platform while alternatives are actually free.
            score += 12_000;
        }

        return score;
    }

    private boolean hasPlannedOverlap(PlatformRequest request, GlobalStation station) {
        if (request == null || station == null) {
            return false;
        }
        List<PlannedPlatformAssignment> stationAssignments = assignmentsByStation.get(station.id);
        if (stationAssignments == null || stationAssignments.isEmpty()) {
            return false;
        }
        for (PlannedPlatformAssignment existing : stationAssignments) {
            if (overlapTicks(
                request.arrivalTick(),
                request.releaseTick(),
                existing.arrivalTick(),
                existing.releaseTick()
            ) > 0) {
                return true;
            }
        }
        return false;
    }

    private void indexLiveClaims(List<Train> trains) {
        if (trains == null || trains.isEmpty()) {
            return;
        }
        for (Train train : trains) {
            if (train == null || train.id == null) {
                continue;
            }
            GlobalStation currentStation = train.getCurrentStation();
            if (currentStation != null && currentStation.id != null) {
                registerLiveClaim(currentStation.id, train.id);
            }
            GlobalStation destinationStation = train.navigation.destination;
            if (destinationStation != null && destinationStation.id != null) {
                registerLiveClaim(destinationStation.id, train.id);
            }
        }
    }

    private void registerLiveClaim(UUID stationId, UUID trainId) {
        liveClaimsByStation.computeIfAbsent(stationId, ignored -> new java.util.HashSet<>()).add(trainId);
    }

    private boolean hasLiveClaimByOtherTrain(PlatformRequest request, GlobalStation station) {
        if (request == null || station == null || station.id == null) {
            return false;
        }
        Set<UUID> claims = liveClaimsByStation.get(station.id);
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        UUID requesterId = request.train() == null ? null : request.train().id;
        for (UUID claimant : claims) {
            if (requesterId == null || !requesterId.equals(claimant)) {
                return true;
            }
        }
        return false;
    }

    private long overlapTicks(long aStart, long aEnd, long bStart, long bEnd) {
        long start = Math.max(aStart, bStart);
        long end = Math.min(aEnd, bEnd);
        return Math.max(0, end - start);
    }

    private record PlatformRequest(
        Train train,
        TrainLine line,
        ScheduleDestinationResolver.DestinationContext destinationContext,
        TrainServiceClass serviceClass,
        long planningTick,
        long arrivalTick,
        long releaseTick
    ) {
    }

    private record StationChoice(GlobalStation station, boolean primary, int score) {
    }

    public record PlannedPlatformAssignment(
        UUID trainId,
        UUID stationId,
        String stationName,
        long arrivalTick,
        long releaseTick,
        LineId lineId,
        TrainServiceClass serviceClass,
        boolean primary,
        int score
    ) {
    }
}
