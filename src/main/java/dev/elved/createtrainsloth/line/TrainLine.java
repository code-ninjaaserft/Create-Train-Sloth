package dev.elved.createtrainsloth.line;

import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public class TrainLine {

    private final LineId id;
    private String displayName;
    private final Set<String> stationNames;
    private final LineSettings settings;

    public TrainLine(LineId id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.stationNames = new LinkedHashSet<>();
        this.settings = new LineSettings();
    }

    public LineId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String value) {
        displayName = value == null || value.isBlank() ? id.value() : value.trim();
    }

    public LineSettings settings() {
        return settings;
    }

    public Collection<String> stationNames() {
        return Collections.unmodifiableSet(stationNames);
    }

    public int stationCount() {
        return stationNames.size();
    }

    public boolean addStationName(String stationName) {
        return stationNames.add(normalizeStation(stationName));
    }

    public boolean removeStationName(String stationName) {
        return stationNames.remove(normalizeStation(stationName));
    }

    public boolean matchesStation(GlobalStation station) {
        return matchesStationName(station.name);
    }

    public boolean matchesStationName(String stationNameRaw) {
        if (stationNames.isEmpty()) {
            return true;
        }
        String stationName = normalizeStation(stationNameRaw);
        for (String configured : stationNames) {
            String configuredStation = normalizeConfiguredStationFilter(configured);
            if (configuredStation.isBlank()) {
                continue;
            }

            if (configuredStation.contains("*")) {
                String regex = Pattern.quote(configuredStation).replace("\\*", "\\E.*\\Q");
                if (stationName.matches(regex)) {
                    return true;
                }
                continue;
            }
            if (configuredStation.equals(stationName)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeConfiguredStationFilter(String configuredRaw) {
        String configured = normalizeStation(configuredRaw);
        if (configured.startsWith("station:")) {
            return configured.substring("station:".length()).trim();
        }
        if (configured.startsWith("hubid:") || configured.startsWith("hub:")) {
            // Hub resolution requires StationHubRegistry and is handled by runtime line matching.
            return "";
        }
        return configured;
    }

    private String normalizeStation(String stationName) {
        return stationName.trim().toLowerCase(Locale.ROOT);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id.value());
        tag.putString("DisplayName", displayName);
        ListTag stations = new ListTag();
        for (String stationName : stationNames) {
            stations.add(StringTag.valueOf(stationName));
        }
        tag.put("Stations", stations);
        tag.put("Settings", settings.write());
        return tag;
    }

    public static TrainLine read(CompoundTag tag) {
        LineId id = new LineId(tag.getString("Id"));
        String displayName = tag.contains("DisplayName") ? tag.getString("DisplayName") : id.value();
        TrainLine line = new TrainLine(id, displayName);

        ListTag stationTags = tag.getList("Stations", Tag.TAG_STRING);
        for (Tag stationTag : stationTags) {
            line.addStationName(stationTag.getAsString());
        }

        if (tag.contains("Settings", Tag.TAG_COMPOUND)) {
            LineSettings loadedSettings = LineSettings.read(tag.getCompound("Settings"));
            line.settings().setMinimumIntervalTicks(loadedSettings.getMinimumIntervalTicksRaw());
            line.settings().setTargetIntervalOverrideTicks(loadedSettings.getTargetIntervalOverrideTicksRaw());
            line.settings().setMinimumDwellTicks(loadedSettings.getMinimumDwellTicksRaw());
            line.settings().setDwellExtensionTicks(loadedSettings.getDwellExtensionTicksRaw());
            line.settings().setSafetyBufferTicks(loadedSettings.getSafetyBufferTicksRaw());
            line.settings().setResynchronizationAggressiveness(loadedSettings.getResynchronizationAggressivenessRaw());
            line.settings().setRouteSwitchCooldownTicks(loadedSettings.getRouteSwitchCooldownTicksRaw());
            line.settings().setRouteReplanWaitTicks(loadedSettings.getRouteReplanWaitTicksRaw());
            line.settings().setServiceClass(loadedSettings.getServiceClassRaw());
            if (loadedSettings.getManualTrainCountRaw() > 0) {
                line.settings().setManualTrainCount(loadedSettings.getManualTrainCountRaw());
            } else {
                line.settings().clearManualTrainCount();
            }
            line.settings().setAllowedDepotHubIds(loadedSettings.allowedDepotHubIds());
        }

        return line;
    }
}
