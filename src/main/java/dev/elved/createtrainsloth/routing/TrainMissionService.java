package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.TrainLine;
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
        int selectedIndex = startIndex;
        for (int i = 0; i < orderedStops.size(); i++) {
            int candidateIndex = Math.floorMod(startIndex + i, orderedStops.size());
            String candidate = orderedStops.get(candidateIndex);
            if (!matchesStop(currentLocation, candidate) || orderedStops.size() == 1) {
                selectedIndex = candidateIndex;
                break;
            }
        }

        String destination = orderedStops.get(selectedIndex);
        state.destinations.addLast(destination);
        state.nextIndex = Math.floorMod(selectedIndex + 1, orderedStops.size());

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
        String activeMission = normalize(state.destinations.peekFirst());
        if (activeMission.isBlank()) {
            state.destinations.pollFirst();
            return;
        }
        if (!matchesStop(currentName, activeMission)) {
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

    private boolean matchesStop(String currentLocation, String configuredStop) {
        String current = normalize(currentLocation);
        String configured = normalize(configuredStop);
        if (current.isBlank() || configured.isBlank()) {
            return false;
        }
        if (configured.contains("*")) {
            String regex = java.util.regex.Pattern.quote(configured).replace("\\*", "\\E.*\\Q");
            return current.matches(regex);
        }
        return configured.equals(current);
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
