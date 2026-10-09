package dev.distantstock.item;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
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
        // Use the synchronized sneak flag, not the resolved CROUCHING pose. The pose is derived
        // later and is not a reliable cross-client/server trigger (flying players are the obvious
        // example). The shared shift flag is exactly what the player actually pressed and is sent
        // to the server/other clients by vanilla.
        return entity != null && entity.isShiftKeyDown() && hasFullSet(entity);
    }

    @Override
    public boolean canElytraFly(ItemStack stack, LivingEntity entity) {
        return getType() == Type.CHESTPLATE && BacktankUtil.hasAirRemaining(stack);
    }

    @Override
    public boolean elytraFlightTick(ItemStack stack, LivingEntity entity, int flightTicks) {
        if (!entity.level().isClientSide && flightTicks % 10 == 0) {
            BacktankUtil.consumeAir(entity, stack, 1);
        }
        return BacktankUtil.hasAirRemaining(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        // Lazy seed: the chestplate starts with full air on first equip, refills to max on every
        // tick when held in creative. Capacity reads a server config, so this cannot run at
        // registration time; inventoryTick fires for armor slots, so it is the earliest hook.
        if (getType() != Type.CHESTPLATE) return;
        if (level.isClientSide) return;
        if (!(entity instanceof LivingEntity living)) return;
        int max = BacktankUtil.maxAir(stack);
        int current = BacktankUtil.getAir(stack);
        if (current >= max) return;
        boolean creative = entity instanceof net.minecraft.world.entity.player.Player player
                && player.getAbilities().instabuild;
        if (creative) {
            stack.set(AllDataComponents.BACKTANK_AIR, max);
        } else if (current == 0) {
            stack.set(AllDataComponents.BACKTANK_AIR, max);
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getType() == Type.CHESTPLATE && BacktankUtil.getAir(stack) < BacktankUtil.maxAir(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        if (getType() != Type.CHESTPLATE) return super.getBarWidth(stack);
        int max = BacktankUtil.maxAir(stack);
        return max == 0 ? 0 : Math.min(13, Math.round(13f * BacktankUtil.getAir(stack) / max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        if (getType() != Type.CHESTPLATE) return super.getBarColor(stack);
        return 0x5DADE2;
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
        if (getType() == Type.CHESTPLATE) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.flight")
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.boost")
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
