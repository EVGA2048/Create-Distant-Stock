package dev.distantstock.block;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoadedDocks {
    private static final Set<DockBlockEntity> ALL = ConcurrentHashMap.newKeySet();
    private static final Set<GaugeBlockEntity> GAUGES = ConcurrentHashMap.newKeySet();

    public static void add(DockBlockEntity be) {
        ALL.add(be);
    }

    public static void remove(DockBlockEntity be) {
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
        for (DockBlockEntity be : ALL) {
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

    public static DockBlockEntity importFor(ItemStack pkg) {
        DockBlockEntity full = null;
        for (DockBlockEntity be : ALL) {
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
        for (DockBlockEntity be : ALL) {
            if (!be.isImport() || be.isRemoved()) {
                continue;
            }
            String filter = be.address().isBlank() ? "*" : be.address();
            if (!PackageItem.matchAddress(pkg, filter)) {
                be.rejected();
            }
        }
    }

    private LoadedDocks() {
    }
}
