package dev.elved.createtrainsloth.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import dev.elved.createtrainsloth.debug.DebugOverlay;
import dev.elved.createtrainsloth.line.LineId;
import dev.elved.createtrainsloth.line.LineManager;
import dev.elved.createtrainsloth.line.LineRegistry;
import dev.elved.createtrainsloth.line.TrainLine;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class TrainSlothCommands {

    private final LineRegistry lineRegistry;
    private final LineManager lineManager;
    private final DebugOverlay debugOverlay;

    public TrainSlothCommands(LineRegistry lineRegistry, LineManager lineManager, DebugOverlay debugOverlay) {
        this.lineRegistry = lineRegistry;
        this.lineManager = lineManager;
        this.debugOverlay = debugOverlay;
    }

    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            literal("trainsloth")
                .requires(source -> source.hasPermission(2))
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
                .then(literal("assign")
                    .then(argument("train_id", UuidArgument.uuid())
                        .then(argument("line_id", StringArgumentType.word())
                            .executes(this::assignTrain))))
                .then(literal("unassign")
                    .then(argument("train_id", UuidArgument.uuid())
                        .executes(this::unassignTrain)))
                .then(literal("debug")
                    .then(literal("train")
                        .then(argument("train_id", UuidArgument.uuid())
                            .executes(this::debugTrain))))
        );
    }

    private int createLine(CommandContext<CommandSourceStack> context, String lineIdRaw, String displayName) {
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

    private int setLineSetting(CommandContext<CommandSourceStack> context) {
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
        UUID trainId = UuidArgument.getUuid(context, "train_id");
        lineRegistry.unassignTrain(trainId);
        context.getSource().sendSuccess(
            () -> Component.translatable("create_train_sloth.command.unassigned", trainId),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    private int debugTrain(CommandContext<CommandSourceStack> context) {
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
}
