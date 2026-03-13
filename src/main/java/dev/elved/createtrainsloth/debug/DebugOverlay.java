package dev.elved.createtrainsloth.debug;

import dev.elved.createtrainsloth.line.LineId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class DebugOverlay {

    private final Map<UUID, String> holdReasonsByTrain = new HashMap<>();
    private final Map<UUID, String> routeSwitchesByTrain = new HashMap<>();
    private final Map<UUID, String> routerStagesByTrain = new HashMap<>();
    private final Map<UUID, String> routerBreakpointsByTrain = new HashMap<>();
    private final Map<UUID, String> depotActionsByTrain = new HashMap<>();
    private final Map<UUID, String> missionByTrain = new HashMap<>();

    public void recordHold(UUID trainId, LineId lineId, String reason) {
        holdReasonsByTrain.put(trainId, "line=" + lineId + " reason=" + reason);
    }

    public void recordRelease(UUID trainId, LineId lineId, long tick, int headwayTicks) {
        holdReasonsByTrain.put(trainId, "line=" + lineId + " released tick=" + tick + " headway=" + headwayTicks);
    }

    public void recordRouteSwitch(UUID trainId, LineId lineId, String signature, int score) {
        routeSwitchesByTrain.put(trainId, "line=" + lineId + " score=" + score + " path=" + signature);
    }

    public void recordRouterStage(
        UUID trainId,
        @Nullable LineId lineId,
        String correlationId,
        String stage,
        String detail
    ) {
        String linePart = lineId == null ? "line=<none>" : "line=" + lineId.value();
        String entry = "cid=" + correlationId + " " + stage + " " + linePart + " " + detail;
        String previous = routerStagesByTrain.get(trainId);
        if (previous == null || previous.isBlank()) {
            routerStagesByTrain.put(trainId, entry);
            return;
        }

        String merged = previous + " -> " + entry;
        if (merged.length() > 700) {
            merged = merged.substring(merged.length() - 700);
        }
        routerStagesByTrain.put(trainId, merged);
    }

    public void recordRouterBreakpoint(
        UUID trainId,
        @Nullable LineId lineId,
        String correlationId,
        String stage,
        String reason
    ) {
        String linePart = lineId == null ? "line=<none>" : "line=" + lineId.value();
        routerBreakpointsByTrain.put(trainId, "cid=" + correlationId + " stage=" + stage + " " + linePart + " reason=" + reason);
    }

    public void recordDepotAction(UUID trainId, String detail) {
        depotActionsByTrain.put(trainId, detail);
    }

    public void recordMission(UUID trainId, String detail) {
        missionByTrain.put(trainId, detail);
    }

    public void clearMission(UUID trainId) {
        missionByTrain.remove(trainId);
    }

    public String debugForTrain(UUID trainId) {
        String hold = holdReasonsByTrain.getOrDefault(trainId, "no dispatch record");
        String route = routeSwitchesByTrain.getOrDefault(trainId, "no routing record");
        String router = routerStagesByTrain.getOrDefault(trainId, "no router trace");
        String breakpoint = routerBreakpointsByTrain.getOrDefault(trainId, "none");
        String depot = depotActionsByTrain.getOrDefault(trainId, "none");
        String mission = missionByTrain.getOrDefault(trainId, "none");
        return "dispatch={" + hold + "} routing={" + route + "} router={" + router + "} break={" + breakpoint + "} depot={" + depot + "} mission={" + mission + "}";
    }
}
