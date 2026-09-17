package dev.distantstock.client;

import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterMenu;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.RemoteRedstoneRequesterBlockEntity;
import dev.distantstock.menu.RemoteRedstoneRequesterMenu;
import dev.distantstock.net.SetRequesterTargetC2S;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 远仓红石请求器的界面：Create 那个窗口**加长一段**，里面放我们那两行。
 *
 * <p>加长而不是在窗口外面另起一块：玩家要的就是"上面棕色的部分变长一点"。那张窗口底图是整块烧在
 * Create 的资源里的 232×120，所以 {@code scripts/gen_requester_gui.py} 把它的棕色段和灰色按钮条
 * 切开、在缝里插进 58 行同色的棕色，拼成我们自己的一张（232×178）。窗口里原有的东西（标题条、
 * 九个幽灵格子、Create 自己那个送货地址框）一个像素没动，只是往下让出了一段。
 *
 * <p>跟着往下走的有三样，都在这里对齐：
 * <ul>
 *   <li>**玩家背包**：它的槽位在菜单里定（{@link RemoteRedstoneRequesterMenu}），框在这里画；</li>
 *   <li>**灰按钮条里那三个图标**：它们的位置是 Create 在 {@code init()} 里按"窗口高 120"算出来的，
 *       底图加长不会带着它们走 —— 不挪就留在棕色段中间；</li>
 *   <li>**新加的两行**：接收港组（货从对面哪个港出来）和本端地址（过海以后包裹换成哪个门牌）。</li>
 * </ul>
 *
 * <p>上面那个框仍然是 Create 自己的送货地址 —— 三个框各管一件事，不重复。
 */
public final class RemoteRedstoneRequesterScreen extends RedstoneRequesterScreen {
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(
            dev.distantstock.DistantStock.MODID, "textures/gui/remote_requester.png");

    /** 窗口底图的尺寸：Create 的 232×120，下面接上我们那段棕色。 */
    private static final int WINDOW_W = 232;
    private static final int WINDOW_H = 120;
    /** Create 把窗口画在 (leftPos + 3, topPos) 处 —— 是 **x** 加 3，不是 y。这里也得跟着。 */
    private static final int WINDOW_X = 3;
    /** 加长的那一段：和 {@link RemoteRedstoneRequesterMenu#BAND} 是同一个数。 */
    private static final int BAND = RemoteRedstoneRequesterMenu.BAND;
    /** 那一截棕色段里，第一行地址条的顶端（相对窗口）。段本身是 88..145，两行各 25 高、中间隔 2。 */
    private static final int ROW_TOP = 91;
    private static final int ROW_STEP = AddressStrip.HEIGHT + 2;
    /**
     * 两条底纸的左右留白。窗口的木头区是第 4..219 列，外面那三列是窗口自己的黑描边 ——
     * 底纸不能盖到描边上，所以右边留得比左边多。
     */
    private static final int ROW_INSET = 10;
    private static final int ROW_INSET_RIGHT = 14;
    /** 灰按钮条（加长后 146..177）里我们那两样东西的位置：Create 自己的图标在 12..48 和 202..220。 */
    private static final int BAR_Y = 153;
    private static final int CONFIRM_X = 54;
    private static final int CONFIRM_W = 44;
    private static final int CONFIRM_H = 18;
    private static final int HINT_X = 104;
    /** 只读说明那一行的 y（相对窗口），和 Create 的图标同一行。 */
    private static final int HINT_Y = 158;

    private AddressEditBox group;
    private AddressEditBox homeAddress;

    public RemoteRedstoneRequesterScreen(RedstoneRequesterMenu menu, Inventory inventory,
                                         Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        // 把加长的那一段也算进窗口高度，vanilla 居中的就是整块 —— 于是窗口整体上移半段，
        // 底下正好让出 58 像素。super.init() 里 Create 会按算好的 guiTop 放它自己那个地址框，
        // 所以这个数必须在它之前加好，之后也不能改回来。
        imageHeight += BAND;
        super.init();

        int left = leftPos + WINDOW_X + ROW_INSET;
        int wide = WINDOW_W - ROW_INSET - ROW_INSET_RIGHT;
        int top = topPos + ROW_TOP;

        RemoteRedstoneRequesterBlockEntity requester = requester();
        group = AddressStrip.create(this, font, left, top, wide);
        group.setMaxLength(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        group.setValue(requester == null ? "" : requester.knownGroupName());
        group.setHint(Component.translatable("gui.distantstock.remote_requester.group.hint"));
        addRenderableWidget(group);

        homeAddress = AddressStrip.create(this, font, left, top + ROW_STEP, wide);
        homeAddress.setMaxLength(64);
        homeAddress.setValue(requester == null || requester.binding() == null
                ? "" : requester.binding().homeAddress());
        homeAddress.setHint(Component.translatable("gui.distantstock.route.home.hint"));
        addRenderableWidget(homeAddress);

        // 确认放在灰按钮条里，和自己那两行底纸分开：底纸整条留给自己，字才写得下。
        addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(Component.translatable("gui.distantstock.confirm"), button -> send())
                .bounds(leftPos + WINDOW_X + CONFIRM_X, topPos + BAR_Y, CONFIRM_W, CONFIRM_H)
                .build());

        moveBottomBar();
    }

    /**
     * 把灰按钮条里那三个图标往下挪 {@link #BAND}。
     *
     * <p>它们是 Create 的 private 字段，这里拿不到，只能从屏幕自己那串控件里认出来。认的条件写得死
     * 一点：**只有落在原灰条那 32 像素里的图标**才挪 —— 万一以后 Create 在别处加了别的图标，这一条
     * 不会顺手把它也搬走。
     */
    private void moveBottomBar() {
        for (GuiEventListener child : children()) {
            if (!(child instanceof IconButton button)) {
                continue;
            }
            int offset = button.getY() - topPos;
            if (offset >= WINDOW_H - 32 && offset <= WINDOW_H) {
                button.setY(button.getY() + BAND);
            }
        }
    }

    private RemoteRedstoneRequesterBlockEntity requester() {
        return menu.contentHolder instanceof RemoteRedstoneRequesterBlockEntity be ? be : null;
    }

    private void send() {
        RemoteRedstoneRequesterBlockEntity requester = requester();
        if (requester == null) {
            return;
        }
        PacketDistributor.sendToServer(new SetRequesterTargetC2S(requester.getBlockPos(),
                group.getValue(), homeAddress.getValue()));
    }

    /**
     * 整块重画：Create 的窗口、我们加长的那一段、还有跟着往下走的物品栏框。
     *
     * <p>不能只调 super 再补几笔：它把窗口和物品栏按固定间距一次画完，中间没有地方塞进来。
     * 所以这里按它的画法画一遍，把物品栏挪到加长段之后。
     *
     * <p>这一版的位置全是照 Create 自己的字节码来的，不再是估的：
     * 窗口在 (leftPos + 3, topPos)、物品栏框在 (leftPos - 3, topPos + 124)、机器名在窗口顶第 4 行、
     * 右边那台方块预览在 (leftPos + 245, topPos + 80)、放大 3 倍。
     */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEX, leftPos + WINDOW_X, topPos, 0, 0, WINDOW_W, WINDOW_H + BAND,
                WINDOW_W, WINDOW_H + BAND);
        // 物品栏框架：Create 放在 (leftPos - 3, topPos + 124)，我们加长了一段，它就往下走同样多。
        // 这个 helper 顺带把"物品栏"三个字也画了，位置和 Create 一致。
        renderPlayerInventory(graphics, leftPos - 3, topPos + 124 + BAND);
        // 机器名在窗口顶的标题条里，底图加长跟它无关。
        Component name = new ItemStack(ModBlocks.REMOTE_REDSTONE_REQUESTER.get()).getHoverName();
        graphics.drawString(font, name,
                leftPos + 117 - font.width(name) / 2, topPos + 4, 0x3D3D3D, false);
        // 右边那台方块预览，换成**我们的**机器 —— 原来画的是 Create 那台蓝的，玩家一眼就会以为进错了界面。
        GuiGameElement.of(new ItemStack(ModBlocks.REMOTE_REDSTONE_REQUESTER.get()))
                .scale(3)
                .render(graphics, leftPos + 245, topPos + 80);

        // 两行底纸画在框自己身上（AddressStrip 两个方向共用一组常数）。
        if (group != null) {
            AddressStrip.renderUnder(graphics, group);
        }
        if (homeAddress != null) {
            AddressStrip.renderUnder(graphics, homeAddress);
        }
        // 一行只读的绑定说明，画在灰按钮条中间那一块空处：这一条只能在别处改（拿调谐过的终端右键它），
        // 不写出来玩家会以为屏幕上少了一个框。**画在背景这一层**：再晚一步就会被物品提示盖住。
        RemoteRedstoneRequesterBlockEntity requester = requester();
        Component line = requester == null || requester.binding() == null
                ? Component.translatable("goggle.distantstock.remote_requester.unbound")
                : Component.translatable("gui.distantstock.remote_requester.source",
                        requester.binding().network().shortLabel());
        // 灰条里从「确认」到 Create 那个勾之间的地方，宽度有限 —— 长了就截断，宁可少几个字也不要
        // 压到隔壁的图标上。
        int room = WINDOW_W - HINT_X - 32;
        graphics.drawString(font, font.plainSubstrByWidth(line.getString(), room),
                leftPos + WINDOW_X + HINT_X, topPos + HINT_Y, 0x3D3D3D, false);
    }

    /** 地址补全跟着框一起活；父类只照顾它自己那个框。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (group != null) {
            group.tick();
        }
        if (homeAddress != null) {
            homeAddress.tick();
        }
    }

    /**
     * 两个框在 Create 的窗口里，而它的 {@code mouseClicked} 先把面板自己的命中测试做完 ——
     * 点在我们框上的那一击到不了框里，焦点永远拿不到。先给我们的框一次机会，再交给它。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (AddressEditBox box : new AddressEditBox[]{group, homeAddress}) {
            if (box != null && box.isMouseOver(mouseX, mouseY)
                    && box.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof AddressEditBox box
                && box.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (getFocused() instanceof AddressEditBox box && box.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }
}
