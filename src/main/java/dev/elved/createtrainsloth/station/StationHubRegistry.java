package dev.elved.createtrainsloth.station;

import dev.elved.createtrainsloth.data.TrainSlothSavedData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StationHubRegistry {

    private final TrainSlothSavedData savedData;

    public StationHubRegistry(TrainSlothSavedData savedData) {
        this.savedData = savedData;
    }

    public Collection<StationHub> allHubs() {
        List<StationHub> hubs = new ArrayList<>(savedData.stationHubs().values());
        hubs.sort(Comparator.comparing(hub -> hub.id().value()));
        return Collections.unmodifiableList(hubs);
    }

    public Optional<StationHub> findHub(StationHubId hubId) {
        return Optional.ofNullable(savedData.stationHubs().get(hubId));
    }

    public StationHub createHub(StationHubId hubId, String displayName) {
        StationHub hub = new StationHub(hubId, displayName);
        hub.setDisplayName(displayName);
        savedData.stationHubs().put(hubId, hub);
        savedData.setDirty();
        return hub;
    }

    public boolean removeHub(StationHubId hubId) {
        StationHub removed = savedData.stationHubs().remove(hubId);
        if (removed == null) {
            return false;
        }
        savedData.setDirty();
        return true;
    }

    public boolean renameHub(StationHubId hubId, String newDisplayName) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        String normalized = newDisplayName == null ? "" : newDisplayName.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (hub.get().displayName().equals(normalized)) {
            return false;
        }
        hub.get().setDisplayName(normalized);
        savedData.setDirty();
        return true;
    }

    public boolean setDepotHub(StationHubId hubId, boolean depotHub) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        if (hub.get().isDepotHub() == depotHub) {
            return false;
        }
        hub.get().setDepotHub(depotHub);
        savedData.setDirty();
        return true;
    }

    public Optional<StationHub> findHubForScheduleFilter(String filter) {
        String normalized = normalize(filter);
        String resolvedHubId = normalizeHubFilter(normalized);
        if (resolvedHubId.isBlank()) {
            return Optional.empty();
        }

        Optional<StationHub> byId;
        try {
            byId = Optional.ofNullable(savedData.stationHubs().get(new StationHubId(resolvedHubId)));
        } catch (IllegalArgumentException ignored) {
            byId = Optional.empty();
        }
        if (byId.isPresent()) {
            return byId;
        }

        for (Map.Entry<StationHubId, StationHub> entry : savedData.stationHubs().entrySet()) {
            StationHub hub = entry.getValue();
            String normalizedDisplay = normalize(hub.displayName());
            if (normalizedDisplay.equals(resolvedHubId) || normalizedDisplay.equals(normalized)) {
                return Optional.of(hub);
            }
        }

        return Optional.empty();
    }

    public boolean addPlatform(StationHubId hubId, String stationName) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        boolean changed = hub.get().addPlatformStationName(stationName);
        if (changed) {
            savedData.setDirty();
        }
        return changed;
    }

    public boolean removePlatform(StationHubId hubId, String stationName) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        boolean changed = hub.get().removePlatformStationName(stationName);
        if (changed) {
            savedData.setDirty();
        }
        return changed;
    }

    public Set<String> removePlatformAndCollectLinkKeys(StationHubId hubId, String stationName) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return Set.of();
        }

        StationHub value = hub.get();
        Set<String> links = value.removeStationLinkKeys(stationName);
        boolean removed = value.removePlatformStationName(stationName);
        if (removed || !links.isEmpty()) {
            savedData.setDirty();
        }
        return removed ? links : Set.of();
    }

    public boolean hasStationLinks(StationHubId hubId, String stationName) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        return !hub.get().stationLinkKeys(stationName).isEmpty();
    }

    public boolean registerStationLink(StationHubId hubId, String stationName, String linkKey) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        boolean changed = hub.get().addStationLinkKey(stationName, linkKey);
        if (changed) {
            savedData.setDirty();
        }
        return changed;
    }

    public boolean unregisterStationLink(StationHubId hubId, String stationName, String linkKey) {
        Optional<StationHub> hub = findHub(hubId);
        if (hub.isEmpty()) {
            return false;
        }
        boolean changed = hub.get().removeStationLinkKey(stationName, linkKey);
        if (changed) {
            savedData.setDirty();
        }
        return changed;
    }

    public void markDirty() {
        savedData.setDirty();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeHubFilter(String normalizedFilter) {
        if (normalizedFilter == null) {
            return "";
        }

        String value = normalizedFilter.trim();
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }

        if (value.startsWith("hubid")) {
            String remainder = value.substring("hubid".length()).trim();
            if (!remainder.isEmpty() && (remainder.charAt(0) == ':' || remainder.charAt(0) == '.')) {
                return remainder.substring(1).trim();
            }
        }

        if (value.startsWith("hub")) {
            String remainder = value.substring("hub".length()).trim();
            if (!remainder.isEmpty() && (remainder.charAt(0) == ':' || remainder.charAt(0) == '.')) {
                return remainder.substring(1).trim();
            }
        }

        return value;
    }
}
