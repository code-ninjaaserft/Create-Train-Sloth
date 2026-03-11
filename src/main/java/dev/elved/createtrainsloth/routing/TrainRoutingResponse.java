package dev.elved.createtrainsloth.routing;

import com.simibubi.create.content.trains.graph.DiscoveredPath;
import org.jetbrains.annotations.Nullable;

public record TrainRoutingResponse(
    @Nullable DiscoveredPath path,
    @Nullable String assignedHubId,
    @Nullable String assignedPlatform,
    String status,
    String correlationId,
    @Nullable String reason,
    @Nullable String details
) {

    public static final String STATUS_ROUTE_ASSIGNED = "ROUTE_ASSIGNED";
    public static final String STATUS_INVALID_REQUEST = "INVALID_REQUEST";
    public static final String STATUS_NO_DESTINATION_MATCH = "NO_DESTINATION_MATCH";
    public static final String STATUS_NO_ROUTE_FOUND = "NO_ROUTE_FOUND";
    public static final String STATUS_UNUSABLE_ROUTE = "UNUSABLE_ROUTE";

    public static TrainRoutingResponse invalidRequest(String correlationId, String reason, @Nullable String details) {
        return new TrainRoutingResponse(null, null, null, STATUS_INVALID_REQUEST, correlationId, reason, details);
    }

    public static TrainRoutingResponse noDestination(
        String correlationId,
        @Nullable String hubId,
        String reason,
        @Nullable String details
    ) {
        return new TrainRoutingResponse(null, hubId, null, STATUS_NO_DESTINATION_MATCH, correlationId, reason, details);
    }

    public static TrainRoutingResponse noRoute(
        String correlationId,
        @Nullable String hubId,
        String reason,
        @Nullable String details
    ) {
        return new TrainRoutingResponse(null, hubId, null, STATUS_NO_ROUTE_FOUND, correlationId, reason, details);
    }

    public static TrainRoutingResponse unusableRoute(
        String correlationId,
        @Nullable String hubId,
        @Nullable String platform,
        String reason,
        @Nullable String details
    ) {
        return new TrainRoutingResponse(null, hubId, platform, STATUS_UNUSABLE_ROUTE, correlationId, reason, details);
    }

    public static TrainRoutingResponse assigned(
        DiscoveredPath path,
        @Nullable String hubId,
        @Nullable String platform,
        String correlationId,
        @Nullable String details
    ) {
        return new TrainRoutingResponse(path, hubId, platform, STATUS_ROUTE_ASSIGNED, correlationId, null, details);
    }

    public boolean hasPath() {
        return path != null && path.destination != null;
    }

    public boolean successful() {
        return STATUS_ROUTE_ASSIGNED.equals(status) && hasPath();
    }
}
