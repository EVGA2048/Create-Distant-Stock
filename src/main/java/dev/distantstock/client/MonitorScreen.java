package dev.distantstock.client;

import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;


/** 固定像素布局的 Create 风链路仪表板。 */
public final class MonitorScreen extends Screen {
    private static final int W = 256;
    /** Link page height, composed entirely from Create's stock-keeper GUI slices. */
    private static final int H = 176;
    /** Tower page height; 36 header + 7x20 body strips + 80 footer. */
    private static final int TOWER_H = 256;
    /** 塔页的行位置：摘要（在页签下面）、第一行塔、四行塔的下界、溢出说明、区块选区。 */
    private static final int TOWER_SUMMARY_Y = 52;
    private static final int TOWER_ROWS_Y = 68;
    private static final int TOWER_ROWS_BOTTOM = TOWER_H - 28;
    private static final int TOWER_MORE_Y = TOWER_H - 40;
    private static final int TOWER_SELECTION_Y = TOWER_H - 26;
    private static final int INK = 0x263B43;
    private static final int MUTED = 0x68828A;
    private static final int HEADER = 0x4B5356;
    private static final int BRASS = 0x718B8B;
    private static final int AETHER = 0x3A9DB0;
    private static final int GOOD = 0x4C9B7A;
    private static final int WARN = 0xC18A4A;
    private static final int BAD = 0xB65E57;
    /** Row pitch on the tower page: three lines of text and the button strip between them. */
    private static final int ROW_H = 40;
    /** What a member tower's own tier allows it to load, as a square. */
    private static int memberCeiling(TowerReadout.Member member) {
        try {
            return dev.distantstock.block.TowerTier.valueOf(member.tier()).chunkRadius();
        } catch (IllegalArgumentException | NullPointerException gone) {
            // A tower that is not in the world has no tier, and a tower with no tier pays for
            // nothing: its radius buttons are shown at their floor rather than at a guess.
            return 0;
        }
    }
    /** Rows are built during render and read back on click, so the two cannot disagree. */
    private final java.util.List<Hit> hits = new java.util.ArrayList<>();
    private final BlockPos source;
    private final boolean towerOnly;
    private LinkSnapshot.View view;
    /** Which half of the dashboard is showing. The tower half needs room the link half is using. */
    private boolean towerPage;

    private int left;
    private int top;

    public MonitorScreen(BlockPos source, LinkSnapshot.View view) {
        this(source, view, false);
    }

    public MonitorScreen(BlockPos source, LinkSnapshot.View view, boolean towerOnly) {
        super(Component.translatable(towerOnly
                ? "gui.distantstock.tower.control"
                : "gui.distantstock.monitor"));
        this.source = source.immutable();
        this.view = view;
        this.towerOnly = towerOnly;
        this.towerPage = towerOnly;
    }

    public boolean isSource(BlockPos source) {
        return this.source.equals(source);
    }

    public void update(LinkSnapshot.View next) {
        view = next;
    }

    @Override
    public void tick() {
        super.tick();
        if (towerOnly && minecraft != null && minecraft.level != null
                && minecraft.level.getGameTime() % 20 == 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.distantstock.net.RequestTowerSnapshotC2S(source));
        }
    }
    /** 当前这一页的底图有多高。切页时整块要重新居中 —— 塔页比链路页高 66 像素。 */
    private int panelH() {
        return towerPage ? TOWER_H : H;
    }

    private void layout() {
        left = (width - W) / 2;
        top = (height - panelH()) / 2;
    }

    @Override
    protected void init() {
        layout();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        layout();
        // Keep the world visible behind machinery screens, but let Create's own GUI textures do
        // all of the panel work. No Distant Stock-authored monitor background is rendered here.
        g.fill(0, 0, width, height, 0x32000000);
        renderCreatePanel(g);

        Component title = Component.translatable(towerOnly
                ? "gui.distantstock.tower.control"
                : "gui.distantstock.monitor");
        g.drawString(font, title, left + 14, top + 13, HEADER, false);
        if (!towerOnly) drawStatus(g);
        hits.clear();
        if (!towerOnly) drawPageToggle(g, mouseX, mouseY);
        if (towerPage) {
            drawTowerPage(g);
            super.render(g, mouseX, mouseY, partial);
            return;
        }

        drawLinkOverview(g);
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * Create-native scalable panel: stock keeper header + repeated body + footer.
     * Both page heights are chosen to be exact multiples of the 20px body strip.
     */
    private void renderCreatePanel(GuiGraphics g) {
        AllGuiTextures.STOCK_KEEPER_REQUEST_HEADER.render(g, left, top);
        int footerY = top + panelH() - AllGuiTextures.STOCK_KEEPER_REQUEST_FOOTER.getHeight();
        for (int y = top + AllGuiTextures.STOCK_KEEPER_REQUEST_HEADER.getHeight();
             y < footerY; y += AllGuiTextures.STOCK_KEEPER_REQUEST_BODY.getHeight()) {
            AllGuiTextures.STOCK_KEEPER_REQUEST_BODY.render(g, left, y);
        }
        AllGuiTextures.STOCK_KEEPER_REQUEST_FOOTER.render(g, left, footerY);
    }

    /**
     * The switch between the link half and the tower half.
     *
     * <p>Two pages rather than one longer panel: both are composed from Create's stock-keeper
     * texture slices, but the tower page needs more vertical room for per-tower controls.
     */
    private void drawPageToggle(GuiGraphics g, int mouseX, int mouseY) {
        toggle(g, left + 12, top + 38, 58, 18, "gui.distantstock.tab.link", !towerPage,
                () -> towerPage = false, mouseX, mouseY);
        toggle(g, left + 82, top + 38, 68, 18, "gui.distantstock.tab.tower", towerPage,
                () -> towerPage = true, mouseX, mouseY);
    }

    private void toggle(GuiGraphics g, int x, int y, int w, int h, String key, boolean active,
                        Runnable action, int mouseX, int mouseY) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        AllGuiTextures texture = active ? AllGuiTextures.BUTTON_DOWN
                : over ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON;
        texture.render(g, x, y);
        Component label = Component.translatable(key);
        g.drawString(font, label, x + 22, y + 5, active ? INK : MUTED, false);
        hits.add(new Hit(x, y, w, h, null, 0, false, false, action));
    }

    private void drawStatus(GuiGraphics g) {
        Component state = Component.translatable(view.linkUp()
                ? "gui.distantstock.status.online"
                : "gui.distantstock.status.offline");
        int x = left + W - 15 - font.width(state);
        (view.linkUp() ? AllGuiTextures.INDICATOR_GREEN : AllGuiTextures.INDICATOR_RED)
                .render(g, x - 22, top + 13);
        g.drawString(font, state, x, top + 13, view.linkUp() ? GOOD : BAD, false);
    }

    /**
     * Link overview intentionally has no peer TPS/MSPT. Transerver is multi-peer, so one process-wide
     * "peer" reading has no well-defined owner and was misleading as soon as a third node joined.
     */
    private void drawLinkOverview(GuiGraphics g) {
        String route = fit(view.linkLabel(), W - 76);
        g.drawString(font, route, left + 14, top + 64, view.linkUp() ? AETHER : MUTED, false);

        double tps = view.localTps();
        AllGuiTextures perf = tps >= 18 ? AllGuiTextures.INDICATOR_GREEN
                : tps >= 15 ? AllGuiTextures.INDICATOR_YELLOW : AllGuiTextures.INDICATOR_RED;
        perf.render(g, left + 14, top + 83);
        Component local = Component.translatable("gui.distantstock.local");
        String localPerf = local.getString() + "  " + oneDecimal(view.localTps())
                + " TPS  ·  " + oneDecimal(view.localMspt()) + " MSPT";
        g.drawString(font, localPerf, left + 38, top + 86, INK, false);

        int y1 = top + 108;
        int y2 = top + 124;
        int y3 = top + 140;
        drawMetric(g, left + 14, y1, "OUT", view.transerverOutbox(),
                view.transerverOutbox() == 0 ? INK : WARN);
        drawMetric(g, left + 90, y1, "IN", view.transerverInbox(),
                view.transerverInbox() == 0 ? INK : WARN);
        drawMetric(g, left + 160, y1, "DONE", view.transerverCompleted(), INK);

        drawMetric(g, left + 14, y2, Component.translatable("gui.distantstock.orders.label").getString(),
                view.orderDepth(), view.orderDepth() == 0 ? INK : WARN);
        drawMetric(g, left + 90, y2, Component.translatable("gui.distantstock.packages.label").getString(),
                view.packageDepth(), view.packageDepth() == 0 ? INK : WARN);
        drawMetric(g, left + 160, y2, Component.translatable("gui.distantstock.in_flight.label").getString(),
                view.inFlight(), view.inFlight() == 0 ? INK : AETHER);

        if (view.transerverDeadLetters() > 0) {
            AllGuiTextures.INDICATOR_RED.render(g, left + 14, y3 - 3);
            g.drawString(font, "DEAD " + view.transerverDeadLetters(), left + 38, y3, BAD, false);
        } else if (view.transerverAttached()) {
            AllGuiTextures.INDICATOR_GREEN.render(g, left + 14, y3 - 3);
            g.drawString(font, "Transerver", left + 38, y3, MUTED, false);
        }
    }

    private void drawMetric(GuiGraphics g, int x, int y, String label, int value, int color) {
        g.drawString(font, label, x, y, MUTED, false);
        g.drawString(font, Integer.toString(value), x + 42, y, color, false);
    }

    /**
     * One row per member tower, each with its own dials.
     *
     * <p>Per tower and not per system: a radius, a loading switch and a carrying switch belong to
     * one tower, and two towers in one system are set independently. The row therefore carries all
     * three, and every button sends the whole record back — the server stores what it is handed, so
     * a button that sent only its own field would blank the other two.
     */
    private void drawTowerPage(GuiGraphics g) {
        TowerReadout tower = view.tower();
        if (!tower.attached()) {
            // Said plainly rather than drawn as zeros. A monitor that is not on a tower has no
            // towers, no radius and no budget, and showing those as numbers would read as a fault
            // in the machine rather than as a monitor standing nowhere in particular.
            Component none = Component.translatable("gui.distantstock.tower.none");
            g.drawString(font, none, left + W / 2 - font.width(none) / 2, top + TOWER_H / 2, BAD, false);
            return;
        }

        // 摘要从 44 挪到 52：页签那一条一直到 46 才结束，44 会让这一行压在页签的下边框上，
        // 玩家截图里"标题被切"就是这个。塔页有自己的高度，不必再挤。
        Component summary = Component.translatable("gui.distantstock.tower.summary",
                tower.members().size(), tower.carried(), tower.limit());
        g.drawString(font, summary, left + 14, top + TOWER_SUMMARY_Y, BRASS, false);
        Component stress = Component.translatable("gui.distantstock.tower.stress",
                (int) tower.stress(), (int) tower.speed());
        g.drawString(font, stress, left + W - 14 - font.width(stress), top + TOWER_SUMMARY_Y,
                tower.speed() <= 0 ? BAD : MUTED, false);

        // The square the system actually keeps loaded, against the largest one any of its members
        // pays for. Both were already on the wire and neither was drawn, so the buttons further up
        // were the only sign a ceiling existed at all — and they stopped at it without saying why.
        //
        // Along the bottom, under the rows rather than above them: the panel's furniture is drawn
        // at fixed heights and the row area starts where the artwork expects it to, so a line
        // pushed in above the rows lands on the frame and pushes everything below it out of place.
        Component selection = tower.selectedSide() <= 0
                ? Component.translatable("gui.distantstock.tower.selection.none",
                tower.maxSide(), tower.maxSide())
                : Component.translatable("gui.distantstock.tower.selection",
                tower.selectedSide(), tower.selectedSide(), tower.maxSide(), tower.maxSide());
        g.drawString(font, selection, left + 14, top + TOWER_SELECTION_Y,
                tower.selectedSide() <= 0 ? MUTED : AETHER, false);

        int y = top + TOWER_ROWS_Y;
        for (TowerReadout.Member member : tower.members()) {
            if (y + ROW_H > top + TOWER_ROWS_BOTTOM) {
                Component more = Component.translatable("gui.distantstock.tower.more",
                        tower.members().size() - (y - top - TOWER_ROWS_Y) / ROW_H);
                // Right-aligned: the selection line sits along the bottom too, and two strings
                // starting at the same x on consecutive lines read as one broken sentence.
                g.drawString(font, more, left + W - 14 - font.width(more), top + TOWER_MORE_Y,
                        MUTED, false);
                return;
            }
            drawTowerRow(g, member, y);
            y += ROW_H;
        }
    }

    private void drawTowerRow(GuiGraphics g, TowerReadout.Member member, int y) {
        BlockPos base = BlockPos.of(member.pos());
        String where = "#" + base.getX() + "," + base.getY() + "," + base.getZ();
        String tier = member.tier().isBlank() ? "—" : member.tier();
        String head = where + "  " + tier;
        g.drawString(font, head, left + 14, y + 1, INK, false);

        // What this tower reaches and what it is rated to carry, right after its tier: the reading
        // an operator needs when a device near the edge of a system is not being served, and the
        // answer to why the member rows and the summary do not add up to the same number.
        int badgeX = left + 14;
        if (!member.tier().isBlank()) {
            Component reach = Component.translatable("gui.distantstock.tower.reach",
                    member.radius(), member.devices());
            badgeX += font.width(head) + 6;
            g.drawString(font, reach, badgeX, y + 1, MUTED, false);
            badgeX += font.width(reach) + 6;
        } else {
            badgeX = left + 110;
        }

        // The one reading that explains all the others when it is set. Create reports a speed of
        // zero both for a stalled network and for a tower with no shaft at all; the flag is what
        // tells the operator which of the two they are looking at.
        if (member.overstressed()) {
            Component over = Component.translatable("gui.distantstock.tower.overstressed");
            g.drawString(font, over, badgeX, y + 1, BAD, false);
        } else if (!member.running()) {
            Component stopped = Component.translatable("gui.distantstock.tower.stopped");
            g.drawString(font, stopped, badgeX, y + 1, WARN, false);
        }
        Component speed = Component.translatable("gui.distantstock.tower.speed",
                (int) Math.abs(member.speed()));
        g.drawString(font, speed, left + W - 14 - font.width(speed), y + 1, MUTED, false);

        int x = left + 14;
        // Both ends are bounded here rather than on the server. The server refuses a radius above
        // the tier and stores a negative one as "the tier's own", so a button that stayed live past
        // either end would look like a working control that puts the radius back where it started.
        boolean canShrink = member.chunkRadius() > 0;
        boolean canGrow = member.chunkRadius() < memberCeiling(member);
        x = smallButton(g, x, y + 11, "−", member, member.chunkRadius() - 1, canShrink);
        Component radius = Component.translatable("gui.distantstock.tower.radius", member.chunkRadius());
        g.drawString(font, radius, x + 3, y + 13, INK, false);
        x += 3 + font.width(radius) + 3;
        x = smallButton(g, x, y + 11, "+", member, member.chunkRadius() + 1, canGrow);
        x += 6;
        x = switchButton(g, x, y + 11, "gui.distantstock.tower.loading", member.loading(), member,
                !member.loading(), member.carrying());
        switchButton(g, x + 4, y + 11, "gui.distantstock.tower.carrying", member.carrying(), member,
                member.loading(), !member.carrying());

        Component ether = Component.translatable("gui.distantstock.tower.ether",
                member.ether(), TowerCoreBlockEntity.ETHER_CAPACITY);
        g.drawString(font, ether, left + 14, y + 30, MUTED, false);
        Component flow = Component.translatable("gui.distantstock.tower.traffic",
                member.sent(), member.received());
        g.drawString(font, flow, left + W - 14 - font.width(flow), y + 30, MUTED, false);
    }

    /** A radius step, using Create's own 18x18 button textures. */
    private int smallButton(GuiGraphics g, int x, int y, String glyph,
                            TowerReadout.Member member, int radius, boolean live) {
        int w = 18;
        (live ? AllGuiTextures.BUTTON : AllGuiTextures.BUTTON_DISABLED).render(g, x, y);
        g.drawString(font, glyph, x + (w - font.width(glyph)) / 2, y + 5,
                live ? INK : MUTED, false);
        if (live) {
            hits.add(new Hit(x, y, w, 18, member, radius, member.loading(), member.carrying(), null));
        }
        return x + w;
    }

    private int switchButton(GuiGraphics g, int x, int y, String key, boolean on,
                             TowerReadout.Member member, boolean loading, boolean carrying) {
        String shortKey = key.endsWith("loading")
                ? "gui.distantstock.tower.loading.short"
                : "gui.distantstock.tower.carrying.short";
        Component glyph = Component.translatable(shortKey);
        (on ? AllGuiTextures.BUTTON_GREEN : AllGuiTextures.BUTTON).render(g, x, y);
        g.drawString(font, glyph, x + (18 - font.width(glyph)) / 2, y + 5,
                on ? 0xFF2E5A4B : INK, false);
        hits.add(new Hit(x, y, 18, 18, member, member.chunkRadius(), loading, carrying, null));
        return x + 18;
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "…";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private boolean inside(double x, double y) {
        return x >= left && x <= left + W && y >= top && y <= top + panelH();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        for (Hit hit : hits) {
            if (x < hit.x || x >= hit.x + hit.w || y < hit.y || y >= hit.y + hit.h) {
                continue;
            }
            if (hit.action != null) {
                hit.action.run();
            } else if (hit.member != null) {
                // The radius is checked again on the server; stepping past the ceiling here is a
                // wasted round trip, not a way to store one.
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.distantstock.net.SetTowerSettingsC2S(source, hit.member.pos(),
                                hit.radius, hit.loading, hit.carrying));
            }
            return true;
        }
        return inside(x, y) || super.mouseClicked(x, y, button);
    }

    /**
     * One clickable rectangle, rebuilt every frame from what was just drawn.
     *
     * <p>Built during render rather than kept in a list beside it, so a rectangle can never describe
     * a button that has moved or gone. A toggle carries an action; a tower button carries the whole
     * setting it would store.
     */
    private record Hit(int x, int y, int w, int h, TowerReadout.Member member, int radius,
                       boolean loading, boolean carrying, Runnable action) {
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return inside(x, y) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return inside(x, y) || super.mouseDragged(x, y, button, dx, dy);
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
