package dev.elved.createtrainsloth.planning;

import dev.elved.createtrainsloth.interlocking.schematic.StellwerkTrainView;
import dev.elved.createtrainsloth.line.InterlockingPlanningService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LinePlanningService;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainLineAssignment;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PlanningService {

    private final LineRegistry lineRegistry;
    private final StationHubRegistry stationHubRegistry;
    private final InterlockingPlanningService interlockingPlanningService;
    private final LinePlanningService linePlanningService;

    public PlanningService(LineRegistry lineRegistry, StationHubRegistry stationHubRegistry) {
        this.lineRegistry = lineRegistry;
        this.stationHubRegistry = stationHubRegistry;
        this.interlockingPlanningService = new InterlockingPlanningService();
        this.linePlanningService = new LinePlanningService();
    }

    public int recommendedTrainCount(TrainLine line, int orderedStopCount, TrainServiceClass serviceClass) {
        return linePlanningService.recommendedTrainCount(line, orderedStopCount, serviceClass);
    }

    public boolean generateLinesFromHubs(List<StellwerkTrainView> schematicTrains) {
        if (lineRegistry == null) {
            return false;
        }
        List<StationHub> hubs = stationHubRegistry == null ? List.of() : new ArrayList<>(stationHubRegistry.allHubs());
        return interlockingPlanningService.generateLinesFromHubs(lineRegistry, hubs, schematicTrains);
    }

    public Optional<String> createRoute(
        Map<String, List<String>> routeStationsByLine,
        Map<String, TrainServiceClass> routeServiceByLine,
        String routeNameRaw,
        String serviceClassRaw
    ) {
        return interlockingPlanningService.createRoute(
            lineRegistry,
            routeStationsByLine,
            routeServiceByLine,
            routeNameRaw,
            serviceClassRaw
        );
    }

    public boolean updateRouteMeta(
        Map<String, TrainServiceClass> routeServiceByLine,
        String lineId,
        String routeNameRaw,
        String serviceClassRaw
    ) {
        return interlockingPlanningService.updateRouteMeta(
            lineRegistry,
            routeServiceByLine,
            lineId,
            routeNameRaw,
            serviceClassRaw
        );
    }

    public boolean deleteRoute(
        Map<String, TrainServiceClass> routeServiceByLine,
        Map<String, List<String>> routeStationsByLine,
        String lineIdRaw
    ) {
        return interlockingPlanningService.deleteRoute(
            lineRegistry,
            routeServiceByLine,
            routeStationsByLine,
            lineIdRaw
        );
    }

    public boolean editRouteStation(
        Map<String, List<String>> routeStationsByLine,
        Map<String, TrainServiceClass> routeServiceByLine,
        String lineIdRaw,
        String stationNameRaw,
        boolean add
    ) {
        return interlockingPlanningService.editRouteStation(
            lineRegistry,
            routeStationsByLine,
            routeServiceByLine,
            lineIdRaw,
            stationNameRaw,
            add
        );
    }

    public boolean moveRouteStation(
        Map<String, List<String>> routeStationsByLine,
        String lineIdRaw,
        int fromIndex,
        int toIndex
    ) {
        return interlockingPlanningService.moveRouteStation(
            lineRegistry,
            routeStationsByLine,
            lineIdRaw,
            fromIndex,
            toIndex
        );
    }

    public boolean assignTrainToLine(UUID trainId, String lineIdRaw, TrainServiceClass serviceClass) {
        if (lineRegistry == null || trainId == null) {
            return false;
        }

        String lineId = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineId.isBlank() || "-".equals(lineId)) {
            return false;
        }
        LineId parsed = new LineId(lineId);
        if (lineRegistry.findLine(parsed).isEmpty()) {
            return false;
        }
        lineRegistry.assignTrain(trainId, parsed, serviceClass == null ? TrainServiceClass.RE : serviceClass);
        return true;
    }

    public boolean unassignTrain(UUID trainId) {
        if (lineRegistry == null || trainId == null) {
            return false;
        }
        Optional<TrainLineAssignment> assignment = lineRegistry.assignmentOf(trainId);
        if (assignment.isEmpty()) {
            return false;
        }
        lineRegistry.unassignTrain(trainId);
        return true;
    }

    public boolean toggleLineDepotHub(String lineIdRaw, String hubIdRaw) {
        if (lineRegistry == null) {
            return false;
        }

        String lineId = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineId.isBlank() || "-".equals(lineId)) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineId)).orElse(null);
        if (line == null) {
            return false;
        }

        String normalizedHubId = normalizeDepotHubId(hubIdRaw);
        if (normalizedHubId.isBlank()) {
            return false;
        }

        String resolvedHubId = normalizedHubId;
        if (stationHubRegistry != null) {
            Optional<StationHub> resolvedHub = stationHubRegistry.findHubForScheduleFilter(normalizedHubId);
            if (resolvedHub.isPresent()) {
                resolvedHubId = resolvedHub.get().id().value();
                if (!resolvedHub.get().isDepotHub() && !line.settings().allowedDepotHubIds().contains(resolvedHubId)) {
                    return false;
                }
            } else if (!line.settings().allowedDepotHubIds().contains(normalizedHubId)) {
                // Unknown ids may still be removed if they already exist, but should not be newly added.
                return false;
            }
        }

        if (!line.settings().toggleAllowedDepotHubId(resolvedHubId)) {
            return false;
        }

        lineRegistry.markDirty();
        return true;
    }

    private String normalizeDepotHubId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("hubid:")) {
            return value.substring("hubid:".length()).trim();
        }
        if (value.startsWith("hub:")) {
            return value.substring("hub:".length()).trim();
        }
        if (value.startsWith("station:")) {
            return value.substring("station:".length()).trim();
        }
        return value;
    }
}
