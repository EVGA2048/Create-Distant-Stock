package dev.distantstock.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 星型：host 听口并填 peers 列表；外服 self.id 不重复，peer.host 只填仓库。
 * 兼容旧的 peer.host / peer.port。
 */
public final class StockConfig {
    public record Peer(String id, String host, int port) {
    }

    /** Transport mode: "transerver" (default), "legacy" (HTTP only), or "both". */
    public static final ModConfigSpec.ConfigValue<String> TRANSPORT_MODE;
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> ROLE;
    public static final ModConfigSpec.ConfigValue<String> SELF_ID;
    public static final ModConfigSpec.ConfigValue<String> BIND;
    public static final ModConfigSpec.ConfigValue<String> PEER_HOST;
    public static final ModConfigSpec.IntValue PEER_PORT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PEERS;
    public static final ModConfigSpec.ConfigValue<String> TOKEN;
    public static final ModConfigSpec.BooleanValue DEMO_STOCK;
    public static final ModConfigSpec.BooleanValue GIVE_MANUAL;
    public static final ModConfigSpec.IntValue CASING_REDSTONE_RANGE;
    public static final ModConfigSpec.BooleanValue TOWER_CHARGE_PARCELS;
    public static final ModConfigSpec.IntValue TOWER_PARCEL_COST;
    public static final ModConfigSpec.IntValue TOWER_MAX_SELECTED_CHUNKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment(
                "Distant Stock. Star: host listens, peers list warehouse clients.",
                "Peer line: id@host:port  e.g. a@10.0.0.2:18772",
                "Legacy peer.host / peer.port still work as one entry.");
        TRANSPORT_MODE = b.comment(
                        "Transport mode. 'transerver' = Transerver only (default).",
                        "'legacy' = HTTP only (old link). 'both' = start both (migration).")
                .define("transport.mode", "transerver");
        ROLE = b.comment("host = warehouse. client = outer survival server.")
                .define("self.role", "host");
        SELF_ID = b.define("self.id", "host");
        BIND = b.define("self.bind", "0.0.0.0:18772");
        PEER_HOST = b.comment("Legacy single peer. Used if peers is empty.")
                .define("peer.host", "");
        PEER_PORT = b.defineInRange("peer.port", 18772, 1, 65535);
        PEERS = b.comment("id@host:port. Warehouse lists all clients. Outer servers list only the host.")
                .defineListAllowEmpty("peers", List.of(), () -> "id@127.0.0.1:18772", o -> o instanceof String);
        TOKEN = b.define("token", "");
        DEMO_STOCK = b.define("debug.demoStock", false);
        GIVE_MANUAL = b.comment("Give one manual the first time a player joins this world.")
                .define("giveManual", true);
        CASING_REDSTONE_RANGE = b.comment(
                        "How far a redstone signal spreads through connected distant casings, in blocks,",
                        "before the window stops opening. Bounds a search per casing, so a large build",
                        "does not hitch when a lever is flipped.")
                .defineInRange("casing.redstoneRange", 32, 1, 64);
        TOWER_CHARGE_PARCELS = b.comment(
                        "Charge the tower ether for every parcel that leaves a dock it carries.",
                        "Off by default: the tower system is still being tested, and a server that",
                        "turned this on by accident would drain its towers before the price is settled.")
                .define("tower.chargeParcels", false);
        TOWER_PARCEL_COST = b.comment(
                        "Millibuckets of ether one parcel costs while tower.chargeParcels is on.",
                        "A parcel that cannot pay stays in its dock; it is never sent unbilled.")
                .defineInRange("tower.parcelCost", 250, 1, 1000000);
        TOWER_MAX_SELECTED_CHUNKS = b.comment(
                        "Ceiling on the chunks all monitors together may select, on top of the square",
                        "each tower already keeps around its own base. A selection that does not fit is",
                        "refused whole and the previous one stays. 0 turns the selector off.")
                .defineInRange("tower.maxSelectedChunks", 512, 0, 100000);
        SPEC = b.build();
    }

    /**
     * The casing window's reach, read often enough that the config's own value is worth not
     * unwrapping each time. Zero means the config is not loaded yet — the block can be ticked
     * before the server config file is read — and the compile-time default stands in.
     */
    public static int casingRedstoneRange() {
        try {
            return CASING_REDSTONE_RANGE.get();
        } catch (IllegalStateException notLoaded) {
            return 32;
        }
    }

    /**
     * Whether a tower pays for the parcels its docks send. Read here rather than through
     * {@link dev.distantstock.routing.TowerBilling}, which adds the switch a game test needs to
     * exercise both answers without editing a config file mid-run.
     */
    public static boolean towerChargeParcels() {
        try {
            return TOWER_CHARGE_PARCELS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** Millibuckets one parcel costs. See {@link #towerChargeParcels()}. */
    public static int towerParcelCost() {
        try {
            return TOWER_PARCEL_COST.get();
        } catch (IllegalStateException notLoaded) {
            return 250;
        }
    }

    /**
     * How many chunks the monitors may select between them. See {@link #towerChargeParcels()}.
     *
     * <p>Zero is a real answer — no selections at all — and is meant to be: a server owner who does
     * not want the feature can turn it off without taking the towers down with it.
     */
    public static int towerMaxSelectionChunks() {
        try {
            return TOWER_MAX_SELECTED_CHUNKS.get();
        } catch (IllegalStateException notLoaded) {
            return 512;
        }
    }

    /** Returns "transerver", "legacy", or "both". */
    public static String transportMode() {
        String m = TRANSPORT_MODE.get();
        if (m != null) {
            String lower = m.trim().toLowerCase(java.util.Locale.ROOT);
            if ("legacy".equals(lower) || "both".equals(lower)) {
                return lower;
            }
        }
        return "transerver";
    }

    public static boolean useTranserver() {
        String m = transportMode();
        return "transerver".equals(m) || "both".equals(m);
    }

    public static boolean useLegacy() {
        String m = transportMode();
        return "legacy".equals(m) || "both".equals(m);
    }

    public static String role() {
        String r = ROLE.get();
        if (r != null && r.equalsIgnoreCase("client")) {
            return "client";
        }
        return "host";
    }

    public static boolean isHost() {
        return "host".equals(role());
    }

    public static String selfId() {
        String id = SELF_ID.get();
        return id == null || id.isBlank() ? "host" : id.trim();
    }

    public static List<String> peerLines() {
        List<String> out = new ArrayList<>();
        for (Peer p : peers()) {
            out.add(p.id + "@" + p.host + ":" + p.port);
        }
        return out;
    }

    public static String peersText() {
        return String.join(", ", peerLines());
    }

    public static void apply(String role, String selfId, String bind, String token, String peersText) {
        String r = role != null && role.equalsIgnoreCase("client") ? "client" : "host";
        ROLE.set(r);
        SELF_ID.set(selfId == null || selfId.isBlank() ? (r.equals("host") ? "host" : "client") : selfId.trim());
        BIND.set(bind == null || bind.isBlank() ? "0.0.0.0:18772" : bind.trim());
        TOKEN.set(token == null ? "" : token);
        List<String> clean = new ArrayList<>();
        if (peersText != null && !peersText.isBlank()) {
            for (String part : peersText.split("[,;\\n]")) {
                Peer p = parse(part, r.equals("client") ? "host" : "peer");
                if (p != null) {
                    clean.add(p.id + "@" + p.host + ":" + p.port);
                }
            }
        }
        PEERS.set(clean);
        if (clean.size() == 1) {
            Peer p = parse(clean.getFirst(), "peer");
            if (p != null) {
                PEER_HOST.set(p.host);
                PEER_PORT.set(p.port);
            }
        } else {
            PEER_HOST.set("");
        }
        SPEC.save();
    }

    public static List<Peer> peers() {
        List<Peer> out = new ArrayList<>();
        for (Object raw : PEERS.get()) {
            Peer p = parse(String.valueOf(raw), "peer");
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            String host = PEER_HOST.get();
            if (host != null && !host.isBlank()) {
                out.add(new Peer("peer", host.trim(), PEER_PORT.get()));
            }
        }
        return out;
    }

    public static boolean hasPeer() {
        return !peers().isEmpty();
    }

    public static Peer first() {
        List<Peer> list = peers();
        return list.isEmpty() ? null : list.getFirst();
    }

    public static Peer byId(String id) {
        if (id == null || id.isBlank()) {
            return first();
        }
        for (Peer p : peers()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return first();
    }

    static Peer parse(String s, String defaultId) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        String id = defaultId == null || defaultId.isBlank() ? "peer" : defaultId;
        String rest = t;
        int at = t.indexOf('@');
        if (at > 0) {
            id = t.substring(0, at).trim();
            rest = t.substring(at + 1).trim();
        }
        int colon = rest.lastIndexOf(':');
        if (colon < 0) {
            return new Peer(id, rest, 18772);
        }
        try {
            return new Peer(id, rest.substring(0, colon).trim(), Integer.parseInt(rest.substring(colon + 1).trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private StockConfig() {
    }
}
