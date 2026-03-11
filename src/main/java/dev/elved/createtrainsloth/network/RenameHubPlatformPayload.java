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

public record RenameHubPlatformPayload(
    BlockPos hubPos,
    String oldPlatformName,
    String newPlatformName
) implements CustomPacketPayload {

    public static final Type<RenameHubPlatformPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CreateTrainSlothMod.MOD_ID, "rename_hub_platform")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RenameHubPlatformPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeBlockPos(payload.hubPos);
            buffer.writeUtf(payload.oldPlatformName, 120);
            buffer.writeUtf(payload.newPlatformName, 120);
        },
        buffer -> new RenameHubPlatformPayload(
            buffer.readBlockPos(),
            buffer.readUtf(120),
            buffer.readUtf(120)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(RenameHubPlatformPayload payload, IPayloadContext context) {
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

            stationHubBlockEntity.renamePlatform(payload.oldPlatformName, payload.newPlatformName);
        });
    }
}
