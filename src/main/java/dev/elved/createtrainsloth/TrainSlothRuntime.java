package dev.elved.createtrainsloth;

import dev.elved.createtrainsloth.command.TrainSlothCommands;
import dev.elved.createtrainsloth.data.TrainSlothSavedData;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.dispatch.DispatchController;
import dev.elved.createtrainsloth.dispatch.HeadwayCalculator;
import dev.elved.createtrainsloth.dispatch.StationStateTracker;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.ScheduleLineSyncService;
import dev.elved.createtrainsloth.routing.AlternativePathSelector;
import dev.elved.createtrainsloth.routing.ReservationAwarenessService;
import dev.elved.createtrainsloth.routing.RoutePreferenceResolver;
import net.minecraft.server.MinecraftServer;

public class TrainSlothRuntime {

    private MinecraftServer server;
    private TrainSlothSavedData savedData;
    private LineRegistry lineRegistry;
    private LineManager lineManager;
    private ScheduleLineSyncService scheduleLineSyncService;
    private StationStateTracker stationStateTracker;
    private HeadwayCalculator headwayCalculator;
    private DispatchController dispatchController;
    private ReservationAwarenessService reservationAwarenessService;
    private RoutePreferenceResolver routePreferenceResolver;
    private AlternativePathSelector alternativePathSelector;
    private DebugOverlay debugOverlay;
    private TrainSlothCommands commands;

    public void initialize(MinecraftServer minecraftServer) {
        if (server == minecraftServer && ready()) {
            return;
        }

        server = minecraftServer;
        savedData = TrainSlothSavedData.load(minecraftServer);
        lineRegistry = new LineRegistry(savedData);
        lineManager = new LineManager(lineRegistry);
        scheduleLineSyncService = new ScheduleLineSyncService(lineRegistry);
        stationStateTracker = new StationStateTracker();
        headwayCalculator = new HeadwayCalculator();
        debugOverlay = new DebugOverlay();
        dispatchController = new DispatchController(lineManager, stationStateTracker, headwayCalculator, debugOverlay);
        reservationAwarenessService = new ReservationAwarenessService();
        routePreferenceResolver = new RoutePreferenceResolver(reservationAwarenessService);
        alternativePathSelector = new AlternativePathSelector(lineManager, routePreferenceResolver, reservationAwarenessService, debugOverlay);
        commands = new TrainSlothCommands(lineRegistry, lineManager, debugOverlay);
    }

    public void clear() {
        server = null;
        savedData = null;
        lineRegistry = null;
        lineManager = null;
        scheduleLineSyncService = null;
        stationStateTracker = null;
        headwayCalculator = null;
        dispatchController = null;
        reservationAwarenessService = null;
        routePreferenceResolver = null;
        alternativePathSelector = null;
        debugOverlay = null;
        commands = null;
    }

    public boolean ready() {
        return server != null && lineManager != null && dispatchController != null && alternativePathSelector != null;
    }

    public MinecraftServer server() {
        return server;
    }

    public DispatchController dispatchController() {
        return dispatchController;
    }

    public ScheduleLineSyncService scheduleLineSyncService() {
        return scheduleLineSyncService;
    }

    public AlternativePathSelector alternativePathSelector() {
        return alternativePathSelector;
    }

    public TrainSlothCommands commands() {
        return commands;
    }

    public DebugOverlay debugOverlay() {
        return debugOverlay;
    }
}
