package dev.elved.createtrainsloth.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.routing.RoutingAuthorityService;
import dev.elved.createtrainsloth.routing.TrainRoutingRequest;
import dev.elved.createtrainsloth.routing.TrainRoutingResponse;
import java.util.List;
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
                train.name == null ? null : train.name.getString(),
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
            if ("recall_in_progress".equals(response.reason())) {
                runtime.startCooldown();
                return null;
            }

            if (TrainRoutingResponse.STATUS_NO_DESTINATION_MATCH.equals(response.status())) {
                train.status.failedNavigationNoTarget(getFilter());
            } else {
                train.status.failedNavigation();
            }
            runtime.startCooldown();
            return null;
        }

        return super.start(runtime, level);
    }
}
