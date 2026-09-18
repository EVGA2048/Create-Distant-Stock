package dev.distantstock.client;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import dev.distantstock.block.RemoteBinding;
import dev.distantstock.net.BindGaugePanelC2S;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create 的工厂仪表界面，**把窗口接长一段**，远仓那两行住在里面。
 *
 * <p>Everything Create draws — the filter, the amount, the connections, the crafting arrangement —
 * is left exactly as it is, because none of it is ours to change and all of it is what a player
 * already knows. What is added is the two things that had no face anywhere: <b>where this panel
 * orders from</b> and <b>what address its goods carry</b>. Before this they existed only as a
 * gesture with a tuned terminal and a line on the goggles, which is why a remote gauge read as an
 * ordinary factory gauge that happened to do nothing.
 *
 * <p>上一版把这两行画在窗口**外面**：垫一块深色底板，再给整个窗口糊一层蓝色。玩家一眼就看出那不是
 * 窗口而是个补丁（「原来在空白的地方有一层蓝色的透明层」）。现在改用窗口自己往下接一截木头
 * （{@code scripts/gen_gauge_band.py} 从 `factory_gauge.png` 的下段贴图里复制出来的），字段就住在
 * 这一截里，蓝底和底板都删了。
 *
 * <p>接长不改变窗口里任何东西的位置：Create 把它的地址框和按钮摆在「窗口高 - 51 / - 25」处，
 * 而窗口高是它自己算的 —— 所以这里一个字段都不用挪，只是把整块往上提半截，让多出来的那一段落在
 * 底下（{@link #setWindowOffset}）。
 *
 * <p>The warehouse is not editable here on purpose. It is a Create logistics network on some node,
 * and the only way to name one is to stand in front of it with a tuned terminal; a text field would
 * be asking the player to type a UUID. The band says which one this panel is pointed at, or says
 * plainly that it is not pointed at one.
 */
public final class RemoteGaugeScreen extends FactoryPanelScreen implements GroupListSink {
    private static final ResourceLocation BAND_TEX = ResourceLocation.fromNamespaceAndPath(
            dev.distantstock.DistantStock.MODID, "textures/gui/remote_gauge_band.png");

    /**
     * 延长段（{@code remote_gauge_band.png}）的尺寸，和 {@code gen_gauge_band.py} 是一份：
     * 顶端那 {@link #COVER} 行盖掉窗口原来的底边，最后一行是新的底边。
     */
    private static final int BAND_H = 68;
    private static final int COVER = 2;
    /** 窗口实际长高了多少 —— 也是居中时要让出来的高度。 */
    private static final int GROW = BAND_H - COVER;
    /** 两行地址条的顶端（相对延长段顶端）。 */
    private static final int ROW_TOP = 4;
    private static final int ROW_STEP = 27;
    /** 只读的那行"这块仪表指着谁"（相对延长段顶端）。 */
    private static final int HINT_TOP = 57;
    /**
     * 底纸的左右留白。延长段（和它上面那段窗口一样）的木头区是第 4..187 列，右边那条黑边比左边宽
     * 得多（188..199），所以右边留得比左边多 —— 底纸压到描边上就不好看了。
     */
    private static final int INSET_L = 10;
    private static final int INSET_R = 19;
    /** 木头上的字色：窗口里那条羊皮纸的浅色，压在木纹上读得清。 */
    private static final int HINT_COLOR = 0xE6D3AE;
    /** 旁边那句"关掉即保存"淡一点：它是说明，不是读数。 */
    private static final int HINT_COLOR_NOTE = 0xB9A88A;
    /** 没仓库时那句提示是警告：它说的是"你现在填的东西存不下来"。 */
    private static final int HINT_COLOR_WARN = 0xE8A18C;

    private final FactoryPanelBehaviour behaviour;
    private com.simibubi.create.content.logistics.AddressEditBox destination;
    private com.simibubi.create.content.logistics.AddressEditBox address;
    /** 接收港组的清单：点一行就把名字填进框里，和终端那个下拉是同一份数据。 */
    private final GroupPicker picker = new GroupPicker();

    private RemoteGaugeScreen(FactoryPanelBehaviour behaviour) {
        super(behaviour);
        this.behaviour = behaviour;
    }

    /** Opens this screen for a panel, on the client. Called from the behaviour's display hook. */
    public static void open(FactoryPanelBehaviour behaviour) {
        // A server has no Screen; this class is only ever loaded on the client, and only from the
        // behaviour's displayScreen, which Create itself guards the same way.
        if (Minecraft.getInstance().player == null) {
            return;
        }
        ScreenOpener.open(new RemoteGaugeScreen(behaviour));
    }

    @Override
    protected void init() {
        // 整块往上提半截：catnip 是按**原来的**窗口高居中的，多出来的那一截只能靠偏移补回来 ——
        // 补完的效果和"按加长后的高度居中"一模一样。这个数必须在 super.init() 之前设好。
        setWindowOffset(0, -GROW / 2);
        super.init();

        int left = guiLeft + INSET_L;
        int wide = windowWidth - INSET_L - INSET_R;
        int top = rowTop();

        destination = AddressStrip.create(this, font, left, top, wide);
        destination.setMaxLength(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        // 框里填的是**组名**，而存下来的是组 id —— 客户端没有目录，本来查不出名字。现在这一页自己
        // 就带着一份组清单（见 {@link GroupPicker}），于是能反查了：填上当前那个组的名字，而不是
        // 空着。空着的问题是玩家每次都以为"上次填的丢了"（2026-09-18 玩家原话："输入完再打开会
        // 变回去"）—— 其实存下来了，只是没显示。清单还没到就先空着，到了再补（见 acceptGroups）。
        destination.setValue(currentDestination());
        destination.setHint(Component.translatable("gui.distantstock.remote_gauge.destination.hint"));
        addRenderableWidget(destination);

        address = AddressStrip.create(this, font, left, top + ROW_STEP, wide);
        address.setMaxLength(64);
        address.setValue(currentAddress(behaviour));
        address.setHint(Component.translatable("gui.distantstock.remote_gauge.address.hint"));
        addRenderableWidget(address);

        // 清单是服务器推的，推的那一次可以和界面创建抢跑 —— 要一次比赌它送到便宜。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new dev.distantstock.net.SetDockGroupC2S("",
                        dev.distantstock.net.SetDockGroupC2S.REFRESH));
    }

    /** 本服的清单到了。 */
    @Override
    public void acceptGroups(dev.distantstock.net.DockGroupsS2C groups) {
        picker.accept(groups);
        nameTheDestination();
    }

    /** 对面公告过来的那张也到了。 */
    @Override
    public void acceptRemotes(dev.distantstock.net.RemoteGroupsS2C remotes) {
        picker.accept(remotes);
        nameTheDestination();
    }

    /**
     * 清单到了以后把当前这个组的名字补进框里（玩家正在打字就不动他）。
     *
     * <p>界面是 {@code init()} 时建的，那时候清单还没到 —— 所以这一步不能只在 init 里做。
     */
    private void nameTheDestination() {
        if (destination == null || destination.isFocused()) {
            return;
        }
        String name = currentDestination();
        if (!name.isEmpty() && !name.equals(destination.getValue())) {
            destination.setValue(name);
        }
    }

    /** 这一格现在指着的组叫什么，查不到就是空串。 */
    private String currentDestination() {
        RemoteBinding binding = knownBinding(behaviour);
        return binding == null ? "" : picker.nameOf(binding.receivingGroup());
    }

    private int pickerX() {
        return guiLeft + INSET_L;
    }

    private int pickerY() {
        return rowTop() + AddressStrip.HEIGHT - 2;
    }

    private int pickerW() {
        return windowWidth - INSET_L - INSET_R;
    }

    /** 点是不是落在清单那一块上（含边框）。 */
    private boolean onPicker(double mouseX, double mouseY) {
        int height = picker.height();
        return height > 0 && mouseX >= pickerX() - 2 && mouseX < pickerX() + pickerW() + 2
                && mouseY >= pickerY() - 2 && mouseY < pickerY() + height + 2;
    }

    /**
     * 关界面就落盘 —— **没有自己的「确认」按钮**。
     *
     * <p>玩家 2026-09-17 问"确认键是做什么的"。这块屏幕本来就是 Create 的工厂面板：数值、过滤器、
     * 连接都是关掉才存，它自己那个勾也是"保存并关屏"。我们的两行跟着一起走最省事，也少一个和勾
     * 并排、谁也说不清分工的按钮。
     *
     * <p>断开连接时这个屏幕也会被移除，那时候连接已经没了，所以先确认还能发包。
     */
    @Override
    public void removed() {
        send();
        super.removed();
    }

    /** 两行地址条的顶端在屏幕上哪儿 —— 延长段顶端再加 {@link #ROW_TOP}。 */
    private int rowTop() {
        return bandTop() + ROW_TOP;
    }

    /** 延长段的顶端：窗口原来的底边往上让 {@link #COVER} 行，好把那两行盖掉。 */
    private int bandTop() {
        return guiTop + windowHeight - COVER;
    }

    private void send() {
        if (destination == null || address == null) {
            return;
        }
        // 关界面的时候连接可能已经断了（退出存档/被踢），这时候发包会 NPE。
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        FactoryPanelBlock.PanelSlot slot = behaviour.slot;
        PacketDistributor.sendToServer(new BindGaugePanelC2S(
                behaviour.blockEntity.getBlockPos(), slot.ordinal(),
                destination.getValue(), address.getValue()));
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderWindow(graphics, mouseX, mouseY, partialTicks);
        // 窗口自己那两行底边（深灰 + 黑）被这一段盖掉，换成一截继续往下的木头，最后一行是新的底边。
        int top = bandTop();
        graphics.blit(BAND_TEX, guiLeft, top, 0, 0, windowWidth, BAND_H, windowWidth, BAND_H);
        // 两条底纸画在框**自己**的位置上（AddressStrip 那两个方向共用同一组常数）：底纸和输入框
        // 对不齐是这类界面最常见的毛病，而它只有两边各算一次偏移时才可能出现。
        if (destination != null) {
            AddressStrip.renderUnder(graphics, destination);
        }
        if (address != null) {
            AddressStrip.renderUnder(graphics, address);
        }
        // 这一行只说一件事：这块仪表指着哪台仓库。右边补一句"关掉即保存"—— 没有确认按钮了，
        // 不说的话玩家不知道该什么时候离开这个界面。仓库名太长时**先让着右边那句**，宁可截仓库名。
        Component note = Component.translatable("gui.distantstock.save_on_close");
        int noteW = font.width(note);
        graphics.drawString(font, note, guiLeft + windowWidth - INSET_R - noteW, top + HINT_TOP,
                HINT_COLOR_NOTE, false);
        Component line = sourceLine(behaviour);
        // 没仓库时那句话说白了是"你现在填的东西存不下来"，用警告色 —— 玩家上一次就是没注意到它。
        int lineColor = knownBinding(behaviour) == null ? HINT_COLOR_WARN : HINT_COLOR;
        if (line != null) {
            int room = Math.max(0, windowWidth - INSET_L - INSET_R - noteW - 8);
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), room),
                    guiLeft + INSET_L, top + HINT_TOP, lineColor, false);
        }
    }

    /**
     * 我们这两个框在 Create 的窗口**里面**，而它的 {@code mouseClicked} 会先把面板自己的命中测试
     * 做完 —— 点在我们框上的那一击到不了框里，于是焦点永远拿不到，框看着在那儿、却一个字也打不进去。
     *
     * <p>先给我们的框一次机会，再交给 Create：顺序反过来的话，落在框上的点击会被它当成"点空白处"
     * 而先清掉焦点。
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
                destination.setValue(picked);
                picker.close();
                return true;
            }
            if (onPicker(mouseX, mouseY)) {
                return true;
            }
            picker.close();
        }
        if (destination != null && destination.isMouseOver(mouseX, mouseY)
                && destination.mouseClicked(mouseX, mouseY, button)) {
            setFocused(destination);
            // 点「接收港组」那一行就把清单打开 —— 这个框的答案是一串已知的名字。
            picker.toggle();
            return true;
        }
        if (address != null && address.isMouseOver(mouseX, mouseY)
                && address.mouseClicked(mouseX, mouseY, button)) {
            setFocused(address);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 清单一律**最后**画。
     *
     * <p>catnip 是先 {@code renderWindow} 再画所有控件，所以在 {@code renderWindow} 里画会被输入框
     * 盖住 —— 这个钩子是那之后。
     */
    @Override
    protected void renderWindowForeground(GuiGraphics graphics, int mouseX, int mouseY,
                                           float partialTicks) {
        super.renderWindowForeground(graphics, mouseX, mouseY, partialTicks);
        if (picker.isOpen()) {
            picker.render(graphics, font, pickerX(), pickerY(), pickerW(), mouseX, mouseY,
                    destination == null ? "" : destination.getValue());
        }
    }

    /**
     * 打字也一样：焦点在我们框上的时候，键先给它。
     *
     * <p>Create 的工厂面板屏幕自己处理按键（数字、快捷键），不先分给我们的话，回车提交之类的会
     * 被它截走。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof com.simibubi.create.content.logistics.AddressEditBox box
                && box.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (getFocused() instanceof com.simibubi.create.content.logistics.AddressEditBox box
                && box.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /**
     * 地址补全跟着框一起活：Create 的地址框在 tick 里刷新候选。
     *
     * <p>这个屏幕的父类是 catnip 的 {@code AbstractSimiScreen}（不是容器屏幕），它给的节拍叫
     * {@code tick()}，没有 {@code containerTick()}。
     */
    @Override
    public void tick() {
        super.tick();
        if (destination != null) {
            destination.tick();
        }
        if (address != null) {
            address.tick();
        }
    }

    /**
     * 这块仪表指着哪台仓库，或者一句"它没有指着谁"；**读不出来的时候返回 null，什么都不画**。
     *
     * <p>"读不出来"是 Deployer 那条路：别的模组的板子上那格仪表我们只能拿到它的地址
     * （{@code DeployerPanels.addressOf}），拿不到它绑的网络。这时候沉默比编一句话强 —— 说"未绑定"
     * 会让一台绑好的仪表看起来是坏的。
     */
    private static Component sourceLine(FactoryPanelBehaviour behaviour) {
        RemoteBinding binding = knownBinding(behaviour);
        if (binding != null) {
            return Component.translatable("gui.distantstock.remote_gauge.source",
                    binding.network().shortLabel());
        }
        if (behaviour.blockEntity instanceof dev.distantstock.block.RemoteGaugeBlockEntity
                || behaviour.blockEntity instanceof dev.distantstock.block.SignalPanelBlockEntity) {
            // **说能做的那一步**，而不是"未绑定"。没仓库时这两行根本存不下来（绑定本身是存它们的地方），
            // 玩家 2026-09-18 报的"填了没用、关掉再开又变回去"就是这个 —— 界面当时只写了"未绑定"，
            // 没写"所以你填了也白填"。
            return Component.translatable("gui.distantstock.remote_gauge.needs_terminal");
        }
        return null;
    }

    /** 这一格绑的是哪台仓库，读不出来是 null。 */
    private static RemoteBinding knownBinding(FactoryPanelBehaviour behaviour) {
        if (behaviour.blockEntity instanceof dev.distantstock.block.RemoteGaugeBlockEntity gauge) {
            return gauge.binding(behaviour.slot);
        }
        if (behaviour.blockEntity instanceof dev.distantstock.block.SignalPanelBlockEntity signal) {
            return signal.binding(behaviour.slot);
        }
        return null;
    }

    /**
     * The address this panel's goods carry today, or an empty string.
     *
     * <p>Only the address can be shown: it is a string the binding carries, and the destination is a
     * group id this side has no name table for.
     */
    private static String currentAddress(FactoryPanelBehaviour behaviour) {
        RemoteBinding binding = knownBinding(behaviour);
        if (binding != null) {
            return binding.address();
        }
        if (!(behaviour.blockEntity instanceof FactoryPanelBlockEntity board)) {
            return "";
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                ? dev.distantstock.panel.DeployerPanels.addressOf(board, behaviour.slot)
                : "";
    }
}
