package dev.elved.createtrainsloth.debug;

import dev.elved.createtrainsloth.line.LineId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DebugOverlay {

    private final Map<UUID, String> holdReasonsByTrain = new HashMap<>();
    private final Map<UUID, String> routeSwitchesByTrain = new HashMap<>();

    public void recordHold(UUID trainId, LineId lineId, String reason) {
        holdReasonsByTrain.put(trainId, "line=" + lineId + " reason=" + reason);
    }

    public void recordRelease(UUID trainId, LineId lineId, long tick, int headwayTicks) {
        holdReasonsByTrain.put(trainId, "line=" + lineId + " released tick=" + tick + " headway=" + headwayTicks);
    }

    public void recordRouteSwitch(UUID trainId, LineId lineId, String signature, int score) {
        routeSwitchesByTrain.put(trainId, "line=" + lineId + " score=" + score + " path=" + signature);
    }

    public String debugForTrain(UUID trainId) {
        String hold = holdReasonsByTrain.getOrDefault(trainId, "no dispatch record");
        String route = routeSwitchesByTrain.getOrDefault(trainId, "no routing record");
        return "dispatch={" + hold + "} routing={" + route + "}";
    }
}
