package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.net.PlaceOrderC2S;
import dev.distantstock.net.SetAddressC2S;
import dev.distantstock.net.JoinNetworkC2S;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Create Stock Keeper / Mobile Packages 便携仓管同一套竖窗：
 * HEADER + 可叠 BODY + FOOTER，贴图走 create:textures/gui/stock_keeper.png。
 */
public final class RequesterScreen extends AbstractContainerScreen<RequesterMenu> {
    private static final int WINDOW_W = 226;
    private static final int COLS = 9;
    private static final int CELL = 20;
    private static final int SLOT = 18;
    private static final int CART_MAX = 9;
    private static final int TITLE = 0x714A40;
    private static final int INK = 0x4A2D31;
    private static final int SEND = 0x252525;
    private static final int PAPER = 0xF8F8EC;
    /**
     * 物品区那一片的加深。
     *
     * <p>窗口底纸是给标签和输入框的浅色，铺满一屏图标时格与格、格与纸的边界全都糊在一起，
     * 玩家一眼看不出"哪一块是能拿东西的"。半透明压在底纸之上、格子之下，图标本身不受影响。
     */
    private static final int LIST_SHADE = 0x3A3A2A1E;
    private static final int HINT = 0xCDBCA8;

    private EditBox search;
    private EditBox address;
    /**
     * 货回到本端以后要穿的地址。
     *
     * <p>终端上这一个和下面那个是一对：下面那个是对面分拣用的（包裹就是在那边被认领的），
     * 这个是过海以后本端分拣用的。两边的门牌经常不是同一个名字，所以必须分开填。
     */
    private EditBox homeAddress;
    private EditBox receivingGroup;
    private net.minecraft.client.gui.components.Button renameButton;
    private final List<CartLine> cart = new ArrayList<>();
    /** The last name sent to the server, so closing a screen the player did not edit sends nothing. */
    private String committedGroup = "";
    private int scroll;
    private int emptyTicks;
    private int successTicks;
    private boolean opened;

    public RequesterScreen(RequesterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = WINDOW_W;
        this.imageHeight = CreateSheets.HEADER.h + CreateSheets.FOOTER.h + CreateSheets.BODY.h * 8;
        this.inventoryLabelY = 10000;
        this.titleLabelY = 10000;
    }

    @Override
    protected void init() {
        imageWidth = WINDOW_W;
        imageHeight = computeHeight();
        super.init();

        String keepSearch = search == null ? "" : search.getValue();
        String keepAddr = address == null ? menu.address(minecraft.player) : address.getValue();
        String keepGroup = receivingGroup == null || receivingGroup.getValue().isBlank()
                ? defaultGroupName() : receivingGroup.getValue();

        search = new EditBox(font, leftPos + 71, topPos + 22, 100, 9,
                Component.translatable("create.gui.stock_keeper.search_items"));
        search.setMaxLength(50);
        search.setBordered(false);
        search.setTextColor(INK);
        search.setValue(keepSearch);
        search.setResponder(v -> scroll = 0);
        addWidget(search);

        address = new EditBox(font, leftPos + 27, topPos + imageHeight - 36, 92, 10,
                Component.translatable("create.gui.stock_keeper.package_address"));
        address.setBordered(false);
        address.setMaxLength(40);
        address.setTextColor(0x714A40);
        address.setValue(keepAddr);
        address.setResponder(v -> sendAddresses());
        addRenderableWidget(address);



        // The field stops short of the plate's right edge so the rename button beside it has
        // somewhere to sit: it used to hang twenty pixels off the end of the artwork, which is
        // what "突兀且错位" was about.
        int groupField = Math.max(60, imageWidth - 144);

        // 本端地址：和收货港组同一套版式的一行，紧挨在它上面。放在这里是因为它和港组回答的是
        // 同一个问题（货落在哪一边），而底下那个地址回答的是另一个（包裹在对面被谁认领）。
        String keepHome = homeAddress == null ? menu.homeAddress(minecraft.player) : homeAddress.getValue();
        homeAddress = new EditBox(font, leftPos + 82, topPos + imageHeight - 119, groupField - 2, 10,
                Component.translatable("gui.distantstock.route.home"));
        homeAddress.setBordered(false);
        homeAddress.setTextColor(INK);
        homeAddress.setMaxLength(40);
        homeAddress.setValue(keepHome == null ? "" : keepHome);
        homeAddress.setResponder(v -> sendAddresses());
        addRenderableWidget(homeAddress);
        receivingGroup = new EditBox(font, leftPos + 82, topPos + imageHeight - 87,
                groupField, 10,
                Component.translatable("gui.distantstock.route.group"));
        receivingGroup.setBordered(false);
        receivingGroup.setTextColor(INK);
        // Editable, and it starts on what this requester already carries. Typing a name nobody has
        // used makes that system; typing one that exists points at it. One field for both, because
        // the design has the player never see a UUID and there is nothing else to type.
        receivingGroup.setMaxLength(dev.distantstock.routing.DockGroup.MAX_NAME_LENGTH);
        // Renaming needs a gesture of its own. Typing a new name and pressing Enter means "point at
        // this", and pointing at a name nobody has used makes a new system — so without a button,
        // renaming one is not reachable at all: it would quietly make a second system instead.
        renameButton = addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(net.minecraft.network.chat.Component.translatable("gui.distantstock.group.rename"),
                        b -> commitDockGroup(dev.distantstock.net.SetDockGroupC2S.RENAME))
                .bounds(leftPos + groupField + 84, topPos + this.imageHeight - 89, 28, 14).build());
        // A small arrow inside the right end of the field, which opens the list of systems. Clicking
        // the field itself still works the old way — this is for the player who would not think to.
        addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(Component.literal("▾"), b -> {
                    // 箭头就是开关键：翻转它，并且跟着聚焦/失焦输入框。
                    //
                    // 它以前只翻标志、不碰焦点，而列表的显示条件是「有焦点 或 标志」——
                    // 点一下输入框之后标志再怎么翻都被焦点盖住，箭头看上去就是个摆设。
                    groupPickerOpen = !groupPickerOpen;
                    receivingGroup.setFocused(groupPickerOpen);
                })
                .bounds(leftPos + 82 + groupField - 12, topPos + imageHeight - 88, 12, 12).build());
        receivingGroup.setValue(keepGroup);
        // Remember what was put in the box, so closing an untouched screen sends nothing. Without
        // this the field's contents were compared against an empty string, so every close looked
        // like an edit — and on a language where the default group's displayed name is not the
        // name the server stores, that "edit" made a new empty system every single time.
        committedGroup = keepGroup;
        receivingGroup.setResponder(ignore -> {
        });
        addRenderableWidget(receivingGroup);

        if (!opened) {
            opened = true;
            uiSound(SoundEvents.WOOD_HIT, 0.5f, 1.5f);
            uiSound(SoundEvents.BOOK_PAGE_TURN, 1f, 1f);
            // 列表是服务器推的，而推的那一次可能和界面创建抢跑；抢输了这一份就永远缺着，
            // 表现是"下拉点开是空的"。开界面时主动要一次，比赌它送到便宜。
            PacketDistributor.sendToServer(new dev.distantstock.net.SetDockGroupC2S(
                    "", dev.distantstock.net.SetDockGroupC2S.REFRESH));
        }
    }

    /**
     * Sends both addresses to the server, whichever one was just typed in.
     *
     * <p>Together because they are one record on the item: sending only the edited field would make
     * each keystroke a claim about the other one, and the claim would be whatever this screen last
     * saw rather than what the server holds.
     */
    private void sendAddresses() {
        if (address == null || homeAddress == null) {
            return;
        }
        PacketDistributor.sendToServer(new SetAddressC2S(address.getValue(), homeAddress.getValue()));
    }

    /**
     * What to put in the group field before the player has typed anything.
     *
     * <p>The name the requester carries, and **空 when it carries none**.
     *
     * <p>以前这里回退到默认系统的名字。玩家 2026-09-18 拍板把这条路封了：默认组不是一间仓库，是
     * "还没加入任何组"那个占位，选中它等于没选 ——「默认港组容易出事，发的东西都进虚空了」。填一个
     * 名字进去，玩家就会以为已经选好了。空着配一句"必须新建或加入一个组"，说的才是实话。
     */
    private String defaultGroupName() {
        var menu = this.getMenu();
        var stack = menu.device(this.minecraft == null ? null : this.minecraft.player);
        if (!menu.isGauge() && stack != null && !stack.isEmpty()) {
            var carried = dev.distantstock.item.RequesterData.receivingGroupName(stack);
            if (carried.isPresent()) {
                return carried.get();
            }
        }
        // A requester made before names were stored carries an id and nothing else. The list the
        // server just sent has the name for that id, so the field can show the truth.
        if (groups != null && groups.carried() != null) {
            for (var entry : groups.groups()) {
                if (entry.id().equals(groups.carried())) {
                    return entry.name();
                }
            }
        }
        return "";
    }

    /**
     * Sends the field to the server when the player is done with it.
     *
     * <p>On Enter and on close, not on every keystroke: the field is a name, and the server turns an
     * unknown name into a new group. Committing per key would leave a trail of systems called "甲",
     * "甲站", "甲站二".
     */
    private void commitDockGroup(int action) {
        if (minecraft == null || minecraft.player == null || receivingGroup == null) {
            return;
        }
        String name = receivingGroup.getValue().trim();
        if (name.equals(committedGroup)) {
            return;
        }
        committedGroup = name;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new dev.distantstock.net.SetDockGroupC2S(name, action));
    }

    @Override
    public void onClose() {
        commitDockGroup(dev.distantstock.net.SetDockGroupC2S.SELECT);
        super.onClose();
    }

    /** The list the server last sent: what this player may point the requester at. */
    private dev.distantstock.net.DockGroupsS2C groups;
    /** The other half of the same list: destinations on servers this one has been let into. */
    private dev.distantstock.net.RemoteGroupsS2C remotes;
    /**
     * The system whose row is asking to be deleted, or null.
     *
     * <p>Held on the client and nowhere else: it is a question, not a state the server has any use
     * for, and a row that stopped asking when the screen closed is the behaviour wanted.
     */
    private String pendingDelete;
    /**
     * The remote destination whose row is asking to be forgotten, or null.
     *
     * <p>A separate field from {@link #pendingDelete} because the two are different acts on rows
     * that sit side by side: deleting a system is done by its owner and takes the system with it,
     * while forgetting a destination only drops this server's memory of one it never owned. A
     * mis-click that ran the wrong one would be the worst of both.
     */
    private String pendingForget;

    /**
     * How far the network list is scrolled.
     *
     * <p>Without it the list simply stopped at the last row that fitted, and a server with a few
     * networks of its own pushed every one of the other server's off the bottom — which is exactly
     * the complaint: "the terminal only shows my own server's networks". The rows were there, below
     * the fold, with no way to reach them.
     */
    private int netScroll;

    /**
     * Whether the system list is being shown because the arrow beside the field was clicked.
     *
     * <p>The list is the answer to "which systems are there", so it must not depend on the player
     * first discovering that the field is editable.
     */
    private boolean groupPickerOpen;

    public void applyRemoteGroups(dev.distantstock.net.RemoteGroupsS2C next) {
        remotes = next;
    }

    /**
     * Where the open list starts, which is above the field rather than below it.
     *
     * <p>Below is where it used to be, and that is exactly where the address box lives — the list
     * covered the field the player had just filled in, which reads as the screen losing its own
     * contents. Above it the list covers the item grid instead, which is what a dropdown is
     * supposed to do.
     */
    private int dropdownY(int rows) {
        return topPos + this.imageHeight - 89 - rows * DROPDOWN_ROW_H;
    }

    /**
     * 列表的行高。
     *
     * <p>原来是 10：字高 9 像素（中文字形更是顶到边），两行之间只剩 1 像素 —— 玩家说的是
     * 「选项栏和输入框字体重合」，看着像两行叠在一起。12 像素给出行距，中文也读得清。
     * 画、点、算高度都用这一个数，改一处不会漏另一处。
     */
    private static final int DROPDOWN_ROW_H = 12;

    /**
     * How many rows the open list has.
     *
     * <p>It used to be one more than this: the last row minted a pairing code for the group named
     * in the field. With the codes gone the row had nothing left to do, and a row that says
     * nothing is worse than a row that is not there — the destinations are the whole list now.
     */
    private int dropdownRows() {
        return Math.min(groupRows(), 6);
    }

    private int groupRows() {
        int local = groups == null ? 0 : groups.groups().size();
        int remote = remotes == null ? 0 : remotes.groups().size();
        return local + remote;
    }

    /** Which row of the open dropdown, if any, is under the mouse. */
    private boolean dropdownHit(double mx, double my, int index, int x, int y, int w) {
        return mx >= x && mx < x + w && my >= y + index * DROPDOWN_ROW_H
                && my < y + index * DROPDOWN_ROW_H + DROPDOWN_ROW_H;
    }

    /**
     * 列表行右边三格的坐标，画和点共用。
     *
     * <p>共用是重点。以前字用一个偏移画、命中区用另一个偏移算，两者差了几像素，于是「网络…」右边
     * 那半个字落在"锁"的格子里：点它只会把小圆点换个颜色，玩家看到的就是"这个按钮没反应"。
     */
    private static final int NET_BUTTON_W = 34;
    private static final int LOCK_BUTTON_W = 12;
    private static final int DELETE_BUTTON_W = 11;

    private static int deleteButtonX(int x, int w) {
        return x + w - DELETE_BUTTON_W;
    }

    private static int lockButtonX(int x, int w) {
        return deleteButtonX(x, w) - LOCK_BUTTON_W;
    }

    private static int netButtonX(int x, int w) {
        return lockButtonX(x, w) - NET_BUTTON_W;
    }

    /** 行内的小按钮：悬停时提亮一格，没有别的装饰。命中区就是这一格。 */
    private void drawRowButton(GuiGraphics g, int x, int y, int w, String label,
                               int mouseX, int mouseY, int colour) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 10;
        if (over) {
            g.fill(x, y, x + w, y + 10, 0x33FFFFFF);
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 1, colour, false);
    }

    public void applyGroups(dev.distantstock.net.DockGroupsS2C next) {
        groups = next;
        // The list is sent while the screen opens and can land after init(), so the field may be
        // showing the default system's name for a requester that is pointed somewhere else — a desk
        // always is, because its group is only knowable from this list. Naming it here is a display
        // change only: committedGroup moves with the text so that closing an untouched screen still
        // sends nothing, which is what keeps a displayed name from becoming a new system.
        if (receivingGroup == null || receivingGroup.isFocused() || next.carried() == null) {
            return;
        }
        if (!receivingGroup.getValue().trim().equals(committedGroup)) {
            return;
        }
        for (var entry : next.groups()) {
            if (entry.id().equals(next.carried())) {
                receivingGroup.setValue(entry.name());
                committedGroup = entry.name();
                return;
            }
        }
    }

    /**
     * The systems on offer, drawn over whatever is under them and only while the field has focus.
     *
     * <p>A dropdown rather than a permanent panel: the screen's fixed artwork has no room for a
     * list, and the list is only wanted at the moment somebody is choosing. Drawing it only when
     * focused also means a player who knows the name can keep ignoring it.
     */
    private void drawGroupList(GuiGraphics g, int mouseX, int mouseY) {
        // 开着就是开着：只看 groupPickerOpen 这一个标志。
        //
        // 以前的条件是「输入框有焦点 **或** 箭头被点过」，于是出现两个说不通的现象：
        // 输入框一旦有焦点（点它、点箭头都会给焦点），箭头就变成了摆设 —— 它翻转的那个标志被
        // "有焦点"盖住了，点什么都没变化；而反过来，想关掉列表也没有路，因为焦点还在。
        // 现在焦点只决定"打字打到哪儿"，开与关只有这一个标志，箭头和点击外面都改它。
        if (groups == null || receivingGroup == null || !groupPickerOpen) {
            return;
        }
        int x = leftPos + 82;
        int y = dropdownY(dropdownRows());
        int w = 112;
        int rows = dropdownRows();
        if (rows == 0) {
            return;
        }
        // 不透明的一整块 + 边框。底下的输入框和标签比它先画，所以这一块真正压住它们 ——
        // 玩家报的"下拉挡不住主页面的字"就是这里看着像两张纸叠在一起。
        g.fill(x - 2, y - 2, x + w + 2, y + rows * DROPDOWN_ROW_H + 2, 0xFF16242A);
        g.fill(x - 1, y - 1, x + w + 1, y + rows * DROPDOWN_ROW_H + 1, 0xFF24343A);
        int local = groups.groups().size();
        for (int i = 0; i < rows; i++) {
            boolean over = dropdownHit(mouseX, mouseY, i, x, y, w);
            int rowY = y + i * DROPDOWN_ROW_H;
            if (i < local) {
                var entry = groups.groups().get(i);
                boolean doomed = entry.name().equals(pendingDelete);
                g.fill(x, rowY, x + w, rowY + 10,
                        doomed ? 0xFF6B2A2A : over ? 0xFF3E5A61 : 0xFF2E444B);
                // The carried one is marked so the field's value and the list agree at a glance.
                boolean here = groups.carried() != null && groups.carried().equals(entry.id());
                // 组主写在名字后面。名单上原本只有一个组名，玩家看到一排「仓库」根本认不出哪一个
                // 是自己的、哪一个是别人的 —— 「到现在了我还是不知道谁是谁」说的就是这一行。
                String left = doomed
                        ? Component.translatable("gui.distantstock.group.delete_confirm").getString()
                        : entry.mine() || entry.owner().isEmpty()
                                ? entry.name()
                                : entry.name() + " · " + entry.owner();
                g.drawString(font, trim(left, netButtonX(x, w) - x - 6), x + 3, rowY + 1,
                        doomed ? 0xFFFFD0D0 : here ? 0xFF9BE0C4 : INK, false);
                // 三个小按钮各自一格，画的和点的是同一组坐标（netButtonX / lockButtonX /
                // deleteButtonX）。以前这里的字是手写偏移、命中区也是手写偏移，两者错开了几像素：
                // 「网络…」右边那半个字落在"锁"的命中区里，点它只会把小圆点换个颜色 —— 从玩家那边看
                // 就是"这个按钮没反应"。同一份坐标就不可能出现这种错。
                drawRowButton(g, netButtonX(x, w), rowY, NET_BUTTON_W,
                        Component.translatable("gui.distantstock.net.button").getString(),
                        mouseX, mouseY, 0xFF9BC8D8);
                if (entry.admitted()) {
                    String count = Integer.toString(entry.docks());
                    g.drawString(font, count, netButtonX(x, w) - 4 - font.width(count), rowY + 1,
                            HINT, false);
                } else {
                    // 没加入的开放网络：整行点下去＝加入并选中，这里先把这件事写出来。
                    String tag = Component.translatable("gui.distantstock.net.join_short").getString();
                    g.drawString(font, tag, netButtonX(x, w) - 4 - font.width(tag), rowY + 1,
                            0xFF7FD8E8, false);
                }
                if (entry.mine()) {
                    // 组主才有的两个：锁，和删掉它。别人的行上没有东西可以上锁或删掉。
                    drawRowButton(g, lockButtonX(x, w), rowY, LOCK_BUTTON_W,
                            entry.open() ? "○" : "●", mouseX, mouseY,
                            entry.open() ? HINT : 0xFFC0A090);
                    drawRowButton(g, deleteButtonX(x, w), rowY, DELETE_BUTTON_W, "×",
                            mouseX, mouseY, doomed ? 0xFFFFD0D0 : 0xFFC0A090);
                }
            } else {
                // A destination on another server, drawn in the same list because it is chosen the
                // same way. The label is what the player has to identify it by: the group's name is
                // written by whoever owns it over there, and two servers can both have a "仓库".
                var entry = remotes.groups().get(i - local);
                boolean doomed = entry.group().toString().equals(pendingForget);
                g.fill(x, rowY, x + w, rowY + 10,
                        doomed ? 0xFF4A2A4A : over ? 0xFF3E5A61 : 0xFF2E444B);
                // The same "×" as an owned row has, and it means the smaller thing: this server
                // stops showing a destination it never owned. Nothing on the other server changes,
                // and the group keeps working for whoever else was let into it.
                g.drawString(font, "×", x + w - 8, rowY + 1, doomed ? 0xFFFFD0D0 : 0xFFC0A090, false);
                boolean here = receivingGroup.getValue().trim().equalsIgnoreCase(entry.name());
                // 对面没让你用的时候整行是灰的。这一条以前根本没有：名单只在那一台服务器里判过，
                // 而且只在指着港的时候判 —— 跨服下的单上没有玩家，对面无从判起，于是"不是我家的仓库"
                // 一直能被选中、发出一张对方照收、然后什么都不会发生的订单。
                int colour = !entry.admitted() ? 0xFF8A8090
                        : doomed ? 0xFFFFD0D0 : here ? 0xFF9BE0C4 : 0xFFC8B6E8;
                // 港数写在名字前面。0 个港＝这个目的地没有门可以进，说在点下去之前。
                String count = entry.docks() > 0 ? Integer.toString(entry.docks())
                        : Component.translatable("gui.distantstock.group.no_dock").getString();
                g.drawString(font, count, x + 3, rowY + 1,
                        entry.docks() > 0 ? HINT : 0xFFC08080, false);
                g.drawString(font, trim(entry.display(), w - 20 - font.width(count)),
                        x + 7 + font.width(count), rowY + 1, colour, false);
            }
        }
    }

    /** A string that fits a row, with the end cut off rather than drawn over the edge. */
    private String trim(String text, int width) {
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

    private boolean groupListClick(double mx, double my) {
        if (groups == null || receivingGroup == null || !groupPickerOpen) {
            return false;
        }
        int x = leftPos + 82;
        int y = dropdownY(dropdownRows());
        int w = 112;
        int rows = dropdownRows();
        int destinations = rows - 1;
        int local = groups.groups().size();
        for (int i = 0; i < rows; i++) {
            if (!dropdownHit(mx, my, i, x, y, w)) {
                continue;
            }
            if (i >= local) {
                // Picking a remote destination. The field carries the group's plain name and the
                // server matches it against what its peers announced — the id never has to reach
                // the client, and nothing here could check it if it did.
                var remote = remotes.groups().get(i - local);
                if (mx >= x + w - 10) {
                    // Two clicks, the same as deleting a system: the first asks, the second hides
                    // this server's copy of the destination. The far side is untouched and will
                    // offer it again the moment anything about it changes.
                    if (remote.group().toString().equals(pendingForget)) {
                        pendingForget = null;
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new dev.distantstock.net.ForgetRemoteGroupC2S(remote.group()));
                    } else {
                        pendingForget = remote.group().toString();
                    }
                    return true;
                }
                pendingForget = null;
                if (!remote.admitted()) {
                    // 对面没让这个人用这个组。选它只会得到一张对面照收、然后没人认领的订单 —— 对面
                    // 判不了这件事（订单上没有玩家），所以判据是从公告带过来的那份名单，只能在这边用。
                    // 行留着，因为"昨天还能用、今天不能了"正是本人最需要看见的那句话。
                    minecraft.player.displayClientMessage(Component.translatable(
                            "gui.distantstock.group.not_admitted", remote.name()), true);
                    return true;
                }
                // 写进去的是「哪台服务器 · 组名」而不是光秃秃的组名。这个框决定货从哪台服务器的哪个港
                // 出来：本服的组＝货回来，对岸的组＝货留对面，两种名字长得一样、只有颜色不同，
                // 选错了要到货出来才发现。把服务器名写进框里，选的是什么就一直看得见。
                String name = remote.display();
                receivingGroup.setValue(name);
                committedGroup = name;
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.SetDockGroupC2S(name,
                                dev.distantstock.net.SetDockGroupC2S.SELECT));
                receivingGroup.setFocused(false);
                return true;
            }
            var entry = groups.groups().get(i);
            // The two dials an owner has on their own row. The delete is the one action on this
            // screen that loses something, so it takes two clicks: the first turns the row red and
            // asks, the second does it. The command has asked the same question since it shipped;
            // this is the same question in the place the player actually is.
            int deleteX = deleteButtonX(x, w);
            int lockX = lockButtonX(x, w);
            int netX = netButtonX(x, w);
            if (entry.mine() && mx >= deleteX) {
                if (entry.name().equals(pendingDelete)) {
                    pendingDelete = null;
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new dev.distantstock.net.SetDockGroupC2S(entry.name(),
                                    dev.distantstock.net.SetDockGroupC2S.DELETE));
                } else {
                    pendingDelete = entry.name();
                }
                return true;
            }
            pendingDelete = null;
            pendingForget = null;
            if (entry.mine() && mx >= lockX && mx < deleteX) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.SetDockGroupC2S(entry.name(),
                                dev.distantstock.net.SetDockGroupC2S.TOGGLE_OPEN));
                return true;
            }
            if (mx >= netX && mx < lockX) {
                openGroup(entry);
                return true;
            }
            receivingGroup.setValue(entry.name());
            committedGroup = entry.name();
            if (!entry.admitted()) {
                // 没加入的网络：点它＝加入它，并且选它。这就是运输蜂停泊港的「添加你自己」，
                // 只是发生在你想用它的时候 —— 一个开着却没人在的网络，本来就是在等这句话。
                //
                // 两个包按顺序发：服务器按收到的顺序处理，加入先落地，后面那个选中才认得这个组。
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.GroupMemberC2S(entry.name(), "",
                                dev.distantstock.net.GroupMemberC2S.JOIN));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.distantstock.net.SetDockGroupC2S(entry.name(),
                            dev.distantstock.net.SetDockGroupC2S.SELECT));
            receivingGroup.setFocused(false);
            return true;
        }
        return false;
    }

    /**
     * Opens the page for a group, in place of this screen.
     *
     * <p>It used to be an overlay drawn on top of the list: 136 pixels wide, with room for one name
     * and one field. Everything that screen is for — who else may use this, add somebody, join,
     * leave, lock it — did not fit, so half of it did not exist and the rest was one row tall. It is
     * a screen of its own now, shaped like the 运输蜂停泊港's network page, and this is the door to it.
     */
    private void openGroup(dev.distantstock.net.DockGroupsS2C.Entry entry) {
        groupPickerOpen = false;
        pendingDelete = null;
        pendingForget = null;
        if (receivingGroup != null) {
            receivingGroup.setFocused(false);
        }
        minecraft.setScreen(new DockGroupScreen(this, entry));
    }

    /**
     * The group the requester carries, or the default when it carries none.
     *
     * <p>Read from the item first and from the synced list second: an older requester holds an id
     * with no name, and the list the server sent is what knows the name for it.
     */
    private java.util.UUID carriedGroupId() {
        if (minecraft == null || minecraft.player == null) {
            return dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID;
        }
        // A desk's group lives in the block, and the list the server sent is the only thing on the
        // client that knows it. Reading the held item here would answer with the group of whatever
        // the player happens to be carrying — including a different requester.
        if (!getMenu().isGauge()) {
            ItemStack stack = getMenu().device(minecraft.player);
            if (stack != null && !stack.isEmpty()) {
                var carried = dev.distantstock.item.RequesterData.receivingGroup(stack);
                if (carried.isPresent()) {
                    return carried.get();
                }
            }
        }
        if (groups != null && groups.carried() != null) {
            return groups.carried();
        }
        return dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID;
    }

    private int computeHeight() {
        int header = CreateSheets.HEADER.h;
        int footer = CreateSheets.FOOTER.h;
        int body = CreateSheets.BODY.h;
        int max = Math.max(header + footer + body * 4, this.height - 10);
        max -= Mth.positiveModulo(max - header - footer, body);
        return Math.min(max, header + footer + body * 17);
    }

    private int itemsX() {
        return leftPos + (WINDOW_W - COLS * CELL) / 2 + 1;
    }

    private int itemsY() {
        return topPos + 33;
    }

    private int orderY() {
        return topPos + imageHeight - 72;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (filtered().isEmpty()) {
            emptyTicks++;
        } else {
            emptyTicks = 0;
        }
        if (successTicks > 0 && cart.isEmpty()) {
            successTicks++;
        } else if (!cart.isEmpty()) {
            successTicks = 0;
        }
    }

    private List<StockCache.Entry> filtered() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<StockCache.Entry> all = menu.stock;
        if (q.isEmpty()) {
            return all;
        }
        List<StockCache.Entry> out = new ArrayList<>();
        for (StockCache.Entry e : all) {
            if (e.itemId.toLowerCase(Locale.ROOT).contains(q)
                    || e.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    private int visibleRows() {
        return Math.max(1, (topPos + imageHeight - 132 - itemsY()) / CELL);
    }

    private int maxScroll() {
        int rows = Math.max(0, (filtered().size() + COLS - 1) / COLS);
        return Math.max(0, rows - visibleRows());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        boolean tuned = menu.tuned(minecraft.player);
        search.setVisible(tuned);
        address.setVisible(tuned);
        homeAddress.setVisible(tuned);
        receivingGroup.setVisible(tuned);
        if (renameButton != null) {
            renameButton.visible = tuned;
        }
        renderBackground(g, mouseX, mouseY, partial);
        super.render(g, mouseX, mouseY, partial);
        // After everything, because a dropdown that the widgets behind it paint over is not one.
        drawGroupList(g, mouseX, mouseY);
        ItemStack hover = hoveredStock(mouseX, mouseY);
        if (hover.isEmpty()) {
            hover = hoveredCart(mouseX, mouseY);
        }
        if (!hover.isEmpty()) {
            g.renderTooltip(font, hover, mouseX, mouseY);
        } else if (address.getValue().isBlank() && !address.isFocused() && address.isHovered()) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("create.gui.factory_panel.restocker_address"),
                    Component.translatable("create.gui.schedule.lmb_edit")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
            ), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        CreateSheets.HEADER.render(g, x - 15, y);
        int by = y + CreateSheets.HEADER.h;
        int bodyTiles = (imageHeight - CreateSheets.HEADER.h - CreateSheets.FOOTER.h) / CreateSheets.BODY.h;
        for (int i = 0; i < bodyTiles; i++) {
            CreateSheets.BODY.render(g, x - 15, by);
            by += CreateSheets.BODY.h;
        }
        CreateSheets.FOOTER.render(g, x - 15, by);

        Component title = Component.translatable("gui.distantstock.title");
        g.drawString(font, title, x + WINDOW_W / 2 - font.width(title) / 2, y + 4, TITLE, false);
        if (!menu.tuned(minecraft.player)) {
            renderNetworks(g, x, y);
            return;
        }

        // 本端地址那一行：和收货港组同一套版式、同一个位置关系，因为它和港组回答的是同一类
        // 问题 —— 出的货在「这一侧」落在哪。底下那个地址回答的是另一类问题 —— 包裹在对面被谁认领。
        renderRouteRow(g, imageHeight - 130, "gui.distantstock.route.home");
        int homeX = homeAddress.getX();
        int homeY = homeAddress.getY();
        g.fill(homeX - 3, homeY - 2, homeX + homeAddress.getWidth() + 3,
                homeY + homeAddress.getHeight() + 2, homeAddress.isFocused() ? 0x66FFFFFF : 0x33000000);
        g.fill(homeX - 3, homeY + homeAddress.getHeight() + 2,
                homeX + homeAddress.getWidth() + 3, homeY + homeAddress.getHeight() + 3,
                homeAddress.isFocused() ? 0xFFFFFFFF : 0xAA8A7250);
        if (homeAddress.getValue().isBlank() && !homeAddress.isFocused()) {
            // 留空是常事（货不过海，或者两边门牌正好同名），所以空格子里写的是留空的后果，
            // 不是一个像必填项一样的标签。
            g.drawString(font, Component.translatable("gui.distantstock.route.home.hint")
                    .withStyle(ChatFormatting.ITALIC), homeX, homeY, HINT, false);
        }

        renderRouteRow(g, imageHeight - 98, "gui.distantstock.route.group");
        // The group field has no border of its own — it is drawn straight onto the brass plate so it
        // reads as one line of a label — and the player who needed it most walked straight past it:
        // "I did not notice there was a receiving group in the terminal". A sunken strip and an
        // underline cost nothing and say "this part is editable".
        int fieldX = receivingGroup.getX();
        int fieldY = receivingGroup.getY();
        g.fill(fieldX - 3, fieldY - 2, fieldX + receivingGroup.getWidth() + 3,
                fieldY + receivingGroup.getHeight() + 2, receivingGroup.isFocused() ? 0x66FFFFFF : 0x33000000);
        g.fill(fieldX - 3, fieldY + receivingGroup.getHeight() + 2,
                fieldX + receivingGroup.getWidth() + 3, fieldY + receivingGroup.getHeight() + 3,
                receivingGroup.isFocused() ? 0xFFFFFFFF : 0xAA8A7250);

        if (address.getValue().isBlank() && !address.isFocused()) {
            g.drawString(font, Component.translatable("gui.distantstock.route.remote")
                    .withStyle(ChatFormatting.ITALIC), address.getX(), address.getY(), HINT, false);
        }
        if (receivingGroup.getValue().isBlank() && !receivingGroup.isFocused()) {
            // 空的这一格现在是真的"没选"，而没选就发不出去（默认组那条路 2026-09-18 关了）。所以
            // 空格子里写的不是"随便挑"，是"必须选一个"。
            g.drawString(font, Component.translatable("gui.distantstock.route.group.required")
                    .withStyle(ChatFormatting.ITALIC), receivingGroup.getX(), receivingGroup.getY(),
                    HINT, false);
        }

        PoseStack ms = g.pose();
        ms.pushPose();
        ms.translate(x - 50, y + imageHeight - 70, -100);
        ms.scale(3.5f, 3.5f, 3.5f);
        g.renderItem(menu.device(minecraft.player), 0, 0);
        ms.popPose();

        int ix = itemsX();
        int iy = itemsY();
        int oy = orderY();
        for (int i = 0; i < Math.min(cart.size(), CART_MAX); i++) {
            CartLine line = cart.get(i);
            ms.pushPose();
            ms.translate(ix + i * CELL, oy, 0);
            renderEntry(g, line.stack, line.count, i == cartIndex(mouseX, mouseY));
            ms.popPose();
        }

        boolean justSent = cart.isEmpty() && successTicks > 0;
        if (isConfirmHovered(mouseX, mouseY) && !justSent) {
            CreateSheets.SEND_HOVER.render(g, x + WINDOW_W - 81, y + imageHeight - 41);
        }
        Component send = Component.translatable("create.gui.stock_keeper.send");
        if (justSent) {
            float alpha = Mth.clamp((successTicks + partial - 5f) / 5f, 0f, 1f);
            ms.pushPose();
            ms.translate(alpha * alpha * 50, 0, 0);
            if (successTicks < 10) {
                g.drawString(font, send, x + WINDOW_W - 42 - font.width(send) / 2, y + imageHeight - 35,
                        withAlpha(SEND, 1 - alpha * alpha), false);
            }
            ms.popPose();
            Component msg = Component.translatable("create.gui.stock_keeper.request_sent");
            float banner = Mth.clamp((successTicks + partial - 10f) / 5f, 0f, 1f);
            if (banner > 0) {
                int msgX = x + WINDOW_W / 2 - (font.width(msg) + 10) / 2;
                int msgY = oy + 5;
                int w = font.width(msg) + 14;
                CreateSheets.BANNER_L.render(g, msgX - 8, msgY - 4);
                CreateSheets.BANNER_M.stretchX(g, msgX, msgY - 4, w);
                CreateSheets.BANNER_R.render(g, msgX + font.width(msg) + 10, msgY - 4);
                g.drawString(font, msg, msgX + 5, msgY, withAlpha(0x8C5D4B, banner), false);
            }
        } else {
            g.drawString(font, send, x + WINDOW_W - 42 - font.width(send) / 2, y + imageHeight - 35, SEND, false);
        }

        int clipTop = y + 17;
        int clipBot = y + imageHeight - 132;
        g.enableScissor(x + 16, clipTop, x + 205, clipBot);

        scroll = Mth.clamp(scroll, 0, maxScroll());
        List<StockCache.Entry> list = filtered();
        for (int slice = -2; slice < maxScroll() * CELL + imageHeight - 72; slice += CreateSheets.BG.h) {
            CreateSheets.BG.render(g, x + 22, y + slice + 18 - scroll * CELL);
        }
        // 物品区比窗口底纸深一档：底纸是给标签和输入框用的浅色，而这一整片格子铺在上面时，
        // 格与格、格与纸之间的边界几乎看不出来，一屏图标糊成一片。深色压在底纸之上、格子之下，
        // 于是"这里是可以拿东西的地方"一眼就分得出来。
        g.fill(x + 16, clipTop, x + 205, clipBot, LIST_SHADE);

        CreateSheets.SEARCH.render(g, x + 42, search.getY() - 5);
        search.render(g, mouseX, mouseY, partial);
        if (search.getValue().isBlank() && !search.isFocused()) {
            g.drawString(font, search.getMessage(),
                    x + WINDOW_W / 2 - font.width(search.getMessage()) / 2, search.getY(), INK, false);
        }

        if (list.isEmpty()) {
            float alpha = Mth.clamp((emptyTicks - 10f) / 5f, 0f, 1f);
            if (alpha > 0) {
                Component msg = trouble();
                List<FormattedCharSequence> lines = font.split(msg, 160);
                for (int i = 0; i < lines.size(); i++) {
                    FormattedCharSequence line = lines.get(i);
                    int lx = x + WINDOW_W / 2 - font.width(line) / 2;
                    int ly = iy + 20 + i * (font.lineHeight + 1);
                    g.drawString(font, line, lx + 1, ly + 1, withAlpha(INK, alpha), false);
                    g.drawString(font, line, lx, ly, withAlpha(PAPER, alpha), false);
                }
            }
        }

        int start = scroll * COLS;
        int cells = COLS * visibleRows();
        for (int i = 0; i < cells && start + i < list.size(); i++) {
            StockCache.Entry e = list.get(start + i);
            int sx = ix + (i % COLS) * CELL;
            int sy = iy + 4 + (i / COLS) * CELL;
            ms.pushPose();
            ms.translate(sx, sy, 0);
            CreateSheets.SLOT.render(g, 0, 0);
            renderEntry(g, e.stack(), e.count,
                    mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT);
            ms.popPose();
        }

        g.disableScissor();

        int windowH = imageHeight - 144;
        int totalH = maxScroll() * CELL + windowH;
        int barSize = Math.max(5, Mth.floor((float) windowH / Math.max(1, totalH) * (windowH - 2)));
        if (maxScroll() > 0 && barSize < windowH - 2) {
            int barX = ix + COLS * CELL;
            int barY = y + 15 + (windowH - 2 - barSize) * scroll / maxScroll();
            CreateSheets.SCROLL_PAD.stretchY(g, barX, barY, barSize);
            CreateSheets.SCROLL_TOP.render(g, barX, barY);
            if (barSize > 16) {
                CreateSheets.SCROLL_MID.render(g, barX, barY + barSize / 2 - 4);
            }
            CreateSheets.SCROLL_BOT.render(g, barX, barY + barSize - 5);
        }
    }

    private Component trouble() {
        if (!menu.tuned(minecraft.player)) {
            return Component.translatable("gui.distantstock.untuned");
        }
        if (menu.stock.isEmpty()) {
            return Component.translatable("create.gui.stock_keeper.inventories_empty");
        }
        return Component.translatable("create.gui.stock_keeper.no_search_results");
    }

    /**
     * One labelled row of the window's lower half: a plate with a label, and a field beside it.
     *
     * <p>The plate is 182 wide at x+22 — the same column as the item slots and the search bar
     * ({@link CreateSheets#BG}). It used to be 194 at x+8, which lined up with nothing: every row of
     * this screen started fourteen pixels to the right of it, and the two rows read as strips cut
     * from somewhere else and laid over the window. Reported as "the textures are all misaligned".
     */
    private void renderRouteRow(GuiGraphics g, int offset, String label) {
        int x = leftPos + ROW_X;
        int y = topPos + offset;
        g.blit(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "distantstock", "textures/gui/route_label.png"), x, y, 0, 0, ROW_W, 26, ROW_W, 26);
        g.drawString(font, Component.translatable(label), x + 16, y + 11, INK, false);
    }

    /** The window's content column: where the item slots, the search bar and these rows all start. */
    private static final int ROW_X = 22;
    /** Its width. See {@link #renderRouteRow}. */
    private static final int ROW_W = 182;

    private void renderEntry(GuiGraphics g, ItemStack stack, int count, boolean hot) {
        PoseStack ms = g.pose();
        ms.pushPose();
        ms.translate((CELL - 18) / 2.0, (CELL - 18) / 2.0, 0);
        ms.translate(9, 9, 0);
        float s = hot ? 1.075f : 1f;
        ms.scale(s, s, s);
        ms.translate(-9, -9, 0);
        g.renderItem(stack, 0, 0);
        ms.popPose();
        ms.pushPose();
        ms.translate(0, 0, 200);
        g.renderItemDecorations(font, stack, 1, 1, compact(count));
        ms.popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    private void renderNetworks(GuiGraphics g, int x, int y) {
        Component heading = Component.translatable("gui.distantstock.networks");
        g.drawString(font, heading, x + WINDOW_W / 2 - font.width(heading) / 2, y + 25, INK, false);
        if (menu.networks.isEmpty()) {
            Component empty = Component.translatable("gui.distantstock.networks.empty");
            for (int i = 0; i < font.split(empty, 164).size(); i++) {
                FormattedCharSequence line = font.split(empty, 164).get(i);
                g.drawString(font, line, x + WINDOW_W / 2 - font.width(line) / 2,
                        y + 54 + i * 11, INK, false);
            }
            return;
        }
        int rows = networkRows();
        netScroll = Mth.clamp(netScroll, 0, Math.max(0, menu.networks.size() - rows));
        int shown = Math.min(menu.networks.size() - netScroll, rows);
        for (int i = 0; i < shown; i++) {
            NetworkDirectory.Entry entry = menu.networks.get(netScroll + i);
            int ry = y + 42 + i * 22;
            // The two servers are drawn apart, because a player has to know which warehouse they
            // are pointing at before they point at it, and both halves send an alias they chose.
            g.fill(x + 25, ry, x + WINDOW_W - 25, ry + 18, entry.local() ? 0x33FFFFFF : 0x33403060);
            g.fill(x + 25, ry + 17, x + WINDOW_W - 25, ry + 18, 0xFFB89C78);
            String label = entry.local() || entry.networkId() == null
                    ? entry.server()
                    : entry.networkId().shortLabel() + "·" + entry.server();
            // 发不出货的网络整行都淡下去。这条是玩家报的那个"下单成功然后石沉大海"：对面服务器有这张
            // 网络、也在公告里报了它，可是对面没有任何一台远仓打包机挂在它下面 —— 订单发过去、
            // 对方收下、然后没有东西会把它打成包裹。两端都没有报错，因为两端都没做错事。
            int faded = entry.packable() ? 0xFF : 0x66;
            g.drawString(font, label, x + 31, ry + 3,
                    blend(entry.local() ? INK : 0xFFC8B6E8, faded), false);
            String id = entry.freq().toString().substring(0, 8);
            g.drawString(font, id, x + 31, ry + 10, blend(0x3A7774, faded), false);
            Component links = entry.packable()
                    ? Component.translatable("gui.distantstock.networks.links", entry.links())
                    : Component.translatable("gui.distantstock.networks.nopacker");
            g.drawString(font, links, x + WINDOW_W - 31 - font.width(links), ry + 6,
                    entry.packable() ? TITLE : 0xFFC08080, false);
        }
        if (menu.networks.size() > rows) {
            Component more = Component.translatable("gui.distantstock.networks.more",
                    netScroll + shown, menu.networks.size());
            g.drawString(font, more, x + WINDOW_W - 27 - font.width(more), y + imageHeight - 118,
                    HINT, false);
        }
    }

    private int networkRows() {
        return Math.max(1, (imageHeight - 96) / 22);
    }

    /**
     * A colour dimmed towards the panel behind it.
     *
     * <p>Used for a network that cannot fulfil an order. The row keeps its place in the list — a
     * player who ordered from it last week needs to find it and see what changed — but stops looking
     * like a network that works.
     */
    private static int blend(int colour, int alpha) {
        int r = (colour >> 16) & 0xFF;
        int g = (colour >> 8) & 0xFF;
        int b = colour & 0xFF;
        int a = alpha & 0xFF;
        // Drawn over the row's own translucent fill, so mixing towards that fill is the approximation
        // that matters; the exact backdrop is not knowable from here.
        return 0xFF000000 | (r * a / 0xFF) << 16 | (g * a / 0xFF) << 8 | (b * a / 0xFF);
    }

    private int networkIndex(double mx, double my) {
        if (menu.tuned(minecraft.player)) {
            return -1;
        }
        int x = leftPos;
        int y = topPos;
        if (mx < x + 25 || mx >= x + WINDOW_W - 25 || my < y + 42) {
            return -1;
        }
        int index = (int) ((my - y - 42) / 22) + netScroll;
        int rowY = y + 42 + (index - netScroll) * 22;
        return index >= 0 && index < menu.networks.size() && index - netScroll < networkRows()
                && my < rowY + 18 ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (groupListClick(mx, my)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            focusTheRowUnder(mx, my);
        }
        int network = networkIndex(mx, my);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && network >= 0) {
            NetworkDirectory.Entry entry = menu.networks.get(network);
            menu.selectedFreq = entry.freq();
            PacketDistributor.sendToServer(new JoinNetworkC2S(entry.freq(), entry.networkId()));
            uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1.1f);
            return true;
        }
        boolean rmb = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (rmb && search.isMouseOver(mx, my)) {
            search.setValue("");
            search.setFocused(true);
            return true;
        }
        if (address.isFocused() && !address.isHovered()) {
            address.setFocused(false);
        }
        if (homeAddress.isFocused() && !homeAddress.isHovered()) {
            homeAddress.setFocused(false);
        }
        if (search.isFocused() && !search.isHovered()) {
            search.setFocused(false);
        }
        if (button == 0 && isConfirmHovered((int) mx, (int) my)) {
            request();
            uiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f);
            return true;
        }
        int cartAt = cartIndex((int) mx, (int) my);
        if (cartAt >= 0) {
            removeCart(cartAt, rmb ? 1 : cart.get(cartAt).count);
            return true;
        }
        ItemStack hit = hoveredStock((int) mx, (int) my);
        if (!hit.isEmpty()) {
            addCart(hit, amount(rmb));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= itemsX() && mx < itemsX() + COLS * CELL && my >= topPos + 16 && my < topPos + imageHeight - 132) {
            scroll = Mth.clamp(scroll - (int) Math.signum(sy), 0, maxScroll());
            return true;
        }
        if (!menu.tuned(minecraft.player) && mx >= leftPos + 25 && mx < leftPos + WINDOW_W - 25
                && my >= topPos + 42) {
            netScroll = Mth.clamp(netScroll - (int) Math.signum(sy), 0,
                    Math.max(0, menu.networks.size() - networkRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Enter is how the group field is committed: the server turns an unknown name into a new
        // system, so sending per keystroke would leave a trail of half-typed ones behind.
        if (receivingGroup != null && receivingGroup.isFocused()
                && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            commitDockGroup(dev.distantstock.net.SetDockGroupC2S.SELECT);
            receivingGroup.setFocused(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && hasShiftDown()) {
            request();
            return true;
        }
        // The field gets the key before the screen does, for every field — an earlier version of
        // this only offered it to the search box, so Backspace and the arrow keys were swallowed by
        // the "while typing" rule below and the name could not be edited at all.
        if (receivingGroup != null && receivingGroup.isFocused()
                && receivingGroup.keyPressed(key, scan, mods)) {
            return true;
        }
        if (address != null && address.isFocused() && address.keyPressed(key, scan, mods)) {
            return true;
        }
        // 本端地址框和上面几个一视同仁。上一版只把它加进了 typing()，没加到这里 —— 于是它有
        // 焦点时退格被下面那条「正在打字就吞掉」的规则吃掉，只能打字不能删字。
        if (homeAddress != null && homeAddress.isFocused()
                && homeAddress.keyPressed(key, scan, mods)) {
            return true;
        }
        if (search != null && search.isFocused() && search.keyPressed(key, scan, mods)) {
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            // Escape closes the list first and the screen second, and it always does one of them.
            // The rule below used to swallow it along with every other key, which left a focused
            // field as a room with no door: the player's report was "escape does not work either".
            if (groupPickerOpen) {
                groupPickerOpen = false;
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        // While one of our fields has focus, every other key is the field's.
        //
        // Vanilla closes a container screen on the inventory key, and letters reach a text field
        // through charTyped rather than keyPressed — so typing an "e" into the search box or the
        // system field closed the screen instead of typing an "e". Vanilla's creative-inventory
        // search box has the same problem and answers it the same way.
        if (typing()) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    /**
     * 点一行就把那一行的输入框聚焦 —— 整行，不只是输入框本身那一条。
     *
     * <p>玩家报的是"三个 UI 不跟手，港组那个点一下不弹窗，得先打一个字再退格"。原因是几何：
     * 每一行的底板有 26 像素高，而里面的输入框只有 10 像素 —— 点在底板上（文字下面、行与行之间、
     * 靠着标签那一侧）什么都不会发生，因为那一击落在 EditBox 之外。三个框都是同一个毛病，
     * 因为三个框画在同一套底板里。
     *
     * <p>港组那一行还额外把列表打开：列表的显示条件原本是"输入框有焦点"，现在点是点、焦点是焦点，
     * 两条路各自都能让它出来。
     *
     * <p>这一击不消费，继续往下传给原版：光标该落在文字的哪个位置还落在哪里。
     */
    private void focusTheRowUnder(double mx, double my) {
        // 点别处就关掉列表。以前只有"点中某一行"才会关，点旁边、点别的输入框都关不掉，
        // 于是那个下拉一旦打开就只能按 ESC 把整个界面退出去了。
        groupPickerOpen = false;
        // 本端地址那一行：底板 -130..-104。
        if (homeAddress != null
                && in(mx, my, ROW_X, ROW_W, imageHeight - 130, 26)) {
            homeAddress.setFocused(true);
            return;
        }
        // 接收港组那一行：底板 -98..-72，输入框在右半边。点它＝把列表打开。
        if (receivingGroup != null
                && in(mx, my, ROW_X, ROW_W, imageHeight - 98, 26)) {
            receivingGroup.setFocused(true);
            groupPickerOpen = true;
            return;
        }
        // 底下那条地址栏：底板比输入框宽一圈。
        if (address != null
                && in(mx, my, 24, 122, imageHeight - 42, 12)) {
            address.setFocused(true);
        }
    }

    /** 方块坐标里的一块矩形，x/宽/顶/高。 */
    private boolean in(double mx, double my, int x, int width, int top, int height) {
        return mx >= leftPos + x && mx < leftPos + x + width
                && my >= topPos + top && my < topPos + top + height;
    }

    /** Whether any of this screen's text fields is taking keys. */
    private boolean typing() {
        return (homeAddress != null && homeAddress.isFocused())
                || (search != null && search.isFocused())
                || (receivingGroup != null && receivingGroup.isFocused())
                || (address != null && address.isFocused());
    }

    private void request() {
        if (cart.isEmpty()) {
            return;
        }
        // The dual-address transport is not implemented yet: never silently discard an entered route.
        List<PlaceOrderC2S.Line> lines = new ArrayList<>();
        for (CartLine line : cart) {
            lines.add(new PlaceOrderC2S.Line(BuiltInRegistries.ITEM.getKey(line.stack.getItem()).toString(), line.count));
        }
        // A name typed into the field and not confirmed with Enter is still what the player means,
        // so the order commits it first and then reads the group. Both packets are handled in the
        // order they were sent, and the order carries the group itself either way, so an order
        // placed in the same breath as the name it was placed under still arrives under that name.
        commitDockGroup(dev.distantstock.net.SetDockGroupC2S.SELECT);
        java.util.UUID group = carriedGroupId();
        PacketDistributor.sendToServer(new PlaceOrderC2S(lines, group));
        cart.clear();
        successTicks = 1;
    }

    private int amount(boolean rmb) {
        if (rmb) {
            return 0;
        }
        if (hasShiftDown()) {
            return 64;
        }
        if (hasControlDown()) {
            return 10;
        }
        return 1;
    }

    private void addCart(ItemStack stack, int n) {
        if (n <= 0) {
            return;
        }
        for (CartLine line : cart) {
            if (ItemStack.isSameItemSameComponents(line.stack, stack)) {
                line.count += n;
                return;
            }
        }
        if (cart.size() >= CART_MAX) {
            return;
        }
        cart.add(new CartLine(stack.copyWithCount(1), n));
        uiSound(SoundEvents.WOOL_STEP, 0.75f, 1.2f);
        uiSound(SoundEvents.BAMBOO_WOOD_STEP, 0.75f, 0.8f);
    }

    private void removeCart(int index, int n) {
        CartLine line = cart.get(index);
        line.count -= n;
        if (line.count <= 0) {
            cart.remove(index);
            uiSound(SoundEvents.WOOL_STEP, 0.75f, 1.8f);
            uiSound(SoundEvents.BAMBOO_WOOD_STEP, 0.75f, 1.8f);
        }
    }

    private boolean isConfirmHovered(int mx, int my) {
        int cx = leftPos + 143;
        int cy = topPos + imageHeight - 39;
        return mx >= cx && mx < cx + 78 && my >= cy && my < cy + 18;
    }

    private ItemStack hoveredStock(int mx, int my) {
        if (my < topPos + 16 || my > topPos + imageHeight - 132) {
            return ItemStack.EMPTY;
        }
        List<StockCache.Entry> list = filtered();
        int start = scroll * COLS;
        int cells = COLS * visibleRows();
        int ix = itemsX();
        int iy = itemsY() + 4;
        for (int i = 0; i < cells; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                break;
            }
            int sx = ix + (i % COLS) * CELL;
            int sy = iy + (i / COLS) * CELL;
            if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) {
                return list.get(idx).stack();
            }
        }
        return ItemStack.EMPTY;
    }

    private int cartIndex(int mx, int my) {
        int oy = orderY();
        int ix = itemsX();
        for (int i = 0; i < cart.size() && i < CART_MAX; i++) {
            int sx = ix + i * CELL;
            if (mx >= sx && mx < sx + SLOT && my >= oy && my < oy + SLOT) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack hoveredCart(int mx, int my) {
        int i = cartIndex(mx, my);
        return i < 0 ? ItemStack.EMPTY : cart.get(i).stack;
    }

    private static String compact(int n) {
        if (n >= 1_000_000) {
            return (n / 1_000_000) + "M";
        }
        if (n >= 1000) {
            return (n / 1000) + "k";
        }
        return String.valueOf(n);
    }

    private static int withAlpha(int rgb, float a) {
        int alpha = Mth.clamp((int) (a * 255), 0, 255);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static void uiSound(net.minecraft.sounds.SoundEvent sound, float vol, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, vol));
    }

    private static final class CartLine {
        final ItemStack stack;
        int count;

        CartLine(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }
}
