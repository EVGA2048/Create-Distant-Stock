package dev.distantstock.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A slot that records an item without ever holding one.
 *
 * The lamp's watch list is a filter: it says which items to look at, not where to keep them. Taking
 * the item for real would mean a player has to give up an iron ingot to be told when iron runs out,
 * which is not a trade anybody wants to make. So the stack is copied in, nothing is consumed, and
 * nothing can be pulled back out.
 *
 * Setting and clearing are driven from {@code MonitorMenu.clicked} instead of the vanilla slot
 * logic, because vanilla only reaches a slot through pickup and place, and neither of those can
 * express "copy this in".
 */
public final class GhostSlot extends Slot {
    public GhostSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    /** Always one: a filter entry is a kind of item, not a quantity. */
    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** Nothing is ever taken out of a filter. */
    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return true;
    }

    @Override
    public ItemStack remove(int amount) {
        return ItemStack.EMPTY;
    }

    /** Stores a single copy, so the caller's stack is never touched. */
    public void setGhost(ItemStack stack) {
        set(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
    }
}
