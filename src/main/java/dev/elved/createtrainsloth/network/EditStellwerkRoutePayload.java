package dev.elved.createtrainsloth.network;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
import dev.elved.createtrainsloth.block.entity.LineManagerComputerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EditStellwerkRoutePayload(
    BlockPos interlockingPos,
    String lineId,
    String stationName,
    String routeName,
    String serviceClass,
    int fromIndex,
    int toIndex,
    byte action
) implements CustomPacketPayload {

    public static final byte ACTION_ADD = 0;
    public static final byte ACTION_REMOVE = 1;
    public static final byte ACTION_MOVE = 2;
    public static final byte ACTION_CREATE = 3;
    public static final byte ACTION_UPDATE_META = 4;
    public static final byte ACTION_DELETE_ROUTE = 5;
    public static final byte ACTION_TOGGLE_DEPOT_HUB = 6;

    public static final Type<EditStellwerkRoutePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreateTrainSlothMod.MOD_ID, "edit_stellwerk_route")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, EditStellwerkRoutePayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeBlockPos(payload.interlockingPos);
            buffer.writeUtf(payload.lineId, 120);
            buffer.writeUtf(payload.stationName, 120);
            buffer.writeUtf(payload.routeName, 120);
            buffer.writeUtf(payload.serviceClass, 16);
            buffer.writeInt(payload.fromIndex);
            buffer.writeInt(payload.toIndex);
            buffer.writeByte(payload.action);
        },
        buffer -> new EditStellwerkRoutePayload(
            buffer.readBlockPos(),
            buffer.readUtf(120),
            buffer.readUtf(120),
            buffer.readUtf(120),
            buffer.readUtf(16),
            buffer.readInt(),
            buffer.readInt(),
            buffer.readByte()
        )
    );

    public static EditStellwerkRoutePayload addStation(BlockPos pos, String lineId, String stationName) {
        return new EditStellwerkRoutePayload(pos, lineId, stationName, "", "", -1, -1, ACTION_ADD);
    }

    public static EditStellwerkRoutePayload removeStation(BlockPos pos, String lineId, String stationName) {
        return new EditStellwerkRoutePayload(pos, lineId, stationName, "", "", -1, -1, ACTION_REMOVE);
    }

    public static EditStellwerkRoutePayload moveStation(BlockPos pos, String lineId, int fromIndex, int toIndex) {
        return new EditStellwerkRoutePayload(pos, lineId, "", "", "", fromIndex, toIndex, ACTION_MOVE);
    }

    public static EditStellwerkRoutePayload createRoute(BlockPos pos, String routeName, String serviceClass) {
        return new EditStellwerkRoutePayload(pos, "", "", routeName, serviceClass, -1, -1, ACTION_CREATE);
    }

    public static EditStellwerkRoutePayload updateMeta(BlockPos pos, String lineId, String routeName, String serviceClass) {
        return new EditStellwerkRoutePayload(pos, lineId, "", routeName, serviceClass, -1, -1, ACTION_UPDATE_META);
    }

    public static EditStellwerkRoutePayload deleteRoute(BlockPos pos, String lineId) {
        return new EditStellwerkRoutePayload(pos, lineId, "", "", "", -1, -1, ACTION_DELETE_ROUTE);
    }

    public static EditStellwerkRoutePayload toggleDepotHub(BlockPos pos, String lineId, String hubId) {
        return new EditStellwerkRoutePayload(pos, lineId, hubId, "", "", -1, -1, ACTION_TOGGLE_DEPOT_HUB);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(EditStellwerkRoutePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            if (player.distanceToSqr(
                payload.interlockingPos.getX() + 0.5D,
                payload.interlockingPos.getY() + 0.5D,
                payload.interlockingPos.getZ() + 0.5D
            ) > 64D) {
                return;
            }

            Object blockEntity = player.level().getBlockEntity(payload.interlockingPos);
            if (!(blockEntity instanceof InterlockingBlockEntity) && !(blockEntity instanceof LineManagerComputerBlockEntity)) {
                return;
            }

            if (payload.action == ACTION_ADD) {
                editRouteStation(blockEntity, payload.lineId, payload.stationName, true);
                return;
            }
            if (payload.action == ACTION_REMOVE) {
                editRouteStation(blockEntity, payload.lineId, payload.stationName, false);
                return;
            }
            if (payload.action == ACTION_MOVE) {
                moveRouteStation(blockEntity, payload.lineId, payload.fromIndex, payload.toIndex);
                return;
            }
            if (payload.action == ACTION_CREATE) {
                createRoute(blockEntity, payload.routeName, payload.serviceClass);
                return;
            }
            if (payload.action == ACTION_UPDATE_META) {
                updateRouteMeta(blockEntity, payload.routeName, payload.serviceClass);
                return;
            }
            if (payload.action == ACTION_DELETE_ROUTE) {
                deleteRoute(blockEntity, payload.lineId);
                return;
            }
            if (payload.action == ACTION_TOGGLE_DEPOT_HUB) {
                toggleDepotHub(blockEntity, payload.lineId, payload.stationName);
            }
        });
    }

    private static void editRouteStation(Object blockEntity, String lineId, String stationName, boolean add) {
        if (blockEntity instanceof InterlockingBlockEntity interlockingBlockEntity) {
            interlockingBlockEntity.editRouteStation(lineId, stationName, add);
            return;
        }
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.editRouteStation(lineId, stationName, add);
        }
    }

    private static void moveRouteStation(Object blockEntity, String lineId, int fromIndex, int toIndex) {
        if (blockEntity instanceof InterlockingBlockEntity interlockingBlockEntity) {
            interlockingBlockEntity.moveRouteStation(lineId, fromIndex, toIndex);
            return;
        }
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.moveRouteStation(lineId, fromIndex, toIndex);
        }
    }

    private static void createRoute(Object blockEntity, String routeName, String serviceClass) {
        if (blockEntity instanceof InterlockingBlockEntity interlockingBlockEntity) {
            interlockingBlockEntity.createRoute(routeName, serviceClass);
            return;
        }
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.createRoute(routeName, serviceClass);
        }
    }

    private static void updateRouteMeta(Object blockEntity, String routeName, String serviceClass) {
        if (blockEntity instanceof InterlockingBlockEntity interlockingBlockEntity) {
            interlockingBlockEntity.updateSelectedRouteMeta(routeName, serviceClass);
            return;
        }
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.updateSelectedRouteMeta(routeName, serviceClass);
        }
    }

    private static void deleteRoute(Object blockEntity, String lineId) {
        if (blockEntity instanceof InterlockingBlockEntity interlockingBlockEntity) {
            interlockingBlockEntity.deleteRoute(lineId);
            return;
        }
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.deleteRoute(lineId);
        }
    }

    private static void toggleDepotHub(Object blockEntity, String lineId, String hubId) {
        if (blockEntity instanceof LineManagerComputerBlockEntity lineManagerComputerBlockEntity) {
            lineManagerComputerBlockEntity.toggleLineDepotHub(lineId, hubId);
        }
    }
}
