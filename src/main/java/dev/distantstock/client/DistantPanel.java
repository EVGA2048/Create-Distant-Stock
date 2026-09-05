package dev.distantstock.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 木框 + 奶油纸面。监视器 / 管理员 / 说明书用，不碰 Create 仓储贴图。 */
final class DistantPanel {
    static final int TITLE = 0xF3E6C8;
    static final int INK = 0x3A2E22;
    static final int PAPER = 0xF3E6C8;
    static final int BRASS = 0x8A6A28;
    static final int LIVE = 0x3A7A86;
    static final int DOWN = 0xA05040;
    static final int MUTED = 0x7A6A50;

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("distantstock", "textures/gui/panel.png");

    static void window(GuiGraphics g, int x, int y, int w, int h) {
        slice(g, x - 4, y - 4, w + 8, h + 8);
    }

    static void title(GuiGraphics g, Font font, Component text, int x, int y, int w) {
        g.drawString(font, text, x + 10, y + 5, TITLE, false);
    }

    static void card(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x332A1C10);
        g.fill(x, y, x + w, y + 1, 0xFF8A6A28);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF8A6A28);
        g.fill(x, y, x + 1, y + h, 0xFF8A6A28);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF8A6A28);
    }

    static void lamp(GuiGraphics g, int x, int y, boolean on) {
        g.fill(x - 1, y - 1, x + 7, y + 7, 0xFF6A5030);
        g.fill(x, y, x + 6, y + 6, on ? 0xFF3D8A7A : 0xFF5A5040);
        if (on) {
            g.fill(x + 1, y + 1, x + 3, y + 3, 0xFFE8F4F0);
        }
    }

    static void field(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF8A6A28);
        g.fill(x, y, x + w, y + h, 0xFFF8F0D8);
    }

    static void bar(GuiGraphics g, int x, int y, int w, int h, double ratio, boolean ok) {
        g.fill(x, y, x + w, y + h, 0xFFD4C4A0);
        g.fill(x, y, x + w, y + 1, 0xFF8A6A28);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF8A6A28);
        int fill = Math.max(0, Math.min(w - 2, (int) Math.round((w - 2) * ratio)));
        int color = ok ? 0xFF3D8A54 : 0xFFA05040;
        if (ok && ratio < 0.85) {
            color = 0xFFC9A24C;
        }
        if (fill > 0) {
            g.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
        }
    }

    private static void slice(GuiGraphics g, int x, int y, int w, int h) {
        int c = 8;
        g.blit(TEX, x, y, c, c, 0, 0, c, c, 256, 256);
        g.blit(TEX, x + w - c, y, c, c, 256 - c, 0, c, c, 256, 256);
        g.blit(TEX, x, y + h - c, c, c, 0, 256 - c, c, c, 256, 256);
        g.blit(TEX, x + w - c, y + h - c, c, c, 256 - c, 256 - c, c, c, 256, 256);
        g.blit(TEX, x + c, y, w - 2 * c, c, c, 0, 256 - 2 * c, c, 256, 256);
        g.blit(TEX, x + c, y + h - c, w - 2 * c, c, c, 256 - c, 256 - 2 * c, c, 256, 256);
        g.blit(TEX, x, y + c, c, h - 2 * c, 0, c, c, 256 - 2 * c, 256, 256);
        g.blit(TEX, x + w - c, y + c, c, h - 2 * c, 256 - c, c, c, 256 - 2 * c, 256, 256);
        g.blit(TEX, x + c, y + c, w - 2 * c, h - 2 * c, c, c, 256 - 2 * c, 256 - 2 * c, 256, 256);
        g.blit(TEX, x + c, y + c, w - 2 * c, 20, c, 8, 256 - 2 * c, 20, 256, 256);
    }

    private DistantPanel() {
    }
}
