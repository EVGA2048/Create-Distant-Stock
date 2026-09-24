package dev.distantstock.menu;

import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.routing.DockMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** One-page Frogport-style configuration and the dock's single physical parcel bay. */
public final class DockMenu extends AbstractContainerMenu {
    // FROGPORT_SLOT is rendered at (13, 9); the actual item goes one pixel inside its bezel.
    public static final int BAY_X = 14;
    public static final int BAY_Y = 15;
    public static final int INV_X = 38;
    public static final int INV_Y = 126;
    public static final int HOTBAR_Y = 184;

    public final BlockPos dockPos;
    public final String initialName;
    public final String initialAddress;
    public final DockMode initialMode;
    public final int initialPriority;
    public final boolean networkBound;
    public final boolean towerActive;

    private DockMenu(int id, Inventory playerInv, BlockPos pos,
                     String name, String address, DockMode mode, int priority,
                     boolean networkBound, boolean towerActive,
                     net.neoforged.neoforge.items.IItemHandler bay) {
        super(ModMenus.DOCK.get(), id);
        this.dockPos = pos.immutable();
        this.initialName = name == null ? "" : name;
        this.initialAddress = address == null ? "" : address;
        this.initialMode = mode == null ? DockMode.RECEIVE : mode;
        this.initialPriority = Math.max(0, Math.min(DockBlockEntity.MAX_PRIORITY, priority));
        this.networkBound = networkBound;
        this.towerActive = towerActive;

        addSlot(new SlotItemHandler(bay, 0, BAY_X, BAY_Y));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9,
                        INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    public static DockMenu server(int id, Inventory inv, DockBlockEntity dock) {
        return new DockMenu(id, inv, dock.getBlockPos(), dock.customName(), dock.knownGroupName(),
                dock.mode(), dock.priority(), dock.distantNetworkScope() != null,
                dock.carriedByTower(), dock.menuBay());
    }

    public static DockMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String name = buf.readUtf(48);
        String address = buf.readUtf(64);
        int mode = Math.max(0, Math.min(DockMode.values().length - 1, buf.readVarInt()));
        int priority = buf.readVarInt();
        boolean bound = buf.readBoolean();
        boolean towerActive = buf.readBoolean();
        return new DockMenu(id, inv, pos, name, address, DockMode.values()[mode], priority,
                bound, towerActive, new ItemStackHandler(1));
    }

    public static void writeOpenData(RegistryFriendlyByteBuf buf, DockBlockEntity dock) {
        buf.writeBlockPos(dock.getBlockPos());
        buf.writeUtf(dock.customName(), 48);
        buf.writeUtf(dock.knownGroupName(), 64);
        buf.writeVarInt(dock.mode().ordinal());
        buf.writeVarInt(dock.priority());
        buf.writeBoolean(dock.distantNetworkScope() != null);
        buf.writeBoolean(dock.carriedByTower());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack original = source.getItem().copy();
        if (index == 0) {
            if (!moveItemStackTo(source.getItem(), 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(source.getItem(), 0, 1, false)) return ItemStack.EMPTY;
        }
        if (source.getItem().isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(dockPos) instanceof DockBlockEntity
                && player.distanceToSqr(dockPos.getCenter()) <= 64;
    }
}
