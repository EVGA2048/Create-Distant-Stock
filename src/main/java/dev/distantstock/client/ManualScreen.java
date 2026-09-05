package dev.distantstock.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** 远仓说明书。独立工业面板，点窗内左右翻页。 */
public final class ManualScreen extends Screen {
    private static final int PAGES = 5;
    private static final int W = 240;
    private static final int H = 176;

    private int page;
    private int left;
    private int top;

    public ManualScreen() {
        super(Component.translatable("gui.distantstock.manual"));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        DistantPanel.window(g, left, top, W, H);
        DistantPanel.title(g, font, Component.translatable("gui.distantstock.manual"), left, top, W);
        String mark = (page + 1) + "/" + PAGES;
        g.drawString(font, mark, left + W - font.width(mark) - 16, top + 5, DistantPanel.BRASS, false);

        DistantPanel.card(g, left + 12, top + 26, W - 24, H - 48);
        Component heading = Component.translatable("gui.distantstock.manual.h" + (page + 1));
        g.drawString(font, heading, left + 20, top + 32, DistantPanel.BRASS, false);
        List<FormattedCharSequence> lines = font.split(Component.translatable("gui.distantstock.manual.p" + (page + 1)), W - 48);
        int ty = top + 50;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, left + 20, ty, DistantPanel.INK, false);
            ty += 12;
            if (ty > top + H - 36) {
                break;
            }
        }
        g.drawString(font, Component.translatable("gui.distantstock.manual.hint"), left + 16, top + H - 16, DistantPanel.MUTED, false);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= left && x <= left + W && y >= top && y <= top + H) {
            if (x < left + W / 2.0) {
                page = (page + PAGES - 1) % PAGES;
            } else {
                page = (page + 1) % PAGES;
            }
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 262 || key == 32) {
            page = (page + 1) % PAGES;
            return true;
        }
        if (key == 263) {
            page = (page + PAGES - 1) % PAGES;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
