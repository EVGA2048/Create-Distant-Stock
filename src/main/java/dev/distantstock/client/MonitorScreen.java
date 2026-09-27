package dev.distantstock.client;

import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.item.ModItems;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;


/** 固定像素布局的 Create 风链路仪表板。 */
public final class MonitorScreen extends Screen {
    /** Bee Port width, with a taller monitor-only information body. */
    private static final int W = 220;
    private static final int H = 112;
    /** Bee Port is a Package Port, not a Frogport: its real header is POSTBOX_HEADER (24 px). */
    private static final int HEADER_H = 24;
    private static final int INK = 0x3D3C48;
    private static final int MUTED = 0x687078;
    private static final int AETHER = 0x3A9DB0;
    private static final int GOOD = 0x4C9B7A;
    private static final int WARN = 0xC18A4A;
    /** Darker amber for warning text drawn directly on the light checker panel. */
    private static final int TRANSPORT_WARN = 0x8F5A1F;
    private static final int BAD = 0xB65E57;
    /** Bee Port body with its bee-slot field removed and the grey information area extended. */
    private static final ResourceLocation BEE_PORT_PANEL = ResourceLocation.fromNamespaceAndPath(
            "distantstock", "textures/gui/monitor_bee_port.png");

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
    /** Which half of the dashboard is showing. */
    private boolean towerPage;
    private int towerIndex;

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
    private void layout() {
        left = (width - W) / 2;
        // PackagePortScreen treats top as the body top and draws its header immediately above it.
        top = (height - (H + HEADER_H)) / 2 + HEADER_H;
    }

    @Override
    protected void init() {
        layout();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        layout();
        g.fill(0, 0, width, height, 0x24000000);
        renderBeePortPanel(g);

        Component title = Component.translatable(towerOnly
                ? "gui.distantstock.tower.control"
                : "gui.distantstock.monitor");
        int headerW = AllGuiTextures.POSTBOX_HEADER.getWidth();
        g.drawString(font, title, left + (headerW - font.width(title)) / 2, top - 11, INK, false);

        // PackagePortScreen renders its block item at exactly this position and scale. Keeping the
        // monitor there makes the whole screen read as one of Create's package-port family UIs.
        var monitorIcon = GuiGameElement.of(ModItems.MONITOR.get().getDefaultInstance());
        monitorIcon.at(left + W + 6, top + H - 56, -200);
        monitorIcon.scale(4).render(g);

        hits.clear();
        // The Bee/Package Port footer already contains the visual confirm button. Unlike the
        // original PackagePortScreen this monitor is a plain Screen, so there is no IconButton
        // widget behind that artwork unless we explicitly register its hit area. Tower controls
        // apply immediately; the check mark simply closes the dashboard, matching Create's port UI.
        hits.add(new Hit(left + W - 33, top + H - 24, 18, 18,
                null, 0, false, false, this::onClose));
        if (!towerOnly) drawPageToggle(g, mouseX, mouseY);
        if (towerPage) {
            drawTowerPage(g, mouseX, mouseY);
        } else {
            drawLinkOverview(g);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * Bee Port composition, without its bee inventory matrix. Bee Port's actual PackagePortScreen
     * uses POSTBOX_HEADER; using FROGPORT_HEADER here was the source of the stray frog eyes.
     */
    private void renderBeePortPanel(GuiGraphics g) {
        AllGuiTextures.POSTBOX_HEADER.render(g, left, top - HEADER_H);
        g.blit(BEE_PORT_PANEL, left, top, 0, 47, W, H);
    }

    /** Bee Port puts its controls on the left rail; the monitor uses the same placement. */
    private void drawPageToggle(GuiGraphics g, int mouseX, int mouseY) {
        sideButton(g, left - 22, top - 10, "链", !towerPage, () -> towerPage = false, mouseX, mouseY);
        sideButton(g, left - 22, top + 8, "塔", towerPage, () -> towerPage = true, mouseX, mouseY);
    }

    private void sideButton(GuiGraphics g, int x, int y, String glyph, boolean active,
                            Runnable action, int mouseX, int mouseY) {
        boolean over = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
        AllGuiTextures texture = active ? AllGuiTextures.BUTTON_DOWN
                : over ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON;
        texture.render(g, x, y);
        g.drawString(font, glyph, x + (18 - font.width(glyph)) / 2, y + 5, active ? INK : MUTED, false);
        hits.add(new Hit(x, y, 18, 18, null, 0, false, false, action));
    }

    /**
     * Link page: only node-local metrics and transport queues. A multi-node Transerver has no single
     * meaningful "peer TPS/MSPT", so that old readout stays gone.
     */
    private void drawLinkOverview(GuiGraphics g) {
        boolean up = view.linkUp();
        (up ? AllGuiTextures.INDICATOR_GREEN : AllGuiTextures.INDICATOR_RED)
                .render(g, left + 12, top + 10);
        Component state = Component.translatable(up
                ? "gui.distantstock.status.online" : "gui.distantstock.status.offline");
        g.drawString(font, state, left + 36, top + 13, up ? GOOD : BAD, false);

        // The Chinese status label is wider than the English one. Keep the route as a distinct,
        // right-aligned field instead of letting it begin at a fixed x and collide with the state.
        String route = fit(view.linkLabel(), 66);
        g.drawString(font, route, left + W - 22 - font.width(route), top + 13,
                up ? AETHER : MUTED, false);

        double tps = view.localTps();
        AllGuiTextures perf = tps >= 18 ? AllGuiTextures.INDICATOR_GREEN
                : tps >= 15 ? AllGuiTextures.INDICATOR_YELLOW : AllGuiTextures.INDICATOR_RED;
        perf.render(g, left + 12, top + 28);
        g.drawString(font, Component.translatable("gui.distantstock.local"), left + 36, top + 31, INK, false);
        String tpsText = oneDecimal(tps) + " TPS";
        String msptText = oneDecimal(view.localMspt()) + " MSPT";
        g.drawString(font, tpsText, left + 108 - font.width(tpsText) / 2, top + 31, INK, false);
        g.drawString(font, msptText, left + 174 - font.width(msptText) / 2, top + 31, INK, false);

        // Two clean three-column rows. Centering the complete label/value pairs makes English and
        // Chinese localisations share the same visual grid instead of drifting with string width.
        drawMetricCentered(g, left + 38, top + 46, "OUT", view.transerverOutbox(),
                view.transerverOutbox() == 0 ? INK : WARN);
        drawMetricCentered(g, left + 110, top + 46, "IN", view.transerverInbox(),
                view.transerverInbox() == 0 ? INK : WARN);
        drawMetricCentered(g, left + 182, top + 46, "DONE", view.transerverCompleted(), INK);

        drawMetricCentered(g, left + 38, top + 60,
                Component.translatable("gui.distantstock.orders.label").getString(), view.orderDepth(),
                view.orderDepth() == 0 ? MUTED : WARN);
        drawMetricCentered(g, left + 110, top + 60,
                Component.translatable("gui.distantstock.packages.label").getString(), view.packageDepth(),
                view.packageDepth() == 0 ? MUTED : WARN);
        drawMetricCentered(g, left + 182, top + 60,
                Component.translatable("gui.distantstock.in_flight.label").getString(), view.inFlight(),
                view.inFlight() == 0 ? MUTED : AETHER);

        // Keep the Bee Port footer purely decorative. Transport health belongs to the grey
        // information area above it, not in the white action strip.
        if (view.transerverDeadLetters() > 0) {
            AllGuiTextures.INDICATOR_RED.render(g, left + 12, top + 70);
            g.drawString(font, "DEAD " + view.transerverDeadLetters(), left + 36, top + 73, BAD, false);
        } else {
            (view.transerverAttached() ? AllGuiTextures.INDICATOR_GREEN : AllGuiTextures.INDICATOR_YELLOW)
                    .render(g, left + 12, top + 70);
            String transport = view.transerverAttached() ? "Transerver" : "Transerver —";
            g.drawString(font, transport, left + 36, top + 73,
                    view.transerverAttached() ? MUTED : TRANSPORT_WARN, false);
        }
    }

    private void drawMetricCentered(GuiGraphics g, int centerX, int y, String label, int value, int color) {
        String text = label + " " + value;
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** One tower at a time, using the same information hierarchy as the Bee Port layout. */
    private void drawTowerPage(GuiGraphics g, int mouseX, int mouseY) {
        TowerReadout tower = view.tower();
        if (!tower.attached() || tower.members().isEmpty()) {
            Component none = Component.translatable("gui.distantstock.tower.none");
            g.drawString(font, none, left + W / 2 - font.width(none) / 2, top + 48, BAD, false);
            return;
        }

        if (towerIndex >= tower.members().size()) towerIndex = tower.members().size() - 1;
        if (towerIndex < 0) towerIndex = 0;
        TowerReadout.Member member = tower.members().get(towerIndex);

        Component summary = Component.translatable("gui.distantstock.tower.summary",
                tower.members().size(), tower.carried(), tower.limit());
        g.drawString(font, fit(summary.getString(), 142), left + 12, top + 10, MUTED, false);

        if (tower.members().size() > 1) {
            navButton(g, left + 168, top + 4, "‹", () -> towerIndex = Math.floorMod(towerIndex - 1, tower.members().size()), mouseX, mouseY);
            navButton(g, left + 188, top + 4, "›", () -> towerIndex = (towerIndex + 1) % tower.members().size(), mouseX, mouseY);
        }

        BlockPos base = BlockPos.of(member.pos());
        String tier = member.tier().isBlank() ? "—" : member.tier();
        String head = "#" + base.getX() + "," + base.getY() + "," + base.getZ() + "  " + tier;
        g.drawString(font, fit(head, 145), left + 12, top + 27, INK, false);
        String speed = Component.translatable("gui.distantstock.tower.speed", (int) Math.abs(member.speed())).getString();
        g.drawString(font, speed, left + W - 12 - font.width(speed), top + 27,
                member.overstressed() ? BAD : member.running() ? MUTED : WARN, false);

        int x = left + 12;
        boolean canShrink = member.chunkRadius() > 0;
        boolean canGrow = member.chunkRadius() < memberCeiling(member);
        x = towerButton(g, x, top + 41, "−", member, member.chunkRadius() - 1,
                member.loading(), member.carrying(), canShrink);
        String radius = Integer.toString(member.chunkRadius());
        g.drawString(font, radius, x + 5, top + 46, INK, false);
        x += font.width(radius) + 10;
        x = towerButton(g, x, top + 41, "+", member, member.chunkRadius() + 1,
                member.loading(), member.carrying(), canGrow);
        x += 8;
        x = towerButton(g, x, top + 41, Component.translatable("gui.distantstock.tower.loading.short").getString(),
                member, member.chunkRadius(), !member.loading(), member.carrying(), true, member.loading());
        towerButton(g, x + 4, top + 41, Component.translatable("gui.distantstock.tower.carrying.short").getString(),
                member, member.chunkRadius(), member.loading(), !member.carrying(), true, member.carrying());

        Component reach = Component.translatable("gui.distantstock.tower.reach",
                member.radius(), member.devices());
        g.drawString(font, fit(reach.getString(), 190), left + 12, top + 62, MUTED, false);

        Component ether = Component.translatable("gui.distantstock.tower.ether",
                member.ether(), TowerCoreBlockEntity.ETHER_CAPACITY);
        g.drawString(font, ether, left + 12, top + 74, MUTED, false);
        Component flow = Component.translatable("gui.distantstock.tower.traffic", member.sent(), member.received());
        String flowText = fit(flow.getString(), 105);
        // Give the right-hand traffic readout a wider safety margin than the normal panel text;
        // CJK glyphs made the previous 12 px margin look clipped against the frame.
        g.drawString(font, flowText, left + W - 28 - font.width(flowText), top + 74, MUTED, false);
    }

    private void navButton(GuiGraphics g, int x, int y, String glyph, Runnable action, int mouseX, int mouseY) {
        boolean over = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
        (over ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON).render(g, x, y);
        g.drawString(font, glyph, x + (18 - font.width(glyph)) / 2, y + 5, INK, false);
        hits.add(new Hit(x, y, 18, 18, null, 0, false, false, action));
    }

    private int towerButton(GuiGraphics g, int x, int y, String glyph, TowerReadout.Member member,
                            int radius, boolean loading, boolean carrying, boolean live) {
        return towerButton(g, x, y, glyph, member, radius, loading, carrying, live, false);
    }

    private int towerButton(GuiGraphics g, int x, int y, String glyph, TowerReadout.Member member,
                            int radius, boolean loading, boolean carrying, boolean live, boolean green) {
        AllGuiTextures texture = !live ? AllGuiTextures.BUTTON_DISABLED
                : green ? AllGuiTextures.BUTTON_GREEN : AllGuiTextures.BUTTON;
        texture.render(g, x, y);
        g.drawString(font, glyph, x + (18 - font.width(glyph)) / 2, y + 5, live ? INK : MUTED, false);
        if (live) hits.add(new Hit(x, y, 18, 18, member, radius, loading, carrying, null));
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
        return x >= left - 22 && x <= left + W && y >= top - HEADER_H && y <= top + H;
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
