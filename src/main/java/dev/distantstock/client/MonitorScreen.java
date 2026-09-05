package dev.distantstock.client;

import dev.distantstock.link.LinkSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** 固定像素布局的 Create 风链路仪表板。 */
public final class MonitorScreen extends Screen {
    private static final int W = 272;
    private static final int H = 190;
    private static final int INK = 0x49352B;
    private static final int MUTED = 0x806B55;
    private static final int HEADER = 0xF3E6C8;
    private static final int BRASS = 0x8A672F;
    private static final int AETHER = 0x357D7B;
    private static final int GOOD = 0x4B8054;
    private static final int WARN = 0xB0833F;
    private static final int BAD = 0xA05040;
    private static final ResourceLocation PANEL =
            ResourceLocation.fromNamespaceAndPath("distantstock", "textures/gui/monitor.png");

    private final LinkSnapshot.View view;
    private int left;
    private int top;

    public MonitorScreen(LinkSnapshot.View view) {
        super(Component.translatable("gui.distantstock.monitor"));
        this.view = view;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
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
                view.peerUp());

        drawCounters(g);
        super.render(g, mouseX, mouseY, partial);
    }

    private void drawStatus(GuiGraphics g) {
        Component state = Component.translatable(view.peerUp()
                ? "gui.distantstock.status.online"
                : "gui.distantstock.status.offline");
        int color = view.peerUp() ? 0xB8E4D8 : 0xF0B4A8;
        int x = left + W - 15 - font.width(state);
        g.drawString(font, state, x, top + 13, color, false);
        int lampX = x - 10;
        g.fill(lampX, top + 14, lampX + 5, top + 19, 0xFF533E28);
        g.fill(lampX + 1, top + 15, lampX + 4, top + 18,
                view.peerUp() ? 0xFF62C8B8 : 0xFF9A5145);
    }

    private void drawRoute(GuiGraphics g) {
        String route = fit(view.linkLabel(), W - 38);
        int color = view.peerUp() ? AETHER : MUTED;
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

        String tpsText = n(tps) + " TPS";
        g.drawString(font, tpsText, x + 59 - font.width(tpsText) / 2, y + 25, INK, false);
        meter(g, x + 10, y + 42, 98, tps);

        String msptText = n(mspt) + " MSPT";
        g.drawString(font, msptText, x + 10, y + 55, MUTED, false);
        if (rtt != null) {
            g.drawString(font, rtt, x + 108 - font.width(rtt), y + 55, MUTED, false);
        }
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
                view.peerUp() ? GOOD : BAD,
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

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
