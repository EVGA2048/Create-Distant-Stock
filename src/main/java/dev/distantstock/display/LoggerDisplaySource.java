package dev.distantstock.display;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.event.EventRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Create Display Link source exposed by a Distant Stock event logger. */
public final class LoggerDisplaySource extends DisplaySource {
    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof LoggerBlockEntity logger)) {
            return EMPTY;
        }

        List<EventRegistry.Record> active = logger.activeRows();
        long unprinted = active.stream()
                .filter(row -> row.severity() != EventRegistry.Severity.INFO && !row.printed())
                .count();

        List<MutableComponent> lines = new ArrayList<>();
        lines.add(Component.translatable("display.distantstock.logger.summary",
                logger.displayCode(), active.size(), unprinted));
        if (stats.maxRows() <= 1) {
            return lines;
        }

        EventRegistry.Record latest = logger.rows().stream()
                .max(Comparator.comparingLong(EventRegistry.Record::updatedAt))
                .orElse(null);
        if (latest == null) {
            lines.add(Component.translatable("display.distantstock.logger.no_events"));
            return lines;
        }

        lines.add(Component.literal(latest.severity().name() + " / " + latest.code()));
        if (stats.maxRows() > 2 && latest.detail() != null && !latest.detail().isBlank()) {
            lines.add(Component.literal(latest.detail()));
        }
        if (stats.maxRows() > 3) {
            lines.add(Component.translatable("display.distantstock.logger.paper",
                    logger.paperRemaining(), LoggerBlockEntity.PAPER_CAPACITY));
        }
        return lines;
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}
