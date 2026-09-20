package dev.distantstock.stock;

import com.simibubi.create.Create;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** Server-side boundary for Create logistics-network ownership/lock semantics. */
public final class CreateNetworkAccess {
    public static boolean isLocal(RemoteNetworkId network, UUID frequency) {
        UUID localNode = TranserverBridge.localNodeUuid();
        if (network != null && localNode != null && localNode.equals(network.nodeId())) {
            return true;
        }
        if (frequency == null) return false;
        return NetworkDirectory.findByFreq(frequency).map(NetworkDirectory.Entry::local).orElse(false);
    }

    /** Remote Distant-Stock members are authorized by Distant Stock, not by a player on this node. */
    public static boolean mayInteract(RemoteNetworkId network, UUID frequency, Player player) {
        if (player == null || !isLocal(network, frequency)) return true;
        UUID freq = network != null ? network.createFrequency() : frequency;
        return freq != null && Create.LOGISTICS.mayInteract(freq, player);
    }

    /** Joining/leaving a Distant Stock network or first-time binding is an administrative act. */
    public static boolean mayAdministrate(RemoteNetworkId network, UUID frequency, Player player) {
        if (player == null || !isLocal(network, frequency)) return false;
        UUID freq = network != null ? network.createFrequency() : frequency;
        return freq != null && Create.LOGISTICS.mayAdministrate(freq, player);
    }

    private CreateNetworkAccess() {
    }
}
