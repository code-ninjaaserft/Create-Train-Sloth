package dev.elved.createtrainsloth.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.interlocking.InterlockingControlService;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import dev.elved.createtrainsloth.line.TrainLineAssignment;
import dev.elved.createtrainsloth.line.TrainServiceClass;
import dev.elved.createtrainsloth.station.StationHub;
import dev.elved.createtrainsloth.station.StationHubId;
import dev.elved.createtrainsloth.station.StationHubRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class TrainSlothCommands {

    private LineRegistry lineRegistry;
    private LineManager lineManager;
    private StationHubRegistry stationHubRegistry;
    private DebugOverlay debugOverlay;
    private InterlockingControlService interlockingControlService;

    public TrainSlothCommands() {
    }

    public void bind(
        LineRegistry lineRegistry,
        LineManager lineManager,
        StationHubRegistry stationHubRegistry,
        DebugOverlay debugOverlay,
        InterlockingControlService interlockingControlService
    ) {
        this.lineRegistry = lineRegistry;
        this.lineManager = lineManager;
        this.stationHubRegistry = stationHubRegistry;
        this.debugOverlay = debugOverlay;
        this.interlockingControlService = interlockingControlService;
    }

    public void clearBindings() {
        this.lineRegistry = null;
        this.lineManager = null;
        this.stationHubRegistry = null;
        this.debugOverlay = null;
        this.interlockingControlService = null;
    }

    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            literal("trainsloth")
                .requires(source -> source.hasPermission(0))
                .then(literal("line")
                    .then(literal("create")
                        .then(argument("line_id", StringArgumentType.word())
                            .executes(context -> createLine(context,
                                StringArgumentType.getString(context, "line_id"),
                                StringArgumentType.getString(context, "line_id")))
                            .then(argument("display_name", StringArgumentType.greedyString())
                                .executes(context -> createLine(context,
                                    StringArgumentType.getString(context, "line_id"),
                                    StringArgumentType.getString(context, "display_name"))))))
                    .then(literal("delete")
                        .then(argument("line_id", StringArgumentType.word())
                            .executes(this::deleteLine)))
                    .then(literal("list")
                        .executes(this::listLines))
                    .then(literal("station")
                        .then(literal("add")
                            .then(argument("line_id", StringArgumentType.word())
                                .then(argument("station_name", StringArgumentType.greedyString())
                                    .executes(this::addStation))))
                        .then(literal("remove")
                            .then(argument("line_id", StringArgumentType.word())
                                .then(argument("station_name", StringArgumentType.greedyString())
                                    .executes(this::removeStation)))))
                    .then(literal("setting")
                        .then(argument("line_id", StringArgumentType.word())
                            .then(argument("key", StringArgumentType.word())
                                .then(argument("value", DoubleArgumentType.doubleArg())
                                    .executes(this::setLineSetting))))))
                .then(literal("hub")
                    .then(literal("create")
                        .then(argument("hub_id", StringArgumentType.word())
                            .executes(context -> createHub(context,
                                StringArgumentType.getString(context, "hub_id"),
                                StringArgumentType.getString(context, "hub_id")))
                            .then(argument("display_name", StringArgumentType.greedyString())
                                .executes(context -> createHub(context,
                                    StringArgumentType.getString(context, "hub_id"),
                                    StringArgumentType.getString(context, "display_name"))))))
                    .then(literal("delete")
                        .then(argument("hub_id", StringArgumentType.word())
                            .executes(this::deleteHub)))
                    .then(literal("list")
                        .executes(this::listHubs))
                    .then(literal("platform")
                        .then(literal("add")
                            .then(argument("hub_id", StringArgumentType.word())
                                .then(argument("station_name", StringArgumentType.greedyString())
                                    .executes(this::addHubPlatform))))
                        .then(literal("remove")
                            .then(argument("hub_id", StringArgumentType.word())
                                .then(argument("station_name", StringArgumentType.greedyString())
                                    .executes(this::removeHubPlatform))))))
                .then(literal("assign")
                    .then(argument("train_id", UuidArgument.uuid())
                        .then(argument("line_id", StringArgumentType.word())
                            .executes(this::assignTrain))))
                .then(literal("unassign")
                    .then(argument("train_id", UuidArgument.uuid())
                        .executes(this::unassignTrain)))
                .then(literal("service")
                    .then(argument("train_id", UuidArgument.uuid())
                        .then(argument("class", StringArgumentType.word())
                            .executes(this::setServiceClass))))
                .then(literal("debug")
                    .then(literal("trains")
                        .executes(this::debugAllTrains))
                    .then(literal("interlocking")
                        .executes(this::debugInterlocking))
                    .then(literal("train")
                        .then(argument("train_id", UuidArgument.uuid())
                            .executes(this::debugTrain)))
                    .then(literal("schedule")
                        .then(argument("train_id", UuidArgument.uuid())
                            .executes(this::debugSchedule))))
        );
    }

    private boolean ensureRuntime(CommandSourceStack source) {
        if (lineRegistry != null
            && lineManager != null
            && stationHubRegistry != null
            && debugOverlay != null
            && interlockingControlService != null) {
            return true;
        }
        source.sendFailure(Component.literal("Create Train Sloth runtime not ready yet. Please retry in a moment."));
        return false;
    }

    private int createLine(CommandContext<CommandSourceStack> context, String lineIdRaw, String displayName) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        LineId lineId = new LineId(lineIdRaw);
        TrainLine line = lineRegistry.findLine(lineId).orElseGet(() -> lineRegistry.createLine(lineId, displayName));
        line.setDisplayName(displayName);
        lineRegistry.markDirty();
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.line_created", lineId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int deleteLine(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        LineId lineId = new LineId(StringArgumentType.getString(context, "line_id"));
        if (!lineRegistry.removeLine(lineId)) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.not_found", lineId.value()));
            return 0;
        }

        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.line_deleted", lineId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int listLines(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        String lineSummary = lineRegistry.allLines().stream()
            .map(line -> line.id().value())
            .collect(Collectors.joining(", "));
        if (lineSummary.isBlank()) {
            lineSummary = "<none>";
        }

        final String finalLineSummary = lineSummary;
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.list_header", finalLineSummary),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private int addStation(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        LineId lineId = new LineId(StringArgumentType.getString(context, "line_id"));
        String stationName = StringArgumentType.getString(context, "station_name");

        Optional<TrainLine> line = lineRegistry.findLine(lineId);
        if (line.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.not_found", lineId.value()));
            return 0;
        }

        line.get().addStationName(stationName);
        lineRegistry.markDirty();
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.station_added", stationName, lineId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int removeStation(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        LineId lineId = new LineId(StringArgumentType.getString(context, "line_id"));
        String stationName = StringArgumentType.getString(context, "station_name");

        Optional<TrainLine> line = lineRegistry.findLine(lineId);
        if (line.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.not_found", lineId.value()));
            return 0;
        }

        line.get().removeStationName(stationName);
        lineRegistry.markDirty();
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.station_removed", stationName, lineId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int createHub(CommandContext<CommandSourceStack> context, String hubIdRaw, String displayName) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        StationHubId hubId = new StationHubId(hubIdRaw);
        StationHub hub = stationHubRegistry.findHub(hubId).orElseGet(() -> stationHubRegistry.createHub(hubId, displayName));
        hub.setDisplayName(displayName);
        stationHubRegistry.markDirty();
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.hub_created", hubId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int deleteHub(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        StationHubId hubId = new StationHubId(StringArgumentType.getString(context, "hub_id"));
        if (!stationHubRegistry.removeHub(hubId)) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.hub_not_found", hubId.value()));
            return 0;
        }

        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.hub_deleted", hubId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int listHubs(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        String hubSummary = stationHubRegistry.allHubs().stream()
            .map(hub -> hub.id().value() + "[" + hub.platformCount() + "]" + (hub.isDepotHub() ? "{depot}" : ""))
            .collect(Collectors.joining(", "));
        if (hubSummary.isBlank()) {
            hubSummary = "<none>";
        }

        final String finalHubSummary = hubSummary;
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.hub_list_header", finalHubSummary),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private int addHubPlatform(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        StationHubId hubId = new StationHubId(StringArgumentType.getString(context, "hub_id"));
        String stationName = StringArgumentType.getString(context, "station_name");

        if (stationHubRegistry.findHub(hubId).isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.hub_not_found", hubId.value()));
            return 0;
        }

        stationHubRegistry.addPlatform(hubId, stationName);
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.hub_platform_added", stationName, hubId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int removeHubPlatform(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        StationHubId hubId = new StationHubId(StringArgumentType.getString(context, "hub_id"));
        String stationName = StringArgumentType.getString(context, "station_name");

        if (stationHubRegistry.findHub(hubId).isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.hub_not_found", hubId.value()));
            return 0;
        }

        stationHubRegistry.removePlatform(hubId, stationName);
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.hub_platform_removed", stationName, hubId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int setLineSetting(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        LineId lineId = new LineId(StringArgumentType.getString(context, "line_id"));
        String key = StringArgumentType.getString(context, "key");
        double value = DoubleArgumentType.getDouble(context, "value");

        Optional<TrainLine> line = lineRegistry.findLine(lineId);
        if (line.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.not_found", lineId.value()));
            return 0;
        }

        TrainLine lineValue = line.get();
        switch (key) {
            case "min_interval" -> lineValue.settings().setMinimumIntervalTicks((int) value);
            case "target_interval" -> lineValue.settings().setTargetIntervalOverrideTicks((int) value);
            case "min_dwell" -> lineValue.settings().setMinimumDwellTicks((int) value);
            case "dwell_extension" -> lineValue.settings().setDwellExtensionTicks((int) value);
            case "safety_buffer" -> lineValue.settings().setSafetyBufferTicks((int) value);
            case "resync" -> lineValue.settings().setResynchronizationAggressiveness(value);
            case "route_cooldown" -> lineValue.settings().setRouteSwitchCooldownTicks((int) value);
            case "route_wait" -> lineValue.settings().setRouteReplanWaitTicks((int) value);
            default -> {
                context.getSource().sendFailure(Component.literal("Unknown setting key: " + key));
                return 0;
            }
        }

        lineRegistry.markDirty();
        context.getSource().sendSuccess(() -> Component.literal("Set " + key + "=" + value + " for line " + lineId.value()), true);
        return Command.SINGLE_SUCCESS;
    }

    private int assignTrain(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        LineId lineId = new LineId(StringArgumentType.getString(context, "line_id"));

        Optional<TrainLine> line = lineRegistry.findLine(lineId);
        if (line.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("create_train_sloth.command.not_found", lineId.value()));
            return 0;
        }

        lineRegistry.assignTrain(trainId, lineId);
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.assigned", trainId, lineId.value()),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int unassignTrain(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        lineRegistry.unassignTrain(trainId);
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.unassigned", trainId),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int debugTrain(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        Train train = Create.RAILWAYS.trains.get(trainId);

        if (train == null) {
            context.getSource().sendFailure(Component.literal("Train " + trainId + " is not currently loaded."));
            return 0;
        }

        String debug = debugOverlay.debugForTrain(trainId);
        context.getSource().sendSuccess(() -> Component.literal("Train " + trainId + " -> " + debug), false);
        return Command.SINGLE_SUCCESS;
    }

    private int debugAllTrains(CommandContext<CommandSourceStack> context) {
        if (Create.RAILWAYS.trains.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No loaded trains."), false);
            return Command.SINGLE_SUCCESS;
        }

        Create.RAILWAYS.trains.values().stream()
            .sorted((a, b) -> a.id.toString().compareTo(b.id.toString()))
            .forEach(train -> context.getSource().sendSuccess(
                () -> Component.literal(train.id + " | " + train.name.getString()),
                false
            ));

        return Command.SINGLE_SUCCESS;
    }

    private int debugInterlocking(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        if (context.getSource().getLevel() == null) {
            context.getSource().sendFailure(Component.literal("No level context available."));
            return 0;
        }

        int activeBlocks = interlockingControlService.activeInterlockingCount(context.getSource().getLevel());
        boolean overrideActive = interlockingControlService.isOverrideActive(context.getSource().getLevel());
        String snapshotSummary = interlockingControlService.latestSnapshot(context.getSource().getLevel())
            .map(snapshot -> "snapshotTick=" + snapshot.tick() + " trackedTrains=" + snapshot.trains().size())
            .orElse("snapshot=<none>");

        String message = "interlockingBlocks=" + activeBlocks
            + " overrideActive=" + overrideActive
            + " " + snapshotSummary;
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return Command.SINGLE_SUCCESS;
    }

    private int debugSchedule(CommandContext<CommandSourceStack> context) {
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        Train train = Create.RAILWAYS.trains.get(trainId);

        if (train == null) {
            context.getSource().sendFailure(Component.literal("Train " + trainId + " is not currently loaded."));
            return 0;
        }

        if (train.runtime == null || train.runtime.getSchedule() == null) {
            context.getSource().sendFailure(Component.literal("Train " + trainId + " has no active schedule runtime."));
            return 0;
        }

        Schedule schedule = train.runtime.getSchedule();
        int currentEntry = train.runtime.currentEntry;
        String instructionId = "<out_of_range>";
        if (currentEntry >= 0 && currentEntry < schedule.entries.size() && schedule.entries.get(currentEntry).instruction != null) {
            instructionId = schedule.entries.get(currentEntry).instruction.getId().toString();
        }

        String navDestination = train.navigation.destination == null ? "<none>" : train.navigation.destination.name;
        String waitingSignal = train.navigation.waitingForSignal == null
            ? "<none>"
            : train.navigation.waitingForSignal.getFirst() + " (" + train.navigation.ticksWaitingForSignal + "t)";

        String message = "state=" + train.runtime.state
            + " currentEntry=" + currentEntry + "/" + schedule.entries.size()
            + " instruction=" + instructionId
            + " navDestination=" + navDestination
            + " waitingSignal=" + waitingSignal;

        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return Command.SINGLE_SUCCESS;
    }

    private int setServiceClass(CommandContext<CommandSourceStack> context) {
        if (!ensureRuntime(context.getSource())) {
            return 0;
        }
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        String rawClass = StringArgumentType.getString(context, "class");
        TrainServiceClass serviceClass = parseServiceClass(rawClass);
        if (serviceClass == null) {
            context.getSource().sendFailure(Component.literal("Unknown service class: " + rawClass + " (valid: S, IR, RE, IC, ICN, ICE)"));
            return 0;
        }

        Optional<TrainLineAssignment> assignment = lineRegistry.assignmentOf(trainId);
        if (assignment.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Train " + trainId + " is not assigned to a line."));
            return 0;
        }

        lineRegistry.assignTrain(trainId, assignment.get().lineId(), serviceClass);
        context.getSource().sendSuccess(
            () -> Component.literal("Set service class " + serviceClass.name() + " for train " + trainId),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private TrainServiceClass parseServiceClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (TrainServiceClass serviceClass : TrainServiceClass.values()) {
            if (serviceClass.name().equals(normalized) || serviceClass.prefix().equals(normalized)) {
                return serviceClass;
            }
        }
        return null;
    }
}
