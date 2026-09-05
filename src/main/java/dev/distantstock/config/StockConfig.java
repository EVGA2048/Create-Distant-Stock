package dev.distantstock.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 两边管理员各填一份。一边 self.id=host 听端口，一边 peer 连出去。
 * 玩家方块里不填 IP / token。没有第三台中心服。
 */
public final class StockConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> SELF_ID;
    public static final ModConfigSpec.ConfigValue<String> BIND;
    public static final ModConfigSpec.ConfigValue<String> PEER_HOST;
    public static final ModConfigSpec.IntValue PEER_PORT;
    public static final ModConfigSpec.ConfigValue<String> TOKEN;
    public static final ModConfigSpec.BooleanValue DEMO_STOCK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment(
                "Distant Stock link contract.",
                "host listens (self.bind). peer connects out (peer.host:peer.port).",
                "Same token both sides. Header: X-DistantStock-Token.",
                "Do not put server display names in code or blocks.");
        SELF_ID = b.comment("This JVM's role id, usually host or peer. Shown on monitor / goggles.")
                .define("self.id", "host");
        BIND = b.comment("Listen address. Host must open this port. Peer can still listen for reverse status.")
                .define("self.bind", "0.0.0.0:18772");
        PEER_HOST = b.comment("Other side IP or hostname. Empty = no outbound HTTP.")
                .define("peer.host", "");
        PEER_PORT = b.defineInRange("peer.port", 18772, 1, 65535);
        TOKEN = b.comment("Shared secret. Empty = no auth (dev only).")
                .define("token", "");
        DEMO_STOCK = b.comment("Fake catalog when cache is empty. Off for live.")
                .define("debug.demoStock", false);
        SPEC = b.build();
    }

    public static String selfId() {
        String id = SELF_ID.get();
        return id == null || id.isBlank() ? "host" : id.trim();
    }

    public static boolean hasPeer() {
        String host = PEER_HOST.get();
        return host != null && !host.isBlank();
    }

    private StockConfig() {
    }
}
