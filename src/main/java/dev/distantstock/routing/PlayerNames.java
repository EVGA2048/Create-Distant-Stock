package dev.distantstock.routing;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Turning typed names into accounts, and accounts back into something a player can read.
 *
 * <p>Every list this mod shows used to be keyed on a uuid, and a uuid is not a name: the terminal
 * said "5d2a90b4" where the player wanted to know whose warehouse this is. The name is what the
 * server already knows and the player already recognises, so it is what gets shown.
 *
 * <p>Resolution is deliberately forgiving about where the name comes from — the player list first
 * because it is authoritative and free, then the profile cache, which is the file the server keeps
 * of everyone who has ever joined. A name neither of those knows cannot be given a uuid, and
 * guessing one would name a membership at an account nobody owns.
 */
public final class PlayerNames {
    private PlayerNames() {
    }

    /**
     * The account behind a typed name: somebody online now, or somebody this server remembers.
     *
     * <p>Empty for a name this server has never seen. A member is added by name, so a typo would
     * otherwise become a membership for a player who does not exist — and, worse, would look
     * exactly like it had worked.
     */
    public static Optional<GameProfile> lookup(MinecraftServer server, String typed) {
        if (server == null || typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String name = typed.trim();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        // 档案缓存可能是 null：game test 的服务器没有它，正式服务器在起来的那一小段也还没有。
        // 这不是"查不到这个人"，只是"这里没有档可查"，所以返回空而不是炸。
        var cache = server.getProfileCache();
        // 名字查档，不区分大小写以外的花活：服务器自己就是这么认人的。
        return cache == null ? Optional.empty() : cache.get(name);
    }

    /** What to call an account: the name if anything here knows it, a short id if nothing does. */
    public static String display(MinecraftServer server, UUID player) {
        if (player == null) {
            return "";
        }
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) {
                return online.getGameProfile().getName();
            }
            var cache = server.getProfileCache();
            Optional<GameProfile> remembered = cache == null ? Optional.empty() : cache.get(player);
            if (remembered.isPresent()) {
                return remembered.get().getName();
            }
        }
        return shortId(player);
    }

    /**
     * The first eight characters of a uuid, for the rare place nothing has a name for one.
     *
     * <p>Never a substitute for a name that exists — it is here so a readout has something to print
     * rather than a blank, not as a way of identifying anybody.
     */
    public static String shortId(UUID id) {
        if (id == null) {
            return "?";
        }
        String text = id.toString();
        return text.substring(0, 8);
    }
}
