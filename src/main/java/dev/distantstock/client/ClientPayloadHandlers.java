package dev.distantstock.client;

import dev.distantstock.net.AdminConfigS2C;
import dev.distantstock.net.LinkSnapshotS2C;
import net.minecraft.client.Minecraft;

/** Client-only packet effects, isolated so dedicated servers never resolve GUI classes. */
public final class ClientPayloadHandlers {
    public static void openMonitor(LinkSnapshotS2C message) {
        Minecraft.getInstance().setScreen(new MonitorScreen(message.view()));
    }

    public static void openAdmin(AdminConfigS2C message) {
        Minecraft.getInstance().setScreen(new AdminScreen(message));
    }

    private ClientPayloadHandlers() {
    }
}
