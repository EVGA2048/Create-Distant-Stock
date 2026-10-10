package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/** Materials for Distant Stock equipment. */
public final class ModArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, DistantStock.MODID);

    /**
     * Resonant quartz is hard rather than bulky. The figures are in {@link EtherCasingBalance},
     * together with the reasoning; the short version is that extra armour points past twenty are
     * discarded by {@code CombatRules} and toughness is the dial that actually does something.
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ETHER_CASING =
            MATERIALS.register("ether_casing", () -> new ArmorMaterial(
                    defenses(),
                    18,
                    SoundEvents.ARMOR_EQUIP_DIAMOND,
                    // What an anvil repairs the suit with. A piece that is not charged wears out, so
                    // this is maintenance stock the player has to keep making.
                    () -> Ingredient.of(ModItems.POLISHED_ETHER_QUARTZ.get()),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(
                            DistantStock.MODID, "ether_casing"))),
                    EtherCasingBalance.TOUGHNESS,
                    EtherCasingBalance.KNOCKBACK_RESISTANCE));

    private static EnumMap<ArmorItem.Type, Integer> defenses() {
        EnumMap<ArmorItem.Type, Integer> values = new EnumMap<>(ArmorItem.Type.class);
        values.put(ArmorItem.Type.BOOTS, EtherCasingBalance.BOOTS_DEFENSE);
        values.put(ArmorItem.Type.LEGGINGS, EtherCasingBalance.LEGGINGS_DEFENSE);
        values.put(ArmorItem.Type.CHESTPLATE, EtherCasingBalance.CHESTPLATE_DEFENSE);
        values.put(ArmorItem.Type.HELMET, EtherCasingBalance.HELMET_DEFENSE);
        values.put(ArmorItem.Type.BODY, EtherCasingBalance.BODY_DEFENSE);
        return values;
    }

    private ModArmorMaterials() {
    }
}
