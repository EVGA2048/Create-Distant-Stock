package dev.distantstock.client;

import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import dev.distantstock.DistantStock;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * Create 那个地址框的底纸，拉伸到任意宽度。
 *
 * <p>玩家要的是"和 Create 那个框一模一样的手感"，而那个框不是一块可以拿来复用的 sprite —— 它烧在
 * 每张窗口底图里（`create:textures/gui/requester.png` 一条，`factory_gauge.png` 里另有一条）。
 * 所以 {@code scripts/gen_address_box.py} 把那一小段像素原样裁出来，抠掉木纹底色，做成三段：
 * 左端（铜丝卷 + 框的左圆角）、中间 1 像素（横着拉长）、右端（纸的右缘 + 黑描边）。用的还是
 * Create 自己那几个像素，不是照着截图重画的。
 *
 * <p>输入框本身也用 Create 的 {@link AddressEditBox}：它在羊皮纸上写不带阴影的字、颜色是那个
 * 灰褐色，自带地址补全（剪贴板、蛙港、包裹上见过的地址），**还会在框的右端画一个剪贴板图标**。
 * 自己写一个 EditBox 只会得到一个"长得像但一用就不对"的框。
 *
 * <p>这里所有常数都是**贴图自己的像素**，和 generator 里那几个一一对应。两边任何一边单独改动都会
 * 让框和底纸错开 —— 而"框和纸对不齐"正是这块东西最容易出的毛病（改过两次）。所以定位只有
 * {@link #create} / {@link #renderUnder} 两个方向，屏幕那边不许再加偏移。
 */
public final class AddressStrip {
    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "textures/gui/address_box.png");

    /** 整条底纸的高度：铜丝顶卷 5 行 + 框 18 行 + 投影 2 行。 */
    public static final int HEIGHT = 25;
    private static final int TEX_W = 24;
    private static final int TEX_H = 25;
    /** 左端：铜丝卷 + 框的左圆角，不能拉伸。 */
    private static final int LEFT = 20;
    /** 中间：羊皮纸内部的一列，横着重复成任意宽度。 */
    private static final int MIDDLE = 1;
    /** 右端：纸的右缘 + 黑描边。 */
    private static final int RIGHT = 3;

    /** 输入框（Create 的 AddressEditBox）相对整条底纸左上角的位置 —— 和 Create 自己窗口里的一致。 */
    private static final int TEXT_X = 29;
    private static final int TEXT_Y = 10;
    /** 输入框的高度，和 Create 自己那个一致。 */
    private static final int BOX_H = 10;
    /** 输入框右端到剪贴板图标的距离（{@code AddressEditBox} 里写死的 4）。 */
    private static final int ICON_GAP = 4;
    /** 右边留给剪贴板图标的位置：和物品图标一样宽，16 像素。 */
    private static final int ICON_W = 16;
    /** 字色：Create 的地址框就是这个灰褐色（0x555555），不是黑的。 */
    private static final int TEXT_COLOR = 0x555555;

    /** 画一条 width 宽的底纸，左上角在 (x, y)。 */
    public static void render(GuiGraphics graphics, int x, int y, int width) {
        int middle = Math.max(0, width - LEFT - RIGHT);
        graphics.blit(TEX, x, y, LEFT, HEIGHT, 0, 0, LEFT, HEIGHT, TEX_W, TEX_H);
        graphics.blit(TEX, x + LEFT, y, middle, HEIGHT, LEFT, 0, MIDDLE, HEIGHT, TEX_W, TEX_H);
        graphics.blit(TEX, x + LEFT + middle, y, RIGHT, HEIGHT,
                LEFT + MIDDLE, 0, RIGHT, HEIGHT, TEX_W, TEX_H);
    }

    /**
     * 画一条 width 宽的底纸，并在里面放一个 Create 的地址输入框。
     *
     * <p>位置由这里统一算，屏幕那边只给左上角和宽度：框和底纸对不齐是这类界面最常见的毛病，
     * 而它只有在两个数出自同一处时才不可能发生。
     */
    public static AddressEditBox create(Screen screen, Font font, int x, int y, int width) {
        AddressEditBox box = new AddressEditBox(screen, new NoShadowFontWrapper(font),
                x + TEXT_X, y + TEXT_Y, boxWidth(width), BOX_H, false);
        box.setTextColor(TEXT_COLOR);
        return box;
    }

    /**
     * 反过来：给一个已经放好的输入框，把它底下那条底纸画出来。
     *
     * <p>和 {@link #create} 是同一组常数的两个方向，所以画出来的纸一定套得住那个框 —— 屏幕那边
     * 只需要记住"我画的是这个框的底纸"，不用自己再加一遍偏移。
     */
    public static void renderUnder(GuiGraphics graphics, AddressEditBox box) {
        render(graphics, box.getX() - TEXT_X, box.getY() - TEXT_Y, width(box.getWidth()));
    }

    /**
     * 一条 width 宽的底纸，中间那个输入框能有多宽。
     *
     * <p>右边必须让出 {@code ICON_GAP + ICON_W}：Create 的地址框会在自己右端外面 4 像素处画那个
     * 剪贴板图标，不让的话图标会压在框的右边框上、甚至伸到纸外面去。
     */
    private static int boxWidth(int width) {
        return Math.max(1, width - TEXT_X - ICON_GAP - ICON_W - RIGHT);
    }

    /** {@link #boxWidth} 的反函数：知道输入框多宽，整条底纸要多宽。 */
    private static int width(int boxWidth) {
        return boxWidth + TEXT_X + ICON_GAP + ICON_W + RIGHT;
    }

    private AddressStrip() {
    }
}
