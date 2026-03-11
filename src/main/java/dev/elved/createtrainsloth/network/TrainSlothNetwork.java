package dev.elved.createtrainsloth.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TrainSlothNetwork {

    private static final String NETWORK_VERSION = "1";

    private TrainSlothNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(
            RenameHubPlatformPayload.TYPE,
            RenameHubPlatformPayload.STREAM_CODEC,
            RenameHubPlatformPayload::handleServer
        );
        registrar.playToServer(
            EditStellwerkRoutePayload.TYPE,
            EditStellwerkRoutePayload.STREAM_CODEC,
            EditStellwerkRoutePayload::handleServer
        );
    }
}
