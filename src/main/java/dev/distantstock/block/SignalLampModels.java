package dev.distantstock.block;

import dev.distantstock.DistantStock;
import dev.distantstock.item.SignalLampPanelItem;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The lamp art, by material, colour and whether it is lit.
 *
 * <p>Common side, like {@link RemoteGaugeModels} and for the same reason: two callers ask for it and
 * they must agree. Our own lamp board draws them from its renderer; a lamp sitting on somebody else's
 * board is drawn by whichever renderer that board's mod registered, and a second table would be two
 * lamps of the same colour that do not match.
 */
public final class SignalLampModels {
    private static final Map<String, PartialModel> LAMPS = new HashMap<>();

    static {
        for (SignalLampPanelItem.Material material : SignalLampPanelItem.Material.values()) {
            for (SignalLampPanelItem.Color color : SignalLampPanelItem.Color.values()) {
                for (String state : new String[]{"off", "on"}) {
                    String key = material.name().toLowerCase() + "_" + color.name().toLowerCase()
                            + "_" + state;
                    LAMPS.put(key, PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                            DistantStock.MODID, "block/signal_panel/" + key)));
                }
            }
        }
    }

    private SignalLampModels() {
    }

    /** Forces this class to load, and with it every partial, before the first model bake. */
    public static void init() {
    }

    public static PartialModel lamp(SignalLampPanelItem.Material material,
                                    SignalLampPanelItem.Color color, boolean lit) {
        return LAMPS.get(material.name().toLowerCase() + "_" + color.name().toLowerCase()
                + "_" + (lit ? "on" : "off"));
    }

    /** Every lamp partial, for the client smoke test to check that all of them baked. */
    public static Map<String, PartialModel> all() {
        return Map.copyOf(LAMPS);
    }
}
