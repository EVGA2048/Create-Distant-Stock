package dev.distantstock.link;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.LinkerBlockEntity;
import dev.distantstock.block.LoadedLinkers;
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
            LinkerBlockEntity dest = LoadedLinkers.importFor(pkg);
            if (dest == null) {
                LoadedLinkers.noMatch(pkg);
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
