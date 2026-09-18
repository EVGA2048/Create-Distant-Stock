package dev.distantstock.routing;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 世界身份是跨服那条链里"谁是谁"的根：一张网络的远程 id 里就钉着它。
 *
 * <p>玩家 2026-09-18 报的「重启服务器后已绑定网络的远仓终端看不到远程库存，必须拆掉请求台重放」
 * 追到底就是这里 —— 这个 id 从来没进过存档：{@code SavedData} 的脏标记默认是 {@code false}，
 * 而 {@code DimensionDataStorage} 只写脏的那些，新建的时候不说一声就等于每次开机换一个身份。
 *
 * <p>所以这里守的不是"这个类能跑"，而是**它到底会不会被写下去**：新建的那一刻必须是脏的，而读回来
 * 的那一份必须还是同一个 id、而且不必再写一遍。
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class WorldIdentityGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aFreshIdentityIsMarkedForWriting(GameTestHelper h) {
        WorldIdentity identity = WorldIdentity.fresh();
        h.assertTrue(identity.isDirty(),
                "新摇出来的世界身份没有标脏 —— SavedData 只写脏的那些，这个 id 就永远进不了存档，"
                        + "重启一次换一个，对面身上记着的网络 id 全部作废");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aStoredIdentityComesBackUnchangedAndClean(GameTestHelper h) {
        var registries = h.getLevel().registryAccess();
        WorldIdentity original = WorldIdentity.fresh();
        CompoundTag written = original.save(new CompoundTag(), registries);
        h.assertTrue(written.hasUUID("WorldId"), "存下去的世界身份里没有 id");

        WorldIdentity reloaded = WorldIdentity.load(written, registries);
        h.assertTrue(reloaded.save(new CompoundTag(), registries).getUUID("WorldId")
                        .equals(written.getUUID("WorldId")),
                "存一遍再读回来，世界 id 变了 —— 那对面记着的网络 id 一样作废");
        h.assertTrue(!reloaded.isDirty(), "读回来的身份是脏的：没变的东西不该每次自动存档都重写一遍");
        h.succeed();
    }
}
