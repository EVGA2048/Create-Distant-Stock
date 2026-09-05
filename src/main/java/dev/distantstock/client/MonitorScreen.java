package dev.distantstock.client;

import dev.distantstock.link.LinkSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Create 仓管竖窗皮，两栏本端/对端。不是网页仪表盘。 */
public final class MonitorScreen extends Screen {
    private static final int WINDOW_W = 226;
    private static final int TITLE = 0x714A40;
    private static final int INK = 0x4A2D31;
    private static final int PAPER = 0xF8F8EC;
    private static final int BRASS = 0xB68C4C;
    private static final int DOWN = 0x8C5D4B;
    private static final int EMBOSS_L = 0xCDBCA8;
    private static final int EMBOSS_D = 0x5A4036;

    private final LinkSnapshot.View view;
    private int left;
    private int top;
    private int heightWin;

    public MonitorScreen(LinkSnapshot.View view) {
        super(Component.translatable("gui.distantstock.monitor"));
        this.view = view;
    }

    @Override
    protected void init() {
        heightWin = CreateSheets.HEADER.h + CreateSheets.BODY.h * 8 + CreateSheets.FOOTER.h;
        left = (width - WINDOW_W) / 2;
        top = (height - heightWin) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        int x = left;
        int y = top;
        CreateSheets.HEADER.render(g, x - 15, y);
        int by = y + CreateSheets.HEADER.h;
        for (int i = 0; i < 8; i++) {
            CreateSheets.BODY.render(g, x - 15, by);
            by += CreateSheets.BODY.h;
        }
        CreateSheets.FOOTER.render(g, x - 15, by);

        Component title = Component.translatable("gui.distantstock.monitor");
        g.drawString(font, title, x + WINDOW_W / 2 - font.width(title) / 2, y + 4, TITLE, false);
        g.drawString(font, view.linkLabel(), x + WINDOW_W / 2 - font.width(view.linkLabel()) / 2, y + 22, INK, false);

        int colW = 88;
        int gap = 10;
        int lx = x + 22;
        int rx = x + 22 + colW + gap;
        int py = y + 42;
        panel(g, lx, py, colW, 78);
        panel(g, rx, py, colW, 78);

        g.drawString(font, Component.translatable("gui.distantstock.local"), lx + 6, py + 5, BRASS, false);
        g.drawString(font, "TPS  " + n(view.localTps()), lx + 6, py + 20, PAPER, false);
        g.drawString(font, "MSPT " + n(view.localMspt()), lx + 6, py + 34, PAPER, false);

        g.drawString(font, Component.translatable("gui.distantstock.peer"), rx + 6, py + 5, BRASS, false);
        if (view.peerUp()) {
            g.drawString(font, "TPS  " + n(view.peerTps()), rx + 6, py + 20, PAPER, false);
            g.drawString(font, "MSPT " + n(view.peerMspt()), rx + 6, py + 34, PAPER, false);
        } else {
            g.drawString(font, Component.translatable("gui.distantstock.link_down"), rx + 6, py + 24, DOWN, false);
        }

        int fy = py + 88;
        panel(g, lx, fy, colW * 2 + gap, 52);
        g.drawString(font, Component.translatable("gui.distantstock.pressure"), lx + 6, fy + 5, BRASS, false);
        String rtt = view.peerRttMs() < 0 ? "—" : ((int) view.peerRttMs() + " ms");
        g.drawString(font, Component.translatable("gui.distantstock.pressure_line",
                view.orderDepth() + view.packageDepth(), rtt, view.inFlight()), lx + 6, fy + 20, PAPER, false);
        g.drawString(font, Component.translatable("gui.distantstock.fails", view.peerFails()), lx + 6, fy + 34, INK, false);
        super.render(g, mouseX, mouseY, partial);
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xAA3A2A24);
        g.fill(x, y, x + w, y + 1, EMBOSS_L);
        g.fill(x, y, x + 1, y + h, EMBOSS_L);
        g.fill(x, y + h - 1, x + w, y + h, EMBOSS_D);
        g.fill(x + w - 1, y, x + w, y + h, EMBOSS_D);
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
