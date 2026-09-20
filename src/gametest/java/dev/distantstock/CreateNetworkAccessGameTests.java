package dev.distantstock;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.stock.CreateNetworkAccess;
import dev.distantstock.link.TranserverBridge;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Contract between Distant Stock's remote-access boundary and Create's own logistics permissions. */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class CreateNetworkAccessGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void distantStockUsesCreateOwnershipAndLockSemantics(GameTestHelper h) {
        UUID node = TranserverBridge.localNodeUuid();
        h.assertTrue(node != null, "Transerver stable node identity is unavailable");
        UUID freq = UUID.randomUUID();
        var remote = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node,
                WorldIdentity.get(h.getLevel()), h.getLevel().dimension().location().toString(), freq);
        var logistics = new LogisticsNetwork(freq);
        Create.LOGISTICS.logisticsNetworks.put(freq, logistics);
        var owner = h.makeMockPlayer(GameType.SURVIVAL);
        var stranger = h.makeMockPlayer(GameType.SURVIVAL);
        try {
            h.assertTrue(CreateNetworkAccess.mayAdministrate(remote, freq, stranger),
                    "an unowned Create network was made stricter than Create itself");

            logistics.owner = owner.getUUID();
            logistics.locked = false;
            h.assertTrue(CreateNetworkAccess.mayInteract(remote, freq, stranger),
                    "an unlocked Create network rejected ordinary interaction");
            h.assertFalse(CreateNetworkAccess.mayAdministrate(remote, freq, stranger),
                    "a non-owner could administrate an owned Create network");

            logistics.locked = true;
            h.assertFalse(CreateNetworkAccess.mayInteract(remote, freq, stranger),
                    "a non-owner could use a locked Create network through Distant Stock");
            h.assertTrue(CreateNetworkAccess.mayInteract(remote, freq, owner)
                            && CreateNetworkAccess.mayAdministrate(remote, freq, owner),
                    "the Create network owner lost access through Distant Stock");
        } finally {
            Create.LOGISTICS.logisticsNetworks.remove(freq);
        }
        h.succeed();
    }

    private CreateNetworkAccessGameTests() {
    }
}
