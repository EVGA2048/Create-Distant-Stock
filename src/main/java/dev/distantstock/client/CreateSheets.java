package dev.distantstock.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 直接画 Create 仓管贴图（stock_keeper.png），不抄进自己的 jar。
 * UV 对齐 6.0.10 AllGuiTextures.STOCK_KEEPER_REQUEST_*。
 */
public enum CreateSheets {
    HEADER(0, 0, 256, 36),
    BODY(0, 48, 256, 20),
    FOOTER(0, 80, 256, 80),
    SEARCH(57, 17, 142, 18),
    SLOT(32, 200, 18, 18),
    SEND_HOVER(55, 200, 80, 20),
    SCROLL_TOP(219, 192, 5, 4),
    SCROLL_PAD(219, 196, 5, 1),
    SCROLL_MID(219, 197, 5, 9),
    SCROLL_BOT(219, 207, 5, 5),
    BANNER_L(64, 228, 8, 16),
    BANNER_M(73, 228, 1, 16),
    BANNER_R(75, 228, 8, 16),
    BG(37, 48, 182, 20);

    static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath("create", "textures/gui/stock_keeper.png");

    final int u;
    final int v;
    final int w;
    final int h;

    CreateSheets(int u, int v, int w, int h) {
        this.u = u;
        this.v = v;
        this.w = w;
        this.h = h;
    }

    public void render(GuiGraphics g, int x, int y) {
        g.blit(TEX, x, y, u, v, w, h);
    }

    public void stretchY(GuiGraphics g, int x, int y, int height) {
        g.blit(TEX, x, y, w, height, u, v, w, h, 256, 256);
    }

    public void stretchX(GuiGraphics g, int x, int y, int width) {
        g.blit(TEX, x, y, width, h, u, v, w, h, 256, 256);
    }
}
