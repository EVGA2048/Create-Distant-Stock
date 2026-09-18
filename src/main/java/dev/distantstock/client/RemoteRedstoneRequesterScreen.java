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
public final class RemoteRedstoneRequesterScreen extends RedstoneRequesterScreen
        implements GroupListSink {
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
    /**
     * 灰按钮条（加长后 146..177）里那两行只读说明的位置。Create 自己的图标在 12..48 和 202..220，
     * 空出来的正好是中间这一块 —— 两行字都放在这儿，一行说这块机器指着哪台仓库，一行说关掉即保存。
     */
    private static final int HINT_X = 54;
    private static final int HINT_Y = 151;
    private static final int NOTE_Y = 162;
    /** 那一块空处有多宽（右边那个勾是 Create 的，别压上去）。 */
    private static final int HINT_ROOM = WINDOW_W - HINT_X - 32;

    private AddressEditBox group;
    private AddressEditBox homeAddress;
    /** 接收港组的清单：点一行就把名字填进框里，和终端那个下拉是同一份数据。 */
    private final GroupPicker picker = new GroupPicker();

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

        moveBottomBar();
        // 清单是服务器推的，而推的那一次可以和界面创建抢跑 —— 抢输了这份就永远缺着，表现是
        // "点开是空的"。要一次比赌它送到便宜（终端那条注释里写的就是这个教训）。
        PacketDistributor.sendToServer(new dev.distantstock.net.SetDockGroupC2S(
                "", dev.distantstock.net.SetDockGroupC2S.REFRESH));
    }

    /** 本服的清单到了。 */
    @Override
    public void acceptGroups(dev.distantstock.net.DockGroupsS2C groups) {
        picker.accept(groups);
        nameTheGroup();
    }

    /** 对面公告过来的那张也到了 —— 远端组和本服的组在同一个列表里选。 */
    @Override
    public void acceptRemotes(dev.distantstock.net.RemoteGroupsS2C remotes) {
        picker.accept(remotes);
        nameTheGroup();
    }

    /**
     * 框里写回这个组**真正的名字**。
     *
     * <p>这台机器显示的组名是从服务端同步过来的，而服务端只在**本服目录**里查得到名字 —— 组在对面
     * 服务器时它退回去写一句 uuid 前八位（{@code RequesterData.shortFreq}）。那句话看着像个名字，
     * 其实谁也认不出，而且玩家一按保存就会被"没有这个港组"顶回来（他根本没法知道自己打的是什么）。
     * 清单到了以后按 id 反查真名，写回去 —— 玩家看到的、能改的、服务端认的，从此是同一个字符串。
     */
    private void nameTheGroup() {
        if (group == null || group.isFocused()) {
            return;
        }
        RemoteRedstoneRequesterBlockEntity requester = requester();
        if (requester == null || requester.binding() == null
                || requester.binding().receivingGroup() == null) {
            return;
        }
        String real = picker.nameOf(requester.binding().receivingGroup());
        if (!real.isEmpty() && !real.equals(group.getValue())) {
            group.setValue(real);
        }
    }

    /**
     * 关界面就落盘 —— **没有「确认」按钮**。
     *
     * <p>玩家 2026-09-17 问"确认键是做什么的"：它和 Create 自己的那个勾并排，谁也说不清分工。既然
     * Create 的工厂面板本来就是关掉才保存、请求器那个勾也是"保存并关屏"，那这两行跟着一起走最省事 ——
     * 玩家按哪个都是关界面，按哪个都会存下来。
     *
     * <p>断开连接时这个屏幕也会被移除，那时候连接已经没了，所以先确认还能发包。
     */
    @Override
    public void removed() {
        send();
        super.removed();
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
        if (requester == null || group == null || homeAddress == null) {
            return;
        }
        // 关界面的时候连接可能已经断了（退出存档/被踢），这时候发包会 NPE。
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
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
        boolean unbound = requester == null || requester.binding() == null;
        // 没绑定的时候说**该做的那一步**（去终端里调谐再右键它），而不是"这台是普通请求器"：
        // 没绑定时这两行根本存不下来（绑定本身就是存它们的地方），玩家 2026-09-18 报的"填了没用"
        // 就是这个。灰条里地方窄，长了会被截断，但"还没绑定 + 终端"这几个字一定在前半句里。
        Component line = unbound
                ? Component.translatable("gui.distantstock.remote_requester.binding")
                : Component.translatable("gui.distantstock.remote_requester.source",
                        requester.binding().network().shortLabel());
        // 灰条里这一块地方宽度有限 —— 长了就截断，宁可少几个字也不要压到隔壁的图标上。
        graphics.drawString(font, font.plainSubstrByWidth(line.getString(), HINT_ROOM),
                leftPos + WINDOW_X + HINT_X, topPos + HINT_Y, unbound ? 0x9A4A38 : 0x3D3D3D, false);
        // 没有「确认」按钮了，所以得说出来：这两行是关掉界面时才送出去的。
        Component note = Component.translatable("gui.distantstock.save_on_close");
        graphics.drawString(font, note, leftPos + WINDOW_X + HINT_X, topPos + NOTE_Y,
                0x6A6A6A, false);
    }

    /**
     * 清单一律**最后**画。
     *
     * <p>它是一块浮在界面上的东西：先画就会被后面的控件和物品栏盖住，看起来像两张纸叠在一起
     * （终端那边踩过同一个坑）。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (picker.isOpen()) {
            picker.render(graphics, font, pickerX(), pickerY(), pickerW(), mouseX, mouseY,
                    group == null ? "" : group.getValue());
        }
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
     *
     * <p>**{@code setFocused(box)} 这一句不能省。** {@code box.mouseClicked(...)} 只把**控件自己的**
     * 聚焦标志置上（那是画高亮用的），屏幕自己不认；而 {@code Screen.getFocused()} 返回的是屏幕那个
     * 字段。少了这一句，框看着是聚焦的、字却一个也打不进去 —— {@link #keyPressed} 里那个
     * {@code getFocused() instanceof AddressEditBox} 永远不成立，键全部落到 super。玩家 2026-09-17
     * 报的"两条框只能被选蓝、打不进字"就是这个。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 清单先来：它画在框上面，点它的时候该落到行里，而不是落到底下那个输入框上。
        if (picker.isOpen()) {
            String picked = picker.hit(mouseX, mouseY, pickerX(), pickerY(), pickerW());
            if (picked != null) {
                group.setValue(picked);
                picker.close();
                return true;
            }
            // 点在这一块里但不是某一行（边框上）也算我们的，别顺手把它关掉又开一次。
            if (onPicker(mouseX, mouseY)) {
                return true;
            }
            picker.close();
        }
        for (AddressEditBox box : new AddressEditBox[]{group, homeAddress}) {
            if (box != null && box.isMouseOver(mouseX, mouseY)
                    && box.mouseClicked(mouseX, mouseY, button)) {
                setFocused(box);
                if (box == group) {
                    // 点「接收港组」那一行就把清单打开 —— 这个框的答案是一串已知的名字，让玩家自己
                    // 背下来是不必要的（终端那边早就这样了）。
                    picker.toggle();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int pickerX() {
        return leftPos + WINDOW_X + ROW_INSET;
    }

    private int pickerY() {
        return topPos + ROW_TOP + AddressStrip.HEIGHT - 2;
    }

    private int pickerW() {
        return WINDOW_W - ROW_INSET - ROW_INSET_RIGHT;
    }

    /** 点是不是落在清单那一块上（含边框）。 */
    private boolean onPicker(double mouseX, double mouseY) {
        int height = picker.height();
        return height > 0 && mouseX >= pickerX() - 2 && mouseX < pickerX() + pickerW() + 2
                && mouseY >= pickerY() - 2 && mouseY < pickerY() + height + 2;
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
