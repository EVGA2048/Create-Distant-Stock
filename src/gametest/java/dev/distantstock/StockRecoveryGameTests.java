package dev.distantstock;

import dev.distantstock.menu.MenuSync;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 跨服库存"自己会回来"的两条保证。
 *
 * <p>玩家 2026-09-18 报的：「重启服务器后已绑定网络的远仓终端无法看到远程服务器的库存，会显示为空的。
 * 必须拆掉远仓请求台或蹲下重置便携式远仓终端再重新加入才行。」查下来是两条各自独立的死路，两条都
 * 只在"重启"这个时刻显形，平时怎么点都看不出来：
 *
 * <ol>
 *   <li><b>对面说"不认识它"被当成终审。</b>服务器刚起来那一秒，本地目录还是空的，请求台所在的区块
 *       也还没打开，于是对面把"我这儿暂时没有这张网络"答成了"没有这张网络"；这边收到就把监视撤了，
 *       而且再也不问 —— 界面永远是空的，直到玩家做点什么把它重新指一遍。</li>
 *   <li><b>只记得频率的设备没人替它问。</b>跨服那条链路是按网络 id 问的（监视表里存的就是它），
 *       而老设备身上可能只有那个旧 UUID。只按频率 watch 等于什么都没发生。</li>
 * </ol>
 *
 * <p>这两条都是"过一会儿就会好"的事情，所以测的是**它会不会自己好**：退避要过期、要能被一次成功的
 * 答复或玩家的重新指向清掉，而只有频率的设备要从目录里把网络 id 补出来。
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class StockRecoveryGameTests {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRefusedNetworkIsAskedAgainRatherThanAbandoned(GameTestHelper h) {
        RemoteNetworkId network = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID());

        StockCache.clearRefusal(network);
        h.assertTrue(StockCache.refusalWaitMs(network) == 0L, "没被拒过的网络却在等");

        StockCache.refuse(network);
        long first = StockCache.refusalWaitMs(network);
        h.assertTrue(first > 0L, "对面说了不认识，却还是马上去问");

        StockCache.refuse(network);
        h.assertTrue(StockCache.refusalWaitMs(network) > first, "连着被拒没有等得更久");
        h.assertTrue(StockCache.refusalWaitMs(network) <= 5 * 60_000L, "退避没有上限");

        // 答上来了（或者玩家重新把它指了一遍）：退避作废，下一次就去问。
        StockCache.clearRefusal(network);
        h.assertTrue(StockCache.refusalWaitMs(network) == 0L, "重新指向之后还在等");
        h.succeed();
    }

    /**
     * 对面记着的是**上一次开机的我们**，我们照样要答。
     *
     * <p>这是玩家 2026-09-18 那句「重启还是要拆掉重放」的最后一段：世界身份以前不落盘（见
     * {@code WorldIdentity}），于是每次开机都换一个世界 id；而收到询问的一方以前**先判世界 id**，
     * 对面身上存的那份自然是旧的，于是一律答"没有这张网络" —— 频率明明就在自己身上。
     *
     * <p>规则现在是：频率在这儿就答，世界 id 只是对面记着的我们长什么样。世界 id 只在**网络不在这儿**
     * 的时候还有意义 —— 那才是"这个世界被换过了"。
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aQueryCarryingAnOldWorldIdIsStillAnswered(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        UUID node = UUID.randomUUID();
        UUID freq = UUID.randomUUID();
        String dimension = level.dimension().location().toString();
        UUID currentWorld = dev.distantstock.routing.WorldIdentity.get(level);
        UUID oldWorld = UUID.randomUUID();

        var stale = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node, oldWorld, dimension, freq);
        var fresh = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node, currentWorld, dimension, freq);
        var otherNode = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, UUID.randomUUID(), oldWorld,
                dimension, freq);

        // 网络不在我这儿：世界 id 对不上 = 这个世界被换过了（终审）；对得上 = 只是还没加载（再问）。
        h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, node, stale)
                == dev.transerver.api.DeliveryResult.REJECTED, "没这张网络、世界也换过了，却不回终审");
        h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, node, fresh)
                == dev.transerver.api.DeliveryResult.RETRY, "世界是对的、只是还没加载，却回了一个终审");
        h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, null, fresh)
                == dev.transerver.api.DeliveryResult.REJECTED, "没挂上 Transerver 却认下了这张网络");
        h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, node, otherNode)
                == dev.transerver.api.DeliveryResult.REJECTED, "问的是别的节点却认下了");

        // 网络就在我这儿：世界 id 是旧的也照答 —— **这一条就是那个 bug**。
        var networks = com.simibubi.create.Create.LOGISTICS.logisticsNetworks;
        networks.put(freq, new com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork(freq));
        try {
            h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, node, fresh)
                    == dev.transerver.api.DeliveryResult.APPLIED, "频率就在这儿，却答了「没有这张网络」");
            h.assertTrue(dev.distantstock.link.TranserverStockService.validateLocal(server, node, stale)
                    == dev.transerver.api.DeliveryResult.APPLIED,
                    "对面记的世界 id 是上一次开机的，就不肯认自己身上这张网络了 —— 重启一次跨服链全断");
        } finally {
            networks.remove(freq);
        }
        h.succeed();
    }

    /** 设备身上那份旧的世界 id 要从目录里被换成新的：对面重新公告过，目录里那份才是现在的它。 */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aStaleNetworkIdOnADeviceIsRefreshedFromTheDirectory(GameTestHelper h) {
        UUID freq = UUID.randomUUID();
        UUID node = UUID.randomUUID();
        var stale = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node, UUID.randomUUID(),
                "minecraft:overworld", freq);
        var current = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, node, UUID.randomUUID(),
                "minecraft:overworld", freq);
        var elsewhere = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA, UUID.randomUUID(),
                UUID.randomUUID(), "minecraft:overworld", freq);

        List<NetworkDirectory.Entry> before = NetworkDirectory.local();
        try {
            NetworkDirectory.replaceLocal(List.of(new NetworkDirectory.Entry(freq, "本服", 2, current, true)));
            h.assertTrue(MenuSync.resolve(stale, freq) == current,
                    "设备身上是上一次开机的世界 id，目录里那份新的没把它换掉");
            h.assertTrue(MenuSync.resolve(null, freq) == current, "只记得频率的没补出网络 id");

            // 频率撞了、节点不一样：那是两个人，不能拿目录里这份去盖设备身上那份。
            NetworkDirectory.replaceLocal(List.of(new NetworkDirectory.Entry(freq, "别服", 2, elsewhere, false)));
            h.assertTrue(MenuSync.resolve(stale, freq) == stale, "换了节点却还是照盖，指向就换了个人");
        } finally {
            NetworkDirectory.replaceLocal(before);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aFrequencyOnlyDeviceStillAsksTheOtherServer(GameTestHelper h) {
        UUID freq = UUID.randomUUID();
        RemoteNetworkId network = new RemoteNetworkId(RemoteNetworkId.CURRENT_SCHEMA,
                UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", freq);

        List<NetworkDirectory.Entry> before = NetworkDirectory.local();
        try {
            NetworkDirectory.replaceLocal(List.of(new NetworkDirectory.Entry(
                    freq, "别的服务器", 1, network, false)));
            h.assertTrue(MenuSync.resolve(null, freq) == network,
                    "只记得频率的设备没能从目录里补出网络 id —— 那样就没人替它去问对面");
            h.assertTrue(MenuSync.resolve(network, freq) == network, "已经带着网络 id 的被改掉了");
            h.assertTrue(MenuSync.resolve(null, UUID.randomUUID()) == null,
                    "目录里没有的频率不该凭空变出一个网络 id");
        } finally {
            NetworkDirectory.replaceLocal(before);
        }
        h.succeed();
    }
}
