package dev.distantstock.menu;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.block.SignalPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The brass lamp's watch list, exposed as ordinary container slots.
 *
 * Using real slots rather than a custom drag handler is the whole trick: dragging from JEI,
 * shift-clicking from the inventory and the usual slot interactions all work without any extra
 * code, and Minecraft syncs the contents for us.
 */
public final class MonitorMenu extends AbstractContainerMenu {
    private static final int COLUMNS = 4;
    private static final int ROWS = SignalPanelBlockEntity.MONITOR_SLOTS / COLUMNS;
    private static final int CELL = 20;
    private static final int MONITOR_X = 48;
    private static final int MONITOR_Y = 20;
    private static final int INV_X = 8;
    /** Read by the screen to place the inventory label one row above the slots. */
    public static final int INV_Y = 88;
    private static final int HOTBAR_Y = 146;

    public final BlockPos lampPos;
    public final FactoryPanelBlock.PanelSlot lampSlot;

    private MonitorMenu(int id, Inventory inv, BlockPos pos, FactoryPanelBlock.PanelSlot lampSlot,
                        Container monitor) {
        super(ModMenus.LAMP_MONITOR.get(), id);
        this.lampPos = pos;
        this.lampSlot = lampSlot;
        for (int i = 0; i < SignalPanelBlockEntity.MONITOR_SLOTS; i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            addSlot(new GhostSlot(monitor, i, MONITOR_X + col * CELL, MONITOR_Y + row * CELL));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    /** Server side: the slots point at the lamp's real storage. */
    public static MonitorMenu server(int id, Inventory inv, BlockPos pos,
                                     FactoryPanelBlock.PanelSlot lampSlot, Container monitor) {
        return new MonitorMenu(id, inv, pos, lampSlot, monitor);
    }

    /** Client side: an empty mirror that Minecraft fills in from slot updates. */
    public static MonitorMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new MonitorMenu(id, inv, buf.readBlockPos(),
                buf.readEnum(FactoryPanelBlock.PanelSlot.class),
                new SimpleContainer(SignalPanelBlockEntity.MONITOR_SLOTS));
    }

    /**
     * Clicks on a watch slot set or clear it rather than moving anything.
     *
     * Vanilla only ever reaches a slot through pickup and place, and neither can express "copy this
     * in without taking it", so the filter is driven from here. Carrying something sets the entry;
     * an empty hand clears it.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < SignalPanelBlockEntity.MONITOR_SLOTS) {
            ItemStack carried = getCarried();
            ((GhostSlot) slots.get(slotId)).setGhost(carried);
            broadcastChanges();
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    /**
     * Shift-clicking an item copies it into the first free watch slot. Nothing leaves the inventory,
     * which is the point: the list is a filter, not storage.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int watched = SignalPanelBlockEntity.MONITOR_SLOTS;
        if (index < 0 || index >= watched) {
            Slot source = slots.get(index);
            if (source.hasItem()) {
                for (int i = 0; i < watched; i++) {
                    if (!slots.get(i).hasItem()) {
                        ((GhostSlot) slots.get(i)).setGhost(source.getItem());
                        broadcastChanges();
                        break;
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // Asked of the block entity rather than the container: monitor() would create an empty
        // watch list on the client just to answer the question.
        return player.level().getBlockEntity(lampPos) instanceof SignalPanelBlockEntity be
                && be.isLamp(lampSlot)
                && player.distanceToSqr(lampPos.getCenter()) <= 64;
    }
}
