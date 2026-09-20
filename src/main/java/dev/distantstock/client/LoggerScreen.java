package dev.distantstock.client;

import dev.distantstock.block.LoggerBlockEntity;

import dev.distantstock.event.EventRegistry;
import dev.distantstock.item.RequesterData;
import dev.distantstock.net.LoggerActionC2S;
import dev.distantstock.net.OpenLoggerS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Compact industrial event panel. The server remains authoritative for filters and ACK state. */
public final class LoggerScreen extends Screen {
    private static final int W = 304;
    private static final int H = 222;
    private static final int ROWS = 8;
    private static final int ROW_H = 18;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final int FRAME = 0xFF11191C;
    private static final int PANEL = 0xFF1D292D;
    private static final int PANEL_2 = 0xFF26363B;
    private static final int INK = 0xFFE6EFEF;
    private static final int MUTED = 0xFF8DA1A6;
    private static final int GOOD = 0xFF74B98A;
    private static final int WARN = 0xFFD9A34B;
    private static final int ERROR = 0xFFD65C55;

    private final BlockPos source;
    private OpenLoggerS2C snapshot;
    private int scroll;
    private int refreshTicks;
    private final List<Hit> hits = new ArrayList<>();

    public LoggerScreen(OpenLoggerS2C snapshot) {
        super(Component.translatable("gui.distantstock.logger.title"));
        this.source = snapshot.source();
        this.snapshot = snapshot;
    }

    public boolean isSource(BlockPos pos) {
        return source.equals(pos);
    }

    public void update(OpenLoggerS2C next) {
        if (next == null || !source.equals(next.source())) return;
        snapshot = next;
        scroll = Math.min(scroll, maxScroll());
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            PacketDistributor.sendToServer(LoggerActionC2S.refresh(source));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, FRAME);
        g.fill(x, y, x + W, y + H, PANEL);
        g.fill(x + 8, y + 8, x + W - 8, y + 30, PANEL_2);

        hits.clear();
        drawHeader(g, x, y, mouseX, mouseY);
        drawFilters(g, x, y, mouseX, mouseY);
        drawRows(g, x, y, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        Component title = Component.translatable("gui.distantstock.logger.title");
        g.drawString(font, title, x + 14, y + 15, INK, false);

        long active = snapshot.rows().stream().filter(OpenLoggerS2C.Row::active).count();
        boolean hasError = snapshot.rows().stream().anyMatch(row -> row.active()
                && row.severity() == EventRegistry.Severity.ERROR);
        boolean hasWarn = snapshot.rows().stream().anyMatch(row -> row.active()
                && row.severity() == EventRegistry.Severity.WARN);
        int lamp = hasError ? ERROR : hasWarn ? WARN : GOOD;
        g.fill(x + W - 70, y + 14, x + W - 64, y + 20, 0xFF3A2F28);
        g.fill(x + W - 69, y + 15, x + W - 65, y + 19, lamp);
        Component activeText = Component.translatable("gui.distantstock.logger.active", active);
        g.drawString(font, activeText, x + W - 58, y + 14, MUTED, false);

        String scope = snapshot.createFrequency() == null
                ? Component.translatable("gui.distantstock.logger.scope.all").getString()
                : Component.translatable("gui.distantstock.logger.scope.network",
                RequesterData.shortFreq(snapshot.createFrequency())).getString();
        g.drawString(font, fit(scope, W - 28), x + 14, y + 34, MUTED, false);
        Component paper = Component.translatable("gui.distantstock.logger.paper",
                snapshot.paperRemaining(), LoggerBlockEntity.PAPER_CAPACITY);
        g.drawString(font, paper, x + W - 14 - font.width(paper), y + 34,
                snapshot.paperRemaining() > 0 ? GOOD : ERROR, false);
    }

    private void drawFilters(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int bx = x + 14;
        int by = y + 47;
        for (EventRegistry.Severity severity : EventRegistry.Severity.values()) {
            String key = "gui.distantstock.logger.level." + severity.name().toLowerCase(java.util.Locale.ROOT);
            Component label = Component.translatable(key);
            int w = 48;
            boolean selected = snapshot.minimumSeverity() == severity;
            boolean over = inside(mouseX, mouseY, bx, by, w, 15);
            g.fill(bx, by, bx + w, by + 15, selected ? 0xFF52676D : over ? 0xFF405158 : 0xFF324147);
            g.drawString(font, label, bx + (w - font.width(label)) / 2, by + 4,
                    selected ? INK : MUTED, false);
            EventRegistry.Severity choice = severity;
            hits.add(new Hit(bx, by, w, 15,
                    () -> PacketDistributor.sendToServer(LoggerActionC2S.level(source, choice))));
            bx += w + 5;
        }
    }

    private void drawRows(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int listX = x + 10;
        int listY = y + 69;
        int listW = W - 20;
        g.fill(listX, listY, listX + listW, listY + ROWS * ROW_H + 2, 0xFF151E21);

        List<OpenLoggerS2C.Row> rows = snapshot.rows();
        if (rows.isEmpty()) {
            Component empty = Component.translatable("gui.distantstock.logger.empty");
            g.drawString(font, empty, listX + (listW - font.width(empty)) / 2,
                    listY + 66, MUTED, false);
            return;
        }

        int end = Math.min(rows.size(), scroll + ROWS);
        for (int index = scroll; index < end; index++) {
            OpenLoggerS2C.Row row = rows.get(index);
            int rowY = listY + 1 + (index - scroll) * ROW_H;
            drawRow(g, row, listX + 1, rowY, listW - 2, mouseX, mouseY);
        }
        if (rows.size() > ROWS) {
            int barH = Math.max(12, (ROWS * ROW_H) * ROWS / rows.size());
            int travel = ROWS * ROW_H - barH;
            int barY = listY + (maxScroll() == 0 ? 0 : travel * scroll / maxScroll());
            g.fill(listX + listW - 3, listY, listX + listW - 1, listY + ROWS * ROW_H, 0xFF273439);
            g.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0xFF70868B);
        }
    }

    private void drawRow(GuiGraphics g, OpenLoggerS2C.Row row, int x, int y, int w,
                         int mouseX, int mouseY) {
        int severity = switch (row.severity()) {
            case INFO -> 0xFF60777D;
            case WARN -> WARN;
            case ERROR -> ERROR;
        };
        int bg = row.active() ? 0xFF223036 : 0xFF1B2529;
        g.fill(x, y, x + w, y + ROW_H - 1, bg);
        g.fill(x, y, x + 3, y + ROW_H - 1, severity);

        String time = TIME.format(Instant.ofEpochMilli(row.updatedAt()));
        g.drawString(font, time, x + 7, y + 3, MUTED, false);
        String code = fit(row.code(), 98);
        g.drawString(font, code, x + 54, y + 3, row.active() ? INK : MUTED, false);
        String source = fit(row.sourceId(), 78);
        g.drawString(font, source, x + 154, y + 3, MUTED, false);
        if (row.count() > 1) {
            g.drawString(font, "×" + row.count(), x + 235, y + 3, MUTED, false);
        }

        int ax = x + w - 44;
        if (!row.active()) {
            g.drawString(font, Component.translatable("gui.distantstock.logger.cleared"),
                    ax, y + 3, GOOD, false);
        } else if (row.acknowledged()) {
            g.drawString(font, Component.translatable("gui.distantstock.logger.acknowledged"),
                    ax, y + 3, MUTED, false);
        } else if (row.severity() != EventRegistry.Severity.INFO) {
            boolean paperAvailable = snapshot.paperRemaining() > 0;
            boolean over = inside(mouseX, mouseY, ax - 3, y + 1, 42, 14);
            g.fill(ax - 3, y + 1, ax + 39, y + 15,
                    !paperAvailable ? 0xFF2E3436 : over ? 0xFF5A4B36 : 0xFF463A2E);
            Component print = Component.translatable("gui.distantstock.logger.print");
            g.drawString(font, print, ax + 18 - font.width(print) / 2, y + 4,
                    paperAvailable ? WARN : MUTED, false);
            if (paperAvailable) {
                hits.add(new Hit(ax - 3, y + 1, 42, 14,
                        () -> PacketDistributor.sendToServer(LoggerActionC2S.print(source(), row.id()))));
            }
        }
    }

    private BlockPos source() {
        return source;
    }

    private int maxScroll() {
        return Math.max(0, snapshot.rows().size() - ROWS);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inside(mx, my, left() + 10, top() + 69, W - 20, ROWS * ROW_H + 2)) {
            scroll = Math.clamp(scroll - (int) Math.signum(sy), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit hit : List.copyOf(hits)) {
                if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private String fit(String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String suffix = "…";
        String value = text;
        while (!value.isEmpty() && font.width(value + suffix) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + suffix;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }
}
