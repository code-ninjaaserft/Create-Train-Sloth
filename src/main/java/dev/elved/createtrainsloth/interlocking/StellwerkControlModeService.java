package dev.elved.createtrainsloth.interlocking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StellwerkControlModeService {

    private final Map<UUID, Boolean> stellwerkEnabledByTrain = new HashMap<>();

    public boolean isStellwerkEnabled(UUID trainId) {
        if (trainId == null) {
            return true;
        }
        return stellwerkEnabledByTrain.getOrDefault(trainId, true);
    }

    public void setStellwerkEnabled(UUID trainId, boolean enabled) {
        if (trainId == null) {
            return;
        }
        stellwerkEnabledByTrain.put(trainId, enabled);
    }

    public void clearTrain(UUID trainId) {
        if (trainId == null) {
            return;
        }
        stellwerkEnabledByTrain.remove(trainId);
    }

    public void clearAll() {
        stellwerkEnabledByTrain.clear();
    }
}
