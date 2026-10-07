package dev.distantstock.menu;

import com.simibubi.create.content.logistics.packagePort.PackagePortMenu;
import dev.distantstock.block.CacheFrogportBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Six-row package buffer for the cache Frogport. */
public final class CacheFrogportMenu extends PackagePortMenu {
    public static final int ROWS = 6;
    public static final int COLUMNS = 9;
    public static final int HEADER_HEIGHT = 17;
    public static final int DEVICE_X = 27;
    public static final int DEVICE_Y = HEADER_HEIGHT + 9;
    public static final int PLAYER_TEXTURE_X = 22;
    public static final int PLAYER_TEXTURE_Y = 179;
    public static final int PLAYER_X = 30;
    public static final int PLAYER_Y = PLAYER_TEXTURE_Y + 18;

    public CacheFrogportMenu(MenuType<?> type, int id, Inventory inventory,
                             CacheFrogportBlockEntity cache) {
        super(type, id, inventory, cache);
    }

    public CacheFrogportMenu(MenuType<?> type, int id, Inventory inventory,
                             RegistryFriendlyByteBuf buffer) {
        super(type, id, inventory, buffer);
    }

    public static CacheFrogportMenu server(int id, Inventory inventory, CacheFrogportBlockEntity cache) {
        return new CacheFrogportMenu(ModMenus.CACHE_FROGPORT.get(), id, inventory, cache);
    }

    @Override
    protected void addSlots() {
        var handler = contentHolder.inventory;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int slot = row * COLUMNS + col;
                addSlot(new SlotItemHandler(handler, slot,
                        DEVICE_X + col * 18, DEVICE_Y + row * 18));
            }
        }
        addPlayerSlots(PLAYER_X, PLAYER_Y);
    }

    @Override
    public boolean stillValid(Player player) {
        return contentHolder != null && !contentHolder.isRemoved()
                && player.distanceToSqr(contentHolder.getBlockPos().getCenter()) <= 64;
    }
}
