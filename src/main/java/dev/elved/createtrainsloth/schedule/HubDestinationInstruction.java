package dev.elved.createtrainsloth.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
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
}
