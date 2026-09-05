package dev.distantstock.link;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

public final class PackagePump {
    public static void drain(MinecraftServer server) {
        LinkQueues.Parcel p;
        int n = 0;
        while (n++ < 8 && (p = LinkQueues.pollInboundPackage()) != null) {
            ItemStack pkg = PackageCodec.decode(p.nbt, server.registryAccess());
            if (pkg.isEmpty() || !PackageItem.isPackage(pkg)) {
                continue;
            }
            DockBlockEntity dest = LoadedDocks.importFor(pkg);
            if (dest == null) {
                LoadedDocks.noMatch(pkg);
                continue;
            }
            if (dest.isFull() || !dest.insert(pkg)) {
                LinkQueues.requeueInbound(p);
                break;
            }
        }
    }

    private PackagePump() {
    }
}
