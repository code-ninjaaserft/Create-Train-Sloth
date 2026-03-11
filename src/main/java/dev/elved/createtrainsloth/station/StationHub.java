package dev.elved.createtrainsloth.station;

import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public class StationHub {

    private final StationHubId id;
    private String displayName;
    private boolean depotHub;
    private final Set<String> platformStationNames;
    private final Map<String, Set<String>> linkKeysByStation;

    public StationHub(StationHubId id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.depotHub = false;
        this.platformStationNames = new LinkedHashSet<>();
        this.linkKeysByStation = new LinkedHashMap<>();
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

    public boolean isDepotHub() {
        return depotHub;
    }

    public void setDepotHub(boolean depotHub) {
        this.depotHub = depotHub;
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
        String normalized = normalizeStation(stationName);
        boolean removed = platformStationNames.remove(normalized);
        if (removed) {
            linkKeysByStation.remove(normalized);
        }
        return removed;
    }

    public boolean addStationLinkKey(String stationName, String linkKey) {
        String normalizedStation = normalizeStation(stationName);
        if (normalizedStation.isBlank() || linkKey == null || linkKey.isBlank()) {
            return false;
        }
        return linkKeysByStation.computeIfAbsent(normalizedStation, ignored -> new LinkedHashSet<>())
            .add(linkKey);
    }

    public boolean removeStationLinkKey(String stationName, String linkKey) {
        String normalizedStation = normalizeStation(stationName);
        Set<String> keys = linkKeysByStation.get(normalizedStation);
        if (keys == null || linkKey == null || linkKey.isBlank()) {
            return false;
        }
        boolean removed = keys.remove(linkKey);
        if (keys.isEmpty()) {
            linkKeysByStation.remove(normalizedStation);
        }
        return removed;
    }

    public Set<String> stationLinkKeys(String stationName) {
        String normalizedStation = normalizeStation(stationName);
        Set<String> keys = linkKeysByStation.get(normalizedStation);
        return keys == null ? Set.of() : Set.copyOf(keys);
    }

    public Set<String> removeStationLinkKeys(String stationName) {
        String normalizedStation = normalizeStation(stationName);
        Set<String> removed = linkKeysByStation.remove(normalizedStation);
        return removed == null ? Set.of() : Set.copyOf(removed);
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
        tag.putBoolean("DepotHub", depotHub);
        ListTag platforms = new ListTag();
        for (String platform : platformStationNames) {
            platforms.add(StringTag.valueOf(platform));
        }
        tag.put("Platforms", platforms);

        ListTag links = new ListTag();
        for (Map.Entry<String, Set<String>> entry : linkKeysByStation.entrySet()) {
            CompoundTag stationLinks = new CompoundTag();
            stationLinks.putString("Station", entry.getKey());
            ListTag linkKeys = new ListTag();
            for (String linkKey : entry.getValue()) {
                linkKeys.add(StringTag.valueOf(linkKey));
            }
            stationLinks.put("LinkKeys", linkKeys);
            links.add(stationLinks);
        }
        tag.put("StationLinks", links);
        return tag;
    }

    public static StationHub read(CompoundTag tag) {
        StationHubId id = new StationHubId(tag.getString("Id"));
        String displayName = tag.contains("DisplayName") ? tag.getString("DisplayName") : id.value();
        StationHub hub = new StationHub(id, displayName);
        hub.setDepotHub(tag.contains("DepotHub", Tag.TAG_BYTE) && tag.getBoolean("DepotHub"));

        ListTag platformTags = tag.getList("Platforms", Tag.TAG_STRING);
        for (Tag platformTag : platformTags) {
            hub.addPlatformStationName(platformTag.getAsString());
        }

        ListTag stationLinks = tag.getList("StationLinks", Tag.TAG_COMPOUND);
        for (Tag stationLinkTag : stationLinks) {
            CompoundTag stationLink = (CompoundTag) stationLinkTag;
            if (!stationLink.contains("Station", Tag.TAG_STRING)) {
                continue;
            }
            String station = stationLink.getString("Station");
            for (Tag linkKey : stationLink.getList("LinkKeys", Tag.TAG_STRING)) {
                hub.addStationLinkKey(station, linkKey.getAsString());
            }
        }
        return hub;
    }
}
