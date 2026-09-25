package dev.distantstock.client;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import dev.distantstock.menu.CacheFrogportMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.createmod.catnip.gui.element.GuiGameElement;

/** Six-row cache inventory assembled from Create's original Frogport GUI sheet. */
public final class CacheFrogportScreen extends AbstractContainerScreen<CacheFrogportMenu> {
    private static final int BODY_TOP = 8;
    private static final int SLOT_ROW_HEIGHT = 18;
    private static final int ORIGINAL_SLOT_BOTTOM = 44;
    private static final int ORIGINAL_TARGET_Y = 58;
    private static final int BODY_FOOTER_HEIGHT = AllGuiTextures.FROGPORT_BG.getHeight() - ORIGINAL_SLOT_BOTTOM;
    private static final int BODY_HEIGHT = BODY_TOP
            + CacheFrogportMenu.ROWS * SLOT_ROW_HEIGHT
            + BODY_FOOTER_HEIGHT;
    private static final int PLAYER_INVENTORY_GAP = 8;
    private static final int TARGET_Y = BODY_TOP
            + CacheFrogportMenu.ROWS * SLOT_ROW_HEIGHT
            + ORIGINAL_TARGET_Y - ORIGINAL_SLOT_BOTTOM;

    private IconButton confirmButton;

    public CacheFrogportScreen(CacheFrogportMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("gui.distantstock.cache_frogport"));
        imageWidth = AllGuiTextures.FROGPORT_BG.getWidth();
        imageHeight = CacheFrogportMenu.HEADER_HEIGHT + BODY_HEIGHT
                + PLAYER_INVENTORY_GAP + AllGuiTextures.PLAYER_INVENTORY.getHeight();
        inventoryLabelX = CacheFrogportMenu.PLAYER_X;
        inventoryLabelY = CacheFrogportMenu.PLAYER_TEXTURE_Y + 6;
    }

    @Override
    protected void init() {
        super.init();
        confirmButton = new IconButton(leftPos + imageWidth - 33,
                topPos + CacheFrogportMenu.HEADER_HEIGHT + TARGET_Y,
                AllIcons.I_CONFIRM);
        confirmButton.withCallback(this::onClose);
        addRenderableWidget(confirmButton);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        AllGuiTextures.FROGPORT_HEADER.render(g, x, y);
        int bodyY = y + CacheFrogportMenu.HEADER_HEIGHT;
        renderCacheBody(g, x, bodyY);

        // Keep the same target/chain readout as Create's normal Frogport. The large cache only
        // replaces the two package rows; it is still a Frogport attached to one chain target.
        AllGuiTextures.FROGPORT_SLOT.render(g, x + 13, bodyY + TARGET_Y);
        ItemStack targetIcon = menu.contentHolder.target != null
                ? menu.contentHolder.target.getIcon()
                : new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor")));
        if (!targetIcon.isEmpty())
            g.renderItem(targetIcon, x + 14, bodyY + TARGET_Y + 1);

        // Create renders the block being configured outside the right edge of Package Port screens.
        // Preserve that visual identity for the green Cache Frogport as well.
        ItemStack icon = new ItemStack(menu.contentHolder.getBlockState().getBlock().asItem());
        GuiGameElement.of(icon)
                .scale(4)
                .at(x + imageWidth + 6, bodyY + BODY_HEIGHT - 56, -200)
                .render(g);

        AllGuiTextures.PLAYER_INVENTORY.render(g,
                x + CacheFrogportMenu.PLAYER_TEXTURE_X,
                y + CacheFrogportMenu.PLAYER_TEXTURE_Y);
    }

    /**
     * Frogport's normal background has two inventory rows baked into the texture. Build a clean
     * six-row body from one native row, then append the original lower control deck unchanged.
     * This removes the built-in two rows instead of drawing our 54 slots over them, while retaining
     * the black-separated target/confirm bar that visually identifies a Create Package Port.
     */
    private static void renderCacheBody(GuiGraphics g, int x, int y) {
        AllGuiTextures bg = AllGuiTextures.FROGPORT_BG;
        int u = bg.getStartX();
        int v = bg.getStartY();
        int textureSize = 256;

        g.blit(bg.getLocation(), x, y,
                u, v,
                bg.getWidth(), BODY_TOP,
                textureSize, textureSize);

        for (int row = 0; row < CacheFrogportMenu.ROWS; row++) {
            g.blit(bg.getLocation(), x, y + BODY_TOP + row * SLOT_ROW_HEIGHT,
                    u, v + BODY_TOP,
                    bg.getWidth(), SLOT_ROW_HEIGHT,
                    textureSize, textureSize);
        }

        g.blit(bg.getLocation(), x, y + BODY_TOP + CacheFrogportMenu.ROWS * SLOT_ROW_HEIGHT,
                u, v + ORIGINAL_SLOT_BOTTOM,
                bg.getWidth(), BODY_FOOTER_HEIGHT,
                textureSize, textureSize);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Keep the short title centered between Frogport's two eye caps. Left-aligning at x=8
        // collides with the left eye on the wide cache window.
        g.drawString(font, title, (imageWidth - font.width(title)) / 2, 6, 0x3D3C48, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x3D3C48, false);
    }
}
