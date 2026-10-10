package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.PortableFluidTankItem;
import dev.distantstock.item.TankFluidColors;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Paints a tank's fluid layer with whatever is inside it.
 *
 * <p>Vanilla's own arrangement, copied exactly: the fluid is layer 0 and gets the tint, the vessel
 * is layer 1 and never does. That is why the two layers exist at all -- see {@code models/item/
 * potion.json}, where a grey liquid blob is tinted per potion and the bottle is drawn over it. The
 * rule is written as {@code layer > 0 ? -1 : tint} to match {@code ItemColors}' own potion entry,
 * and getting it backwards would tint the metal shell instead of the contents.
 *
 * <p>All three tanks are registered because colour providers are keyed by item, not by class: a tank
 * left out of this list renders its fluid in whatever colour the missing-sprite path produces.
 */
@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TankItemColors {
    @SubscribeEvent
    public static void itemColors(RegisterColorHandlersEvent.Item e) {
        e.register((stack, layer) -> layer > 0 ? -1 : TankFluidColors.of(PortableFluidTankItem.fluid(stack)),
                ModItems.COPPER_FLUID_TANK.get(),
                ModItems.STURDY_FLUID_TANK.get(),
                ModItems.RESONANT_CANISTER.get());
    }

    private TankItemColors() {
    }
}
