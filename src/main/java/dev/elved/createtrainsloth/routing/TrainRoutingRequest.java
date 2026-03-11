package dev.elved.createtrainsloth.routing;

import dev.elved.createtrainsloth.line.LineId;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public record TrainRoutingRequest(
    String correlationId,
    UUID trainId,
    @Nullable LineId lineId,
    @Nullable String currentLocation,
    String requestedDestination,
    String requestSource
) {

    public static TrainRoutingRequest create(
        String correlationId,
        UUID trainId,
        @Nullable LineId lineId,
        @Nullable String currentLocation,
        String requestedDestination,
        String requestSource
    ) {
        String cid = correlationId == null || correlationId.isBlank() ? "router-unknown" : correlationId.trim();
        String destination = requestedDestination == null ? "" : requestedDestination.trim();
        String source = requestSource == null || requestSource.isBlank() ? "unknown" : requestSource.trim();
        return new TrainRoutingRequest(cid, trainId, lineId, currentLocation, destination, source);
    }
}
