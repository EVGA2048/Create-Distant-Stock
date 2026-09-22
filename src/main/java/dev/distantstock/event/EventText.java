package dev.distantstock.event;

import net.minecraft.network.chat.Component;

/** Human-readable presentation for stable event codes. Codes remain unchanged in saved/event data. */
public final class EventText {
    public static Component title(String code) {
        if (code == null || code.isBlank()) return Component.empty();
        return switch (code) {
            case EventRegistry.Codes.CHAIN_NO_ROUTE ->
                    Component.translatable("event.distantstock.chain.no_route.title");
            case EventRegistry.Codes.CHAIN_PING_TIMEOUT ->
                    Component.translatable("event.distantstock.chain.ping_timeout.title");
            case EventRegistry.Codes.CHAIN_CACHE_FULL ->
                    Component.translatable("event.distantstock.chain.cache_full.title");
            default -> Component.literal(code);
        };
    }

    public static Component detail(String code, String detail) {
        String value = detail == null ? "" : detail;
        return switch (code) {
            case EventRegistry.Codes.CHAIN_NO_ROUTE ->
                    Component.translatable("event.distantstock.chain.no_route.detail", value);
            case EventRegistry.Codes.CHAIN_PING_TIMEOUT ->
                    Component.translatable("event.distantstock.chain.ping_timeout.detail", value);
            case EventRegistry.Codes.CHAIN_CACHE_FULL ->
                    Component.translatable("event.distantstock.chain.cache_full.detail", value);
            default -> value.isBlank() ? Component.empty() : Component.translatable(value);
        };
    }

    private EventText() {
    }
}
