package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class TrainMissionService {

    private final Map<UUID, MissionState> missionByTrain = new HashMap<>();
    private final StationHubRegistry stationHubRegistry;

    public TrainMissionService(StationHubRegistry stationHubRegistry) {
        this.stationHubRegistry = stationHubRegistry;
    }

    public void tick(List<Train> trains, java.util.function.Function<Train, Optional<TrainLine>> lineResolver, DebugOverlay debugOverlay) {
        if (trains == null || trains.isEmpty()) {
            return;
        }
        List<Train> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : ordered) {
            if (train == null || train.derailed || train.runtime == null) {
                continue;
            }
            if (train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }

            completeIfArrived(train, debugOverlay);
            ensureMission(train, lineResolver.apply(train), debugOverlay);
        }
    }

    public Optional<String> peekMissionDestination(UUID trainId) {
        MissionState state = missionByTrain.get(trainId);
        if (state == null || state.destinations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.destinations.peekFirst());
    }

    public void recordRouteAssigned(Train train, String destinationFilter, DebugOverlay debugOverlay) {
        if (train == null || destinationFilter == null || destinationFilter.isBlank()) {
            return;
        }
        MissionState state = missionByTrain.computeIfAbsent(train.id, ignored -> new MissionState());
        if (state.destinations.isEmpty()) {
            state.destinations.addLast(destinationFilter);
        }
        if (debugOverlay != null) {
            debugOverlay.recordMission(
                train.id,
                "MISSION_ASSIGNED_TO_ROUTE train=" + shortId(train.id)
                    + " destination=" + destinationFilter
            );
        }
    }

    private void ensureMission(Train train, Optional<TrainLine> line, DebugOverlay debugOverlay) {
        MissionState state = missionByTrain.computeIfAbsent(train.id, ignored -> new MissionState());
        if (!state.destinations.isEmpty()) {
            return;
        }

        String currentLocation = currentLocation(train);
        String lineId = line.map(value -> value.id().value()).orElse("<none>");
        if (debugOverlay != null) {
            debugOverlay.recordMission(
                train.id,
                "MISSION_REQUEST: ich bin zug " + shortId(train.id)
                    + " line=" + lineId
                    + " current=" + (currentLocation == null ? "<unknown>" : currentLocation)
                    + " queue=empty"
            );
        }

        if (line.isEmpty()) {
            if (debugOverlay != null) {
                debugOverlay.recordMission(train.id, "MISSION_WAIT reason=no_line_assignment");
            }
            return;
        }

        List<String> orderedStops = new ArrayList<>(line.get().stationNames());
        if (orderedStops.isEmpty()) {
            if (debugOverlay != null) {
                debugOverlay.recordMission(train.id, "MISSION_WAIT line=" + line.get().id().value() + " reason=no_stops");
            }
            return;
        }

        int startIndex = Math.floorMod(state.nextIndex, orderedStops.size());
        int selectedStopIndex = -1;
        for (int i = 0; i < orderedStops.size(); i++) {
            int candidateIndex = Math.floorMod(startIndex + i, orderedStops.size());
            String candidate = orderedStops.get(candidateIndex);
            if (isNodeRoutePoint(candidate)) {
                continue;
            }
            if (!matchesStop(train, currentLocation, candidate) || orderedStops.size() == 1) {
                selectedStopIndex = candidateIndex;
                break;
            }
        }

        if (selectedStopIndex < 0) {
            if (debugOverlay != null) {
                debugOverlay.recordMission(
                    train.id,
                    "MISSION_WAIT line=" + line.get().id().value() + " reason=no_routable_stop"
                );
            }
            return;
        }

        String destination = buildMissionDestination(orderedStops, startIndex, selectedStopIndex);
        state.destinations.addLast(destination);
        state.nextIndex = Math.floorMod(selectedStopIndex + 1, orderedStops.size());

        if (debugOverlay != null) {
            debugOverlay.recordMission(
                train.id,
                "MISSION_CREATED line=" + line.get().id().value() + " destination=" + destination
            );
        }
    }

    private void completeIfArrived(Train train, DebugOverlay debugOverlay) {
        MissionState state = missionByTrain.get(train.id);
        if (state == null || state.destinations.isEmpty()) {
            return;
        }

        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation == null || currentStation.name == null || currentStation.name.isBlank()) {
            return;
        }
        if (train.navigation.destination != null) {
            return;
        }

        String currentName = normalize(currentStation.name);
        String activeMission = state.destinations.peekFirst();
        String activeStop = extractMissionStopFilter(activeMission);
        if (normalize(activeStop).isBlank()) {
            state.destinations.pollFirst();
            return;
        }
        if (!matchesStop(train, currentName, activeStop)) {
            return;
        }

        String completed = state.destinations.pollFirst();
        if (debugOverlay != null) {
            debugOverlay.recordMission(train.id, "MISSION_COMPLETED destination=" + completed + " at=" + currentStation.name);
        }
    }

    private String currentLocation(Train train) {
        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation == null || currentStation.name == null || currentStation.name.isBlank()) {
            return null;
        }
        return normalize(currentStation.name);
    }

    private boolean matchesStop(Train train, String currentLocation, String configuredStop) {
        String stopFilter = extractMissionStopFilter(configuredStop);
        String current = normalize(currentLocation);
        String configured = normalizeStationFilter(stopFilter);
        if (current.isBlank() || configured.isBlank()) {
            return false;
        }

        Optional<StationHub> hub = resolveHubForFilter(stopFilter);
        if (hub.isPresent()) {
            return hub.get().matchesStationName(current);
        }

        if (isNodeRoutePoint(stopFilter)) {
            return false;
        }

        if (configured.contains("*")) {
            String regex = java.util.regex.Pattern.quote(configured).replace("\\*", "\\E.*\\Q");
            return current.matches(regex);
        }
        return configured.equals(current);
    }

    private String buildMissionDestination(List<String> routePoints, int startIndex, int stopIndex) {
        if (routePoints == null || routePoints.isEmpty()) {
            return "";
        }

        String stopFilter = normalize(routePoints.get(stopIndex));
        List<String> viaNodes = new ArrayList<>();

        int guard = 0;
        int index = startIndex;
        while (index != stopIndex && guard < routePoints.size()) {
            String point = routePoints.get(index);
            if (isNodeRoutePoint(point)) {
                String node = normalizeNodeRoutePoint(point);
                if (!node.isBlank()) {
                    viaNodes.add(node);
                }
            }
            index = Math.floorMod(index + 1, routePoints.size());
            guard++;
        }

        if (viaNodes.isEmpty()) {
            return stopFilter;
        }

        return "via[" + String.join(",", viaNodes) + "]->" + stopFilter;
    }

    private String extractMissionStopFilter(String destination) {
        if (destination == null) {
            return "";
        }

        String trimmed = destination.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith("via[")) {
            return trimmed;
        }

        int closing = trimmed.indexOf(']');
        if (closing < 0) {
            return trimmed;
        }

        String remainder = trimmed.substring(closing + 1).trim();
        if (remainder.startsWith("->")) {
            remainder = remainder.substring(2).trim();
        }
        return remainder.isBlank() ? trimmed : remainder;
    }

    private boolean isNodeRoutePoint(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = normalize(raw);
        if (normalized.startsWith("node:") || normalized.startsWith("nodeid:")) {
            return true;
        }
        return normalized.matches("^n\\d+$");
    }

    private String normalizeNodeRoutePoint(String raw) {
        String normalized = normalize(raw);
        if (normalized.startsWith("nodeid:")) {
            return normalized.substring("nodeid:".length()).trim();
        }
        if (normalized.startsWith("node:")) {
            return normalized.substring("node:".length()).trim();
        }
        return normalized;
    }

    private Optional<StationHub> resolveHubForFilter(String filter) {
        if (stationHubRegistry == null || filter == null || filter.isBlank()) {
            return Optional.empty();
        }
        return stationHubRegistry.findHubForScheduleFilter(filter);
    }

    private String normalizeStationFilter(String filter) {
        String configured = normalize(filter);
        if (configured.startsWith("station:")) {
            return configured.substring("station:".length()).trim();
        }
        return configured;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String shortId(UUID trainId) {
        String token = trainId.toString().replace("-", "");
        return token.length() <= 6 ? token : token.substring(0, 6);
    }

    private static class MissionState {
        private final Deque<String> destinations = new ArrayDeque<>();
        private int nextIndex = 0;
    }
}
