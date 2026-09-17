package dev.distantstock.client;

import dev.distantstock.net.DockGroupsS2C;
import dev.distantstock.net.GroupMemberC2S;
import dev.distantstock.net.SetDockGroupC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 一个收货港组的界面：谁建的、还有谁在里面、加人、进、出。
 *
 * <p>这一块原来画在终端列表上，压着系统列表，塞得下一个名字和一格输入框，仅此而已。形状是照着
 * Create: Mobile Packages 的运输蜂停泊港的「网络设置」做的（所有者 / 网络中的玩家 / 加人 / 锁），
 * 因为那套东西玩家已经认识：一个网络有主人，主人有名单，网络可以锁，你在名单里或者它没锁你就
 * 进得来。远仓港组本来就是这个形状 —— 数据层一个字都不用改，缺的只是把这个形状画出来。
 *
 * <p>它不是容器界面，开的还是同一个 {@code RequesterMenu}，关掉回到终端。所以这里没有物品栏、
 * 没有菜单同步，所有状态都是服务器推来的那一份 {@link DockGroupsS2C}：界面自己一个字段都不记，
 * 免得同一个网络在两处长得不一样。
 */
public final class DockGroupScreen extends Screen {
    private static final int PANEL_W = 200;
    private static final int TITLE_H = 15;
    private static final int ROW_H = 11;
    /** 名单能画几行，多出来的折成一行「还有 N 人」。 */
    private static final int MAX_ROWS = 6;
    private static final int FIELD_H = 12;
    private static final int BUTTON_H = 16;

    private static final int FRAME = 0xFF1B282C;
    private static final int PAPER = 0xFF24343A;
    private static final int INK = 0xFFEAF6F8;
    private static final int HINT = 0xFF8FB2BC;
    private static final int OWNER = 0xFF9BE0C4;
    private static final int BAR = 0xFF3E5A61;

    private final RequesterScreen parent;
    /** 服务器推来的那一行。每次推送都换掉它，界面永远画最新的那一份。 */
    private DockGroupsS2C.Entry entry;
    private EditBox memberInput;
    /** 删除要按两下：这一下是第一次。 */
    private boolean confirmingDelete;

    public DockGroupScreen(RequesterScreen parent, DockGroupsS2C.Entry entry) {
        super(Component.translatable("gui.distantstock.net.title", entry.name()));
        this.parent = parent;
        this.entry = entry;
    }

    /** 服务器推了新名单。Server side 是唯一真相，这里只换一份画。 */
    public void applyGroups(DockGroupsS2C msg) {
        DockGroupsS2C.Entry found = null;
        for (DockGroupsS2C.Entry row : msg.groups()) {
            if (row.id().equals(entry.id())) {
                found = row;
            }
        }
        if (found == null) {
            // 组没了（被删了）。界面继续开着一个不存在的网络，只会画出上一个名字的残影。
            onClose();
            return;
        }
        entry = found;
    }

    // ------------------------------------------------------------------ geometry

    private int panelX() {
        return (width - PANEL_W) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    /** 名单区画几行：没有别人时也留一行，用来写「还没有别人」。 */
    private int memberLines() {
        int named = entry.members().size();
        int shown = Math.min(named, MAX_ROWS);
        if (named > shown) {
            shown++;
        }
        return Math.max(1, shown);
    }

    private int fieldY() {
        return panelY() + TITLE_H + ROW_H + memberLines() * ROW_H + 6;
    }

    private int buttonsY() {
        return fieldY() + FIELD_H + 8;
    }

    private int panelH() {
        return TITLE_H + ROW_H + memberLines() * ROW_H + 6 + FIELD_H + 8 + BUTTON_H + 6;
    }

    private boolean mine() {
        return entry.mine();
    }

    /** 组主：能加人、能锁、能删。名单里的人：能走。没进名单的：能进（如果它没锁）。 */
    private boolean canJoin() {
        return !entry.admitted() && entry.open();
    }

    private boolean canLeave() {
        return !mine() && entry.admitted();
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        // The field is the only widget here, and it is only there for the owner: adding a name is
        // the one thing on this screen that needs typing at all.
        if (mine()) {
            memberInput = new EditBox(font, panelX() + 5, fieldY() + 1, PANEL_W - 52, 10,
                    Component.translatable("gui.distantstock.member.hint_name"));
            memberInput.setBordered(false);
            memberInput.setTextColor(INK);
            memberInput.setMaxLength(16);
            addRenderableWidget(memberInput);
            setFocused(memberInput);
            memberInput.setFocused(true);
        } else {
            memberInput = null;
        }
    }

    /**
     * 画面板。放在 background 这一步而不是 render 里：屏幕上的控件是在 background 之后统一画的，
     * 面板画在 render 里就会盖住自己的输入框。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = panelX();
        int y = panelY();
        int w = PANEL_W;
        int h = panelH();
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PAPER);

        // 标题：网络：<名字>
        g.drawString(font, trim(Component.translatable("gui.distantstock.net.title", entry.name())
                .getString(), w - 24), x + 5, y + 4, INK, false);
        boolean overClose = mouseX >= x + w - 12 && mouseX < x + w && mouseY >= y && mouseY < y + TITLE_H;
        g.drawString(font, "×", x + w - 10, y + 4, overClose ? 0xFFFFD0D0 : HINT, false);
        g.fill(x + 3, y + TITLE_H - 2, x + w - 3, y + TITLE_H - 1, BAR);

        int rowY = y + TITLE_H;
        // 所有者那一行，右边是锁：锁着的网络只有名单里的人能用，而这是组主唯一能改的东西。
        g.drawString(font, trim(Component.translatable("gui.distantstock.net.owner",
                        entry.owner().isEmpty()
                                ? Component.translatable("gui.distantstock.member.nobody").getString()
                                : entry.owner()).getString(), w - 60),
                x + 5, rowY + 1, OWNER, false);
        if (mine()) {
            int bw = 44;
            int bx = x + w - bw - 5;
            boolean overLock = mouseX >= bx && mouseX < bx + bw && mouseY >= rowY && mouseY < rowY + ROW_H;
            g.fill(bx, rowY, bx + bw, rowY + ROW_H - 1, overLock ? BAR : 0xFF2E444B);
            g.drawString(font, Component.translatable(entry.open()
                                    ? "gui.distantstock.net.unlock" : "gui.distantstock.net.lock").getString(),
                    bx + 4, rowY + 1, INK, false);
        } else {
            g.drawString(font, Component.translatable(entry.open()
                            ? "gui.distantstock.net.open" : "gui.distantstock.net.locked").getString(),
                    x + w - 55, rowY + 1, HINT, false);
        }
        rowY += ROW_H;

        // 名单。组主不画在自己的名单里 —— 他在那上面单独一行，画两遍会像是两个人。
        List<String> members = entry.members();
        int shown = Math.min(members.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            String name = members.get(i);
            g.drawString(font, trim(name, w - 26), x + 5, rowY + 1, INK, false);
            if (mine()) {
                boolean over = mouseX >= x + w - 16 && mouseX < x + w - 4
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                g.drawString(font, "×", x + w - 13, rowY + 1, over ? 0xFFFFD0D0 : 0xFFC0A090, false);
            }
            rowY += ROW_H;
        }
        if (members.size() > shown) {
            g.drawString(font, Component.translatable("gui.distantstock.net.more",
                    members.size() - shown).getString(), x + 5, rowY + 1, HINT, false);
            rowY += ROW_H;
        } else if (members.isEmpty()) {
            g.drawString(font, Component.translatable("gui.distantstock.net.empty").getString(),
                    x + 5, rowY + 1, HINT, false);
            rowY += ROW_H;
        }

        if (mine() && memberInput != null) {
            int fy = fieldY();
            memberInput.setX(x + 5);
            memberInput.setY(fy + 1);
            g.fill(x + 3, fy, x + w - 44, fy + FIELD_H - 1,
                    memberInput.isFocused() ? 0x66FFFFFF : 0x33000000);
            if (memberInput.getValue().isEmpty()) {
                g.drawString(font, Component.translatable("gui.distantstock.member.hint_name").getString(),
                        x + 6, fy + 1, HINT, false);
            }
            button(g, x + w - 42, fy, 40, FIELD_H - 1, "gui.distantstock.net.add", mouseX, mouseY, true);
        } else if (!mine()) {
            g.drawString(font, Component.translatable(entry.admitted()
                            ? "gui.distantstock.net.is_member" : entry.open()
                            ? "gui.distantstock.net.not_member_open"
                            : "gui.distantstock.net.not_member_locked").getString(),
                    x + 5, fieldY() + 2, HINT, false);
        }

        int by = buttonsY();
        if (mine()) {
            // 组主不能离开自己的网络 —— 那不是"退出"，那是删掉或者交给别人，这里两个都还没做。
            button(g, x + w - 62, by, 58, BUTTON_H - 1,
                    confirmingDelete ? "gui.distantstock.net.delete_confirm" : "gui.distantstock.net.delete",
                    mouseX, mouseY, true);
        } else if (canLeave()) {
            button(g, x + w - 62, by, 58, BUTTON_H - 1, "gui.distantstock.net.leave", mouseX, mouseY, true);
        } else if (canJoin()) {
            button(g, x + w - 62, by, 58, BUTTON_H - 1, "gui.distantstock.net.join", mouseX, mouseY, true);
        }
    }

    /** 一个按钮：底色随鼠标、字居中。画和点都用同一套矩形，所以点到的永远是看到的那个。 */
    private void button(GuiGraphics g, int x, int y, int w, int h, String key,
                        int mouseX, int mouseY, boolean enabled) {
        boolean over = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, over ? 0xFF5A7A82 : BAR);
        Component label = Component.translatable(key);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, INK, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = panelX();
        int y = panelY();
        int w = PANEL_W;

        if (mouseX >= x + w - 12 && mouseX < x + w && mouseY >= y && mouseY < y + TITLE_H) {
            onClose();
            return true;
        }
        int rowY = y + TITLE_H;
        if (mine() && mouseX >= x + w - 49 && mouseX < x + w - 5
                && mouseY >= rowY && mouseY < rowY + ROW_H) {
            send(new SetDockGroupC2S(entry.name(), SetDockGroupC2S.TOGGLE_OPEN));
            return true;
        }
        rowY += ROW_H;
        int shown = Math.min(entry.members().size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            if (mine() && mouseX >= x + w - 16 && mouseX < x + w - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_H) {
                send(new GroupMemberC2S(entry.name(), entry.members().get(i), GroupMemberC2S.REMOVE));
                return true;
            }
            rowY += ROW_H;
        }

        int fy = fieldY();
        if (mine() && mouseX >= x + w - 42 && mouseX < x + w - 2
                && mouseY >= fy && mouseY < fy + FIELD_H) {
            commitMember();
            return true;
        }

        int by = buttonsY();
        if (mouseX >= x + w - 62 && mouseX < x + w - 4 && mouseY >= by && mouseY < by + BUTTON_H) {
            if (mine()) {
                if (confirmingDelete) {
                    send(new SetDockGroupC2S(entry.name(), SetDockGroupC2S.DELETE));
                } else {
                    confirmingDelete = true;
                }
                return true;
            }
            if (canLeave()) {
                send(new GroupMemberC2S(entry.name(), "", GroupMemberC2S.LEAVE));
                return true;
            }
            if (canJoin()) {
                send(new GroupMemberC2S(entry.name(), "", GroupMemberC2S.JOIN));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 按键先给输入框，然后才是这个界面自己的。
     *
     * <p>反过来的顺序是终端上踩过的坑：退格、E、ESC 全被界面吃掉，输入框表现成"不跟手"。这里
     * 只多认一个回车（＝按「加入」），而回车本来就不会被输入框消费，所以不存在抢键。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            commitMember();
            return true;
        }
        return false;
    }

    private void commitMember() {
        if (!mine() || memberInput == null) {
            return;
        }
        String name = memberInput.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        memberInput.setValue("");
        send(new GroupMemberC2S(entry.name(), name, GroupMemberC2S.ADD));
    }

    /** 一个包出去，界面等服务器推新名单回来再画 —— 本地先改状态会让两处说法不一致。 */
    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 关掉回终端，不是回到空屏幕：这个界面是从终端开出来的，退出去应该回到原处。 */
    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }
}
