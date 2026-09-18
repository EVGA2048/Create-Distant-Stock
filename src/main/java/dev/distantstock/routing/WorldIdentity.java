package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stable ID stored with a world/dimension; survives display-name and dimension-key changes.
 *
 * <p><b>这个 id 要落盘，而"落盘"在这里得自己说一声。</b>{@code SavedData} 的脏标记默认是
 * {@code false}，{@code DimensionDataStorage.save()} 只写脏的那些：新摇一个 id 就不管了，等于每次
 * 开机都换一个身份。玩家 2026-09-18 报的「重启服务器之后远仓终端看不到对面的库存，必须把请求台拆掉
 * 重放」根子就是它 —— 对面记着的网络 id 里带着**上一次开机**的世界 id，重启之后一律对不上，我们每次
 * 询问都被判成"你这张网络不在我这儿"。存档目录里从来没见过
 * {@code distantstock_world_identity.dat}，就是它留下的痕迹。
 *
 * <p>身份换了不只是库存看不见：跨服下单也是拿这个 id 认路的（{@code TranserverOrderService.accept}
 * 判的是同一件事），所以重启一次，整条跨服链都断，而且断得没有声音。
 */
public final class WorldIdentity extends SavedData {
    private static final String DATA_NAME = "distantstock_world_identity";
    private static final Factory<WorldIdentity> FACTORY = new Factory<>(WorldIdentity::fresh, WorldIdentity::load);

    private UUID id = UUID.randomUUID();

    /**
     * 新摇一个身份，并且**立刻标脏**。
     *
     * <p>工厂的构造器只在"存档里还没有这份数据"的时候被调用，所以这一下不会变成每次自动存档都重写
     * 一遍；读回来那一份走的是 {@link #load}，是干净的。
     */
    static WorldIdentity fresh() {
        WorldIdentity identity = new WorldIdentity();
        identity.setDirty();
        return identity;
    }

    public static UUID get(ServerLevel level) {
        return data(level).id;
    }

    /** A copied world must not keep the same routable identity while both copies are loaded. */
    public static void ensureUnique(MinecraftServer server) {
        var levels = new ArrayList<ServerLevel>();
        server.getAllLevels().forEach(levels::add);
        levels.sort(Comparator.comparing(level -> level.dimension().location().toString()));
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : levels) {
            WorldIdentity identity = data(level);
            if (!seen.add(identity.id)) {
                identity.id = UUID.randomUUID();
                identity.setDirty();
                seen.add(identity.id);
            }
        }
    }

    private static WorldIdentity data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putUUID("WorldId", id);
        return tag;
    }

    static WorldIdentity load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldIdentity identity = new WorldIdentity();
        if (tag.hasUUID("WorldId")) {
            identity.id = tag.getUUID("WorldId");
        }
        return identity;
    }
}
