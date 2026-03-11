package dev.elved.createtrainsloth.network;

import dev.elved.createtrainsloth.CreateTrainSlothMod;
import dev.elved.createtrainsloth.block.entity.StationHubBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RenameStationHubPayload(
    BlockPos hubPos,
    String newHubName
) implements CustomPacketPayload {

    public static final Type<RenameStationHubPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreateTrainSlothMod.MOD_ID, "rename_station_hub")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RenameStationHubPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeBlockPos(payload.hubPos);
            buffer.writeUtf(payload.newHubName, 120);
        },
        buffer -> new RenameStationHubPayload(
            buffer.readBlockPos(),
            buffer.readUtf(120)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(RenameStationHubPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            if (player.distanceToSqr(
                payload.hubPos.getX() + 0.5D,
                payload.hubPos.getY() + 0.5D,
                payload.hubPos.getZ() + 0.5D
            ) > 64D) {
                return;
            }

            if (!(player.level().getBlockEntity(payload.hubPos) instanceof StationHubBlockEntity stationHubBlockEntity)) {
                return;
            }

            stationHubBlockEntity.renameHub(payload.newHubName);
        });
    }
}
