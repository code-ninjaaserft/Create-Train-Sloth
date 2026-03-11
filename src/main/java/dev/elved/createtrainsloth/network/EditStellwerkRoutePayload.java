package dev.elved.createtrainsloth.network;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.entity.InterlockingBlockEntity;
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

            if (!(player.level().getBlockEntity(payload.interlockingPos) instanceof InterlockingBlockEntity interlockingBlockEntity)) {
                return;
            }

            if (payload.action == ACTION_ADD) {
                interlockingBlockEntity.editRouteStation(payload.lineId, payload.stationName, true);
                return;
            }
            if (payload.action == ACTION_REMOVE) {
                interlockingBlockEntity.editRouteStation(payload.lineId, payload.stationName, false);
                return;
            }
            if (payload.action == ACTION_MOVE) {
                interlockingBlockEntity.moveRouteStation(payload.lineId, payload.fromIndex, payload.toIndex);
                return;
            }
            if (payload.action == ACTION_CREATE) {
                interlockingBlockEntity.createRoute(payload.routeName, payload.serviceClass);
                return;
            }
            if (payload.action == ACTION_UPDATE_META) {
                interlockingBlockEntity.updateSelectedRouteMeta(payload.routeName, payload.serviceClass);
                return;
            }
            if (payload.action == ACTION_DELETE_ROUTE) {
                interlockingBlockEntity.deleteRoute(payload.lineId);
            }
        });
    }
}
