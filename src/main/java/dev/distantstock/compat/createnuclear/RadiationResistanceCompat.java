package dev.distantstock.compat.createnuclear;

import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingArmorItem;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/**
 * Optional bridge for Create: Nuclear, so the resonant suit protects its wearer from radiation.
 *
 * <p>Create Nuclear does not test for its own armour by type or tag. It registers an attribute,
 * {@code createnuclear:generic.irradiated_resistance}, and its suit simply carries a modifier for it;
 * exposure is then scaled by {@code Mth.clamp(resistance, 0, 1)} read straight off the entity. That
 * makes this a data question rather than a code one -- anything that carries the attribute is
 * protective, which is what lets an addon join in without being asked.
 *
 * <p>Their suit puts {@code ADD_VALUE 0.25} on each piece, so a full set reaches the cap of 1.0 and
 * the wearer is immune. This uses the same figure, which makes the resonant suit an equal
 * alternative rather than a weaker one. It cannot be a stronger one: the cap is the cap.
 *
 * <p>No Create Nuclear type is named anywhere in this file. The attribute is looked up by id from
 * the registry, and the whole handler is a no-op when the addon is not installed, so this loads
 * either way and needs no ModList guard. That also means it is invisible in the dev environment,
 * where Create Nuclear is not on the classpath -- it has to be checked in a real pack.
 *
 * <p>The attribute is added through {@link ItemAttributeModifierEvent} rather than by overriding
 * {@code getDefaultAttributeModifiers}, because the default modifiers are a static component built
 * once at registration, when another mod's attribute may not be in the registry yet. This event runs
 * per query, on both sides, and lands in the tooltip for free -- the player sees "Radiation
 * Resistance +0.25" on each piece without any line of ours saying so.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class RadiationResistanceCompat {
    private static final ResourceLocation IRRADIATED_RESISTANCE =
            ResourceLocation.fromNamespaceAndPath("createnuclear", "generic.irradiated_resistance");
    private static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_radiation_resistance");

    /** Matches Create Nuclear's own suit; four pieces of this reach their cap of 1.0. */
    private static final double PER_PIECE = 0.25;

    /** Volatile because the event fires on whichever thread asks, client and server both. */
    private static volatile Holder<Attribute> attribute;

    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        if (!(event.getItemStack().getItem() instanceof EtherCasingArmorItem armour)) return;
        Holder<Attribute> resistance = resistance();
        if (resistance == null) return;
        event.addModifier(resistance,
                new AttributeModifier(MODIFIER_ID, PER_PIECE, AttributeModifier.Operation.ADD_VALUE),
                // Worn in its own slot, exactly as their pieces are scoped.
                EquipmentSlotGroup.bySlot(armour.getType().getSlot()));
    }

    /**
     * The attribute, or null when Create Nuclear is absent.
     *
     * <p>A miss is deliberately not cached: the first query can arrive before the registry is frozen,
     * and remembering that as "no such attribute" would silently disable the compat for the session.
     * Probing again costs a map lookup on a frozen registry, which is nothing next to the attribute
     * recomputation the caller is already doing.
     */
    private static Holder<Attribute> resistance() {
        Holder<Attribute> found = attribute;
        if (found == null) {
            found = BuiltInRegistries.ATTRIBUTE.getHolder(IRRADIATED_RESISTANCE).orElse(null);
            attribute = found;
        }
        return found;
    }

    private RadiationResistanceCompat() {
    }
}
