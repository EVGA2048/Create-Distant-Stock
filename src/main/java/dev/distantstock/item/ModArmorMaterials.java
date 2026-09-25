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
     * A small step above netherite rather than a second creative-mode armour tier:
     * one extra armour point, +1 toughness, slightly more knockback resistance and durability.
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ETHER_CASING =
            MATERIALS.register("ether_casing", () -> new ArmorMaterial(
                    defenses(),
                    18,
                    SoundEvents.ARMOR_EQUIP_DIAMOND,
                    () -> Ingredient.of(ModItems.POLISHED_ETHER_QUARTZ.get()),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(
                            DistantStock.MODID, "ether_casing"))),
                    4.0F,
                    0.15F));

    private static EnumMap<ArmorItem.Type, Integer> defenses() {
        EnumMap<ArmorItem.Type, Integer> values = new EnumMap<>(ArmorItem.Type.class);
        values.put(ArmorItem.Type.BOOTS, 3);
        values.put(ArmorItem.Type.LEGGINGS, 6);
        values.put(ArmorItem.Type.CHESTPLATE, 8);
        values.put(ArmorItem.Type.HELMET, 4);
        values.put(ArmorItem.Type.BODY, 12);
        return values;
    }

    private ModArmorMaterials() {
    }
}
