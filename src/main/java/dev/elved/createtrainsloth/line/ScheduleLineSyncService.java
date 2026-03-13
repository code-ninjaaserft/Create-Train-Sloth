package dev.elved.createtrainsloth.line;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ScheduleLineSyncService {

    private final LineRegistry lineRegistry;
    private final StationHubRegistry stationHubRegistry;
    private final Map<UUID, LineId> managedAssignments = new HashMap<>();

    public ScheduleLineSyncService(LineRegistry lineRegistry, StationHubRegistry stationHubRegistry) {
        this.lineRegistry = lineRegistry;
        this.stationHubRegistry = stationHubRegistry;
    }

    public void syncFromSchedules(List<Train> trains) {
        if (!TrainSlothConfig.SCHEDULE.enableScheduleLineAutoSetup.get()) {
            return;
        }

        List<Train> sorted = new ArrayList<>(trains);
        sorted.sort(Comparator.comparing(train -> train.id.toString()));

        Set<UUID> seenTrains = new LinkedHashSet<>();
        for (Train train : sorted) {
            seenTrains.add(train.id);
            syncHubMetadataFromSchedule(train);
            Optional<DerivedLineSpec> optionalSpec = deriveLineSpec(train);
            if (optionalSpec.isEmpty()) {
                clearManagedAssignmentIfNeeded(train.id);
                continue;
            }

            DerivedLineSpec spec = optionalSpec.get();
            TrainLine line = lineRegistry.findLine(spec.lineId()).orElseGet(() -> lineRegistry.createLine(spec.lineId(), spec.displayName()));
            boolean changed = false;

            if (!line.displayName().equals(spec.displayName())) {
                line.setDisplayName(spec.displayName());
                changed = true;
            }

            for (String stationFilter : spec.stationFilters()) {
                changed |= line.addStationName(stationFilter);
            }

            if (spec.serviceClass() != null && line.settings().resolveServiceClass() != spec.serviceClass()) {
                line.settings().setServiceClass(spec.serviceClass());
                changed = true;
            }

            changed |= applySettings(line, spec.settings());
            if (changed) {
                lineRegistry.markDirty();
            }

            Optional<TrainLineAssignment> currentAssignment = lineRegistry.assignmentOf(train.id);
            boolean idleAtDepot = isIdleAtDepot(train);
            if (idleAtDepot && currentAssignment.isEmpty()) {
                managedAssignments.remove(train.id);
                continue;
            }

            LineId managedLine = managedAssignments.get(train.id);
            if (managedLine != null
                && currentAssignment.isPresent()
                && !currentAssignment.get().lineId().equals(managedLine)) {
                managedAssignments.remove(train.id);
                managedLine = null;
            }

            if (currentAssignment.isPresent() && !currentAssignment.get().lineId().equals(spec.lineId())) {
                continue;
            }

            boolean force = TrainSlothConfig.SCHEDULE.forceScheduleLineAssignment.get();
            boolean assignedByScheduleBefore = managedLine != null;
            boolean shouldAssign = !idleAtDepot && (force || assignedByScheduleBefore || currentAssignment.isEmpty());

            if (shouldAssign) {
                TrainServiceClass serviceClass = spec.serviceClass();
                if (serviceClass == null) {
                    serviceClass = currentAssignment.map(TrainLineAssignment::serviceClass).orElse(TrainServiceClass.RE);
                }
                lineRegistry.assignTrain(train.id, spec.lineId(), serviceClass);
                managedAssignments.put(train.id, spec.lineId());
            }
        }

        managedAssignments.keySet().removeIf(trainId -> !seenTrains.contains(trainId));
    }

    private boolean isIdleAtDepot(Train train) {
        if (train == null || stationHubRegistry == null) {
            return false;
        }
        if (train.runtime == null) {
            return false;
        }
        if (train.navigation.destination != null) {
            return false;
        }

        GlobalStation currentStation = train.getCurrentStation();
        if (currentStation == null) {
            return false;
        }

        for (StationHub hub : stationHubRegistry.allHubs()) {
            if (hub.isDepotHub() && hub.matchesStation(currentStation)) {
                return true;
            }
        }
        return false;
    }

    private Optional<DerivedLineSpec> deriveLineSpec(Train train) {
        if (train.runtime == null) {
            return Optional.empty();
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashSet<String> stationFilters = new LinkedHashSet<>();
        Map<String, String> metadata = new HashMap<>();
        int maxTimedWaitTicks = 0;

        for (ScheduleEntry entry : schedule.entries) {
            if (entry == null || entry.instruction == null) {
                continue;
            }

            ScheduleInstruction instruction = entry.instruction;
            if (instruction instanceof DestinationInstruction destinationInstruction) {
                String filter = destinationInstruction.getFilter();
                if (filter != null && !filter.isBlank()) {
                    stationFilters.add(filter.trim().toLowerCase(Locale.ROOT));
                }
            } else if (instruction instanceof ChangeTitleInstruction changeTitleInstruction) {
                metadata.putAll(parseLineMetadata(changeTitleInstruction.getScheduleTitle()));
            }

            if (TrainSlothConfig.SCHEDULE.deriveDwellFromTimedConditions.get() && entry.conditions != null) {
                for (List<ScheduleWaitCondition> conditionGroup : entry.conditions) {
                    for (ScheduleWaitCondition condition : conditionGroup) {
                        if (condition instanceof TimedWaitCondition timedWaitCondition) {
                            maxTimedWaitTicks = Math.max(maxTimedWaitTicks, timedWaitCondition.totalWaitTicks());
                        }
                    }
                }
            }
        }

        String lineIdValue = metadata.get("line");
        String displayName = metadata.getOrDefault("name", "");

        if ((lineIdValue == null || lineIdValue.isBlank()) && stationFilters.isEmpty()) {
            return Optional.empty();
        }

        if (lineIdValue == null || lineIdValue.isBlank()) {
            String signature = String.join(">", stationFilters);
            lineIdValue = "sched_" + Integer.toUnsignedString(signature.hashCode(), 36);
            if (displayName.isBlank()) {
                displayName = "Schedule " + lineIdValue.substring("sched_".length());
            }
        }

        if (displayName.isBlank()) {
            displayName = lineIdValue;
        }

        LineSettingsOverrides overrides = parseSettingOverrides(metadata);
        if (maxTimedWaitTicks > 0 && overrides.minimumDwellTicks() == null) {
            overrides = overrides.withMinimumDwellTicks(maxTimedWaitTicks);
        }

        TrainServiceClass serviceClass = parseServiceClass(metadata);
        return Optional.of(new DerivedLineSpec(new LineId(lineIdValue), displayName, stationFilters, overrides, serviceClass));
    }

    private void syncHubMetadataFromSchedule(Train train) {
        if (train.runtime == null) {
            return;
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return;
        }

        for (ScheduleEntry entry : schedule.entries) {
            if (entry == null || !(entry.instruction instanceof ChangeTitleInstruction changeTitleInstruction)) {
                continue;
            }

            Map<String, String> metadata = parseLineMetadata(changeTitleInstruction.getScheduleTitle());
            String hubRaw = metadata.get("hub");
            if (hubRaw == null || hubRaw.isBlank()) {
                continue;
            }

            StationHubId hubId = new StationHubId(sanitizeLineId(hubRaw));
            String hubDisplayName = metadata.getOrDefault("hub_name", metadata.getOrDefault("hub_display", hubId.value()));
            StationHub hub = stationHubRegistry.findHub(hubId).orElseGet(() -> stationHubRegistry.createHub(hubId, hubDisplayName));
            hub.setDisplayName(hubDisplayName);
            stationHubRegistry.markDirty();

            String platform = metadata.get("platform");
            if (platform == null || platform.isBlank()) {
                platform = metadata.get("station");
            }
            if (platform != null && !platform.isBlank()) {
                stationHubRegistry.addPlatform(hubId, platform);
            }
        }
    }

    private boolean applySettings(TrainLine line, LineSettingsOverrides overrides) {
        boolean changed = false;

        if (overrides.minimumIntervalTicks() != null && overrides.minimumIntervalTicks() != line.settings().getMinimumIntervalTicksRaw()) {
            line.settings().setMinimumIntervalTicks(overrides.minimumIntervalTicks());
            changed = true;
        }
        if (overrides.targetIntervalOverrideTicks() != null && overrides.targetIntervalOverrideTicks() != line.settings().getTargetIntervalOverrideTicksRaw()) {
            line.settings().setTargetIntervalOverrideTicks(overrides.targetIntervalOverrideTicks());
            changed = true;
        }
        if (overrides.minimumDwellTicks() != null && overrides.minimumDwellTicks() != line.settings().getMinimumDwellTicksRaw()) {
            line.settings().setMinimumDwellTicks(overrides.minimumDwellTicks());
            changed = true;
        }
        if (overrides.dwellExtensionTicks() != null && overrides.dwellExtensionTicks() != line.settings().getDwellExtensionTicksRaw()) {
            line.settings().setDwellExtensionTicks(overrides.dwellExtensionTicks());
            changed = true;
        }
        if (overrides.safetyBufferTicks() != null && overrides.safetyBufferTicks() != line.settings().getSafetyBufferTicksRaw()) {
            line.settings().setSafetyBufferTicks(overrides.safetyBufferTicks());
            changed = true;
        }
        if (overrides.resynchronizationAggressiveness() != null
            && !overrides.resynchronizationAggressiveness().equals(line.settings().getResynchronizationAggressivenessRaw())) {
            line.settings().setResynchronizationAggressiveness(overrides.resynchronizationAggressiveness());
            changed = true;
        }
        if (overrides.routeSwitchCooldownTicks() != null && overrides.routeSwitchCooldownTicks() != line.settings().getRouteSwitchCooldownTicksRaw()) {
            line.settings().setRouteSwitchCooldownTicks(overrides.routeSwitchCooldownTicks());
            changed = true;
        }
        if (overrides.routeReplanWaitTicks() != null && overrides.routeReplanWaitTicks() != line.settings().getRouteReplanWaitTicksRaw()) {
            line.settings().setRouteReplanWaitTicks(overrides.routeReplanWaitTicks());
            changed = true;
        }

        return changed;
    }

    private void clearManagedAssignmentIfNeeded(UUID trainId) {
        LineId managedLine = managedAssignments.remove(trainId);
        if (managedLine == null) {
            return;
        }

        Optional<TrainLineAssignment> assignment = lineRegistry.assignmentOf(trainId);
        if (assignment.isPresent() && assignment.get().lineId().equals(managedLine)) {
            lineRegistry.unassignTrain(trainId);
        }
    }

    private Map<String, String> parseLineMetadata(String title) {
        if (title == null || title.isBlank()) {
            return Map.of();
        }

        String trimmed = title.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);

        if (lowered.startsWith("line:")) {
            String[] parts = trimmed.substring(5).trim().split("\\s+", 2);
            String lineId = parts.length == 0 ? "" : parts[0];
            if (lineId.isBlank()) {
                return Map.of();
            }
            return Map.of("line", sanitizeLineId(lineId));
        }

        if (!lowered.startsWith("cts:")) {
            return Map.of();
        }

        String payload = trimmed.substring(4).trim();
        if (payload.isBlank()) {
            return Map.of();
        }

        Map<String, String> metadata = new HashMap<>();
        String[] tokens = payload.split("[;|,]");
        for (String token : tokens) {
            String cleaned = token.trim();
            if (cleaned.isBlank()) {
                continue;
            }

            int separator = cleaned.indexOf('=');
            if (separator < 0) {
                separator = cleaned.indexOf(':');
            }
            if (separator < 0) {
                continue;
            }

            String key = cleaned.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = cleaned.substring(separator + 1).trim();
            if (value.isBlank()) {
                continue;
            }

            if (key.equals("line") || key.equals("l")) {
                metadata.put("line", sanitizeLineId(value));
            } else if (key.equals("name") || key.equals("title") || key.equals("display")) {
                metadata.put("name", value);
            } else {
                metadata.put(key, value);
            }
        }

        return metadata;
    }

    private String sanitizeLineId(String rawValue) {
        return rawValue
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
    }

    private LineSettingsOverrides parseSettingOverrides(Map<String, String> metadata) {
        return new LineSettingsOverrides(
            parseInt(metadata, "min_interval", "min"),
            parseInt(metadata, "target_interval", "target"),
            parseInt(metadata, "min_dwell", "dwell"),
            parseInt(metadata, "dwell_extension", "dwell_ext"),
            parseInt(metadata, "safety_buffer", "buffer"),
            parseDouble(metadata, "resync"),
            parseInt(metadata, "route_cooldown"),
            parseInt(metadata, "route_wait")
        );
    }

    private TrainServiceClass parseServiceClass(Map<String, String> metadata) {
        String value = null;
        if (metadata.containsKey("service")) {
            value = metadata.get("service");
        } else if (metadata.containsKey("class")) {
            value = metadata.get("class");
        } else if (metadata.containsKey("train_class")) {
            value = metadata.get("train_class");
        }

        if (value == null || value.isBlank()) {
            return null;
        }

        return TrainServiceClass.fromStringOrDefault(value, TrainServiceClass.RE);
    }

    private Integer parseInt(Map<String, String> metadata, String... keys) {
        for (String key : keys) {
            if (!metadata.containsKey(key)) {
                continue;
            }
            try {
                return Integer.parseInt(metadata.get(key));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double parseDouble(Map<String, String> metadata, String key) {
        if (!metadata.containsKey(key)) {
            return null;
        }
        try {
            return Double.parseDouble(metadata.get(key));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record DerivedLineSpec(
        LineId lineId,
        String displayName,
        Set<String> stationFilters,
        LineSettingsOverrides settings,
        TrainServiceClass serviceClass
    ) {
    }

    private record LineSettingsOverrides(
        Integer minimumIntervalTicks,
        Integer targetIntervalOverrideTicks,
        Integer minimumDwellTicks,
        Integer dwellExtensionTicks,
        Integer safetyBufferTicks,
        Double resynchronizationAggressiveness,
        Integer routeSwitchCooldownTicks,
        Integer routeReplanWaitTicks
    ) {
        LineSettingsOverrides withMinimumDwellTicks(int dwellTicks) {
            return new LineSettingsOverrides(
                minimumIntervalTicks,
                targetIntervalOverrideTicks,
                dwellTicks,
                dwellExtensionTicks,
                safetyBufferTicks,
                resynchronizationAggressiveness,
                routeSwitchCooldownTicks,
                routeReplanWaitTicks
            );
        }
    }
}
