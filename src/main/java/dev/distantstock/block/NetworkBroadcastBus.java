package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;

import java.util.UUID;

/** Dispatches one broadcast to loaded output devices on the same Create logistics network. */
public final class NetworkBroadcastBus {
    public interface Receiver {
        void receiveNetworkBroadcast(String text, int soundProfile);
    }

    public static int send(UUID network, String text, int soundProfile) {
        if (network == null || text == null || text.isBlank()) return 0;
        int delivered = 0;
        for (LogisticallyLinkedBehaviour link : LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
            if (!(link.blockEntity instanceof Receiver receiver)) continue;
            receiver.receiveNetworkBroadcast(text, soundProfile);
            delivered++;
        }
        return delivered;
    }

    private NetworkBroadcastBus() {
    }
}
