package dev.distantstock.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/** 主手 / 副手 / Curios body / 背包，跟 Mobile Packages 便携仓管同一套找法。 */
public final class RequesterFind {
    public static ItemStack find(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof RequesterItem) {
                return stack;
            }
        }
        ItemStack curios = fromCurios(player);
        if (!curios.isEmpty()) {
            return curios;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof RequesterItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static InteractionHand preferredHand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof RequesterItem) {
                return hand;
            }
        }
        return InteractionHand.MAIN_HAND;
    }

    private static ItemStack fromCurios(Player player) {
        if (!ModList.get().isLoaded("curios")) {
            return ItemStack.EMPTY;
        }
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getInv = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object opt = getInv.invoke(null, player);
            if (!(opt instanceof Optional<?> inventoryOpt) || inventoryOpt.isEmpty()) {
                return ItemStack.EMPTY;
            }
            Object handler = inventoryOpt.get();
            Method getCurios = handler.getClass().getMethod("getCurios");
            Object map = getCurios.invoke(handler);
            if (!(map instanceof Map<?, ?> curios)) {
                return ItemStack.EMPTY;
            }
            for (Object stacksHandler : curios.values()) {
                Method getSlots = stacksHandler.getClass().getMethod("getSlots");
                Method getStacks = stacksHandler.getClass().getMethod("getStacks");
                int slots = (int) getSlots.invoke(stacksHandler);
                Object stacks = getStacks.invoke(stacksHandler);
                Method getStackInSlot = stacks.getClass().getMethod("getStackInSlot", int.class);
                for (int i = 0; i < slots; i++) {
                    Object raw = getStackInSlot.invoke(stacks, i);
                    if (raw instanceof ItemStack stack && stack.getItem() instanceof RequesterItem) {
                        return stack;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return ItemStack.EMPTY;
    }

    private RequesterFind() {
    }
}
