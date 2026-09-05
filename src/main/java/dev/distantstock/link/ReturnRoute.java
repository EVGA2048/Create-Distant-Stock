package dev.distantstock.link;

import java.util.concurrent.ConcurrentHashMap;

/** 星型：下单的 from 记在地址上，出货港寄包裹时找回对端。 */
public final class ReturnRoute {
    private static final ConcurrentHashMap<String, String> BY_ADDR = new ConcurrentHashMap<>();

    public static void remember(String address, String from) {
        if (from == null || from.isBlank()) {
            return;
        }
        BY_ADDR.put(address == null ? "" : address, from);
    }

    public static String peek(String address) {
        return BY_ADDR.getOrDefault(address == null ? "" : address, "");
    }

    private ReturnRoute() {
    }
}
