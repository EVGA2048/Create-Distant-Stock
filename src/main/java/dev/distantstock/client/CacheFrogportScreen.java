package dev.distantstock.client;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.menu.CacheFrogportMenu;
import dev.distantstock.net.SetCacheFrogportReleaseC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Six-row cache inventory assembled from Create's original Frogport GUI sheet. */
public final class CacheFrogportScreen extends AbstractContainerScreen<CacheFrogportMenu> {
    private static final int[] RELEASE_DELAYS = {0, 5, 10, 15, 30};
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
    private static final int REPLAY_CONTROL_X = 91;
    private static final int REPLAY_CONTROL_W = 77;
    private static final int REPLAY_CONTROL_H = 18;
    private static final int SHAPER_MODE_U = 56;
    private static final int SHAPER_MODE_V = 20;

    private IconButton confirmButton;
    private SelectionScrollInput replayInput;
    private Label replayLabel;
    private boolean initializingReplay;

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

        int controlY = topPos + CacheFrogportMenu.HEADER_HEIGHT + TARGET_Y;
        replayLabel = new Label(leftPos + REPLAY_CONTROL_X + 12, controlY + 5, Component.empty())
                .colored(0xFFFFFF).withShadow();
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) menu.contentHolder;
        replayInput = (SelectionScrollInput) new SelectionScrollInput(
                leftPos + REPLAY_CONTROL_X, controlY, REPLAY_CONTROL_W, REPLAY_CONTROL_H)
                .forOptions(List.of(
                        Component.translatable("value.distantstock.cache_frogport.redstone"),
                        Component.translatable("value.distantstock.cache_frogport.seconds", 5),
                        Component.translatable("value.distantstock.cache_frogport.seconds", 10),
                        Component.translatable("value.distantstock.cache_frogport.seconds", 15),
                        Component.translatable("value.distantstock.cache_frogport.seconds", 30)))
                .titled(Component.translatable("value.distantstock.cache_frogport.release"))
                .writingTo(replayLabel)
                .calling(this::setReplayMode)
                .setState(releaseIndex(cache.releaseDelaySeconds()));
        initializingReplay = true;
        replayInput.onChanged();
        initializingReplay = false;
        centerReplayLabel();
        addRenderableWidget(replayLabel);
        addRenderableWidget(replayInput);
    }

    private void setReplayMode(int index) {
        int safe = Math.max(0, Math.min(RELEASE_DELAYS.length - 1, index));
        centerReplayLabel();
        if (initializingReplay) return;
        PacketDistributor.sendToServer(new SetCacheFrogportReleaseC2S(
                menu.contentHolder.getBlockPos(), RELEASE_DELAYS[safe]));
    }

    private void centerReplayLabel() {
        if (replayLabel == null) return;
        replayLabel.setX(leftPos + REPLAY_CONTROL_X
                + (REPLAY_CONTROL_W - font.width(replayLabel.text)) / 2);
    }

    private static int releaseIndex(int seconds) {
        for (int i = 0; i < RELEASE_DELAYS.length; i++) {
            if (RELEASE_DELAYS[i] == seconds) return i;
        }
        return 1;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        centerReplayLabel();

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

        // Create's Worldshaper selector frame makes the replay delay read as a scrollable machine
        // setting rather than plain text. Mouse wheel cycles Redstone / 5 / 10 / 15 / 30 seconds.
        g.drawString(font, Component.translatable("gui.distantstock.cache_frogport.replay"),
                x + 45, bodyY + TARGET_Y + 5, 0x3D3C48, false);
        g.blit(AllGuiTextures.TERRAINZAPPER.getLocation(),
                x + REPLAY_CONTROL_X, bodyY + TARGET_Y,
                SHAPER_MODE_U, SHAPER_MODE_V, REPLAY_CONTROL_W, REPLAY_CONTROL_H);

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
