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
import dev.elved.createtrainsloth.interlocking.StellwerkControlModeService;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.routing.AlternativePathSelector;
import dev.elved.createtrainsloth.routing.PlatformAssignmentService;
import dev.elved.createtrainsloth.routing.ReservationAwarenessService;
import dev.elved.createtrainsloth.routing.RoutePreferenceResolver;
import dev.elved.createtrainsloth.routing.ScheduleAlternativeResolver;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import net.minecraft.server.MinecraftServer;

public class TrainSlothRuntime {

    private MinecraftServer server;
    private TrainSlothSavedData savedData;
    private LineRegistry lineRegistry;
    private StationHubRegistry stationHubRegistry;
    private LineManager lineManager;
    private ScheduleLineSyncService scheduleLineSyncService;
    private StationStateTracker stationStateTracker;
    private HeadwayCalculator headwayCalculator;
    private DispatchController dispatchController;
    private ReservationAwarenessService reservationAwarenessService;
    private RoutePreferenceResolver routePreferenceResolver;
    private ScheduleAlternativeResolver scheduleAlternativeResolver;
    private PlatformAssignmentService platformAssignmentService;
    private InterlockingControlService interlockingControlService;
    private StellwerkControlModeService stellwerkControlModeService;
    private AlternativePathSelector alternativePathSelector;
    private DebugOverlay debugOverlay;
    private final TrainSlothCommands commands = new TrainSlothCommands();

    public void initialize(MinecraftServer minecraftServer) {
        if (server == minecraftServer && ready()) {
            return;
        }

        server = minecraftServer;
        savedData = TrainSlothSavedData.load(minecraftServer);
        lineRegistry = new LineRegistry(savedData);
        stationHubRegistry = new StationHubRegistry(savedData);
        lineManager = new LineManager(lineRegistry);
        scheduleLineSyncService = new ScheduleLineSyncService(lineRegistry, stationHubRegistry);
        stationStateTracker = new StationStateTracker();
        headwayCalculator = new HeadwayCalculator();
        debugOverlay = new DebugOverlay();
        dispatchController = new DispatchController(lineManager, stationStateTracker, headwayCalculator, debugOverlay);
        reservationAwarenessService = new ReservationAwarenessService();
        routePreferenceResolver = new RoutePreferenceResolver(reservationAwarenessService);
        scheduleAlternativeResolver = new ScheduleAlternativeResolver();
        platformAssignmentService = new PlatformAssignmentService(
            lineManager,
            scheduleAlternativeResolver,
            stationHubRegistry,
            reservationAwarenessService
        );
        interlockingControlService = new InterlockingControlService();
        stellwerkControlModeService = new StellwerkControlModeService();
        alternativePathSelector = new AlternativePathSelector(
            lineManager,
            routePreferenceResolver,
            reservationAwarenessService,
            scheduleAlternativeResolver,
            platformAssignmentService,
            interlockingControlService,
            stellwerkControlModeService,
            debugOverlay,
            stationHubRegistry
        );
        commands.bind(lineRegistry, lineManager, stationHubRegistry, debugOverlay, interlockingControlService);
    }

    public void clear() {
        server = null;
        savedData = null;
        lineRegistry = null;
        stationHubRegistry = null;
        lineManager = null;
        scheduleLineSyncService = null;
        stationStateTracker = null;
        headwayCalculator = null;
        dispatchController = null;
        reservationAwarenessService = null;
        routePreferenceResolver = null;
        scheduleAlternativeResolver = null;
        platformAssignmentService = null;
        interlockingControlService = null;
        stellwerkControlModeService = null;
        alternativePathSelector = null;
        debugOverlay = null;
        commands.clearBindings();
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

    public ScheduleAlternativeResolver scheduleAlternativeResolver() {
        return scheduleAlternativeResolver;
    }

    public PlatformAssignmentService platformAssignmentService() {
        return platformAssignmentService;
    }

    public InterlockingControlService interlockingControlService() {
        return interlockingControlService;
    }

    public StellwerkControlModeService stellwerkControlModeService() {
        return stellwerkControlModeService;
    }

    public StationHubRegistry stationHubRegistry() {
        return stationHubRegistry;
    }

    public LineRegistry lineRegistry() {
        return lineRegistry;
    }

    public LineManager lineManager() {
        return lineManager;
    }

    public TrainSlothCommands commands() {
        return commands;
    }

    public DebugOverlay debugOverlay() {
        return debugOverlay;
    }
}
