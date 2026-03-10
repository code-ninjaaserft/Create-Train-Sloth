package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RoutePreferenceResolver {

    private final ReservationAwarenessService reservationAwarenessService;

    public RoutePreferenceResolver(ReservationAwarenessService reservationAwarenessService) {
        this.reservationAwarenessService = reservationAwarenessService;
    }

    public RouteResolution resolve(
        Train train,
        TrainLine line,
        DiscoveredPath primaryPath,
        List<DiscoveredPath> candidates,
        PlatformAssignmentService.PlannedPlatformAssignment plannedAssignment,
        AlternativePathSelector.TrainRouteState state,
        long gameTick
    ) {
        if (candidates.isEmpty()) {
            return RouteResolution.waitOnly();
        }

        Map<String, Integer> scoresBySignature = new HashMap<>();
        Map<String, DiscoveredPath> pathBySignature = new HashMap<>();
        String primarySignature = AlternativePathSelector.pathSignature(primaryPath);
        String currentSignature = state.currentSignature() == null ? primarySignature : state.currentSignature();
        UUID primaryDestinationId = primaryPath.destination != null ? primaryPath.destination.id : null;

        for (DiscoveredPath candidate : candidates) {
            String signature = AlternativePathSelector.pathSignature(candidate);
            int score = scorePath(
                train,
                line,
                candidate,
                primarySignature,
                primaryDestinationId,
                plannedAssignment,
                currentSignature,
                state,
                gameTick
            );
            scoresBySignature.put(signature, score);
            pathBySignature.putIfAbsent(signature, candidate);
        }

        Map.Entry<String, Integer> best = scoresBySignature.entrySet().stream()
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .orElse(null);
        if (best == null) {
            return RouteResolution.waitOnly();
        }

        int currentScore = scoresBySignature.getOrDefault(currentSignature, Integer.MIN_VALUE / 4);

        int threshold = TrainSlothConfig.ROUTING.scoreImprovementThreshold.get();
        boolean cooldownActive = state.currentSignature() != null
            && !best.getKey().equals(state.currentSignature())
            && gameTick - state.lastSwitchTick() < line.settings().resolveRouteSwitchCooldownTicks();

        if (cooldownActive) {
            return RouteResolution.waitOnly();
        }

        if (best.getKey().equals(currentSignature)) {
            return RouteResolution.keep(pathBySignature.get(best.getKey()), best.getKey(), best.getValue());
        }

        if (best.getValue() < currentScore + threshold) {
            return RouteResolution.keep(pathBySignature.get(currentSignature), currentSignature, currentScore);
        }

        return RouteResolution.switchTo(pathBySignature.get(best.getKey()), best.getKey(), best.getValue());
    }

    private int scorePath(
        Train train,
        TrainLine line,
        DiscoveredPath path,
        String primarySignature,
        UUID primaryDestinationId,
        PlatformAssignmentService.PlannedPlatformAssignment plannedAssignment,
        String currentSignature,
        AlternativePathSelector.TrainRouteState state,
        long gameTick
    ) {
        String signature = AlternativePathSelector.pathSignature(path);

        int score = 0;
        if (signature.equals(primarySignature)) {
            score += 500;
        }

        score -= (int) Math.round(Math.abs(path.distance));
        score -= (int) Math.round(path.cost);

        if (path.destination != null) {
            Train present = path.destination.getPresentTrain();
            if (present != null && !present.id.equals(train.id)) {
                score -= 2800;
            }

            Train imminent = path.destination.getImminentTrain();
            if (imminent != null && !imminent.id.equals(train.id)) {
                score -= 900;
            }
        }

        int occupied = reservationAwarenessService.countOccupiedSignals(train, path);
        score -= occupied * 700;

        int conflicts = reservationAwarenessService.estimateConflictComplexity(path);
        score -= conflicts * 60;

        if (state.currentSignature() != null && signature.equals(state.currentSignature())) {
            score += 180;
        }

        if (state.currentSignature() != null && !signature.equals(state.currentSignature())) {
            long sinceLastSwitch = gameTick - state.lastSwitchTick();
            int cooldown = line.settings().resolveRouteSwitchCooldownTicks();
            if (sinceLastSwitch < cooldown) {
                score -= (int) ((cooldown - sinceLastSwitch) * 10);
            }
        }

        // Once a train has waited long enough at a red signal, de-prioritize staying on that exact route
        // so alternatives can be selected deterministically.
        if (signature.equals(currentSignature) && train.navigation.waitingForSignal != null) {
            int replanThreshold = line.settings().resolveRouteReplanWaitTicks();
            int overWait = Math.max(0, train.navigation.ticksWaitingForSignal - replanThreshold);
            score -= 300 + Math.min(1600, overWait * 20);
        }

        if (train.navigation.waitingForSignal != null && primaryDestinationId != null && path.destination != null) {
            int replanThreshold = line.settings().resolveRouteReplanWaitTicks();
            int overWait = Math.max(0, train.navigation.ticksWaitingForSignal - replanThreshold);

            boolean primaryDestinationPath = primaryDestinationId.equals(path.destination.id);
            if (primaryDestinationPath) {
                // Main destination remains preferred while free, but once it is blocked long enough,
                // alternatives should be able to win decisively.
                score -= 700 + Math.min(2600, overWait * 25);
            } else {
                // Alternative destinations are only considered in blocked situations.
                // Give them a rising bonus with waiting time so the train eventually reroutes.
                score += 500 + Math.min(2400, overWait * 20);
            }
        }

        if (plannedAssignment != null && path.destination != null) {
            if (plannedAssignment.stationId().equals(path.destination.id)) {
                score += 1800 + plannedAssignment.serviceClass().priorityWeight() * 10;
            } else {
                score -= 700;
            }
        }

        return score;
    }

    public record RouteResolution(boolean shouldSwitch, boolean keepCurrent, DiscoveredPath selectedPath, String signature, int score) {

        public static RouteResolution waitOnly() {
            return new RouteResolution(false, false, null, "", Integer.MIN_VALUE);
        }

        public static RouteResolution keep(DiscoveredPath path, String signature, int score) {
            return new RouteResolution(false, true, path, signature, score);
        }

        public static RouteResolution switchTo(DiscoveredPath path, String signature, int score) {
            return new RouteResolution(true, false, path, signature, score);
        }
    }
}
