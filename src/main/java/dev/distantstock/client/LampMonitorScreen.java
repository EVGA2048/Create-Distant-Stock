package dev.distantstock.client;

import dev.distantstock.block.LampState;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.menu.MonitorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * The brass lamp's watch list.
 *
 * Everything is drawn with fills so the screen needs no texture of its own. The layout is
 * MUL's: a pale-blue sheet, and a grid whose filled cells take their colour from that item's
 * state, so the row reads as a line of small indicator lamps.
 *
 * The chrome around it follows Create instead of vanilla: a steel bezel with a lit top edge,
 * a header band that darkens downwards, a brass rule under it, and slot wells recessed the way
 * Create recesses them. Set against the flat pale sheet those are what make it stop looking
 * like an empty box.
 */
public final class LampMonitorScreen extends AbstractContainerScreen<MonitorMenu> {
    /** The pale sheet, kept from MUL's mockup. */
    private static final int SHEET = 0xFFD6E2EF;
    /** Steel bezel, dark outside and lit inside, the way Create frames a casing. */
    private static final int BEZEL_OUTER = 0xFF5C646C;
    private static final int BEZEL_INNER = 0xFFFFFFFF;
    /** Header band, darkening downwards. */
    private static final int HEADER_TOP = 0xFFE6EDF3;
    private static final int HEADER_BOTTOM = 0xFFBFC9D3;
    /** Create's brass, used as a rule under the header. */
    private static final int BRASS = 0xFFC08A3E;
    private static final int BRASS_SHADOW = 0xFF7A5620;

    private static final int INK = 0xFF2F3841;
    private static final int NETWORK_INK = 0xFF4C5761;
    /** Recessed slot: dark top-left edge, lit bottom-right edge, pale well. */
    private static final int WELL_EDGE_DARK = 0xFF9AA5AF;
    private static final int WELL_EDGE_LIGHT = 0xFFF2F6FA;
    private static final int WELL_FACE = 0xFFC3CDD7;
    private static final int PLUS = 0xFFF4F8FB;
    /** Filled but not sampled yet, which happens for the first second after the window opens. */
    private static final int UNSAMPLED_CELL = 0xFF8C97A2;

    private static final int CELL_SIZE = 18;
    private static final int HEADER_HEIGHT = 16;

    public LampMonitorScreen(MonitorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("gui.distantstock.lamp_monitor"));
        imageWidth = 176;
        imageHeight = 170;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int right = x + imageWidth;
        int bottom = y + imageHeight;

        g.fill(x, y, right, bottom, BEZEL_OUTER);
        g.fill(x + 1, y + 1, right - 1, bottom - 1, SHEET);
        // Lit inner edge along the top and left, so the panel reads as raised.
        g.fill(x + 1, y + 1, right - 1, y + 2, BEZEL_INNER);
        g.fill(x + 1, y + 1, x + 2, bottom - 1, BEZEL_INNER);

        header(g, x, y, right);
        watchGrid(g, x, y);
        playerSlots(g, x, y);

        g.drawString(font, networkLabel(), x + 8, y + 62, NETWORK_INK, false);
    }

    /**
     * The player's own slots, drawn as the same recessed wells as the watch list.
     *
     * Nothing else draws them: this screen paints its whole background itself, so without this
     * the carried items would float on a flat sheet with no slots under them.
     */
    private void playerSlots(GuiGraphics g, int x, int y) {
        int first = SignalPanelBlockEntity.MONITOR_SLOTS;
        for (int i = first; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            well(g, x + slot.x - 1, y + slot.y - 1, WELL_FACE);
        }
    }

    private void header(GuiGraphics g, int x, int y, int right) {
        int top = y + 1;
        int bottom = top + HEADER_HEIGHT;
        for (int row = top; row < bottom; row++) {
            float t = (row - top) / (float) (HEADER_HEIGHT - 1);
            g.fill(x + 2, row, right - 2, row + 1, lerpColor(HEADER_TOP, HEADER_BOTTOM, t));
        }
        g.fill(x + 2, bottom, right - 2, bottom + 1, BRASS_SHADOW);
        g.fill(x + 2, bottom + 1, right - 2, bottom + 2, BRASS);
    }

    private void watchGrid(GuiGraphics g, int x, int y) {
        for (int i = 0; i < SignalPanelBlockEntity.MONITOR_SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            well(g, sx, sy, slot.hasItem() ? stateTint(i) : WELL_FACE);
            if (!slot.hasItem()) {
                plus(g, sx + CELL_SIZE / 2, sy + CELL_SIZE / 2);
            }
        }
    }

    /** A recessed slot: shadowed on the top-left, lit on the bottom-right, like Create's. */
    private static void well(GuiGraphics g, int x, int y, int face) {
        g.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, WELL_EDGE_DARK);
        g.fill(x, y, x + 1, y + CELL_SIZE, WELL_EDGE_DARK);
        g.fill(x + 1, y + 1, x + CELL_SIZE, y + CELL_SIZE, face);
        g.fill(x + 1, y + CELL_SIZE - 1, x + CELL_SIZE, y + CELL_SIZE, WELL_EDGE_LIGHT);
        g.fill(x + CELL_SIZE - 1, y + 1, x + CELL_SIZE, y + CELL_SIZE, WELL_EDGE_LIGHT);
    }

    /** A small plus drawn from two bars, so the screen needs no texture of its own. */
    private static void plus(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 4, cy - 1, cx + 4, cy + 1, PLUS);
        g.fill(cx - 1, cy - 4, cx + 1, cy + 4, PLUS);
    }

    private static int lerpColor(int from, int to, float t) {
        int r = (int) ((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) ((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The cell background carries the item's state, so one glance covers the whole watch list. */
    private int stateTint(int index) {
        List<LampState> states = states();
        LampState state = states.size() > index ? states.get(index) : null;
        if (state == null) {
            return UNSAMPLED_CELL;
        }
        return switch (state) {
            case IDLE, ALL_GOOD -> 0xFF6FBF63;
            case ACT -> 0xFF4FBFCB;
            case WARN -> 0xFFE0A040;
            case WARN_URGENT, FATAL -> 0xFFD4504A;
        };
    }

    private List<LampState> states() {
        SignalPanelBlockEntity panel = panel();
        return panel == null ? List.of() : panel.monitorStates(menu.lampSlot);
    }

    private Component networkLabel() {
        SignalPanelBlockEntity panel = panel();
        var network = panel == null ? null : panel.lampNetwork(menu.lampSlot);
        if (network == null) {
            return Component.translatable("gui.distantstock.monitor.unbound");
        }
        return Component.translatable("gui.distantstock.monitor.network",
                Component.literal(RequesterData.shortFreq(network)).withStyle(ChatFormatting.DARK_AQUA));
    }

    private SignalPanelBlockEntity panel() {
        return minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(menu.lampPos) instanceof SignalPanelBlockEntity panel
                ? panel : null;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Component title = Component.translatable("gui.distantstock.lamp_monitor");
        g.drawString(font, title, imageWidth / 2 - font.width(title) / 2, 4, INK, true);
        // Sits one row above the inventory slots. It used to be pinned at 60, which put it on
        // top of the network line drawn at 62.
        g.drawString(font, playerInventoryTitle, 8, MonitorMenu.INV_Y - 12, INK, true);
    }
}
