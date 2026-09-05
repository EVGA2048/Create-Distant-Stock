package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoadedLinkers {
    private static final Set<LinkerBlockEntity> ALL = ConcurrentHashMap.newKeySet();
    private static final Set<GaugeBlockEntity> GAUGES = ConcurrentHashMap.newKeySet();

    public static void add(LinkerBlockEntity be) {
        ALL.add(be);
    }

    public static void remove(LinkerBlockEntity be) {
        ALL.remove(be);
    }

    public static void add(GaugeBlockEntity be) {
        GAUGES.add(be);
    }

    public static void remove(GaugeBlockEntity be) {
        GAUGES.remove(be);
    }

    public static List<UUID> watched() {
        List<UUID> out = new ArrayList<>();
        for (LinkerBlockEntity be : ALL) {
            if (be.freq() != null) {
                out.add(be.freq());
            }
        }
        for (GaugeBlockEntity be : GAUGES) {
            if (be.freq() != null) {
                out.add(be.freq());
            }
        }
        return out;
    }

    public static LinkerBlockEntity importFor(ItemStack pkg) {
        LinkerBlockEntity full = null;
        for (LinkerBlockEntity be : ALL) {
            if (!be.isImport() || be.isRemoved()) {
                continue;
            }
            String filter = be.address().isBlank() ? "*" : be.address();
            if (!PackageItem.matchAddress(pkg, filter)) {
                continue;
            }
            if (!be.isFull()) {
                return be;
            }
            full = be;
        }
        return full;
    }

    public static void noMatch(ItemStack pkg) {
        for (LinkerBlockEntity be : ALL) {
            if (!be.isImport() || be.isRemoved()) {
                continue;
            }
            String filter = be.address().isBlank() ? "*" : be.address();
            if (!PackageItem.matchAddress(pkg, filter)) {
                be.rejected();
            }
        }
    }

    private LoadedLinkers() {
    }
}
