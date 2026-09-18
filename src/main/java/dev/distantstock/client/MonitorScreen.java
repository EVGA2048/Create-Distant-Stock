package dev.distantstock.client;

import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** 固定像素布局的 Create 风链路仪表板。 */
public final class MonitorScreen extends Screen {
    private static final int W = 272;
    /** 链路页的高度。那张底图是手绘的，一个像素都不动。 */
    private static final int H = 190;
    /**
     * 塔页的高度，和 {@code scripts/gen_monitor_tower_bg.py} 是一份。
     *
     * <p>两页各用各的底图：链路页那张（{@code monitor.png}）下半张是给两个大读数和五个计数器画的
     * 家具，塔页把一行行塔画上去，那些空框就露在下面 —— 玩家说的是「背景是为链路页设计的，塔页
     * 完全不适配」。塔页那张是照着同一张图的页眉和底边另拼的，中间是平的，四行塔正好放得下。
     */
    private static final int TOWER_H = 256;
    /** 塔页的行位置：摘要（在页签下面）、第一行塔、四行塔的下界、溢出说明、区块选区。 */
    private static final int TOWER_SUMMARY_Y = 52;
    private static final int TOWER_ROWS_Y = 68;
    private static final int TOWER_ROWS_BOTTOM = TOWER_H - 40;
    private static final int TOWER_MORE_Y = TOWER_H - 38;
    private static final int TOWER_SELECTION_Y = TOWER_H - 26;
    private static final int INK = 0x263B43;
    private static final int MUTED = 0x68828A;
    private static final int HEADER = 0xF4FBF8;
    private static final int BRASS = 0x718B8B;
    private static final int AETHER = 0x3A9DB0;
    private static final int GOOD = 0x4C9B7A;
    private static final int WARN = 0xC18A4A;
    private static final int BAD = 0xB65E57;
    /** Row pitch on the tower page: three lines of text and the button strip between them. */
    private static final int ROW_H = 36;
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
    private static final ResourceLocation PANEL =
            ResourceLocation.fromNamespaceAndPath("distantstock", "textures/gui/monitor.png");
    /** 塔页那张：同一套页眉和底边，中间是平的，见 {@link #TOWER_H}。 */
    private static final ResourceLocation PANEL_TOWER =
            ResourceLocation.fromNamespaceAndPath("distantstock", "textures/gui/monitor_tower.png");

    /** Rows are built during render and read back on click, so the two cannot disagree. */
    private final java.util.List<Hit> hits = new java.util.ArrayList<>();
    private final BlockPos source;
    private LinkSnapshot.View view;
    /** Which half of the dashboard is showing. The tower half needs room the link half is using. */
    private boolean towerPage;
    private int flipTicks;
    private int previousTps;
    /**
     * The peer's own previous readings.
     *
     * <p>Separate from the local pair on purpose. Both endpoints share one flip animation, and one
     * pair of "the value before this update" between them meant the peer's cells spent the first
     * fifth of every second showing <em>this</em> server's numbers before falling to their own —
     * the flicker between 20 and 0 that the operator reported.
     */
    private int previousPeerTps;
    private int previousPeerMspt;
    private int previousMspt;
    private int left;
    private int top;

    public MonitorScreen(BlockPos source, LinkSnapshot.View view) {
        super(Component.translatable("gui.distantstock.monitor"));
        this.source = source.immutable();
        this.view = view;
    }

    public boolean isSource(BlockPos source) {
        return this.source.equals(source);
    }

    public void update(LinkSnapshot.View next) {
        previousTps = (int) Math.round(view.localTps() * 10);
        previousMspt = (int) Math.round(view.localMspt() * 10);
        previousPeerTps = (int) Math.round(view.peerTps() * 10);
        previousPeerMspt = (int) Math.round(view.peerMspt() * 10);
        view = next;
        flipTicks = 8;
    }

    @Override
    public void tick() {
        super.tick();
        if (flipTicks > 0) {
            flipTicks--;
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
        // 每一帧都重算：点一下页签换的是另一张（更高）的底图，位置必须跟着走，否则塔页会顶到屏幕外。
        layout();
        // A machinery panel should remain part of the world, not open Minecraft's blurred menu backdrop.
        g.fill(0, 0, width, height, 0x4208171B);
        g.blit(towerPage ? PANEL_TOWER : PANEL, left, top, 0, 0, W, panelH(), W, panelH());

        Component title = Component.translatable("gui.distantstock.monitor");
        g.drawString(font, title, left + 14, top + 13, HEADER, false);
        drawStatus(g);
        hits.clear();
        drawPageToggle(g, mouseX, mouseY);
        if (towerPage) {
            drawTowerPage(g);
            super.render(g, mouseX, mouseY, partial);
            return;
        }
        drawRoute(g);

        drawEndpoint(g, left + 12, top + 51,
                Component.translatable("gui.distantstock.local"),
                view.localTps(), view.localMspt(), null, true, true,
                previousTps, previousMspt);
        drawEndpoint(g, left + 142, top + 51,
                Component.translatable("gui.distantstock.peer"),
                view.peerTps(), view.peerMspt(),
                view.peerRttMs() < 0 ? "—" : (int) view.peerRttMs() + " ms",
                view.linkUp(), view.peerFresh(),
                previousPeerTps, previousPeerMspt);

        drawCounters(g);
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * The switch between the link half and the tower half.
     *
     * <p>Two pages rather than one longer panel: the dashboard is a fixed 272x190 of authored
     * artwork and the tower half wants a list where the link half wants two big readouts. Stacking
     * them would mean either squashing both or drawing past the panel.
     */
    private void drawPageToggle(GuiGraphics g, int mouseX, int mouseY) {
        toggle(g, left + 12, top + 27, 40, 12, "gui.distantstock.tab.link", !towerPage,
                () -> towerPage = false, mouseX, mouseY);
        toggle(g, left + 55, top + 27, 40, 12, "gui.distantstock.tab.tower", towerPage,
                () -> towerPage = true, mouseX, mouseY);
    }

    private void toggle(GuiGraphics g, int x, int y, int w, int h, String key, boolean active,
                        Runnable action, int mouseX, int mouseY) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, active ? 0xFF3E5A61 : over ? 0xFF44575D : 0xFF38484E);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, active ? 0xFF2E444B : 0xFF2A383E);
        Component label = Component.translatable(key);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3,
                active ? 0xFFD8EEEA : MUTED, false);
        hits.add(new Hit(x, y, w, h, null, 0, false, false, action));
    }

    private void drawStatus(GuiGraphics g) {
        Component state = Component.translatable(view.linkUp()
                ? "gui.distantstock.status.online"
                : "gui.distantstock.status.offline");
        int color = view.linkUp() ? 0xB8E4D8 : 0xF0B4A8;
        int x = left + W - 15 - font.width(state);
        g.drawString(font, state, x, top + 13, color, false);
        int lampX = x - 10;
        g.fill(lampX, top + 14, lampX + 5, top + 19, 0xFF533E28);
        g.fill(lampX + 1, top + 15, lampX + 4, top + 18,
                view.linkUp() ? 0xFF62C8B8 : 0xFF9A5145);
    }

    private void drawRoute(GuiGraphics g) {
        String route = fit(view.linkLabel(), W - 38);
        int color = view.linkUp() ? AETHER : MUTED;
        g.drawString(font, route, left + W / 2 - font.width(route) / 2, top + 35, color, false);
    }

    /**
     * One endpoint's two readings.
     *
     * @param fresh whether this server has heard from that end recently. False is not the same as
     *              offline: the link answers, but nobody has said what it is doing, and the cells
     *              show a dash rather than a zero — zero is a reading, and this is the absence of one
     */
    private void drawEndpoint(GuiGraphics g, int x, int y, Component name,
                              double tps, double mspt, String rtt, boolean online, boolean fresh,
                              int previousTenths, int previousMsptTenths) {
        g.drawString(font, name, x + 10, y + 6, BRASS, false);
        if (!online) {
            Component down = Component.translatable("gui.distantstock.link_down");
            g.drawString(font, down, x + 59 - font.width(down) / 2, y + 39, BAD, false);
            return;
        }
        if (!fresh) {
            drawFlipReadout(g, "—", "TPS", x + 59, y + 23, MUTED);
            drawFlipReadout(g, "—", "MSPT", x + 43, y + 53, MUTED);
            if (rtt != null) {
                g.drawString(font, rtt, x + 108 - font.width(rtt), y + 55, MUTED, false);
            }
            return;
        }

        String tpsText = flipValue(n(tps), previousTenths, tps, "");
        drawFlipReadout(g, tpsText, "TPS", x + 59, y + 23, online ? AETHER : MUTED);
        meter(g, x + 10, y + 42, 98, tps);

        String msptText = flipValue(n(mspt), previousMsptTenths, mspt, "");
        drawFlipReadout(g, msptText, "MSPT", x + 43, y + 53, MUTED);
        if (rtt != null) {
            g.drawString(font, rtt, x + 108 - font.width(rtt), y + 55, MUTED, false);
        }
    }

    /** Pixel flip-board cells inspired by Create's display boards; values remain readable at GUI scale 1. */
    private void drawFlipReadout(GuiGraphics g, String value, String unit, int centreX, int y, int color) {
        int cellW = 7;
        int gap = 1;
        int cellsW = value.length() * (cellW + gap) - gap;
        int unitW = font.width(unit);
        int total = cellsW + 4 + unitW;
        int x = centreX - total / 2;
        for (int i = 0; i < value.length(); i++) {
            int cx = x + i * (cellW + gap);
            g.fill(cx, y, cx + cellW, y + 11, 0xFF43575D);
            g.fill(cx + 1, y + 1, cx + cellW - 1, y + 5, 0xFF71888E);
            g.fill(cx + 1, y + 6, cx + cellW - 1, y + 10, 0xFF52676D);
            g.fill(cx, y + 5, cx + cellW, y + 6, 0xFF2E4147);
            String glyph = value.substring(i, i + 1);
            g.drawString(font, glyph, cx + (cellW - font.width(glyph)) / 2, y + 2,
                    0xFFE8F4F2, false);
        }
        g.drawString(font, unit, x + cellsW + 4, y + 2, color, false);
    }

    private void meter(GuiGraphics g, int x, int y, int w, double tps) {
        g.fill(x, y, x + w, y + 7, 0xFFBDA982);
        g.fill(x + 1, y + 1, x + w - 1, y + 6, 0xFFE2D0AA);
        int fill = Math.max(0, Math.min(w - 2, (int) Math.round((w - 2) * tps / 20.0)));
        int color = tps >= 18 ? GOOD : tps >= 15 ? WARN : BAD;
        if (fill > 0) {
            g.fill(x + 1, y + 1, x + 1 + fill, y + 6, 0xFF000000 | color);
            g.fill(x + 1, y + 1, x + 1 + fill, y + 2, 0x55FFFFFF);
        }
        for (int mark = 1; mark < 4; mark++) {
            int mx = x + mark * w / 4;
            g.fill(mx, y + 1, mx + 1, y + 6, 0x55806B55);
        }
    }

    private void drawCounters(GuiGraphics g) {
        g.drawString(font, Component.translatable("gui.distantstock.pressure"),
                left + 22, top + 135, BRASS, false);

        Component[] labels = {
                Component.translatable("gui.distantstock.online.label"),
                Component.translatable("gui.distantstock.orders.label"),
                Component.translatable("gui.distantstock.packages.label"),
                Component.translatable("gui.distantstock.in_flight.label"),
                Component.translatable("gui.distantstock.fails.label")
        };
        String[] values = {
                view.peersUp() + "/" + Math.max(1, view.peersTotal()),
                Integer.toString(view.orderDepth()),
                Integer.toString(view.packageDepth()),
                Integer.toString(view.inFlight()),
                Integer.toString(view.peerFails())
        };
        int[] colors = {
                view.linkUp() ? GOOD : BAD,
                view.orderDepth() == 0 ? INK : WARN,
                view.packageDepth() == 0 ? INK : WARN,
                view.inFlight() == 0 ? INK : AETHER,
                view.peerFails() == 0 ? INK : BAD
        };
        for (int i = 0; i < labels.length; i++) {
            int cx = left + 36 + i * 49;
            g.drawString(font, labels[i], cx - font.width(labels[i]) / 2, top + 151, MUTED, false);
            g.drawString(font, values[i], cx - font.width(values[i]) / 2, top + 165, colors[i], false);
        }
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
        g.drawString(font, ether, left + 14, y + 24, MUTED, false);
        Component flow = Component.translatable("gui.distantstock.tower.traffic",
                member.sent(), member.received());
        g.drawString(font, flow, left + W - 14 - font.width(flow), y + 24, MUTED, false);
    }

    /** A radius step. Sends the whole record, because the server stores what it is handed. */
    private int smallButton(GuiGraphics g, int x, int y, String glyph,
                            TowerReadout.Member member, int radius, boolean live) {
        int w = 11;
        g.fill(x, y, x + w, y + 11, live ? 0xFF4A5F66 : 0xFF39474C);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, live ? 0xFF2E444B : 0xFF232D31);
        g.drawString(font, glyph, x + (w - font.width(glyph)) / 2, y + 2,
                live ? 0xFFD8EEEA : 0xFF5C6B70, false);
        if (live) {
            hits.add(new Hit(x, y, w, 11, member, radius, member.loading(), member.carrying(), null));
        }
        return x + w;
    }

    private int switchButton(GuiGraphics g, int x, int y, String key, boolean on,
                             TowerReadout.Member member, boolean loading, boolean carrying) {
        Component label = Component.translatable(key);
        Component state = Component.translatable(on
                ? "gui.distantstock.tower.on" : "gui.distantstock.tower.off");
        int w = font.width(label) + font.width(state) + 10;
        g.fill(x, y, x + w, y + 11, 0xFF4A5F66);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, on ? 0xFF2E5A4B : 0xFF2E444B);
        g.drawString(font, label, x + 3, y + 2, MUTED, false);
        g.drawString(font, state, x + w - 3 - font.width(state), y + 2,
                on ? 0xFF9BE0C4 : 0xFFC0A090, false);
        hits.add(new Hit(x, y, w, 11, member, member.chunkRadius(), loading, carrying, null));
        return x + w;
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

    private String flipValue(String current, int previousTenths, double value, String suffix) {
        if (flipTicks == 0 || previousTenths == 0) {
            return current;
        }
        double progress = (8 - flipTicks) / 8.0;
        if (progress < 0.5) {
            return n(previousTenths / 10.0) + suffix;
        }
        return current;
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
