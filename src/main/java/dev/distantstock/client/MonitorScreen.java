package dev.distantstock.client;

import dev.distantstock.link.LinkSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** 固定像素布局的 Create 风链路仪表板。 */
public final class MonitorScreen extends Screen {
    private static final int W = 272;
    private static final int H = 190;
    private static final int INK = 0x263B43;
    private static final int MUTED = 0x68828A;
    private static final int HEADER = 0xF4FBF8;
    private static final int BRASS = 0x718B8B;
    private static final int AETHER = 0x3A9DB0;
    private static final int GOOD = 0x4C9B7A;
    private static final int WARN = 0xC18A4A;
    private static final int BAD = 0xB65E57;
    private static final ResourceLocation PANEL =
            ResourceLocation.fromNamespaceAndPath("distantstock", "textures/gui/monitor.png");

    private final BlockPos source;
    private LinkSnapshot.View view;
    private int flipTicks;
    private int previousTps;
    private int previousMspt;
    private int left;
    private int top;

    public MonitorScreen(BlockPos source, LinkSnapshot.View view) {
        super(Component.translatable("gui.distantstock.monitor"));
        this.source = source.immutable();
        this.view = view;
    }

    public boolean isSource(BlockPos source) {
        return this.source.equals(source);
    }

    public void update(LinkSnapshot.View next) {
        previousTps = (int) Math.round(view.localTps() * 10);
        previousMspt = (int) Math.round(view.localMspt() * 10);
        view = next;
        flipTicks = 8;
    }

    @Override
    public void tick() {
        super.tick();
        if (flipTicks > 0) {
            flipTicks--;
        }
    }
    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // A machinery panel should remain part of the world, not open Minecraft's blurred menu backdrop.
        g.fill(0, 0, width, height, 0x4208171B);
        g.blit(PANEL, left, top, 0, 0, W, H, W, H);

        Component title = Component.translatable("gui.distantstock.monitor");
        g.drawString(font, title, left + 14, top + 13, HEADER, false);
        drawStatus(g);
        drawRoute(g);

        drawEndpoint(g, left + 12, top + 51,
                Component.translatable("gui.distantstock.local"),
                view.localTps(), view.localMspt(), null, true);
        drawEndpoint(g, left + 142, top + 51,
                Component.translatable("gui.distantstock.peer"),
                view.peerTps(), view.peerMspt(),
                view.peerRttMs() < 0 ? "—" : (int) view.peerRttMs() + " ms",
                view.linkUp());

        drawCounters(g);
        super.render(g, mouseX, mouseY, partial);
    }

    private void drawStatus(GuiGraphics g) {
        Component state = Component.translatable(view.linkUp()
                ? "gui.distantstock.status.online"
                : "gui.distantstock.status.offline");
        int color = view.linkUp() ? 0xB8E4D8 : 0xF0B4A8;
        int x = left + W - 15 - font.width(state);
        g.drawString(font, state, x, top + 13, color, false);
        int lampX = x - 10;
        g.fill(lampX, top + 14, lampX + 5, top + 19, 0xFF533E28);
        g.fill(lampX + 1, top + 15, lampX + 4, top + 18,
                view.linkUp() ? 0xFF62C8B8 : 0xFF9A5145);
    }

    private void drawRoute(GuiGraphics g) {
        String route = fit(view.linkLabel(), W - 38);
        int color = view.linkUp() ? AETHER : MUTED;
        g.drawString(font, route, left + W / 2 - font.width(route) / 2, top + 35, color, false);
    }

    private void drawEndpoint(GuiGraphics g, int x, int y, Component name,
                              double tps, double mspt, String rtt, boolean online) {
        g.drawString(font, name, x + 10, y + 6, BRASS, false);
        if (!online) {
            Component down = Component.translatable("gui.distantstock.link_down");
            g.drawString(font, down, x + 59 - font.width(down) / 2, y + 39, BAD, false);
            return;
        }

        String tpsText = flipValue(n(tps), previousTps, tps, "");
        drawFlipReadout(g, tpsText, "TPS", x + 59, y + 23, online ? AETHER : MUTED);
        meter(g, x + 10, y + 42, 98, tps);

        String msptText = flipValue(n(mspt), previousMspt, mspt, "");
        drawFlipReadout(g, msptText, "MSPT", x + 43, y + 53, MUTED);
        if (rtt != null) {
            g.drawString(font, rtt, x + 108 - font.width(rtt), y + 55, MUTED, false);
        }
    }

    /** Pixel flip-board cells inspired by Create's display boards; values remain readable at GUI scale 1. */
    private void drawFlipReadout(GuiGraphics g, String value, String unit, int centreX, int y, int color) {
        int cellW = 7;
        int gap = 1;
        int cellsW = value.length() * (cellW + gap) - gap;
        int unitW = font.width(unit);
        int total = cellsW + 4 + unitW;
        int x = centreX - total / 2;
        for (int i = 0; i < value.length(); i++) {
            int cx = x + i * (cellW + gap);
            g.fill(cx, y, cx + cellW, y + 11, 0xFF43575D);
            g.fill(cx + 1, y + 1, cx + cellW - 1, y + 5, 0xFF71888E);
            g.fill(cx + 1, y + 6, cx + cellW - 1, y + 10, 0xFF52676D);
            g.fill(cx, y + 5, cx + cellW, y + 6, 0xFF2E4147);
            String glyph = value.substring(i, i + 1);
            g.drawString(font, glyph, cx + (cellW - font.width(glyph)) / 2, y + 2,
                    0xFFE8F4F2, false);
        }
        g.drawString(font, unit, x + cellsW + 4, y + 2, color, false);
    }

    private void meter(GuiGraphics g, int x, int y, int w, double tps) {
        g.fill(x, y, x + w, y + 7, 0xFFBDA982);
        g.fill(x + 1, y + 1, x + w - 1, y + 6, 0xFFE2D0AA);
        int fill = Math.max(0, Math.min(w - 2, (int) Math.round((w - 2) * tps / 20.0)));
        int color = tps >= 18 ? GOOD : tps >= 15 ? WARN : BAD;
        if (fill > 0) {
            g.fill(x + 1, y + 1, x + 1 + fill, y + 6, 0xFF000000 | color);
            g.fill(x + 1, y + 1, x + 1 + fill, y + 2, 0x55FFFFFF);
        }
        for (int mark = 1; mark < 4; mark++) {
            int mx = x + mark * w / 4;
            g.fill(mx, y + 1, mx + 1, y + 6, 0x55806B55);
        }
    }

    private void drawCounters(GuiGraphics g) {
        g.drawString(font, Component.translatable("gui.distantstock.pressure"),
                left + 22, top + 135, BRASS, false);

        Component[] labels = {
                Component.translatable("gui.distantstock.online.label"),
                Component.translatable("gui.distantstock.orders.label"),
                Component.translatable("gui.distantstock.packages.label"),
                Component.translatable("gui.distantstock.in_flight.label"),
                Component.translatable("gui.distantstock.fails.label")
        };
        String[] values = {
                view.peersUp() + "/" + Math.max(1, view.peersTotal()),
                Integer.toString(view.orderDepth()),
                Integer.toString(view.packageDepth()),
                Integer.toString(view.inFlight()),
                Integer.toString(view.peerFails())
        };
        int[] colors = {
                view.linkUp() ? GOOD : BAD,
                view.orderDepth() == 0 ? INK : WARN,
                view.packageDepth() == 0 ? INK : WARN,
                view.inFlight() == 0 ? INK : AETHER,
                view.peerFails() == 0 ? INK : BAD
        };
        for (int i = 0; i < labels.length; i++) {
            int cx = left + 36 + i * 49;
            g.drawString(font, labels[i], cx - font.width(labels[i]) / 2, top + 151, MUTED, false);
            g.drawString(font, values[i], cx - font.width(values[i]) / 2, top + 165, colors[i], false);
        }
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "…";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private boolean inside(double x, double y) {
        return x >= left && x <= left + W && y >= top && y <= top + H;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return inside(x, y) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return inside(x, y) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return inside(x, y) || super.mouseDragged(x, y, button, dx, dy);
    }

    private String flipValue(String current, int previousTenths, double value, String suffix) {
        if (flipTicks == 0 || previousTenths == 0) {
            return current;
        }
        double progress = (8 - flipTicks) / 8.0;
        if (progress < 0.5) {
            return n(previousTenths / 10.0) + suffix;
        }
        return current;
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
