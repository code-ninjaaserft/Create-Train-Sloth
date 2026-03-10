package dev.elved.createtrainsloth.integration.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.TrainSlothRuntime;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public class CreateIntegrationHooks {

    private final TrainSlothRuntime runtime;

    public CreateIntegrationHooks(TrainSlothRuntime runtime) {
        this.runtime = runtime;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime.initialize(event.getServer());
        CreateTrainSlothMod.LOGGER.info("[CreateTrainSloth] Runtime initialized");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runtime.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        if (!runtime.ready()) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                runtime.initialize(server);
            }
        }

        if (runtime.commands() != null) {
            runtime.commands().register(event);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLevelTickStart(LevelTickEvent.Pre event) {
        Level level = event.getLevel();
        if (level.isClientSide() || level.dimension() != Level.OVERWORLD) {
            return;
        }

        initializeFromLevel(level);
        if (!runtime.ready()) {
            return;
        }

        List<Train> trains = List.copyOf(Create.RAILWAYS.trains.values());
        runtime.interlockingControlService().captureTick(level, trains);
        runtime.scheduleAlternativeResolver().restoreMainDestinationOverridesAfterArrival(trains);
        runtime.scheduleAlternativeResolver().rebindMainConditionsAfterAlternativeArrival(trains);
        runtime.scheduleAlternativeResolver().advancePastAlternativeEntries(trains);
        runtime.platformAssignmentService().plan(level, trains);
        runtime.alternativePathSelector().preRailwayTick(level, trains);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLevelTickPre(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide() || level.dimension() != Level.OVERWORLD) {
            return;
        }

        initializeFromLevel(level);
        if (!runtime.ready()) {
            return;
        }

        List<Train> trains = List.copyOf(Create.RAILWAYS.trains.values());
        runtime.scheduleLineSyncService().syncFromSchedules(trains);
        runtime.dispatchController().preRailwayTick(level, trains);

        // TODO(Create integration): If upstream Create exposes train pre-departure extension hooks,
        // replace cooldown-based holding with explicit schedule departure callbacks.
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLevelTickPost(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide() || level.dimension() != Level.OVERWORLD) {
            return;
        }

        initializeFromLevel(level);
        if (!runtime.ready()) {
            return;
        }

        List<Train> trains = List.copyOf(Create.RAILWAYS.trains.values());
        runtime.dispatchController().postRailwayTick(level, trains);
        runtime.alternativePathSelector().postRailwayTick(level, trains);

        // TODO(Create integration): Evaluate a cleaner route-selection extension point in Create's
        // Navigation.startNavigation / ScheduleRuntime flow to avoid path recalculation from addon side.
    }

    private void initializeFromLevel(Level level) {
        if (runtime.ready()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            runtime.initialize(server);
        }
    }
}
