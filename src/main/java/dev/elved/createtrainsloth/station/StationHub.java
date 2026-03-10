package dev.elved.createtrainsloth.station;

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

public class StationHub {

    private final StationHubId id;
    private String displayName;
    private final Set<String> platformStationNames;

    public StationHub(StationHubId id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.platformStationNames = new LinkedHashSet<>();
    }

    public StationHubId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String value) {
        displayName = value == null || value.isBlank() ? id.value() : value.trim();
    }

    public Collection<String> platformStationNames() {
        return Collections.unmodifiableSet(platformStationNames);
    }

    public int platformCount() {
        return platformStationNames.size();
    }

    public boolean addPlatformStationName(String stationName) {
        return platformStationNames.add(normalizeStation(stationName));
    }

    public boolean removePlatformStationName(String stationName) {
        return platformStationNames.remove(normalizeStation(stationName));
    }

    public boolean matchesStation(GlobalStation station) {
        return station != null && matchesStationName(station.name);
    }

    public boolean matchesStationName(String stationNameRaw) {
        if (platformStationNames.isEmpty()) {
            return false;
        }
        String stationName = normalizeStation(stationNameRaw);
        for (String configured : platformStationNames) {
            if (configured.contains("*")) {
                String regex = Pattern.quote(configured).replace("\\*", "\\E.*\\Q");
                if (stationName.matches(regex)) {
                    return true;
                }
                continue;
            }
            if (configured.equals(stationName)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeStation(String stationName) {
        return stationName == null ? "" : stationName.trim().toLowerCase(Locale.ROOT);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id.value());
        tag.putString("DisplayName", displayName);
        ListTag platforms = new ListTag();
        for (String platform : platformStationNames) {
            platforms.add(StringTag.valueOf(platform));
        }
        tag.put("Platforms", platforms);
        return tag;
    }

    public static StationHub read(CompoundTag tag) {
        StationHubId id = new StationHubId(tag.getString("Id"));
        String displayName = tag.contains("DisplayName") ? tag.getString("DisplayName") : id.value();
        StationHub hub = new StationHub(id, displayName);

        ListTag platformTags = tag.getList("Platforms", Tag.TAG_STRING);
        for (Tag platformTag : platformTags) {
            hub.addPlatformStationName(platformTag.getAsString());
        }
        return hub;
    }
}

