package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import dev.elved.createtrainsloth.config.TrainSlothConfig;
import dev.elved.createtrainsloth.schedule.AlternativeDestinationInstruction;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public class ScheduleAlternativeResolver {

    private final Map<UUID, Integer> activeAlternativeEntryByTrain = new HashMap<>();
    private final Map<UUID, Integer> activeMainEntryByTrain = new HashMap<>();
    private final Map<UUID, MainDestinationOverride> activeMainDestinationOverrideByTrain = new HashMap<>();

    public void rebindMainConditionsAfterAlternativeArrival(List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            restoreMainDestinationOverridesAfterArrival(trains);
            activeAlternativeEntryByTrain.clear();
            activeMainEntryByTrain.clear();
            return;
        }

        List<Train> sorted = new ArrayList<>(trains);
        sorted.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : sorted) {
            UUID trainId = train.id;
            Integer alternativeEntry = activeAlternativeEntryByTrain.get(trainId);
            Integer mainEntry = activeMainEntryByTrain.get(trainId);
            if (alternativeEntry == null || mainEntry == null) {
                continue;
            }

            if (train.runtime == null || train.runtime.getSchedule() == null || train.runtime.paused || train.derailed) {
                clearActivation(trainId);
                continue;
            }

            Schedule schedule = train.runtime.getSchedule();
            if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
                clearActivation(trainId);
                continue;
            }

            int size = schedule.entries.size();
            if (alternativeEntry < 0 || alternativeEntry >= size || mainEntry < 0 || mainEntry >= size) {
                clearActivation(trainId);
                continue;
            }

            if (train.runtime.state == ScheduleRuntime.State.POST_TRANSIT
                && train.runtime.currentEntry == alternativeEntry
                && train.navigation.destination == null) {
                train.runtime.currentEntry = mainEntry;
                reinitializeConditionProgressForEntry(train.runtime, schedule, mainEntry);
                train.runtime.displayLinkUpdateRequested = true;
                clearActivation(trainId);
                continue;
            }

            if (train.runtime.state == ScheduleRuntime.State.PRE_TRANSIT
                && train.runtime.currentEntry != alternativeEntry
                && train.runtime.currentEntry != mainEntry) {
                clearActivation(trainId);
            }
        }
    }

    public void advancePastAlternativeEntries(List<Train> trains) {
        if (!TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            restoreMainDestinationOverridesAfterArrival(trains);
            activeAlternativeEntryByTrain.clear();
            activeMainEntryByTrain.clear();
            return;
        }

        List<Train> sorted = new ArrayList<>(trains);
        sorted.sort(Comparator.comparing(train -> train.id.toString()));

        for (Train train : sorted) {
            if (train.runtime == null || train.runtime.paused || train.derailed) {
                continue;
            }
            if (train.runtime.state != ScheduleRuntime.State.PRE_TRANSIT) {
                continue;
            }

            Schedule schedule = train.runtime.getSchedule();
            if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
                continue;
            }

            int size = schedule.entries.size();
            int current = train.runtime.currentEntry;
            if (current < 0 || current >= size) {
                continue;
            }

            int safety = 0;
            boolean changed = false;
            while (safety++ < size) {
                ScheduleEntry entry = schedule.entries.get(train.runtime.currentEntry);
                if (!isAlternativeInstruction(entry.instruction)) {
                    break;
                }
                if (!isAlternativeLinkedToPreviousMain(schedule, train.runtime.currentEntry)) {
                    break;
                }

                train.runtime.currentEntry++;
                changed = true;

                if (train.runtime.currentEntry < size) {
                    continue;
                }

                train.runtime.currentEntry = 0;
                if (!schedule.cyclic) {
                    train.runtime.paused = true;
                    train.runtime.completed = true;
                    break;
                }
            }

            if (changed) {
                train.runtime.displayLinkUpdateRequested = true;
            }
        }
    }

    public List<String> collectAlternativeFiltersForCurrentEntry(Train train) {
        return collectAlternativeEntriesForCurrentEntry(train).stream()
            .map(AlternativeEntry::filter)
            .toList();
    }

    public List<AlternativeEntry> collectAlternativeEntriesForCurrentEntry(Train train) {
        if (!TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            return List.of();
        }

        if (train.runtime == null) {
            return List.of();
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return List.of();
        }

        int current = train.runtime.currentEntry;
        if (current < 0 || current >= schedule.entries.size()) {
            return List.of();
        }

        List<AlternativeEntry> alternatives = new ArrayList<>();
        for (int i = current + 1; i < schedule.entries.size(); i++) {
            ScheduleInstruction instruction = schedule.entries.get(i).instruction;
            if (!isAlternativeInstruction(instruction)) {
                break;
            }
            if (!isAlternativeLinkedToPreviousMain(schedule, i)) {
                break;
            }
            if (instruction instanceof DestinationInstruction destinationInstruction) {
                String filter = destinationInstruction.getFilter();
                if (filter != null && !filter.isBlank()) {
                    alternatives.add(new AlternativeEntry(i, filter));
                }
            }
        }

        return List.copyOf(alternatives);
    }

    public Optional<DestinationInstruction> resolveCurrentMainDestination(Train train) {
        if (train.runtime == null) {
            return Optional.empty();
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return Optional.empty();
        }

        int current = train.runtime.currentEntry;
        if (current < 0 || current >= schedule.entries.size()) {
            return Optional.empty();
        }

        ScheduleInstruction instruction = schedule.entries.get(current).instruction;
        if (isAlternativeInstruction(instruction)) {
            return Optional.empty();
        }
        if (instruction instanceof DestinationInstruction destinationInstruction) {
            return Optional.of(destinationInstruction);
        }
        return Optional.empty();
    }

    public boolean isAlternativeInstruction(ScheduleInstruction instruction) {
        return instruction != null && AlternativeDestinationInstruction.ID.equals(instruction.getId());
    }

    public boolean activateAlternativeEntry(Train train, int entryIndex) {
        if (!TrainSlothConfig.ROUTING.enableScheduleAlternativeInstruction.get()) {
            return false;
        }
        if (train.runtime == null) {
            return false;
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return false;
        }
        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return false;
        }

        ScheduleInstruction instruction = schedule.entries.get(entryIndex).instruction;
        if (!isAlternativeInstruction(instruction)) {
            return false;
        }

        int currentEntry = train.runtime.currentEntry;
        if (currentEntry >= 0 && currentEntry < schedule.entries.size()) {
            ScheduleInstruction currentInstruction = schedule.entries.get(currentEntry).instruction;
            if (!isAlternativeInstruction(currentInstruction) && currentInstruction instanceof DestinationInstruction) {
                activeMainEntryByTrain.put(train.id, currentEntry);
                activeAlternativeEntryByTrain.put(train.id, entryIndex);
            }
        }

        train.runtime.currentEntry = entryIndex;
        train.runtime.displayLinkUpdateRequested = true;
        return true;
    }

    public boolean activateMainDestinationOverride(Train train, String stationFilter) {
        if (train == null || stationFilter == null || stationFilter.isBlank()) {
            return false;
        }

        if (train.runtime == null) {
            return false;
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return false;
        }

        int entryIndex = train.runtime.currentEntry;
        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return false;
        }

        ScheduleEntry entry = schedule.entries.get(entryIndex);
        if (!(entry.instruction instanceof DestinationInstruction destinationInstruction)) {
            return false;
        }
        if (isAlternativeInstruction(entry.instruction)) {
            return false;
        }

        MainDestinationOverride existing = activeMainDestinationOverrideByTrain.get(train.id);
        if (existing != null && existing.entryIndex() != entryIndex) {
            restoreMainDestinationOverrideNow(train);
            existing = null;
        }

        String forcedFilter = stationFilter.trim();
        String currentFilter = destinationInstruction.getFilter();
        if (forcedFilter.equals(currentFilter)) {
            if (existing == null) {
                return true;
            }

            activeMainDestinationOverrideByTrain.put(
                train.id,
                new MainDestinationOverride(existing.entryIndex(), existing.originalFilter(), forcedFilter)
            );
            return true;
        }

        String originalFilter = existing == null ? currentFilter : existing.originalFilter();
        destinationInstruction.getData().putString("Text", forcedFilter);
        train.runtime.displayLinkUpdateRequested = true;
        activeMainDestinationOverrideByTrain.put(
            train.id,
            new MainDestinationOverride(entryIndex, originalFilter, forcedFilter)
        );
        return true;
    }

    public boolean restoreMainDestinationOverrideNow(Train train) {
        if (train == null) {
            return false;
        }

        MainDestinationOverride override = activeMainDestinationOverrideByTrain.remove(train.id);
        if (override == null) {
            return false;
        }

        if (train.runtime == null) {
            return false;
        }

        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
            return false;
        }

        boolean restored = applyDestinationFilter(schedule, override.entryIndex(), override.originalFilter());
        if (restored) {
            train.runtime.displayLinkUpdateRequested = true;
        }
        return restored;
    }

    public void restoreMainDestinationOverridesAfterArrival(List<Train> trains) {
        if (activeMainDestinationOverrideByTrain.isEmpty()) {
            return;
        }

        Map<UUID, Train> trainById = new HashMap<>();
        for (Train train : trains) {
            trainById.put(train.id, train);
        }

        List<UUID> toClear = new ArrayList<>();
        for (Map.Entry<UUID, MainDestinationOverride> entry : activeMainDestinationOverrideByTrain.entrySet()) {
            UUID trainId = entry.getKey();
            MainDestinationOverride override = entry.getValue();
            Train train = trainById.get(trainId);

            if (train == null || train.runtime == null || train.derailed) {
                toClear.add(trainId);
                continue;
            }

            Schedule schedule = train.runtime.getSchedule();
            if (schedule == null || schedule.entries == null || schedule.entries.isEmpty()) {
                toClear.add(trainId);
                continue;
            }

            int entryIndex = override.entryIndex();
            if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
                toClear.add(trainId);
                continue;
            }

            boolean leftOverriddenEntry = train.runtime.currentEntry != entryIndex;
            boolean arrivedAtOverriddenDestination = train.runtime.currentEntry == entryIndex
                && train.runtime.state == ScheduleRuntime.State.POST_TRANSIT
                && train.navigation.destination == null;
            boolean noLongerInTransit = train.runtime.state == ScheduleRuntime.State.PRE_TRANSIT && leftOverriddenEntry;

            if (!leftOverriddenEntry && !arrivedAtOverriddenDestination && !noLongerInTransit) {
                continue;
            }

            if (applyDestinationFilter(schedule, entryIndex, override.originalFilter())) {
                train.runtime.displayLinkUpdateRequested = true;
            }
            toClear.add(trainId);
        }

        toClear.forEach(activeMainDestinationOverrideByTrain::remove);
    }

    private boolean applyDestinationFilter(Schedule schedule, int entryIndex, String filter) {
        if (schedule == null || schedule.entries == null || filter == null || filter.isBlank()) {
            return false;
        }
        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return false;
        }

        ScheduleEntry entry = schedule.entries.get(entryIndex);
        if (!(entry.instruction instanceof DestinationInstruction destinationInstruction)) {
            return false;
        }
        if (isAlternativeInstruction(entry.instruction)) {
            return false;
        }

        destinationInstruction.getData().putString("Text", filter);
        return true;
    }

    private boolean isAlternativeLinkedToPreviousMain(Schedule schedule, int index) {
        if (index <= 0 || schedule.entries == null || schedule.entries.isEmpty()) {
            return false;
        }

        for (int i = index - 1; i >= 0; i--) {
            ScheduleInstruction previous = schedule.entries.get(i).instruction;
            if (isAlternativeInstruction(previous)) {
                continue;
            }
            return previous instanceof DestinationInstruction;
        }

        return false;
    }

    private void clearActivation(UUID trainId) {
        activeAlternativeEntryByTrain.remove(trainId);
        activeMainEntryByTrain.remove(trainId);
    }

    private void reinitializeConditionProgressForEntry(ScheduleRuntime runtime, Schedule schedule, int entryIndex) {
        runtime.conditionProgress.clear();
        runtime.conditionContext.clear();

        if (entryIndex < 0 || entryIndex >= schedule.entries.size()) {
            return;
        }

        ScheduleEntry entry = schedule.entries.get(entryIndex);
        if (entry == null || entry.conditions == null || !entry.instruction.supportsConditions()) {
            return;
        }

        List<List<ScheduleWaitCondition>> conditions = entry.conditions;
        for (int i = 0; i < conditions.size(); i++) {
            runtime.conditionProgress.add(0);
            runtime.conditionContext.add(new CompoundTag());
        }
    }

    public record AlternativeEntry(int entryIndex, String filter) {
    }

    private record MainDestinationOverride(int entryIndex, String originalFilter, String forcedFilter) {
    }
}
