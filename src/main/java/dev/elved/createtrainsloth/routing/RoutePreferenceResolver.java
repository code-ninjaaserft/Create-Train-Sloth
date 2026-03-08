package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        AlternativePathSelector.TrainRouteState state,
        long gameTick
    ) {
        if (candidates.isEmpty()) {
            return RouteResolution.waitOnly();
        }

        Map<String, Integer> scoresBySignature = new HashMap<>();
        Map<String, DiscoveredPath> pathBySignature = new HashMap<>();
        String primarySignature = AlternativePathSelector.pathSignature(primaryPath);

        for (DiscoveredPath candidate : candidates) {
            String signature = AlternativePathSelector.pathSignature(candidate);
            int score = scorePath(train, line, candidate, primarySignature, state, gameTick);
            scoresBySignature.put(signature, score);
            pathBySignature.putIfAbsent(signature, candidate);
        }

        Map.Entry<String, Integer> best = scoresBySignature.entrySet().stream()
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .orElse(null);
        if (best == null) {
            return RouteResolution.waitOnly();
        }

        String currentSignature = state.currentSignature() == null ? primarySignature : state.currentSignature();
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
        AlternativePathSelector.TrainRouteState state,
        long gameTick
    ) {
        String signature = AlternativePathSelector.pathSignature(path);

        int score = 0;
        if (signature.equals(primarySignature)) {
            score += 4000;
        }

        score -= (int) Math.round(Math.abs(path.distance));
        score -= (int) Math.round(path.cost / 2D);

        int occupied = reservationAwarenessService.countOccupiedSignals(train, path);
        score -= occupied * 600;

        int conflicts = reservationAwarenessService.estimateConflictComplexity(path);
        score -= conflicts * 80;

        if (state.currentSignature() != null && signature.equals(state.currentSignature())) {
            score += 250;
        }

        if (state.currentSignature() != null && !signature.equals(state.currentSignature())) {
            long sinceLastSwitch = gameTick - state.lastSwitchTick();
            int cooldown = line.settings().resolveRouteSwitchCooldownTicks();
            if (sinceLastSwitch < cooldown) {
                score -= (int) ((cooldown - sinceLastSwitch) * 10);
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
