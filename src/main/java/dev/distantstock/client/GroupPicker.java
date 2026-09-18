package dev.distantstock.client;

import dev.distantstock.net.DockGroupsS2C;
import dev.distantstock.net.RemoteGroupsS2C;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 接收港组的一张清单：点一行，名字就填进框里。
 *
 * <p>玩家 2026-09-18 的原话是「他们俩没有接收港组的选项，那怎么传？」—— 那两行框是空白的自由文本，
 * 玩家得先记住组名再一个字一个字打对；打错了服务端只会丢一句"没有这个港组"，界面上什么都不说，
 * 于是看起来就是"填了没用、关掉再开又变回去了"。终端那边早有这张清单（同样是这份数据），这里把它
 * 搬过来：**同一个问题的同一个答案，不该在两张界面上长得不一样**。
 *
 * <p>数据来源和终端的下拉完全一致：{@link DockGroupsS2C}（本服、已按玩家的权限筛过）+
 * {@link RemoteGroupsS2C}（对面服务器公告过来的）。本服的排前面 —— 玩家要选的通常是自己的。
 *
 * <p>只做"选一个名字"这一件事：上锁、改名、删除、看成员都在终端的「网络…」那一页，这里重复一遍
 * 只会多出四个按不出反应的按钮。
 */
public final class GroupPicker {
    /**
     * 一行的行高，和终端那个下拉一样。
     *
     * <p>12 而不是 10：字高 9 像素，中文字形顶到边，10 像素的行距看着像两行叠在一起 —— 玩家
     * 2026-09-18 报的「选项栏和输入框字体重合」就是所有下拉共有的这个问题。
     */
    private static final int ROW_H = 12;
    /** 最多画几行。再多就写一行"还有 N 个"，剩下的去终端里看。 */
    private static final int MAX_ROWS = 10;

    private static final int FRAME = 0xFF16242A;
    private static final int PANEL = 0xFF24343A;
    private static final int ROW = 0xFF2E444B;
    private static final int ROW_OVER = 0xFF3E5A61;
    private static final int INK = 0xFFD8EEEA;
    private static final int MUTED = 0xFF9BB0B6;
    private static final int CLOSED = 0xFF8A8090;
    private static final int HERE = 0xFF9BE0C4;
    private static final int REMOTE = 0xFFC8B6E8;

    private DockGroupsS2C local;
    private RemoteGroupsS2C remotes;
    private boolean open;

    void accept(DockGroupsS2C next) {
        local = next;
    }

    void accept(RemoteGroupsS2C next) {
        remotes = next;
    }

    /** 清单到了没有。没到之前框里只能是自己打字。 */
    boolean hasList() {
        return local != null;
    }

    private int total() {
        return (local == null ? 0 : local.groups().size())
                + (remotes == null ? 0 : remotes.groups().size());
    }

    private int rows() {
        return Math.min(total(), MAX_ROWS);
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        open = !open;
    }

    void close() {
        open = false;
    }

    /** 清单画出来有多高（含边框和"还有 N 个"那一行）。 */
    int height() {
        int rows = rows();
        if (rows == 0) {
            return 0;
        }
        return rows * ROW_H + 4 + (total() > MAX_ROWS ? ROW_H : 0);
    }

    /** 按 id 反查组名 —— 把已经存下来的那个组显示回框里，找不到就是空串。 */
    String nameOf(UUID id) {
        if (id == null) {
            return "";
        }
        if (local != null) {
            for (DockGroupsS2C.Entry entry : local.groups()) {
                if (entry.id().equals(id)) {
                    return entry.name();
                }
            }
        }
        if (remotes != null) {
            for (RemoteGroupsS2C.Entry entry : remotes.groups()) {
                if (entry.group().equals(id)) {
                    return entry.name();
                }
            }
        }
        return "";
    }

    /**
     * 把清单画出来，左上角在 (x, y)。
     *
     * <p>画的时候在**最后**：它是一块浮在界面上的东西，先画就会被后面的控件盖住（终端那边踩过）。
     */
    void render(GuiGraphics g, Font font, int x, int y, int width, double mouseX, double mouseY,
                String current) {
        int rows = rows();
        if (rows == 0) {
            return;
        }
        int extra = total() > MAX_ROWS ? ROW_H : 0;
        int height = rows * ROW_H + extra;
        g.fill(x - 2, y - 2, x + width + 2, y + height + 2, FRAME);
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, PANEL);

        int localCount = local == null ? 0 : local.groups().size();
        for (int i = 0; i < rows; i++) {
            int rowY = y + i * ROW_H;
            boolean over = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (i < localCount) {
                DockGroupsS2C.Entry entry = local.groups().get(i);
                g.fill(x, rowY, x + width, rowY + ROW_H, over ? ROW_OVER : ROW);
                // 没被放行的行画灰：名单是权限，不是装饰。这一格玩家点得下去但发不出去，所以先把
                // 它说在点之前 —— 灰字配"未加入"比点完什么也没发生好。
                boolean usable = entry.admitted();
                String head = usable ? Integer.toString(entry.docks())
                        : Component.translatable("gui.distantstock.net.join_short").getString();
                g.drawString(font, head, x + 3, rowY + 1, usable ? MUTED : 0xFF7FD8E8, false);
                // 组主跟在名字后面：一排「仓库」谁也认不出哪个是自己的（终端那边玩家就这么说过）。
                String label = entry.mine() || entry.owner().isEmpty()
                        ? entry.name() : entry.name() + " · " + entry.owner();
                boolean here = current != null && current.trim().equalsIgnoreCase(entry.name());
                g.drawString(font, trim(font, label, width - 12 - font.width(head)),
                        x + 7 + font.width(head), rowY + 1,
                        !usable ? CLOSED : here ? HERE : INK, false);
            } else {
                RemoteGroupsS2C.Entry entry = remotes.groups().get(i - localCount);
                g.fill(x, rowY, x + width, rowY + ROW_H, over ? ROW_OVER : ROW);
                boolean usable = entry.admitted();
                String head = entry.docks() > 0 ? Integer.toString(entry.docks())
                        : Component.translatable("gui.distantstock.group.no_dock").getString();
                g.drawString(font, head, x + 3, rowY + 1, entry.docks() > 0 ? MUTED : 0xFFC08080,
                        false);
                boolean here = current != null && current.trim().equalsIgnoreCase(entry.name());
                g.drawString(font, trim(font, entry.display(), width - 12 - font.width(head)),
                        x + 7 + font.width(head), rowY + 1,
                        !usable ? CLOSED : here ? HERE : REMOTE, false);
            }
        }
        if (extra > 0) {
            Component more = Component.translatable("gui.distantstock.group.more", total() - MAX_ROWS);
            g.drawString(font, more, x + 3, y + rows * ROW_H + 1, MUTED, false);
        }
    }

    /**
     * 点中了哪一行：要填进框里的名字，或者 null。
     *
     * <p>没被放行的行也返回名字：填进去之后服务端会拒绝并说明原因（那是唯一判得了的地方），
     * 比在这里假装它不存在强 —— 玩家至少能看到"我选了它、它说我不在里面"。
     */
    String hit(double mouseX, double mouseY, int x, int y, int width) {
        int rows = rows();
        if (rows == 0 || mouseX < x || mouseX >= x + width) {
            return null;
        }
        int index = (int) ((mouseY - y) / ROW_H);
        if (mouseY < y || index < 0 || index >= rows) {
            return null;
        }
        int localCount = local == null ? 0 : local.groups().size();
        if (index < localCount) {
            return local.groups().get(index).name();
        }
        return remotes.groups().get(index - localCount).name();
    }

    /** 一行里放得下的字符串，尾巴截掉而不是画到框外面。 */
    private static String trim(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int cut = text.length();
        while (cut > 1 && font.width(text.substring(0, cut) + ellipsis) > width) {
            cut--;
        }
        return text.substring(0, cut) + ellipsis;
    }
}
