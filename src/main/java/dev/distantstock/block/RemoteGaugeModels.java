package dev.distantstock.block;

import dev.distantstock.DistantStock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The remote gauge's panel art, by name.
 *
 * <p>These live on the common side rather than next to the renderer because they are asked for in
 * two places that must agree: the block entity renderer that draws our own board, and the panel
 * type that tells Create (or Deployer) which housing to draw when a remote gauge sits on somebody
 * else's board. A second table for the second caller is how the two would end up drawing different
 * housings for the same panel.
 */
public final class RemoteGaugeModels {
    private static final String[] PARTS = {
            "panel", "panel_with_bulb", "panel_restocker", "panel_restocker_with_bulb",
            "bulb_light", "bulb_red"
    };
    private static final Map<String, PartialModel> MODELS = new HashMap<>();

    static {
        for (String part : PARTS) {
            MODELS.put(part, PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                    DistantStock.MODID, "block/remote_gauge/" + part)));
        }
    }

    private RemoteGaugeModels() {
    }

    /** Forces this class to load, and with it every partial, before the first model bake. */
    public static void init() {
    }

    /** The housing for one slot. Create picks the passive/active variant from the configured amount. */
    public static PartialModel panel(boolean restocker, boolean active) {
        return MODELS.get(restocker
                ? (active ? "panel_restocker_with_bulb" : "panel_restocker")
                : (active ? "panel_with_bulb" : "panel"));
    }

    /** The bulb that sits on the housing: white while this panel is content, red while it is not. */
    public static PartialModel bulb(boolean lit) {
        return MODELS.get(lit ? "bulb_light" : "bulb_red");
    }
}
