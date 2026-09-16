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
    private static final int HINT = 0xCDBCA8;

    private EditBox search;
    private EditBox address;
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
        address.setResponder(v -> PacketDistributor.sendToServer(new SetAddressC2S(v)));
        addRenderableWidget(address);


        // The field stops short of the plate's right edge so the rename button beside it has
        // somewhere to sit: it used to hang twenty pixels off the end of the artwork, which is
        // what "突兀且错位" was about.
        int groupField = Math.max(60, imageWidth - 144);
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
                    groupPickerOpen = !groupPickerOpen;
                    if (groupPickerOpen) {
                        receivingGroup.setFocused(true);
                    }
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
        }
    }

    /**
     * What to put in the group field before the player has typed anything.
     *
     * <p>The name the requester carries, falling back to the default system's name: a requester that
     * has never been pointed anywhere is pointing at the default group, and showing an empty box
     * would suggest it is pointing at nothing.
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
        // server just sent has the name for that id, so the field can show the truth rather than
        // the default group's name, which would be a different system entirely.
        if (groups != null && groups.carried() != null) {
            for (var entry : groups.groups()) {
                if (entry.id().equals(groups.carried())) {
                    return entry.name();
                }
            }
        }
        return net.minecraft.network.chat.Component
                .translatable("gui.distantstock.group.default").getString();
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

    /**
     * Spends a pairing code typed into the destination field, if that is what is in it.
     *
     * <p>One field for both, because from the player's side they are the same act: naming a
     * destination that is not here yet. A name makes a system on this server; a code names one on
     * another server, which is the one thing this side cannot invent for itself. They are told
     * apart by shape — six characters from an alphabet with nothing confusable in it — and a name
     * that happens to look like one is a name nobody can have made here anyway.
     *
     * <p>The answer comes back in chat a moment later, possibly from another server, so the field
     * is left alone: the code is the one thing the player may still want to check they typed.
     */
    private boolean redeemCodeIfTyped() {
        if (receivingGroup == null || groups == null) {
            return false;
        }
        String typed = receivingGroup.getValue().trim();
        if (!dev.distantstock.routing.PairingCodes.wellFormed(typed)) {
            return false;
        }
        for (var entry : groups.groups()) {
            if (entry.name().equalsIgnoreCase(typed)) {
                return false;
            }
        }
        if (remotes != null) {
            for (var entry : remotes.groups()) {
                if (entry.name().equalsIgnoreCase(typed) || entry.display().equalsIgnoreCase(typed)) {
                    return false;
                }
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.distantstock.net.PairCodeC2S(
                dev.distantstock.net.PairCodeC2S.REDEEM, typed, 0));
        committedGroup = typed;
        return true;
    }

    @Override
    public void onClose() {
        if (!redeemCodeIfTyped()) {
            commitDockGroup(dev.distantstock.net.SetDockGroupC2S.SELECT);
        }
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
     * How many rows the open dropdown has: the destinations, then the pairing row.
     *
     * <p>The last row is not a destination — it mints a code for whatever is in the field — and it
     * is there because the alternative was a second button beside the field, and the field's plate
     * has room for one. It is also where the player is already looking when they are choosing a
     * destination, which is exactly when "my friend's server is not in this list" is the question.
     */
    /**
     * Where the open list starts, which is above the field rather than below it.
     *
     * <p>Below is where it used to be, and that is exactly where the address box lives — the list
     * covered the field the player had just filled in, which reads as the screen losing its own
     * contents. Above it the list covers the item grid instead, which is what a dropdown is
     * supposed to do.
     */
    private int dropdownY(int rows) {
        return topPos + this.imageHeight - 89 - rows * 10;
    }

    private int dropdownRows() {
        return Math.min(groupRows(), 6) + 1;
    }

    /** The local group with this name, or null when nobody here has used the name. */
    private dev.distantstock.net.DockGroupsS2C.Entry knownGroup(String name) {
        if (groups == null || name == null || name.isEmpty()) {
            return null;
        }
        for (var entry : groups.groups()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    private int groupRows() {
        int local = groups == null ? 0 : groups.groups().size();
        int remote = remotes == null ? 0 : remotes.groups().size();
        return local + remote;
    }

    /** Which row of the open dropdown, if any, is under the mouse. */
    private boolean dropdownHit(double mx, double my, int index, int x, int y, int w) {
        return mx >= x && mx < x + w && my >= y + index * 10 && my < y + index * 10 + 10;
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
        // Drawn while the field has focus, and also while the little arrow beside it has been
        // clicked. Focus alone was not enough: a player who did not know the field was a field
        // never clicked into it, and so never saw that the list of systems existed at all —
        // "I don't know what systems there are" was the report, about a list that was already
        // there and already showed every one of them.
        if (groups == null || receivingGroup == null
                || !(receivingGroup.isFocused() || groupPickerOpen)) {
            return;
        }
        int x = leftPos + 82;
        int y = dropdownY(dropdownRows());
        int w = 112;
        int rows = dropdownRows();
        if (rows == 0) {
            return;
        }
        g.fill(x - 1, y - 1, x + w + 1, y + rows * 10 + 1, 0xFF24343A);
        int local = groups.groups().size();
        int destinations = rows - 1;
        for (int i = 0; i < rows; i++) {
            boolean over = dropdownHit(mouseX, mouseY, i, x, y, w);
            int rowY = y + i * 10;
            if (i == destinations) {
                // The pairing row, and the one place the player finds out whether they may use it.
                //
                // A code can only be minted by the group's owner, and a group made from the server
                // console has no owner at all — so the row did nothing for exactly the players who
                // needed it most, and said nothing about why. The reason is on the row now.
                String typed = receivingGroup.getValue().trim();
                var known = knownGroup(typed);
                boolean blocked = known != null && !known.mine();
                boolean unknown = known == null && !typed.isEmpty()
                        && groups != null && groups.groups().stream()
                                .noneMatch(entry -> entry.name().equalsIgnoreCase(typed));
                String label;
                int colour;
                if (blocked) {
                    label = Component.translatable("gui.distantstock.pair.mint_row.not_owner").getString();
                    colour = 0xFFB09090;
                } else if (unknown) {
                    label = Component.translatable("gui.distantstock.pair.mint_row.new_group", typed).getString();
                    colour = 0xFFC8B090;
                } else {
                    label = Component.translatable("gui.distantstock.pair.mint_row", typed).getString();
                    colour = 0xFFD8C8F0;
                }
                g.fill(x, rowY, x + w, rowY + 10, over ? 0xFF5A4A72 : 0xFF3A3050);
                g.drawString(font, trim(label, w - 6), x + 3, rowY + 1, colour, false);
            } else if (i < local) {
                var entry = groups.groups().get(i);
                boolean doomed = entry.name().equals(pendingDelete);
                g.fill(x, rowY, x + w, rowY + 10,
                        doomed ? 0xFF6B2A2A : over ? 0xFF3E5A61 : 0xFF2E444B);
                // The carried one is marked so the field's value and the list agree at a glance.
                boolean here = groups.carried() != null && groups.carried().equals(entry.id());
                String left = doomed
                        ? Component.translatable("gui.distantstock.group.delete_confirm").getString()
                        : entry.name();
                g.drawString(font, trim(left, w - (entry.mine() ? 40 : 30)), x + 3, rowY + 1,
                        doomed ? 0xFFFFD0D0 : here ? 0xFF9BE0C4 : INK, false);
                if (entry.mine()) {
                    // The owner's two dials, at the end of their own row: the lock, and the way to
                    // be rid of it. Both are only drawn on a row this player owns — there is
                    // nothing to open or delete on somebody else's system.
                    g.drawString(font, "×", x + w - 8, rowY + 1, doomed ? 0xFFFFD0D0 : 0xFFC0A090, false);
                }
                String tail = entry.docks() + (entry.open() ? "  ○" : "  ●");
                g.drawString(font, tail, x + w - 12 - font.width(tail), rowY + 1,
                        entry.open() ? HINT : 0xFFC0A090, false);
            } else {
                // A destination on another server, drawn in the same list because it is chosen the
                // same way. The label is what the player has to identify it by: the group's name is
                // written by whoever owns it over there, and two servers can both have a "仓库".
                var entry = remotes.groups().get(i - local);
                boolean doomed = entry.group().toString().equals(pendingForget);
                g.fill(x, rowY, x + w, rowY + 10,
                        doomed ? 0xFF4A2A4A : over ? 0xFF3E5A61 : 0xFF2E444B);
                // The same "×" as an owned row has, and it means the smaller thing: this server
                // stops remembering a destination it never owned. Nothing on the other server
                // changes, and the group keeps working for whoever else was let into it.
                g.drawString(font, "×", x + w - 8, rowY + 1, doomed ? 0xFFFFD0D0 : 0xFFC0A090, false);
                boolean here = receivingGroup.getValue().trim().equalsIgnoreCase(entry.name());
                g.drawString(font, trim(entry.display(), w - 16), x + 3, rowY + 1,
                        doomed ? 0xFFFFD0D0 : here ? 0xFF9BE0C4 : 0xFFC8B6E8, false);
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
        if (groups == null || receivingGroup == null
                || !(receivingGroup.isFocused() || groupPickerOpen)) {
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
            if (i == destinations) {
                // Mint a code for the group named in the field. The server checks ownership; this
                // side only decides which group the row is offering.
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.PairCodeC2S(dev.distantstock.net.PairCodeC2S.ISSUE,
                                receivingGroup.getValue().trim(),
                                dev.distantstock.routing.PairingCodes.DEFAULT_MINUTES));
                receivingGroup.setFocused(false);
                return true;
            }
            if (i >= local) {
                // Picking a remote destination. The field carries the group's plain name and the
                // server matches it against what the pairing codes brought in — the id never has
                // to reach the client, and nothing here could check it if it did.
                var remote = remotes.groups().get(i - local);
                if (mx >= x + w - 10) {
                    // Two clicks, the same as deleting a system: the first asks, the second drops
                    // this server's memory of a destination. Getting a new one costs another
                    // pairing code from whoever owns the group.
                    if (remote.group().toString().equals(pendingForget)) {
                        pendingForget = null;
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new dev.distantstock.net.PairCodeC2S(
                                        dev.distantstock.net.PairCodeC2S.FORGET,
                                        remote.group().toString(), 0));
                    } else {
                        pendingForget = remote.group().toString();
                    }
                    return true;
                }
                pendingForget = null;
                String name = remote.name();
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
            if (entry.mine() && mx >= x + w - 10) {
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
            if (entry.mine() && mx >= x + w - 28) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.SetDockGroupC2S(entry.name(),
                                dev.distantstock.net.SetDockGroupC2S.TOGGLE_OPEN));
                return true;
            }
            receivingGroup.setValue(entry.name());
            committedGroup = entry.name();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.distantstock.net.SetDockGroupC2S(entry.name(),
                            dev.distantstock.net.SetDockGroupC2S.SELECT));
            receivingGroup.setFocused(false);
            return true;
        }
        return false;
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

    private void renderRouteRow(GuiGraphics g, int offset, String label) {
        int x = leftPos + 8;
        int y = topPos + offset;
        g.blit(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "distantstock", "textures/gui/route_label.png"), x, y, 0, 0, 194, 26, 194, 26);
        g.drawString(font, Component.translatable(label), x + 24, y + 11, INK, false);
    }

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
            g.drawString(font, label, x + 31, ry + 3, entry.local() ? INK : 0xFFC8B6E8, false);
            String id = entry.freq().toString().substring(0, 8);
            g.drawString(font, id, x + 31, ry + 10, 0x3A7774, false);
            Component links = Component.translatable("gui.distantstock.networks.links", entry.links());
            g.drawString(font, links, x + WINDOW_W - 31 - font.width(links), ry + 6, TITLE, false);
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
            if (!redeemCodeIfTyped()) {
                commitDockGroup(dev.distantstock.net.SetDockGroupC2S.SELECT);
            }
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

    /** Whether any of this screen's text fields is taking keys. */
    private boolean typing() {
        return (search != null && search.isFocused())
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
