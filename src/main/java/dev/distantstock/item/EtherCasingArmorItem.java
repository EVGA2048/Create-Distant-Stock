package dev.distantstock.item;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** The four-piece Distant Casing suit. */
public final class EtherCasingArmorItem extends ArmorItem {
    public EtherCasingArmorItem(Type type, Item.Properties properties) {
        super(ModArmorMaterials.ETHER_CASING, type, properties);
    }

    public static boolean hasFullSet(LivingEntity entity) {
        return entity != null
                && entity.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.ETHER_CASING_HELMET.get())
                && entity.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.ETHER_CASING_CHESTPLATE.get())
                && entity.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.ETHER_CASING_LEGGINGS.get())
                && entity.getItemBySlot(EquipmentSlot.FEET).is(ModItems.ETHER_CASING_BOOTS.get());
    }

    public static boolean isCloaking(LivingEntity entity) {
        return entity != null && entity.isCrouching() && hasFullSet(entity);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.cloak")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.stealth")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.reach")
                .withStyle(ChatFormatting.GRAY));
        if (getType() == Type.HELMET) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.goggles")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
                                            ArmorMaterial.Layer layer, boolean innerModel) {
        int phase = entity instanceof LivingEntity living ? EtherCasingCloakState.get(living) : 0;
        phase = Math.max(0, Math.min(EtherCasingCloakState.PHASES, phase));
        return ResourceLocation.fromNamespaceAndPath("distantstock",
                "textures/models/armor/ether_casing_layer_" + (innerModel ? 2 : 1)
                        + "_phase_" + phase + ".png");
    }
}
