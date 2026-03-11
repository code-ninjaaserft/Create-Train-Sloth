package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScheduleDestinationResolver {

    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile("^(.*?)(?:\\s+\\d+)?$");
    private final ScheduleAlternativeResolver scheduleAlternativeResolver;
    private final StationHubRegistry stationHubRegistry;
    private final LineManager lineManager;

    public ScheduleDestinationResolver(
        ScheduleAlternativeResolver scheduleAlternativeResolver,
        StationHubRegistry stationHubRegistry,
        LineManager lineManager
    ) {
        this.scheduleAlternativeResolver = scheduleAlternativeResolver;
        this.stationHubRegistry = stationHubRegistry;
        this.lineManager = lineManager;
    }

    public Optional<DestinationContext> resolve(Train train) {
        if (train.graph == null) {
            return Optional.empty();
        }

        List<GlobalStation> allStations = sortedStations(train);
        Optional<DestinationInstruction> destinationInstruction = scheduleAlternativeResolver.resolveCurrentMainDestination(train);
        Optional<MainDestinationResolution> mainResolution = resolveMainDestination(train, allStations, destinationInstruction);
        if (mainResolution.isEmpty()) {
            mainResolution = resolveLineDestination(train, allStations);
        }
        if (mainResolution.isEmpty()) {
            return Optional.empty();
        }

        GlobalStation primaryDestination = mainResolution.get().primaryDestination();
        Map<UUID, GlobalStation> candidateById = new LinkedHashMap<>();
        candidateById.put(primaryDestination.id, primaryDestination);
        for (GlobalStation station : mainResolution.get().mainCandidates()) {
            candidateById.putIfAbsent(station.id, station);
        }

        String sourceFilter = mainResolution.get().sourceFilter();
        if (shouldApplyNumericFamilyFallback(destinationInstruction)) {
            addNumericFamilyStations(allStations, primaryDestination.name, candidateById);
        }

        Map<UUID, Integer> alternativeEntryByStation = new LinkedHashMap<>();
        if (TrainSlothConfig.ROUTING.useScheduleDestinationAlternatives.get()) {
            List<ScheduleAlternativeResolver.AlternativeEntry> alternatives =
                scheduleAlternativeResolver.collectAlternativeEntriesForCurrentEntry(train);

            for (ScheduleAlternativeResolver.AlternativeEntry alternative : alternatives) {
                List<GlobalStation> alternativeStations = resolveStationsForFilter(allStations, alternative.filter());
                for (GlobalStation station : alternativeStations) {
                    if (station.id.equals(primaryDestination.id)) {
                        continue;
                    }
                    candidateById.putIfAbsent(station.id, station);
                    alternativeEntryByStation.putIfAbsent(station.id, alternative.entryIndex());
                }
            }
            if (!alternatives.isEmpty()) {
                sourceFilter = sourceFilter + " +alts(" + alternatives.size() + ")";
            }
        }

        return Optional.of(
            new DestinationContext(
                primaryDestination,
                List.copyOf(candidateById.values()),
                Map.copyOf(alternativeEntryByStation),
                sourceFilter
            )
        );
    }

    private Optional<MainDestinationResolution> resolveMainDestination(
        Train train,
        List<GlobalStation> allStations,
        Optional<DestinationInstruction> destinationInstruction
    ) {
        List<GlobalStation> mainCandidates = List.of();
        String sourceFilter = train.navigation.destination != null ? train.navigation.destination.name : "<none>";

        if (destinationInstruction.isPresent()) {
            DestinationInstruction instruction = destinationInstruction.get();
            ResolvedFilter resolved = resolveMainInstructionFilter(allStations, instruction);
            mainCandidates = resolved.stations();
            sourceFilter = resolved.source();
        }

        GlobalStation primaryDestination;
        if (train.navigation.destination != null) {
            primaryDestination = train.navigation.destination;
        } else {
            if (mainCandidates.isEmpty()) {
                return Optional.empty();
            }

            DiscoveredPath bestPath = train.navigation.findPathTo(new ArrayList<>(mainCandidates), TrainSlothConfig.ROUTING.maxSearchCost.get());
            if (bestPath != null && bestPath.destination != null) {
                primaryDestination = bestPath.destination;
            } else {
                primaryDestination = mainCandidates.get(0);
            }
        }

        if (mainCandidates.isEmpty()) {
            mainCandidates = List.of(primaryDestination);
        }

        return Optional.of(new MainDestinationResolution(primaryDestination, List.copyOf(mainCandidates), sourceFilter));
    }

    private Optional<MainDestinationResolution> resolveLineDestination(Train train, List<GlobalStation> allStations) {
        if (lineManager == null) {
            return Optional.empty();
        }

        Optional<TrainLine> optionalLine = lineManager.lineForTrain(train);
        if (optionalLine.isEmpty()) {
            return Optional.empty();
        }

        TrainLine line = optionalLine.get();
        List<GlobalStation> lineStations = new ArrayList<>();
        for (GlobalStation station : allStations) {
            if (line.matchesStation(station)) {
                lineStations.add(station);
            }
        }
        if (lineStations.isEmpty()) {
            return Optional.empty();
        }

        GlobalStation currentStation = train.getCurrentStation();
        UUID currentStationId = currentStation == null ? null : currentStation.id;

        GlobalStation primary = null;
        if (!lineStations.isEmpty()) {
            List<GlobalStation> preferredStations = lineStations.stream()
                .filter(station -> currentStationId == null || !station.id.equals(currentStationId))
                .toList();
            List<GlobalStation> searchPool = preferredStations.isEmpty() ? lineStations : preferredStations;
            DiscoveredPath bestPath = train.navigation.findPathTo(new ArrayList<>(searchPool), TrainSlothConfig.ROUTING.maxSearchCost.get());
            if (bestPath != null && bestPath.destination != null) {
                primary = bestPath.destination;
            } else if (!searchPool.isEmpty()) {
                primary = searchPool.get(0);
            }
        }

        if (primary == null) {
            primary = lineStations.get(0);
        }

        return Optional.of(new MainDestinationResolution(primary, List.copyOf(lineStations), "line:" + line.id().value()));
    }

    private ResolvedFilter resolveMainInstructionFilter(List<GlobalStation> allStations, DestinationInstruction instruction) {
        String filter = instruction.getFilter();
        Optional<StationHub> hub = resolveHubForFilter(filter);
        if (hub.isPresent()) {
            List<GlobalStation> hubStations = resolveStationsForHub(allStations, hub.get());
            if (!hubStations.isEmpty()) {
                return new ResolvedFilter(hubStations, "hub:" + hub.get().id().value());
            }
        }

        List<GlobalStation> matched = matchByRegex(allStations, instruction.getFilterForRegex());
        return new ResolvedFilter(matched, filter);
    }

    private List<GlobalStation> resolveStationsForFilter(List<GlobalStation> allStations, String filter) {
        Optional<StationHub> hub = resolveHubForFilter(filter);
        if (hub.isPresent()) {
            List<GlobalStation> hubStations = resolveStationsForHub(allStations, hub.get());
            if (!hubStations.isEmpty()) {
                return hubStations;
            }
        }
        return matchByRegex(allStations, toRegex(filter));
    }

    private List<GlobalStation> resolveStationsForHub(List<GlobalStation> allStations, StationHub hub) {
        List<GlobalStation> matched = new ArrayList<>();
        for (GlobalStation station : allStations) {
            if (hub.matchesStation(station)) {
                matched.add(station);
            }
        }
        return List.copyOf(matched);
    }

    private List<GlobalStation> matchByRegex(List<GlobalStation> stations, String regex) {
        List<GlobalStation> matched = new ArrayList<>();
        for (GlobalStation station : stations) {
            if (station.name != null && station.name.matches(regex)) {
                matched.add(station);
            }
        }
        return List.copyOf(matched);
    }

    private Optional<StationHub> resolveHubForFilter(String filter) {
        if (stationHubRegistry == null || !isExactFilter(filter)) {
            return Optional.empty();
        }
        return stationHubRegistry.findHubForScheduleFilter(filter);
    }

    private boolean shouldApplyNumericFamilyFallback(Optional<DestinationInstruction> destinationInstruction) {
        if (!TrainSlothConfig.ROUTING.enableNumericStationFamilyFallback.get()) {
            return false;
        }

        if (destinationInstruction.isEmpty()) {
            return true;
        }

        String filter = destinationInstruction.get().getFilter();
        if (!isExactFilter(filter)) {
            return false;
        }

        return resolveHubForFilter(filter).isEmpty();
    }

    private List<GlobalStation> sortedStations(Train train) {
        List<GlobalStation> stations = new ArrayList<>(train.graph.getPoints(EdgePointType.STATION));
        stations.sort(
            Comparator.comparing((GlobalStation station) -> normalizeStation(station.name))
                .thenComparing(station -> station.id.toString())
        );
        return stations;
    }

    private void addNumericFamilyStations(
        List<GlobalStation> allStations,
        String baseStationNameRaw,
        Map<UUID, GlobalStation> candidateById
    ) {
        String familyBase = resolveFamilyBase(baseStationNameRaw);
        if (familyBase.isBlank()) {
            return;
        }

        for (GlobalStation station : allStations) {
            String normalizedName = normalizeStation(station.name);
            if (isNumericStationSibling(normalizedName, familyBase)) {
                candidateById.putIfAbsent(station.id, station);
            }
        }
    }

    private static String resolveFamilyBase(String stationNameRaw) {
        String normalized = normalizeStation(stationNameRaw);
        Matcher matcher = TRAILING_NUMBER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return normalized;
        }
        return matcher.group(1).trim();
    }

    private static boolean isNumericStationSibling(String normalizedStationName, String normalizedBase) {
        if (normalizedStationName.equals(normalizedBase)) {
            return true;
        }

        if (!normalizedStationName.startsWith(normalizedBase + " ")) {
            return false;
        }

        String suffix = normalizedStationName.substring(normalizedBase.length()).trim();
        if (suffix.isBlank()) {
            return false;
        }

        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isExactFilter(String filter) {
        return filter != null && !filter.contains("*");
    }

    private static String toRegex(String filter) {
        if (filter == null || filter.isBlank()) {
            return ".*";
        }

        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < filter.length(); i++) {
            char c = filter.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                if ("\\.[]{}()+-^$?|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private static String normalizeStation(String stationNameRaw) {
        if (stationNameRaw == null) {
            return "";
        }
        return stationNameRaw.trim().toLowerCase(Locale.ROOT);
    }

    public record DestinationContext(
        GlobalStation primaryDestination,
        List<GlobalStation> candidateStations,
        Map<UUID, Integer> alternativeEntryByStation,
        String sourceFilter
    ) {
    }

    private record ResolvedFilter(List<GlobalStation> stations, String source) {
    }

    private record MainDestinationResolution(
        GlobalStation primaryDestination,
        List<GlobalStation> mainCandidates,
        String sourceFilter
    ) {
    }
}
