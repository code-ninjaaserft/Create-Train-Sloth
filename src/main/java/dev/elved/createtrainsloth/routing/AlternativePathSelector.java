package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class AlternativePathSelector {

    private final LineManager lineManager;
    private final RoutePreferenceResolver routePreferenceResolver;
    private final ReservationAwarenessService reservationAwarenessService;
    private final DebugOverlay debugOverlay;
    private final Map<UUID, TrainRouteState> stateByTrain = new HashMap<>();

    public AlternativePathSelector(
        LineManager lineManager,
        RoutePreferenceResolver routePreferenceResolver,
        ReservationAwarenessService reservationAwarenessService,
        DebugOverlay debugOverlay
    ) {
        this.lineManager = lineManager;
        this.routePreferenceResolver = routePreferenceResolver;
        this.reservationAwarenessService = reservationAwarenessService;
        this.debugOverlay = debugOverlay;
    }

    public void postRailwayTick(Level level, List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableAlternativeRouting.get()) {
            return;
        }

        long gameTick = level.getGameTime();
        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
            if (optionalLine.isEmpty()) {
                continue;
            }

            TrainLine line = optionalLine.get();
            if (!isRerouteCandidate(train, line)) {
                continue;
            }

            GlobalStation destination = train.navigation.destination;
            DiscoveredPath primaryPath = train.navigation.findPathTo(destination, -1);
            DiscoveredPath dynamicPath = train.navigation.findPathTo(destination, TrainSlothConfig.ROUTING.maxSearchCost.get());
            if (primaryPath == null || dynamicPath == null) {
                continue;
            }

            boolean blocked = train.navigation.waitingForSignal != null
                && train.navigation.ticksWaitingForSignal >= line.settings().resolveRouteReplanWaitTicks();
            blocked |= reservationAwarenessService.isPrimaryPathBlocked(train, primaryPath);
            if (!blocked) {
                continue;
            }

            List<DiscoveredPath> candidates = List.of(primaryPath, dynamicPath);
            TrainRouteState routeState = stateByTrain.computeIfAbsent(train.id, ignored -> new TrainRouteState());

            RoutePreferenceResolver.RouteResolution resolution = routePreferenceResolver.resolve(
                train,
                line,
                primaryPath,
                candidates,
                routeState,
                gameTick
            );

            if (!resolution.shouldSwitch() || resolution.selectedPath() == null) {
                continue;
            }

            double result = train.navigation.startNavigation(resolution.selectedPath());
            if (result >= 0) {
                routeState.currentSignature = resolution.signature();
                routeState.lastSwitchTick = gameTick;
                debugOverlay.recordRouteSwitch(train.id, line.id(), resolution.signature(), resolution.score());
                logVerbose(train, line, "route switch -> " + resolution.signature() + " score=" + resolution.score());
            }
        }
    }

    private boolean isRerouteCandidate(Train train, TrainLine line) {
        if (train.graph == null || train.derailed) {
            return false;
        }

        if (train.navigation.destination == null) {
            return false;
        }

        if (train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused) {
            return false;
        }

        if (train.navigation.waitingForSignal == null) {
            return false;
        }

        return train.navigation.ticksWaitingForSignal >= line.settings().resolveRouteReplanWaitTicks();
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
        if (!TrainSlothConfig.DEBUG.verboseLogs.get()) {
            return;
        }
        CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth][Routing] train={} line={} {}", train.id, line.id(), detail);
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
