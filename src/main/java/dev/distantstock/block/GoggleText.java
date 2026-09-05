package dev.distantstock.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

final class GoggleText {
    static void title(List<Component> tip, String key) {
        tip.add(Component.empty());
        tip.add(Component.translatable(key).withStyle(ChatFormatting.WHITE));
    }

    static void line(List<Component> tip, String key, Object... args) {
        tip.add(t(key, args).withStyle(ChatFormatting.GRAY));
    }

    static void value(List<Component> tip, String key, ChatFormatting color, Object... args) {
        tip.add(t(key, args).withStyle(color));
    }

    static MutableComponent t(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private GoggleText() {
    }
}
