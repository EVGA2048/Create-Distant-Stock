package dev.distantstock.client;

import dev.distantstock.menu.CacheFrogportMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Compact six-row buffer UI. Every cached parcel remains an ordinary clickable slot. */
public final class CacheFrogportScreen extends AbstractContainerScreen<CacheFrogportMenu> {
    private static final int FRAME = 0xFF3B444A;
    private static final int FACE = 0xFFD8E0E4;
    private static final int SLOT_DARK = 0xFF89959C;
    private static final int SLOT_LIGHT = 0xFFF3F6F7;
    private static final int SLOT_FACE = 0xFFBAC5CA;
    private static final int INK = 0xFF263238;

    public CacheFrogportScreen(CacheFrogportMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("gui.distantstock.cache_frogport"));
        imageWidth = 176;
        imageHeight = 222;
        inventoryLabelY = 128;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, FRAME);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, FACE);

        for (Slot slot : menu.slots) {
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, SLOT_DARK);
            g.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_FACE);
            g.fill(sx + 1, sy + 17, sx + 18, sy + 18, SLOT_LIGHT);
            g.fill(sx + 17, sy + 1, sx + 18, sy + 18, SLOT_LIGHT);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 6, INK, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, INK, false);
    }
}
