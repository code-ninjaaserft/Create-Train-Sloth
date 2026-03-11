package dev.elved.createtrainsloth.line;

import dev.elved.createtrainsloth.interlocking.schematic.StellwerkTrainView;
import dev.elved.createtrainsloth.station.StationHub;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class InterlockingPlanningService {

    public boolean generateLinesFromHubs(
        LineRegistry lineRegistry,
        Collection<StationHub> stationHubs,
        List<StellwerkTrainView> schematicTrains
    ) {
        if (lineRegistry == null) {
            return false;
        }

        boolean generatedAny = false;
        List<StationHub> hubs = new ArrayList<>(stationHubs == null ? List.of() : stationHubs);
        hubs.sort(Comparator.comparing(hub -> hub.id().value()));

        for (StationHub hub : hubs) {
            String lineIdRaw = sanitizeLineId("hub_" + hub.id().value());
            LineId lineId = new LineId(lineIdRaw);
            TrainLine line = lineRegistry.findLine(lineId).orElse(null);
            if (line == null) {
                line = lineRegistry.createLine(lineId, hub.displayName());
            }
            line.setDisplayName(hub.displayName());
            for (String platform : hub.platformStationNames()) {
                line.addStationName(platform);
            }
            generatedAny = true;
        }

        if (!generatedAny) {
            List<StellwerkTrainView> trains = schematicTrains == null ? List.of() : schematicTrains;
            for (StellwerkTrainView train : trains) {
                String lineIdRaw = sanitizeLineId("line_" + shortTrainToken(train.trainId()));
                LineId lineId = new LineId(lineIdRaw);
                TrainLine line = lineRegistry.findLine(lineId).orElse(null);
                if (line == null) {
                    line = lineRegistry.createLine(lineId, "Line " + train.trainName());
                }
                line.setDisplayName(train.trainName());
                generatedAny = true;
            }
        }

        if (!generatedAny) {
            return false;
        }

        lineRegistry.markDirty();
        return true;
    }

    public Optional<String> createRoute(
        LineRegistry lineRegistry,
        Map<String, List<String>> routeStationsByLine,
        Map<String, TrainServiceClass> routeServiceByLine,
        String routeNameRaw,
        String serviceClassRaw
    ) {
        if (lineRegistry == null) {
            return Optional.empty();
        }

        String routeName = routeNameRaw == null ? "" : routeNameRaw.trim();
        if (routeName.isBlank()) {
            return Optional.empty();
        }

        String baseId = sanitizeLineId("route_" + routeName);
        String finalId = baseId;
        int suffix = 2;
        while (lineRegistry.findLine(new LineId(finalId)).isPresent()) {
            finalId = baseId + "_" + suffix++;
        }

        TrainLine line = lineRegistry.createLine(new LineId(finalId), routeName);
        line.setDisplayName(routeName);
        routeStationsByLine.put(finalId, new ArrayList<>());
        routeServiceByLine.put(finalId, parseServiceClass(serviceClassRaw));
        lineRegistry.markDirty();
        return Optional.of(finalId);
    }

    public boolean updateRouteMeta(
        LineRegistry lineRegistry,
        Map<String, TrainServiceClass> routeServiceByLine,
        String lineId,
        String routeNameRaw,
        String serviceClassRaw
    ) {
        if (lineRegistry == null || lineId == null || lineId.isBlank() || "-".equals(lineId)) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineId)).orElse(null);
        if (line == null) {
            return false;
        }

        String routeName = routeNameRaw == null ? "" : routeNameRaw.trim();
        boolean changed = false;
        if (!routeName.isBlank() && !routeName.equals(line.displayName())) {
            line.setDisplayName(routeName);
            changed = true;
        }

        TrainServiceClass parsedService = parseServiceClass(serviceClassRaw);
        if (routeServiceByLine.getOrDefault(lineId, TrainServiceClass.RE) != parsedService) {
            routeServiceByLine.put(lineId, parsedService);
            changed = true;
        }

        if (!changed) {
            return false;
        }

        lineRegistry.markDirty();
        return true;
    }

    public boolean deleteRoute(
        LineRegistry lineRegistry,
        Map<String, TrainServiceClass> routeServiceByLine,
        Map<String, List<String>> routeStationsByLine,
        String lineIdRaw
    ) {
        if (lineRegistry == null) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineIdValue.isBlank()) {
            return false;
        }

        boolean removed = lineRegistry.removeLine(new LineId(lineIdValue));
        if (!removed) {
            return false;
        }

        routeServiceByLine.remove(lineIdValue);
        routeStationsByLine.remove(lineIdValue);
        return true;
    }

    public boolean editRouteStation(
        LineRegistry lineRegistry,
        Map<String, List<String>> routeStationsByLine,
        Map<String, TrainServiceClass> routeServiceByLine,
        String lineIdRaw,
        String stationNameRaw,
        boolean add
    ) {
        if (lineRegistry == null) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        String stationName = stationNameRaw == null ? "" : stationNameRaw.trim();
        if (lineIdValue.isBlank() || stationName.isBlank()) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineIdValue)).orElse(null);
        if (line == null) {
            return false;
        }

        List<String> orderedStations = routeStationsByLine.computeIfAbsent(lineIdValue, ignored -> new ArrayList<>());
        String normalizedStation = normalizeRoutePoint(stationName);
        boolean changed;
        if (add) {
            changed = !orderedStations.contains(normalizedStation) && orderedStations.add(normalizedStation);
        } else {
            changed = orderedStations.remove(normalizedStation);
        }
        if (!changed) {
            return false;
        }

        applyRouteStationsToLine(line, orderedStations);
        routeServiceByLine.putIfAbsent(lineIdValue, TrainServiceClass.RE);
        lineRegistry.markDirty();
        return true;
    }

    public boolean moveRouteStation(
        LineRegistry lineRegistry,
        Map<String, List<String>> routeStationsByLine,
        String lineIdRaw,
        int fromIndex,
        int toIndex
    ) {
        if (lineRegistry == null) {
            return false;
        }

        String lineIdValue = lineIdRaw == null ? "" : lineIdRaw.trim();
        if (lineIdValue.isBlank()) {
            return false;
        }

        TrainLine line = lineRegistry.findLine(new LineId(lineIdValue)).orElse(null);
        List<String> orderedStations = routeStationsByLine.get(lineIdValue);
        if (line == null || orderedStations == null || orderedStations.isEmpty()) {
            return false;
        }

        if (fromIndex < 0 || fromIndex >= orderedStations.size() || toIndex < 0 || toIndex >= orderedStations.size()) {
            return false;
        }
        if (fromIndex == toIndex) {
            return false;
        }

        String station = orderedStations.remove(fromIndex);
        orderedStations.add(toIndex, station);
        applyRouteStationsToLine(line, orderedStations);
        lineRegistry.markDirty();
        return true;
    }

    private static String shortTrainToken(UUID trainId) {
        String raw = trainId.toString().replace("-", "");
        return raw.length() <= 6 ? raw : raw.substring(0, 6);
    }

    private static String sanitizeLineId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "line";
        }
        String cleaned = raw.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return cleaned.isBlank() ? "line" : cleaned;
    }

    private void applyRouteStationsToLine(TrainLine line, List<String> orderedStations) {
        Set<String> current = Set.copyOf(line.stationNames());
        for (String station : current) {
            line.removeStationName(station);
        }
        for (String station : orderedStations) {
            line.addStationName(station);
        }
    }

    private TrainServiceClass parseServiceClass(String raw) {
        return TrainServiceClass.fromStringOrDefault(raw, TrainServiceClass.RE);
    }

    private String normalizeRoutePoint(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        if (normalized.startsWith("hubid:")) {
            String value = normalized.substring("hubid:".length()).trim();
            return value.isBlank() ? "" : "hubid:" + value;
        }
        if (normalized.startsWith("hub:")) {
            String value = normalized.substring("hub:".length()).trim();
            return value.isBlank() ? "" : "hubid:" + value;
        }
        if (normalized.startsWith("station:")) {
            String value = normalized.substring("station:".length()).trim();
            return value.isBlank() ? "" : "station:" + value;
        }

        return normalized;
    }
}
