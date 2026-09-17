package dev.distantstock.link;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Target-side parcel validation, de-duplication and insertion. */
public final class TranserverPackageService {
    private static final Logger LOG = LogManager.getLogger();

    /**
     * 每件还没落地的包裹，上一次"为什么还没落地"是什么时候说的。
     *
     * <p>返回 RETRY 之后重投是 Transerver 的事，投递间隔由它定，不归这边管；这边能管的是**别说
     * 得太少，也别说得太多**。以前这是 DEBUG 一行，于是"港收不了这件货"在一台正常运行的服务器上
     * 是完全不可见的：包裹就那样每几秒被解一次包、找一次港、再原样退回去，谁也不知道，直到有人去翻
     * 日志的 DEBUG。玩家报的"下单成功了然后什么都没到"有一半是这个。
     *
     * <p>现在第一遍说，之后每半分钟说一次。既不是静默，也不是把日志刷爆 —— 一件卡住的包裹能这样
     * 待上一整天。
     */
    private static final Map<java.util.UUID, Long> WAITING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COMPLAIN_INTERVAL_MS = 30_000;

    /** 第一次发现"这一侧没有这个收货港组"是什么时候，按游戏刻算。见 {@link #UNKNOWN_GROUP_GRACE_TICKS}。 */
    private static final Map<java.util.UUID, Long> UNKNOWN_GROUP_SINCE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 等多久才判定这个港组不会出现了：五分钟。
     *
     * <p>足够长：组是两边各建一次的，对面可能正在建（这条路上最慢的是人的手速，不是网络）。足够短：
     * 一件货在错误的地址上一动不动地躺着，是玩家会来问"我的东西呢"的那几分钟。
     */
    private static final long UNKNOWN_GROUP_GRACE_TICKS = 20L * 60 * 5;

    /** 这个收货港组在这一侧存在吗。默认组永远存在 —— 它写在每个存档的目录里。 */
    private static boolean groupExists(MinecraftServer server, java.util.UUID group) {
        if (group == null || group.equals(dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return true;
        }
        return dev.distantstock.routing.DockGroupDirectory.get(server).find(group).isPresent();
    }

    /**
     * 说一次"这件包裹还在等，原因是这个"，按 {@link #COMPLAIN_INTERVAL_MS} 限流。
     *
     * <p>理由用中文写，因为读这一行的人是在诊断"我的货为什么没到"，而不是在读协议。
     */
    private static void complain(PackageDispatchCodec.Dispatch dispatch, String sourceNode, String why) {
        long now = System.currentTimeMillis();
        Long last = WAITING.get(dispatch.parcelId());
        if (last != null && now - last < COMPLAIN_INTERVAL_MS) {
            return;
        }
        // 有界：一件包裹落地时会被移走，留下的只有真的没人管的那些，而它们不该无限增长。
        if (WAITING.size() > 512) {
            WAITING.clear();
        }
        WAITING.put(dispatch.parcelId(), now);
        LOG.warn("[DistantStock/Parcel] 包裹收不下，会一直重投 parcel={} source={} group={} 原因={}",
                dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(), why);
    }

    public static void register() {
        TranserverBridge.handler(RoutingChannels.PACKAGE_DISPATCH, TranserverPackageService::receive);
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        final PackageDispatchCodec.Dispatch dispatch;
        try {
            dispatch = PackageDispatchCodec.decode(message.payload());
        } catch (IOException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        String sourceNode = message.source();
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(apply(server, dispatch, sourceNode)));
        return result;
    }

    /**
     * Validates a dispatch against this server and inserts the parcel, returning what the caller
     * should do with the source-side record.
     *
     * <p>Public because the escrow pump delivers parcels addressed to this node in-process, with no
     * transport in between: the same validation, de-duplication and insertion must run for a local
     * delivery as for one that arrived over Transerver, or a parcel sent to "this node" would take a
     * second, weaker path through the code.
     */
    public static DeliveryResult apply(MinecraftServer server, PackageDispatchCodec.Dispatch dispatch,
                                       String sourceNode) {
        return apply(server, dispatch, sourceNode, UNKNOWN_GROUP_GRACE_TICKS);
    }

    /**
     * 同一个判断，宽限期由调用者给。
     *
     * <p>宽限期是参数而不是一个可改的静态量，因为 game test 是**并行跑在同一个 JVM 里**的：一个
     * "把宽限期调成 0"的开关会让同时在跑的别的用例一起变成立刻退件，而那种失败每次都换一批用例。
     * 调用者给，测出来的就只是自己这一条 —— 所以它是 public 的，和 {@code TowerActivation.pinDevice}
     * 一样，是给测试用的那道缝。
     */
    public static DeliveryResult apply(MinecraftServer server, PackageDispatchCodec.Dispatch dispatch,
                                       String sourceNode, long unknownGroupGraceTicks) {
        long now = server.overworld().getGameTime();
        ParcelLedger ledger = ParcelLedger.get(server);
        if (ledger.contains(dispatch.parcelId())) {
            LOG.info("[DistantStock/Parcel] duplicate already applied parcel={} source={} group={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.APPLIED;
        }
        // Tell the source exactly what this server lacks. Only the source owns those mods, so it is the only
        // node that can take the offending items out of the parcel and hand them back to its own logistics.
        List<String> missing = dispatch.manifest().missingRegistryEntries();
        if (!missing.isEmpty()) {
            LOG.warn("[DistantStock/Parcel] rejected incompatible parcel={} source={} group={} missing={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(), missing);
            requestStrip(sourceNode, dispatch.parcelId(), missing);
            return DeliveryResult.REJECTED;
        }
        ItemStack parcel = PackageCodec.decode(dispatch.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty() || !PackageItem.isPackage(parcel)) {
            LOG.warn("[DistantStock/Parcel] rejected unreadable parcel={} source={} group={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.REJECTED;
        }
        // 过海。包裹从对面来，身上穿的还是对面那台服务器的门牌 —— 在这边认不出任何一台港，
        // 所以先换上这一侧的地址，再去找港。这就是「两个地址」里交换的那一下，也是为什么
        // 包裹到了以后只剩一个地址：旧的那个指的是它不会再去的服务器。
        if (dev.distantstock.routing.RemoteRouteData.applyHomeAddress(parcel)) {
            LOG.info("[DistantStock/Parcel] home address applied parcel={} source={} address={}",
                    dispatch.parcelId(), sourceNode,
                    com.simibubi.create.content.logistics.box.PackageItem.getAddress(parcel));
        }
        // 收件港组在**这一侧**根本不存在。这跟"港满了"是两回事：港满了是等一会儿就好，这个等多久
        // 都不会好 —— 组 id 是随机的，没人能再把它造出来，而那些包裹会一直被重投到天荒地老。
        //
        // 留一段宽限期再判死：名字两边各建一次是这套设计的常态，对面可能正在建这个组，几秒钟之后
        // 这件货就该落地了。宽限期一过就退回（REJECTED），退回的路是现成的 —— 包裹从发件港的底面
        // 掉出来还给玩家，发件港早就没了才进服务器的退件箱（见 ParcelEscrowPump.handleRejected）。
        // 也就是说，最坏的结局是"货回到你手里并告诉你为什么"，而不是"货没了"。
        if (!groupExists(server, dispatch.receivingDockGroupId())) {
            long since = UNKNOWN_GROUP_SINCE.computeIfAbsent(dispatch.parcelId(), key -> now);
            if (now - since < unknownGroupGraceTicks) {
                complain(dispatch, sourceNode, "这一侧还没有这个收货港组，等一下它可能就建好了");
                return DeliveryResult.RETRY;
            }
            UNKNOWN_GROUP_SINCE.remove(dispatch.parcelId());
            LOG.warn("[DistantStock/Parcel] 退回：这一侧没有这个收货港组 parcel={} source={} group={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.REJECTED;
        }
        UNKNOWN_GROUP_SINCE.remove(dispatch.parcelId());

        DockBlockEntity dock = LoadedDocks.importFor(parcel, dispatch.receivingDockGroupId());
        if (dock == null || dock.isFull()) {
            complain(dispatch, sourceNode, dock == null ? "没有一台能收它的港" : "港满了");
            return DeliveryResult.RETRY;
        }
        if (!dock.insert(parcel)) {
            complain(dispatch, sourceNode, "港拒收");
            return DeliveryResult.RETRY;
        }
        WAITING.remove(dispatch.parcelId());
        ledger.markApplied(dispatch.parcelId());
        // 指名给某个玩家的包裹，落地时告诉那个人一声。他可能正在别处忙，而这件货只有他能取走 ——
        // 不说的话就是"寄了但没人知道到了"。
        String addressee = dev.distantstock.routing.ParcelAddressing.addressee(parcel);
        if (!addressee.isEmpty()) {
            net.minecraft.server.level.ServerPlayer target =
                    server.getPlayerList().getPlayerByName(addressee);
            if (target != null) {
                target.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.parcel.arrived",
                        dock.getBlockPos().getX() + ", " + dock.getBlockPos().getY()
                                + ", " + dock.getBlockPos().getZ()), false);
            }
        }
        LOG.info("[DistantStock/Parcel] applied parcel={} source={} group={} dock={} dimension={}",
                dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(), dock.getBlockPos(),
                dock.getLevel() == null ? "unknown" : dock.getLevel().dimension().location());
        return DeliveryResult.APPLIED;
    }

    private static void requestStrip(String sourceNode, java.util.UUID parcelId, List<String> missing) {
        if (sourceNode == null || sourceNode.isBlank()) {
            return;
        }
        try {
            PackageStripCodec.Notice notice = new PackageStripCodec.Notice(parcelId, missing,
                    "missing_registry_entries");
            TranserverBridge.send(sourceNode, RoutingChannels.PACKAGE_STRIP,
                    PackageStripCodec.encode(notice), parcelId.toString());
        } catch (IOException ignored) {
            // Without the notice the source falls back to returning the whole parcel to its own dock.
        }
    }

    private TranserverPackageService() {
    }
}
