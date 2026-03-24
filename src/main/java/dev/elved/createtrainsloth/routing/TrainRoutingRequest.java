package dev.elved.createtrainsloth.routing;

import dev.elved.createtrainsloth.line.LineId;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public record TrainRoutingRequest(
    String correlationId,
    UUID trainId,
    @Nullable String trainName,
    @Nullable LineId lineId,
    @Nullable String currentLocation,
    String requestedDestination,
    String requestSource
) {

    public static TrainRoutingRequest create(
        String correlationId,
        UUID trainId,
        @Nullable String trainName,
        @Nullable LineId lineId,
        @Nullable String currentLocation,
        String requestedDestination,
        String requestSource
    ) {
        String cid = correlationId == null || correlationId.isBlank() ? "router-unknown" : correlationId.trim();
        String destination = requestedDestination == null ? "" : requestedDestination.trim();
        String source = requestSource == null || requestSource.isBlank() ? "unknown" : requestSource.trim();
        String name = trainName == null ? null : trainName.trim();
        if (name != null && name.isBlank()) {
            name = null;
        }
        return new TrainRoutingRequest(cid, trainId, name, lineId, currentLocation, destination, source);
    }
}
