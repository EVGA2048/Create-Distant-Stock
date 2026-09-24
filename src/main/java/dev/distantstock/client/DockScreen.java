package dev.distantstock.client;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.menu.DockMenu;
import dev.distantstock.net.ConfigureDockC2S;
import dev.distantstock.routing.DockMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Distant Dock configuration in Create's native GUI language.
 *
 * <p>Distant Stock owns the layout while the panel palette stays close to Create's normal logistics
 * screens. Editable/scrollable controls reuse Create's own Station and Worldshaper frames, so they
 * look interactive for the same reason Create's native screens do. There is no exposed group UUID
 * or group-management workflow.
 */
public final class DockScreen extends AbstractSimiContainerScreen<DockMenu> {
    private static final int PANEL_W = 220;
    private static final int HEADER_H = 18;
    private static final int PANEL_BODY_H = 100;
    private static final int PANEL_TEX_H = HEADER_H + PANEL_BODY_H;
    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(
            "distantstock", "textures/gui/remote_dock.png");

    /** Exact Create Worldshaper control frames; DockScreen only changes the surrounding panel. */
    private static final int SHAPER_MODE_U = 56;
    private static final int SHAPER_MODE_V = 20;
    private static final int SHAPER_MODE_W = 77;
    private static final int SHAPER_MODE_H = 18;
    private static final int SHAPER_PARAM_U = 56;
    private static final int SHAPER_PARAM_V = 40;
    private static final int SHAPER_PARAM_W = 18;
    private static final int SHAPER_PARAM_H = 18;
    private static final int INK = 0x263B43;
    private static final int CONTROL_TEXT = 0xFFFFFF;

    private EditBox addressBox;
    private EditBox nameBox;
    private SelectionScrollInput modeInput;
    private ScrollInput priorityInput;
    private Label modeLabel;
    private Label priorityLabel;
    private IconButton confirmButton;
    private List<Rect2i> extraAreas = List.of();
    private boolean configurationSent;

    public DockScreen(DockMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    private void centerLabel(Label label, int x, int width) {
        label.setX(x + (width - font.width(label.text)) / 2);
    }

    @Override
    protected void init() {
        setWindowSize(PANEL_W, PANEL_BODY_H + AllGuiTextures.PLAYER_INVENTORY.getHeight());
        super.init();
        clearWidgets();

        int x = getGuiLeft();
        int y = getGuiTop();
        NoShadowFontWrapper noShadow = new NoShadowFontWrapper(font);

        // Exactly the Package Port name/address strip: this is the receiving address users type.
        addressBox = new EditBox(noShadow, x + 23, y - 12, PANEL_W - 46, 10, Component.empty());
        addressBox.setBordered(false);
        addressBox.setMaxLength(64);
        addressBox.setTextColor(INK);
        addressBox.setValue(menu.initialAddress);
        addressBox.setFocused(false);
        addressBox.setResponder(s -> addressBox.setX(nameBoxX(s, addressBox)));
        addressBox.setX(nameBoxX(addressBox.getValue(), addressBox));
        addRenderableWidget(addressBox);

        // Create station-style textbox: the visual frame is rendered in renderBg(), while the
        // EditBox itself remains borderless exactly like Create's own editable names.
        nameBox = new EditBox(noShadow, x + 52, y + 25, 132, 10,
                Component.translatable("gui.distantstock.dock.name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(48);
        nameBox.setTextColor(CONTROL_TEXT);
        nameBox.setValue(menu.initialName);
        addRenderableWidget(nameBox);

        int modeX = x + 72;
        int modeY = y + 56;
        modeLabel = new Label(modeX + 16, modeY + 5, Component.empty()).colored(CONTROL_TEXT).withShadow();
        modeInput = (SelectionScrollInput) new SelectionScrollInput(modeX, modeY, SHAPER_MODE_W, SHAPER_MODE_H)
                .forOptions(List.of(
                        Component.translatable("gui.distantstock.dock.mode.receive"),
                        Component.translatable("gui.distantstock.dock.mode.send"),
                        Component.translatable("gui.distantstock.dock.mode.bidirectional")))
                .titled(Component.translatable("gui.distantstock.dock.mode"))
                .writingTo(modeLabel)
                .calling(state -> centerLabel(modeLabel, modeX, SHAPER_MODE_W))
                .setState(menu.initialMode.ordinal());

        int priorityX = x + 72;
        int priorityY = y + 78;
        priorityLabel = new Label(priorityX + 7, priorityY + 5, Component.empty()).colored(CONTROL_TEXT).withShadow();
        priorityInput = new ScrollInput(priorityX, priorityY, SHAPER_PARAM_W, SHAPER_PARAM_H)
                .withRange(0, DockBlockEntity.MAX_PRIORITY + 1)
                .titled(Component.translatable("gui.distantstock.dock.priority"))
                .format(value -> Component.literal(Integer.toString(value)))
                .writingTo(priorityLabel)
                .calling(state -> centerLabel(priorityLabel, priorityX, SHAPER_PARAM_W))
                .setState(menu.initialPriority);

        modeInput.onChanged();
        priorityInput.onChanged();

        addRenderableWidget(modeLabel);
        addRenderableWidget(modeInput);
        addRenderableWidget(priorityLabel);
        addRenderableWidget(priorityInput);

        confirmButton = new IconButton(x + PANEL_W - 33, y + 76, AllIcons.I_CONFIRM);
        confirmButton.withCallback(this::onClose);
        addRenderableWidget(confirmButton);

        extraAreas = ImmutableList.of(new Rect2i(x + PANEL_W, y + 50, 70, 60));
    }

    private int nameBoxX(String s, EditBox box) {
        return getGuiLeft() + PANEL_W / 2
                - (Math.min(font.width(s), box.getWidth()) + 10) / 2;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = getGuiLeft();
        int y = getGuiTop();

        // Distant Stock owns the panel layout; interactive affordances below are rendered from
        // Create's own GUI sheets, not imitations.
        graphics.blit(PANEL, x, y - HEADER_H, 0f, 0f,
                PANEL_W, PANEL_TEX_H, PANEL_W, PANEL_TEX_H);

        String addressText = addressBox.getValue();
        if (!addressBox.isFocused()) {
            if (addressText.isEmpty()) {
                addressText = Component.translatable("gui.distantstock.dock.address").getString();
                graphics.drawString(font, addressText, nameBoxX(addressText, addressBox), y - 11, 4013128, false);
            }
            AllGuiTextures.STATION_EDIT_NAME.render(graphics,
                    nameBoxX(addressText, addressBox) + font.width(addressText) + 5, y - 14);
        }

        graphics.drawString(font, Component.translatable("gui.distantstock.dock.name"), x + 45, y + 9,
                INK, false);
        AllGuiTextures.STATION_TEXTBOX_TOP.render(graphics, x + 45, y + 20);

        graphics.drawString(font, Component.translatable("gui.distantstock.dock.mode"), x + 14, y + 61,
                INK, false);
        graphics.blit(AllGuiTextures.TERRAINZAPPER.getLocation(), x + 72, y + 56,
                SHAPER_MODE_U, SHAPER_MODE_V, SHAPER_MODE_W, SHAPER_MODE_H);

        graphics.drawString(font, Component.translatable("gui.distantstock.dock.priority"), x + 14, y + 83,
                INK, false);
        graphics.blit(AllGuiTextures.TERRAINZAPPER.getLocation(), x + 72, y + 78,
                SHAPER_PARAM_U, SHAPER_PARAM_V, SHAPER_PARAM_W, SHAPER_PARAM_H);

        // One physical parcel bay. The remaining internal inventories are recovery state, not
        // capacity, so they are intentionally not exposed as extra slots.
        AllGuiTextures.FROGPORT_SLOT.render(graphics, x + 13, y + 14);

        int invX = leftPos + 30;
        int invY = topPos + 8 + imageHeight - AllGuiTextures.PLAYER_INVENTORY.getHeight();
        renderPlayerInventory(graphics, invX, invY);

        if (!menu.networkBound) {
            graphics.drawString(font,
                    Component.translatable("gui.distantstock.dock.network_unbound").withStyle(ChatFormatting.RED),
                    x + 96, y + 83, 0xB65E57, false);
        }
        if (!menu.towerActive) {
            graphics.drawString(font,
                    Component.translatable("gui.distantstock.dock.tower_inactive").withStyle(ChatFormatting.RED),
                    x + 14, y + 103, 0xB65E57, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean hitEnter = getFocused() instanceof EditBox && (keyCode == 257 || keyCode == 335);
        if (hitEnter) {
            setFocused(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendConfiguration() {
        if (configurationSent) return;
        configurationSent = true;
        PacketDistributor.sendToServer(new ConfigureDockC2S(menu.dockPos,
                nameBox == null ? menu.initialName : nameBox.getValue(),
                addressBox == null ? menu.initialAddress : addressBox.getValue(),
                modeInput == null ? menu.initialMode.ordinal() : modeInput.getState(),
                priorityInput == null ? menu.initialPriority : priorityInput.getState()));
    }

    @Override
    public void onClose() {
        // Send while the server still sees DockMenu as the active container. The old implementation
        // waited for removed(), which can run after the close-container packet and caused the server
        // to reject an otherwise valid configuration update.
        sendConfiguration();
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public List<Rect2i> getExtraAreas() {
        return extraAreas;
    }
}
