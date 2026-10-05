package dev.distantstock.display;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import dev.distantstock.block.AnnouncerBlockEntity;
import dev.distantstock.block.BroadcastSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Four Display Link target rows map directly to {1}..{4} on the announcer template. */
public final class AnnouncerDisplayTarget extends DisplayTarget {
    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        var targetBlockEntity = context.getTargetBlockEntity();
        if (!(targetBlockEntity instanceof BroadcastSource announcer)) return;
        if (text.isEmpty()) {
            if (line >= 0 && line < AnnouncerBlockEntity.PARAMETER_COUNT) {
                reserve(line, targetBlockEntity, context);
                announcer.setParameter(line, "");
            }
            return;
        }
        for (int i = 0; i < text.size(); i++) {
            int slot = line + i;
            if (slot < 0 || slot >= AnnouncerBlockEntity.PARAMETER_COUNT) break;
            reserve(slot, targetBlockEntity, context);
            announcer.setParameter(slot, text.get(i).getString());
        }
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        return new DisplayTargetStats(AnnouncerBlockEntity.PARAMETER_COUNT,
                AnnouncerBlockEntity.MAX_PARAMETER, this);
    }

    @Override
    public Component getLineOptionText(int line) {
        return Component.translatable("display_target.distantstock.announcer.parameter", line + 1);
    }
}
