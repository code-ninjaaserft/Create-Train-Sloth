package dev.elved.createtrainsloth.line;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
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
    private final Map<UUID, LineId> managedAssignments = new HashMap<>();

    public ScheduleLineSyncService(LineRegistry lineRegistry) {
        this.lineRegistry = lineRegistry;
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

            changed |= applySettings(line, spec.settings());
            if (changed) {
                lineRegistry.markDirty();
            }

            Optional<TrainLineAssignment> currentAssignment = lineRegistry.assignmentOf(train.id);
            boolean force = TrainSlothConfig.SCHEDULE.forceScheduleLineAssignment.get();
            boolean assignedByScheduleBefore = managedAssignments.containsKey(train.id);
            boolean shouldAssign = force || assignedByScheduleBefore || currentAssignment.isEmpty();

            if (shouldAssign) {
                lineRegistry.assignTrain(train.id, spec.lineId());
                managedAssignments.put(train.id, spec.lineId());
            }
        }

        managedAssignments.keySet().removeIf(trainId -> !seenTrains.contains(trainId));
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

        return Optional.of(new DerivedLineSpec(new LineId(lineIdValue), displayName, stationFilters, overrides));
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
        LineSettingsOverrides settings
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
