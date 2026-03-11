package dev.elved.createtrainsloth.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.routing.RoutingAuthorityService;
import dev.elved.createtrainsloth.routing.TrainRoutingRequest;
import dev.elved.createtrainsloth.routing.TrainRoutingResponse;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public class HubDestinationInstruction extends DestinationInstruction {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
        CreateTrainSlothMod.MOD_ID,
        "hub_destination"
    );
    private static final String STAGE_REQUEST_CREATED = "REQUEST_CREATED";
    private static final String STAGE_RESPONSE_APPLIED = "RESPONSE_APPLIED";

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public List<Component> getTitleAs(String type) {
        return ImmutableList.of(
            Component.translatable("create_train_sloth.schedule." + type + ".hub_destination")
                .withStyle(ChatFormatting.GOLD),
            Component.translatable("generic.in_quotes", Component.literal(getLabelText()))
        );
    }

    @Override
    @Nullable
    public DiscoveredPath start(ScheduleRuntime runtime, Level level) {
        Train train = runtime.train;
        if (train == null || train.graph == null) {
            return super.start(runtime, level);
        }

        if (!train.hasForwardConductor() && !train.hasBackwardConductor()) {
            train.status.missingConductor();
            runtime.startCooldown();
            return null;
        }

        RoutingAuthorityService routingAuthorityService = CreateTrainSlothMod.runtime().routingAuthorityService();
        if (routingAuthorityService != null) {
            LineId lineId = CreateTrainSlothMod.runtime().lineManager() == null
                ? null
                : CreateTrainSlothMod.runtime().lineManager().lineForTrain(train).map(line -> line.id()).orElse(null);
            String currentLocation = train.getCurrentStation() == null ? null : train.getCurrentStation().name;
            String correlationId = "hub-" + Long.toString(level.getGameTime(), 36) + "-" + train.id.toString().substring(0, 6);
            TrainRoutingRequest request = TrainRoutingRequest.create(
                correlationId,
                train.id,
                lineId,
                currentLocation,
                getFilter(),
                "hub_destination_instruction"
            );

            DebugOverlay debugOverlay = CreateTrainSlothMod.runtime().debugOverlay();
            if (debugOverlay != null) {
                debugOverlay.recordRouterStage(
                    train.id,
                    lineId,
                    request.correlationId(),
                    STAGE_REQUEST_CREATED,
                    "destination=" + request.requestedDestination()
                );
            }

            TrainRoutingResponse response = routingAuthorityService.requestRoute(level, train, request);
            if (debugOverlay != null) {
                String detail = "status=" + response.status()
                    + " reason=" + (response.reason() == null ? "-" : response.reason())
                    + " platform=" + (response.assignedPlatform() == null ? "-" : response.assignedPlatform());
                debugOverlay.recordRouterStage(train.id, lineId, response.correlationId(), STAGE_RESPONSE_APPLIED, detail);
                if (!response.successful()) {
                    debugOverlay.recordRouterBreakpoint(
                        train.id,
                        lineId,
                        response.correlationId(),
                        STAGE_RESPONSE_APPLIED,
                        response.reason() == null ? response.status() : response.reason()
                    );
                }
            }

            if (response.hasPath()) {
                return response.path();
            }

            DiscoveredPath legacyFallbackPath = resolveLegacyHubPath(train);
            if (legacyFallbackPath != null) {
                if (debugOverlay != null) {
                    debugOverlay.recordRouterStage(
                        train.id,
                        lineId,
                        response.correlationId(),
                        STAGE_RESPONSE_APPLIED,
                        "status=FALLBACK_LEGACY reason=" + (response.reason() == null ? "-" : response.reason())
                    );
                }
                return legacyFallbackPath;
            }

            if (TrainRoutingResponse.STATUS_NO_DESTINATION_MATCH.equals(response.status())) {
                train.status.failedNavigationNoTarget(getFilter());
            } else {
                train.status.failedNavigation();
            }
            runtime.startCooldown();
            return null;
        }

        StationHubRegistry stationHubRegistry = CreateTrainSlothMod.runtime().stationHubRegistry();
        if (stationHubRegistry == null) {
            return super.start(runtime, level);
        }

        Optional<StationHub> hub = stationHubRegistry.findHubForScheduleFilter(getFilter());
        if (hub.isEmpty()) {
            return super.start(runtime, level);
        }

        ArrayList<GlobalStation> validStations = new ArrayList<>();
        for (GlobalStation globalStation : train.graph.getPoints(EdgePointType.STATION)) {
            if (hub.get().matchesStation(globalStation)) {
                validStations.add(globalStation);
            }
        }

        if (validStations.isEmpty()) {
            train.status.failedNavigationNoTarget(getFilter());
            runtime.startCooldown();
            return null;
        }

        DiscoveredPath best = train.navigation.findPathTo(validStations, Double.MAX_VALUE);
        if (best == null) {
            train.status.failedNavigation();
            runtime.startCooldown();
            return null;
        }

        return best;
    }

    @Nullable
    private DiscoveredPath resolveLegacyHubPath(Train train) {
        if (train == null || train.graph == null) {
            return null;
        }

        StationHubRegistry stationHubRegistry = CreateTrainSlothMod.runtime().stationHubRegistry();
        if (stationHubRegistry == null) {
            return null;
        }

        Optional<StationHub> hub = stationHubRegistry.findHubForScheduleFilter(getFilter());
        if (hub.isEmpty()) {
            return null;
        }

        List<GlobalStation> validStations = new ArrayList<>();
        for (GlobalStation globalStation : train.graph.getPoints(EdgePointType.STATION)) {
            if (hub.get().matchesStation(globalStation)) {
                validStations.add(globalStation);
            }
        }

        if (validStations.isEmpty()) {
            return null;
        }

        return train.navigation.findPathTo(new ArrayList<>(validStations), Double.MAX_VALUE);
    }
}
