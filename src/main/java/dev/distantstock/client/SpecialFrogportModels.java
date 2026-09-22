package dev.distantstock.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.DistantStock;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Reuses Create's Frogport geometry/animation with authored Distant Stock textures.
 *
 * No RGB multiplication is used here: the orange/green colours come entirely from the texture
 * assets, preserving the original metal, wood, eyes, mouth, tongue and indicator colours.
 */
public final class SpecialFrogportModels {
    private static final PartialModel DIAGNOSTIC_BODY = model("diagnostic_body");
    private static final PartialModel DIAGNOSTIC_HEAD = model("diagnostic_head");
    private static final PartialModel DIAGNOSTIC_HEAD_GOGGLES = model("diagnostic_head_goggles");
    private static final PartialModel DIAGNOSTIC_TONGUE = model("diagnostic_tongue");

    private static final PartialModel CACHE_BODY = model("cache_body");
    private static final PartialModel CACHE_HEAD = model("cache_head");
    private static final PartialModel CACHE_HEAD_GOGGLES = model("cache_head_goggles");
    private static final PartialModel CACHE_TONGUE = model("cache_tongue");

    private static PartialModel model(String name) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                DistantStock.MODID, "block/frogport/" + name));
    }

    public static void init() {
        // Class loading before model bake performs PartialModel registration.
    }

    public static PartialModel replace(FrogportBlockEntity frog, PartialModel original) {
        if (frog instanceof DiagnosticFrogportBlockEntity) {
            return replace(original, DIAGNOSTIC_BODY, DIAGNOSTIC_HEAD,
                    DIAGNOSTIC_HEAD_GOGGLES, DIAGNOSTIC_TONGUE);
        }
        if (frog instanceof CacheFrogportBlockEntity) {
            return replace(original, CACHE_BODY, CACHE_HEAD,
                    CACHE_HEAD_GOGGLES, CACHE_TONGUE);
        }
        return original;
    }

    private static PartialModel replace(PartialModel original,
                                        PartialModel body,
                                        PartialModel head,
                                        PartialModel goggles,
                                        PartialModel tongue) {
        if (original == AllPartialModels.FROGPORT_BODY) return body;
        if (original == AllPartialModels.FROGPORT_HEAD) return head;
        if (original == AllPartialModels.FROGPORT_HEAD_GOGGLES) return goggles;
        if (original == AllPartialModels.FROGPORT_TONGUE) return tongue;
        return original;
    }

    private SpecialFrogportModels() {
    }
}
