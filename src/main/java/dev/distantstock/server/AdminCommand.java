package dev.distantstock.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.distantstock.DistantStock;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.link.InboundOrderInbox;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.PackageCodec;
import dev.distantstock.link.ParcelEscrow;
import dev.distantstock.link.ParcelQuarantine;
import dev.distantstock.link.ParcelReturnInbox;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.net.AdminConfigS2C;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PlayerNames;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Administrator commands. Everything that reports custody lives here so no parcel can be deleted or
 * duplicated without an explicit operator action.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class AdminCommand {
    private static final int PAGE_SIZE = 8;
    private static final int MAX_SUGGESTIONS = 64;

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("distantstock")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    PacketDistributor.sendToPlayer(ctx.getSource().getPlayerOrException(),
                            AdminConfigS2C.fromConfig());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("status").executes(AdminCommand::status))
                .then(Commands.literal("returns")
                        .executes(ctx -> returnsList(ctx, 1))
                        .then(Commands.literal("list")
                                .executes(ctx -> returnsList(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> returnsList(ctx,
                                                IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("restore")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestReturnIds(ctx, builder))
                                        .executes(AdminCommand::returnsRestore)))
                        .then(Commands.literal("give")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestReturnIds(ctx, builder))
                                        .executes(AdminCommand::returnsGive)))
                        .then(Commands.literal("export")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestReturnIds(ctx, builder))
                                        .executes(AdminCommand::returnsExport))))
                .then(Commands.literal("quarantine")
                        .executes(ctx -> quarantineList(ctx, 1))
                        .then(Commands.literal("list")
                                .executes(ctx -> quarantineList(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> quarantineList(ctx,
                                                IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("give")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestQuarantineIds(ctx, builder))
                                        .executes(AdminCommand::quarantineGive)))
                        .then(Commands.literal("export")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestQuarantineIds(ctx, builder))
                                        .executes(AdminCommand::quarantineExport)))
                        .then(Commands.literal("discard")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestQuarantineIds(ctx, builder))
                                        .executes(AdminCommand::quarantineDiscard))))
                .then(Commands.literal("help").executes(AdminCommand::help))
                .then(Commands.literal("tower").executes(AdminCommand::towerStatus))
                .then(Commands.literal("stock").executes(AdminCommand::stockStatus))
                .then(Commands.literal("group")
                        .executes(AdminCommand::groupList)
                        .then(Commands.literal("list").executes(AdminCommand::groupList))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(AdminCommand::groupCreate)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                        .executes(ctx -> groupDelete(ctx, false))
                                        .then(Commands.literal("confirm")
                                                .executes(ctx -> groupDelete(ctx, true)))))
                        // 成员名单：被点名的玩家能用这个锁着的组，改不了它。
                        .then(Commands.literal("member")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("group", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                                .executes(AdminCommand::memberList)))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("group", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> groupMember(ctx, true)))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("group", StringArgumentType.string())
                                                .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> groupMember(ctx, false)))))))
                // 配对码已经删掉了（用户 2026-09-17 判定没用）：远端的港组现在跟着公告自己过来，
                // 不需要谁来念一串码。指令里留下的是"看看认识了哪些"和"忘掉一个"。
                .then(Commands.literal("remote")
                        .executes(AdminCommand::remoteList)
                        .then(Commands.literal("list").executes(AdminCommand::remoteList))
                        .then(Commands.literal("forget")
                                .then(Commands.argument("group", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggestRemoteGroupNames(ctx, builder))
                                        .executes(AdminCommand::remoteForget))))
                .then(Commands.literal("dock")
                        .then(Commands.literal("group")
                                .then(Commands.argument("group", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                        .executes(AdminCommand::dockGroup)))
                        .then(Commands.literal("send-to")
                                .then(Commands.argument("group", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggestGroupNames(ctx, builder))
                                        .executes(AdminCommand::dockSendTo)))));
    }

    /**
     * Every subcommand, in one page.
     *
     * <p>Nothing here is the only way to do something: the same operations are in the terminal's
     * screen, and this exists for administrators and for scripted setups. It says so, because a
     * player who finds the commands first would otherwise assume the interface is the hard way
     * around.
     */
    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", List.of(
                "远仓指令（所有操作在终端界面里也能做，指令只是快捷方式）：",
                "  /distantstock status                传输模式与队列",
                "  /distantstock help                  这一页",
                "  /distantstock tower                 每座塔的状态 + 哪些设备没被带载、为什么",
                "  /distantstock group list            列出所有系统（港组）",
                "  /distantstock group create <名字>    新建一个系统",
                "  /distantstock group delete <名字>    删除（要再输一次 confirm）",
                "  /distantstock group member list <组名>          谁能用这个锁着的组",
                "  /distantstock group member add <组名> <玩家名>   把一个人放进来（只有组主能改）",
                "  /distantstock group member remove <组名> <玩家名> 把他请出去",
                "  /distantstock remote list            对面服务器公告过来的港组（目的地）",
                "  /distantstock remote forget <远端组> 隐藏一个远端目的地（对面不受影响）",
                "  /distantstock dock group <名字>      把脚下的港加入系统",
                "  /distantstock dock send-to <名字>    让脚下的港发往那个系统",
                "  /distantstock returns list|restore|give|export   被退回的包裹",
                "  /distantstock quarantine list|give|export|discard 隔离的包裹"
        ))), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Removes a system, and only ever on the second ask.
     *
     * <p>Typing the name once prints what would be lost and does nothing; adding {@code confirm}
     * does it. The docks that were in it go back to the default system rather than being broken,
     * which is the part that makes this safe to offer at all.
     */
    private static int groupDelete(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
        String name = StringArgumentType.getString(ctx, "name");
        var directory = DockGroupDirectory.get(ctx.getSource().getServer());
        var group = directory.findByName(name).orElse(null);
        if (group == null) {
            ctx.getSource().sendFailure(Component.literal("没有这个系统：" + name));
            return 0;
        }
        if (group.id().equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            ctx.getSource().sendFailure(Component.literal("默认系统不能删除。"));
            return 0;
        }
        if (!confirmed) {
            int docks = LoadedDocks.allInGroup(group.id()).size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "将删除系统「" + group.name() + "」，其中 " + docks
                            + " 个已加载的港会退回默认系统。确认请再执行一次："
                            + "/distantstock group delete " + group.name() + " confirm"), false);
            return Command.SINGLE_SUCCESS;
        }
        for (var dock : LoadedDocks.allInGroup(group.id())) {
            dock.setGroupId(DockGroupDirectory.DEFAULT_GROUP_ID);
        }
        directory.delete(group.id());
        ctx.getSource().sendSuccess(() -> Component.literal("已删除系统「" + name + "」。"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        LinkSnapshot.View view = LinkSnapshot.view();
        MinecraftServer server = ctx.getSource().getServer();
        ParcelEscrow escrow = ParcelEscrow.get(server);
        ParcelReturnInbox returns = ParcelReturnInbox.get(server);
        ParcelQuarantine quarantine = ParcelQuarantine.get(server);
        InboundOrderInbox orders = InboundOrderInbox.get(server);
        ctx.getSource().sendSuccess(() -> Component.literal("远仓 · " + view.linkLabel())
                .withStyle(view.linkUp() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "托管 " + escrow.size()
                        + "（待提交 " + escrow.count(ParcelEscrow.State.HELD)
                        + " / 在途 " + escrow.count(ParcelEscrow.State.SUBMITTED)
                        + " / 待退回 " + escrow.count(ParcelEscrow.State.REJECTED) + "）"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "退件箱 " + returns.size() + " / 隔离库 " + quarantine.size()
                        + "（/distantstock returns list 查看逐条保管状态）"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Transerver 队列：出站 " + view.transerverOutbox()
                        + " / 入站 " + view.transerverInbox()
                        + " / 已完成 " + view.transerverCompleted()
                        + " / 死信 " + view.transerverDeadLetters()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "远程订单：待处理 " + orders.count(InboundOrderInbox.State.RECEIVED)
                        + " / 已受理 " + orders.count(InboundOrderInbox.State.APPLIED)
                        + " / 结果不确定 " + orders.count(InboundOrderInbox.State.PROCESSING)), false);
        if (!view.transerverFailure().isBlank()) {
            ctx.getSource().sendFailure(Component.literal("最近错误：" + view.transerverFailure()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int returnsList(CommandContext<CommandSourceStack> ctx, int page) {
        List<ParcelReturnInbox.Record> records = ParcelReturnInbox.get(ctx.getSource().getServer()).records();
        ctx.getSource().sendSuccess(() -> Component.literal("远仓退件箱：" + records.size() + " 件"
                + (records.isEmpty() ? ""
                        : "（自动退回来源港；restore 立即退回，give 交给管理员，export 导出原始数据）")), false);
        return page(records, page, ctx, AdminCommand::describeReturn, "returns");
    }

    private static int quarantineList(CommandContext<CommandSourceStack> ctx, int page) {
        List<ParcelQuarantine.Record> records = ParcelQuarantine.get(ctx.getSource().getServer()).records();
        ctx.getSource().sendSuccess(() -> Component.literal("远仓隔离库：" + records.size() + " 件"
                + (records.isEmpty() ? "" : "（需要人工处理：give 可解码的，export 导出，discard 显式删除）")), false);
        return page(records, page, ctx, AdminCommand::describeQuarantine, "quarantine");
    }

    private static int returnsRestore(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ParcelReturnInbox inbox = ParcelReturnInbox.get(server);
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelReturnInbox.Record> found = resolve(token, inbox.records(), ParcelReturnInbox.Record::parcelId);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelReturnInbox.Record record = found.get();
        ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty()) {
            return failure(ctx, "该记录无法解码，请用 export 导出原始数据后人工处理");
        }
        DockBlockEntity origin = LoadedDocks.at(record.originDimension(), record.originPos());
        if (origin == null) {
            return failure(ctx, "来源港未加载：" + place(record.originDimension(), record.originPos())
                    + "，可在其加载后重试，或先用 give 把包裹交给管理员");
        }
        if (!origin.hasFallbackRoom(1) || !origin.offerFallback(parcel)) {
            return failure(ctx, "来源港回退面已满：" + place(record.originDimension(), record.originPos())
                    + "，请先清空港下方的容器");
        }
        origin.noteFallback("goggle.distantstock.fallback.parcel");
        inbox.remove(record.parcelId());
        ctx.getSource().sendSuccess(() -> Component.literal("已从回退面退回 " + shortId(record.parcelId())
                + " · " + place(record.originDimension(), record.originPos())), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int returnsGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        ParcelReturnInbox inbox = ParcelReturnInbox.get(server);
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelReturnInbox.Record> found = resolve(token, inbox.records(), ParcelReturnInbox.Record::parcelId);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelReturnInbox.Record record = found.get();
        ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty()) {
            return failure(ctx, "该记录无法解码，请先用 export 导出原始数据");
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (player.getInventory().getFreeSlot() < 0) {
            return failure(ctx, "背包已满，请先腾出一格");
        }
        player.getInventory().placeItemBackInInventory(parcel);
        inbox.remove(record.parcelId());
        ctx.getSource().sendSuccess(() -> Component.literal("已把包裹 " + shortId(record.parcelId())
                + " 交给 " + player.getName().getString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int returnsExport(CommandContext<CommandSourceStack> ctx) {
        ParcelReturnInbox inbox = ParcelReturnInbox.get(ctx.getSource().getServer());
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelReturnInbox.Record> found = resolve(token, inbox.records(), ParcelReturnInbox.Record::parcelId);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelReturnInbox.Record record = found.get();
        List<String> lines = new ArrayList<>();
        lines.add("kind=return");
        lines.add("parcelId=" + record.parcelId());
        lines.add("detail=" + record.detail());
        lines.add("handedOffAt=" + record.handedOffAt());
        lines.add("attempts=" + record.attempts());
        lines.add("lastAttemptAt=" + record.lastAttemptAt());
        lines.add("sha256=" + ParcelQuarantine.hashOf(record.encodedPackage()));
        lines.add("payloadBytes=" + record.encodedPackage().length());
        lines.add("address=" + record.address());
        lines.add("destinationNode=" + record.destinationNode());
        lines.add("receivingDockGroup=" + record.receivingDockGroupId());
        lines.add("createdAt=" + record.createdAt());
        return export(ctx, "returns", record.parcelId(), lines, record.originDimension(), record.originPos(),
                record.reason(), record.encodedPackage());
    }

    private static int quarantineGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        ParcelQuarantine library = ParcelQuarantine.get(server);
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelQuarantine.Record> found = resolve(token, library.records(), ParcelQuarantine.Record::id);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelQuarantine.Record record = found.get();
        ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty()) {
            return failure(ctx, "该记录无法解码（数据" + (record.intact() ? "完整" : "已损坏")
                    + "），请先用 export 导出原始数据");
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (player.getInventory().getFreeSlot() < 0) {
            return failure(ctx, "背包已满，请先腾出一格");
        }
        player.getInventory().placeItemBackInInventory(parcel);
        library.remove(record.id());
        ctx.getSource().sendSuccess(() -> Component.literal("已把隔离包裹 " + shortId(record.id())
                + " 交给 " + player.getName().getString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int quarantineExport(CommandContext<CommandSourceStack> ctx) {
        ParcelQuarantine library = ParcelQuarantine.get(ctx.getSource().getServer());
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelQuarantine.Record> found = resolve(token, library.records(), ParcelQuarantine.Record::id);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelQuarantine.Record record = found.get();
        List<String> lines = new ArrayList<>();
        lines.add("kind=quarantine");
        lines.add("quarantineId=" + record.id());
        lines.add("parcelId=" + record.parcelId());
        lines.add("sha256=" + record.sha256());
        lines.add("intact=" + record.intact());
        lines.add("detail=" + record.detail());
        lines.add("payloadBytes=" + record.encodedPackage().length());
        lines.add("address=" + record.address());
        lines.add("destinationNode=" + record.destinationNode());
        lines.add("receivingDockGroup=" + record.receivingDockGroupId());
        lines.add("createdAt=" + record.createdAt());
        return export(ctx, "quarantine", record.id(), lines, record.originDimension(), record.originPos(),
                record.reason(), record.encodedPackage());
    }

    private static int quarantineDiscard(CommandContext<CommandSourceStack> ctx) {
        ParcelQuarantine library = ParcelQuarantine.get(ctx.getSource().getServer());
        String token = StringArgumentType.getString(ctx, "id");
        Optional<ParcelQuarantine.Record> found = resolve(token, library.records(), ParcelQuarantine.Record::id);
        if (found.isEmpty()) {
            return notFound(ctx, token);
        }
        ParcelQuarantine.Record record = found.get();
        library.remove(record.id());
        ctx.getSource().sendSuccess(() -> Component.literal("已删除隔离记录 " + shortId(record.id())
                + "，原始数据不再保留（建议先 export）").withStyle(ChatFormatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 每座塔现在是什么状态，以及哪些远仓设备没被带载、为什么。
     *
     * <p>没塔就不能用，于是"我的工厂为什么停了"变成这个模组最常被问的问题，而它的答案有好几种：
     * 塔根本没搭成、搭成了但没在转、设备在塔的范围之外、塔带载的设备数用满了。这四种在游戏里长得
     * 一模一样 —— 都是"机器不动了"。护目镜只说得清你正看着的那一台，这条指令一口气把整个维度说清楚。
     *
     * <p>报的是两个分开的问题：「哪个塔罩着它」和「哪个塔带着它」。前者是几何，建好了就成立；后者
     * 还要那座塔在转、而且没被操作员关掉带载开关。混成一句话说，玩家会去拆一座本来没问题的塔。
     */
    /**
     * 跨服库存这条链，逐节打出来。
     *
     * <p>玩家 2026-09-18 报的「重启服务器后已绑定网络的远仓终端看不到远程服务器的库存，必须拆掉
     * 请求台或重新加入才行」有四种长得一模一样的失败：这张网络不在本服目录里（公告还没到）、在目录里
     * 但没人在看、在看着却还没问出去、问了对面说没有（退避中）。界面在这四种下都是"空"，所以得有这么
     * 一条命令把中间那几节读出来 —— 和 {@code /distantstock tower} 是同一个道理。
     */
    private static int stockStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        var directory = dev.distantstock.stock.NetworkDirectory.local();
        var peers = dev.distantstock.stock.NetworkDirectory.peer();
        ctx.getSource().sendSuccess(() -> Component.literal("本服网络 " + directory.size()
                + " 张 · 对面公告过来 " + peers.size() + " 张"), false);
        for (var entry : directory) {
            ctx.getSource().sendSuccess(() -> Component.literal("· 本服 " + shortFreq(entry.freq())
                    + " · 端口 " + entry.links() + " 个 · " + (entry.packable() ? "能打包" : "没打包机")), false);
        }
        for (var entry : peers) {
            ctx.getSource().sendSuccess(() -> Component.literal("· 对面 " + shortFreq(entry.freq())
                    + " · " + entry.server() + " · " + (entry.packable() ? "能打包" : "没打包机")
                    + (entry.networkId() == null ? " · 没有网络 id" : "")), false);
        }

        // 正在看的那些，才是"终端里那张网络"：没人在看就不会有人去问，界面自然是空的。
        var watched = dev.distantstock.stock.StockCache.watchedNetworks(5 * 60_000L);
        ctx.getSource().sendSuccess(() -> Component.literal("正在问的 " + watched.size() + " 张："), false);
        for (var network : watched) {
            long age = dev.distantstock.stock.StockCache.ageMs(network);
            long pending = dev.distantstock.link.TranserverStockService.pendingAgeMs(network);
            long refusal = dev.distantstock.stock.StockCache.refusalWaitMs(network);
            int size = dev.distantstock.stock.StockCache.size(network);
            ctx.getSource().sendSuccess(() -> Component.literal("· " + shortFreq(network.createFrequency())
                    + " · 节点 " + network.nodeId().toString().substring(0, 8)
                    + " · 缓存 " + size + " 条"
                    + " · 上次答复 " + (age < 0 ? "从没有过" : age / 1000 + " 秒前")
                    + (pending < 0 ? "" : " · 有一问没回音（" + pending / 1000 + " 秒）")
                    + (refusal <= 0 ? "" : " · 对面说没有过，还要等 " + refusal / 1000 + " 秒")), false);
        }
        var recent = dev.distantstock.link.TranserverStockService.recentQueries();
        ctx.getSource().sendSuccess(() -> Component.literal(recent.isEmpty()
                ? "别人来问过库存吗：一次都没有" : "最近别人来问库存，我们这样答的（最新在前）："), false);
        for (String line : recent) {
            ctx.getSource().sendSuccess(() -> Component.literal("· " + line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String shortFreq(java.util.UUID freq) {
        return freq == null ? "—" : freq.toString().substring(0, 8);
    }

    private static int towerStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<dev.distantstock.block.TowerCoreBlockEntity> towers =
                dev.distantstock.block.LoadedTowers.all();
        ctx.getSource().sendSuccess(() -> Component.literal("互通塔：" + towers.size()
                + " 座已加载（没塔在转的维度里，远仓设备一律不工作）"), false);
        for (var tower : towers) {
            var level = tower.getLevel();
            var tier = tower.tier();
            var id = level == null ? null : dev.distantstock.routing.TowerSystem.TowerId.of(
                    level.dimension(), tower.getBlockPos());
            var usage = id == null ? null : dev.distantstock.routing.TowerActivation.usage(id);
            String line = (level == null ? "?" : level.dimension().location().toString())
                    + " " + tower.getBlockPos().toShortString()
                    + " · 等级 " + (tier == null ? "不成塔" : tier.name())
                    + " · " + (tower.isRunning() ? "正在转" : "没在转")
                    + (usage == null ? "" : " · 带载 " + usage.carried() + "/" + usage.limit());
            ctx.getSource().sendSuccess(() -> Component.literal("· " + line), false);
        }

        List<String> idle = new ArrayList<>();
        for (var dock : LoadedDocks.allDocks()) {
            noteIfIdle(idle, dock.getLevel(), dock.getBlockPos(), "远仓港" + dockMode(dock));
        }
        for (var gauge : LoadedDocks.allGauges()) {
            noteIfIdle(idle, gauge.getLevel(), gauge.getBlockPos(), "远仓仪表");
        }
        for (var packager : dev.distantstock.block.LoadedDevices.packagers()) {
            noteIfIdle(idle, packager.getLevel(), packager.getBlockPos(), "远仓打包机");
        }
        for (var monitor : dev.distantstock.block.LoadedDevices.monitors()) {
            // 监视器不在带载名单里：它不被"带着"，它只是画那座塔。所以只报它底下有没有塔。
            var level = monitor.getLevel();
            if (level != null && dev.distantstock.routing.TowerActivation.towerAt(
                    level, monitor.getBlockPos()) == null) {
                idle.add(where(level, monitor.getBlockPos()) + " 远仓监视器 —— 头顶没有塔，读数会停在「无」");
            }
        }
        if (idle.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("所有已加载的远仓设备都在工作"), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("没在工作：" + idle.size()
                    + " 台（护目镜对着它们也是这么说的）"), false);
            for (String line : idle) {
                ctx.getSource().sendSuccess(() -> Component.literal("· " + line), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 港没在转的原因里，模式算一条：发货模式的港根本不收件，和有没有塔无关。 */
    private static String dockMode(dev.distantstock.block.DockBlockEntity dock) {
        return switch (dock.mode()) {
            case SEND -> "（发货模式，收不了件）";
            case BIDIRECTIONAL -> "（双向）";
            case RECEIVE -> "";
        };
    }

    /**
     * 这一台为什么没被带载，用玩家能照着修的话写出来。
     *
     * <p>"没塔罩着"和"塔在、但它没在转"是两件要做的事：前者要往上堆耦合器，后者要看那根传动轴。
     * 报成同一句话，玩家会把一座好塔拆了重建。
     */
    private static void noteIfIdle(List<String> out, net.minecraft.world.level.Level level,
                                   BlockPos pos, String what) {
        if (level == null || dev.distantstock.routing.TowerActivation.active(level, pos)) {
            return;
        }
        boolean underATower = dev.distantstock.routing.TowerActivation.towerAt(level, pos) != null;
        out.add(where(level, pos) + " " + what + " —— " + (underATower
                ? "塔在头顶，但它没在转（或缺应力），所以这台没被带载"
                : "不在任何塔的激活范围内"));
    }

    private static String where(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.dimension().location() + " " + pos.toShortString();
    }

    /**
     * 所有港组：名字、uuid、当前有多少个已加载的港属于它。
     *
     * <p>港组是收件侧的路由单位，而在此之前游戏里根本造不出第二个组（create/rename 一个调用者都没有），
     * 所以这条命令是管理员第一次能看到「组」这个对象长什么样。
     */
    private static int groupList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<DockGroup> groups = DockGroupDirectory.get(server).all();
        ctx.getSource().sendSuccess(() -> Component.literal("远仓港组：" + groups.size()
                + "（/distantstock dock group|send-to <组名> 把准星前的港加进组或指向组；成员见 group member；点 uuid 可复制）"), false);
        for (DockGroup group : groups) {
            int loaded = LoadedDocks.allInGroup(group.id()).size();
            // 组主写成名字：列表是给人看的，一串 uuid 前八位谁也认不出是谁。
            String owner = group.owner() == null ? "（无主，对所有人开放）"
                    : PlayerNames.display(server, group.owner())
                    + (group.open() ? "（开放加入）" : "（已锁定）");
            String members = group.members().isEmpty() ? ""
                    : " · 成员 " + group.members().size();
            ctx.getSource().sendSuccess(() -> Component.literal("· " + group.name()
                    + " · 已加载的港 " + loaded + " · 组主 " + owner + members + " · ")
                    .append(clickableId(group.id())), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 把某个玩家加进组 / 移出组。ownership 在界面上是硬检查，这里同样。 */
    private static int groupMember(CommandContext<CommandSourceStack> ctx, boolean add)
            throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        DockGroupDirectory directory = DockGroupDirectory.get(server);
        String name = StringArgumentType.getString(ctx, "group");
        DockGroup group = directory.findByName(name).orElse(null);
        if (group == null) {
            return failure(ctx, "没有这个港组：" + name);
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        boolean owner = player != null && group.ownedBy(player.getUUID());
        if (!owner && !ctx.getSource().hasPermission(2)) {
            return failure(ctx, "只有组主能改成员名单：" + group.name());
        }
        String typed = StringArgumentType.getString(ctx, "player");
        if (add) {
            var account = PlayerNames.lookup(server, typed);
            if (account.isEmpty()) {
                return failure(ctx, "这个服务器不认识玩家「" + typed + "」：名字要跟他进服时的名字完全一样");
            }
            if (group.ownedBy(account.get().getId())) {
                return failure(ctx, account.get().getName() + " 是这个组的主人，本来就能用");
            }
            if (group.hasMember(account.get().getId())) {
                directory.addMember(group.id(), account.get().getId(), account.get().getName());
                ctx.getSource().sendSuccess(() -> Component.literal(
                        account.get().getName() + " 已经在名单里（名字已刷新）"), false);
                return Command.SINGLE_SUCCESS;
            }
            if (group.members().size() >= DockGroup.MAX_MEMBERS) {
                return failure(ctx, "成员名单满了（上限 " + DockGroup.MAX_MEMBERS + " 人）");
            }
            directory.addMember(group.id(), account.get().getId(), account.get().getName());
            ctx.getSource().sendSuccess(() -> Component.literal("已把 " + account.get().getName()
                    + " 加入港组「" + group.name() + "」：他现在能用这个组，改不了它"), false);
            return Command.SINGLE_SUCCESS;
        }
        UUID id = null;
        String label = typed;
        for (var row : group.members().entrySet()) {
            if (row.getValue() != null && row.getValue().equalsIgnoreCase(typed)) {
                id = row.getKey();
                label = row.getValue();
                break;
            }
        }
        if (id == null) {
            var account = PlayerNames.lookup(server, typed).orElse(null);
            if (account != null && group.hasMember(account.getId())) {
                id = account.getId();
                label = account.getName();
            }
        }
        if (id == null) {
            return failure(ctx, "「" + typed + "」不在这个组的名单里");
        }
        String member = label;
        directory.removeMember(group.id(), id);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "已把 " + member + " 移出港组「" + group.name() + "」"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int memberList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "group");
        DockGroup group = DockGroupDirectory.get(server).findByName(name).orElse(null);
        if (group == null) {
            return failure(ctx, "没有这个港组：" + name);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("港组「" + group.name() + "」· 组主 "
                + (group.owner() == null ? "无（服务器建的，对所有人开放）"
                        : PlayerNames.display(server, group.owner()))
                + " · " + (group.open() ? "开放加入：谁都能把自己加进名单"
                        : "已锁定：只有组主和名单里的人能用")), false);
        // 名单为空也要说出来，中间那条横线就是「没人」和「命令没输出」的区别。
        if (group.members().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  ·（名单是空的）"), false);
        }
        for (var row : group.members().entrySet()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  · " + row.getValue()
                    + " · ").append(clickableId(row.getKey())), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  成员只能用这个组（往组里加港、往组里下单），改不了它；名单只在这个服务器内有效，跨服不生效"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int groupCreate(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        final DockGroup created;
        try {
            created = DockGroupDirectory.get(server).create(name);
        } catch (IllegalArgumentException refused) {
            // Rejected for a blank name, one that is too long, or one already taken. The message
            // says which, because an admin staring at 创建失败 learns nothing from it.
            return failure(ctx, "建组被拒：" + refused.getMessage());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("已创建港组「" + created.name() + "」· ")
                .append(clickableId(created.id())), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * What this server has heard from its peers about their dock groups.
     *
     * <p>Read-only, and deliberately so: every row here was said by the server that owns the group,
     * and there is nothing this side can change about it. The one thing a player can do is stop
     * being shown a row.
     */
    private static int remoteList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<dev.distantstock.routing.RemoteGroups.Entry> remotes =
                dev.distantstock.routing.RemoteGroups.get(server).all();
        ctx.getSource().sendSuccess(() -> Component.literal("已认识 " + remotes.size()
                + " 个远端港组（对面服务器的公告带过来的，不用配对码）："), false);
        for (var remote : remotes) {
            String owner = remote.owner() == null ? "无主" : remote.owner().toString().substring(0, 8);
            ctx.getSource().sendSuccess(() -> Component.literal("· " + remote.display()
                    + " · 港 " + remote.docks() + " 个 · 名单 " + remote.members().size()
                    + " 人 · " + (remote.open() ? "开放加入" : "锁着") + " · 组主 " + owner
                    + " · 节点 " + remote.node()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Hides a destination this server knows about on another server.
     *
     * <p>Nothing over there changes — the group is still there and still works for anyone else who
     * was let into it — but this server stops offering it. Parcels already on their way still land:
     * the promise was made, and this command is a memory, not a wall.
     *
     * <p>The hide lasts while the far side says the same thing about the group. Renamed over there,
     * or a changed member list, and it is offered again on the next announcement — see
     * {@link dev.distantstock.routing.RemoteGroups#forget}.
     */
    private static int remoteForget(CommandContext<CommandSourceStack> ctx) {
        String token = StringArgumentType.getString(ctx, "group");
        var remotes = dev.distantstock.routing.RemoteGroups.get(ctx.getSource().getServer());
        var entry = remotes.findByName(token).orElse(null);
        if (entry == null) {
            return failure(ctx, "没有这个远端港组：" + token + "（用 /distantstock remote list 看有哪些）");
        }
        remotes.forget(entry.group());
        ctx.getSource().sendSuccess(() -> Component.literal("已隐藏远端港组「" + entry.display()
                + "」。对面那台服务器上的组没有任何变化；对面改动它之后会重新出现。"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int dockGroup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<DockGroup> group = resolveGroup(ctx, StringArgumentType.getString(ctx, "group"));
        if (group.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        DockBlockEntity dock = lookedAtDock(player);
        if (dock == null) {
            return failure(ctx, "没有瞄到远仓港：请把准星正对一个港方块再执行这条命令");
        }
        dock.setGroupId(group.get().id());
        ctx.getSource().sendSuccess(() -> Component.literal("港 " + place(dock)
                + " 已加入港组「" + group.get().name() + "」"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 把玩家准星正对的那个港的默认目的地设成「本机 + 指定港组」。 */
    private static int dockSendTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String token = StringArgumentType.getString(ctx, "group");
        // A destination on another server first, and only if this one has no group by that name:
        // the two live in different files, and a name that means something here must not be
        // shadowed by a copy of it from elsewhere. This is the command that sends parcels across —
        // a dock pointed at a remote group and a remote node ships to the machine that answers to
        // it over there.
        var remote = dev.distantstock.routing.RemoteGroups
                .get(ctx.getSource().getServer()).findByName(token).orElse(null);
        if (remote != null) {
            DockBlockEntity target = lookedAtDock(player);
            if (target == null) {
                return failure(ctx, "没有瞄到远仓港：请把准星正对一个港方块再执行这条命令");
            }
            target.setDefaultDestination(remote.node(), remote.group());
            ctx.getSource().sendSuccess(() -> Component.literal("港 " + place(target)
                    + " 的默认目的地已设为 " + remote.display() + "（节点 " + shortId(remote.node()) + "）"), false);
            if (!target.canSend()) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "提示：该港当前是收货模式，不会发货；切换到发送/双向模式后这个默认目的地才会生效"), false);
            }
            return Command.SINGLE_SUCCESS;
        }
        Optional<DockGroup> group = resolveGroup(ctx, token);
        if (group.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        DockBlockEntity dock = lookedAtDock(player);
        if (dock == null) {
            return failure(ctx, "没有瞄到远仓港：请把准星正对一个港方块再执行这条命令");
        }
        // TranserverBridge.nodeId() 在没挂 Transerver 时是 null，而默认目的地必须写进一个能真正落地的
        // uuid，所以这里解析 localNodeId()：挂上了就是 Transerver 节点 id，没挂上就是本机哨兵。
        // 没有给 setDefaultDestination 加一个收 String 的重载——记录里 destinationNode 存的本来就是
        // nodeId().toString()，TranserverBridge.isLocal 也是拿同一串字符串去比，bool 值完全一致；加第二种
        // 表示形式反而要额外保证两边永远同步。
        UUID node = UUID.fromString(TranserverBridge.localNodeId());
        dock.setDefaultDestination(node, group.get().id());
        ctx.getSource().sendSuccess(() -> Component.literal("港 " + place(dock) + " 的默认目的地已设为 本机 "
                + shortId(node) + " + 港组「" + group.get().name() + "」"), false);
        if (!dock.canSend()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "提示：该港当前是收货模式，不会发货；切换到发送/双向模式后这个默认目的地才会生效"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 玩家准星指向的那个港；没瞄到方块、方块不是港、或港不在已加载区块里都返回 null。
     * 交互距离多放一格，和 SignalPanelBlock / RemoteGaugeBlock 的取法一致，免得贴脸时打空。
     */
    private static DockBlockEntity lookedAtDock(ServerPlayer player) {
        var hit = player.pick(player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        return player.level().getBlockEntity(blockHit.getBlockPos()) instanceof DockBlockEntity dock ? dock : null;
    }

    /**
     * 组名 → 港组。容错顺序：先按名字（忽略前后空白和大小写），再按 uuid 前缀（group list 出来的是 uuid）。
     * 找不到、或名字对应多个组时，在这里就把可选的组名列出来并返回 empty，调用方直接结束。
     */
    private static Optional<DockGroup> resolveGroup(CommandContext<CommandSourceStack> ctx, String token) {
        List<DockGroup> groups = DockGroupDirectory.get(ctx.getSource().getServer()).all();
        String needle = token == null ? "" : token.trim();
        if (needle.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("组名不能为空，可用组名：" + names(groups)));
            return Optional.empty();
        }
        List<DockGroup> byName = groups.stream().filter(group -> group.name().equalsIgnoreCase(needle)).toList();
        if (byName.size() == 1) {
            return Optional.of(byName.getFirst());
        }
        if (byName.size() > 1) {
            ctx.getSource().sendFailure(Component.literal("港组名「" + needle + "」对应多个港组，请改用 uuid 前缀区分："
                    + names(byName)));
            return Optional.empty();
        }
        // 建组时不禁止重名，所以 uuid 前缀是唯一可靠的兜底写法。
        String prefix = needle.toLowerCase(Locale.ROOT).replace("-", "");
        List<DockGroup> byId = groups.stream()
                .filter(group -> group.id().toString().replace("-", "").startsWith(prefix))
                .toList();
        if (byId.size() == 1) {
            return Optional.of(byId.getFirst());
        }
        ctx.getSource().sendFailure(Component.literal("找不到港组「" + needle + "」（"
                + (byId.isEmpty() ? "这个名字和 uuid 前缀都没有匹配" : "uuid 前缀不唯一") + "），可用组名：" + names(groups)));
        return Optional.empty();
    }

    private static String names(List<DockGroup> groups) {
        return String.join("、", groups.stream().limit(MAX_SUGGESTIONS).map(DockGroup::name).toList());
    }

    /** Remote destinations, offered by the same names the list and the screen draw. */
    private static CompletableFuture<Suggestions> suggestRemoteGroupNames(CommandContext<CommandSourceStack> ctx,
                                                                          SuggestionsBuilder builder) {
        List<String> names = dev.distantstock.routing.RemoteGroups
                .get(ctx.getSource().getServer()).all().stream()
                .limit(MAX_SUGGESTIONS)
                .map(dev.distantstock.routing.RemoteGroups.Entry::display)
                .toList();
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestGroupNames(CommandContext<CommandSourceStack> ctx,
                                                                    SuggestionsBuilder builder) {
        List<String> names = DockGroupDirectory.get(ctx.getSource().getServer()).all().stream()
                .limit(MAX_SUGGESTIONS)
                .map(DockGroup::name)
                .toList();
        return SharedSuggestionProvider.suggest(names, builder);
    }

    /** 组 uuid 手抄太容易错，做成点一下即复制；dock group / send-to 也接受它的前缀。 */
    private static Component clickableId(UUID id) {
        String value = id.toString();
        return Component.literal(value).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击复制 uuid"))));
    }

    private static String place(DockBlockEntity dock) {
        var level = dock.getLevel();
        return level == null
                ? "<维度未加载> @ " + dock.getBlockPos().toShortString()
                : place(level.dimension().location().toString(), dock.getBlockPos().asLong());
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String kind, UUID id, List<String> header,
                              String originDimension, long originPos, String reason, String payload) {
        List<String> lines = new ArrayList<>(header);
        lines.add("reason=" + reason);
        lines.add("originDimension=" + originDimension);
        lines.add("origin=" + place(originDimension, originPos));
        lines.add("payloadBase64=");
        lines.add(Base64.getMimeEncoder(76, new byte[]{'\n'})
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        try {
            Path file = writeDiagnostic(ctx.getSource().getServer(), kind, id, lines);
            ctx.getSource().sendSuccess(() -> Component.literal("诊断文件：" + file), false);
        } catch (IOException exception) {
            return failure(ctx, "写诊断文件失败：" + exception.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Path writeDiagnostic(MinecraftServer server, String kind, UUID id, List<String> lines)
            throws IOException {
        Path directory = server.getServerDirectory().resolve("distantstock-diagnostics");
        Files.createDirectories(directory);
        Path file = directory.resolve(kind + "-" + id + ".txt");
        Files.writeString(file, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return file;
    }

    private static <T> int page(List<T> records, int page, CommandContext<CommandSourceStack> ctx,
                                BiFunction<Integer, T, String> describe, String listCommand) {
        if (records.isEmpty()) {
            return Command.SINGLE_SUCCESS;
        }
        int pages = (records.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.min(Math.max(page, 1), pages);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, records.size());
        for (int i = from; i < to; i++) {
            T record = records.get(i);
            int index = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal(describe.apply(index, record)), false);
        }
        if (pages > 1) {
            int shown = current;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "第 " + shown + "/" + pages + " 页 · /distantstock " + listCommand + " list <页码>"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String describeReturn(int index, ParcelReturnInbox.Record record) {
        return "#" + index + " [" + shortId(record.parcelId()) + "] " + record.reason()
                + " · 目标 " + node(record.destinationNode())
                + " · 来源 " + place(record.originDimension(), record.originPos())
                + " · 等待 " + age(record.handedOffAt()) + " · 重试 " + record.attempts()
                + (record.address().isBlank() ? "" : " · 地址 " + record.address());
    }

    private static String describeQuarantine(int index, ParcelQuarantine.Record record) {
        return "#" + index + " [" + shortId(record.id()) + "] " + record.reason()
                + " · 数据" + (record.intact() ? "完整" : "损坏")
                + " · 目标 " + node(record.destinationNode())
                + " · 来源 " + place(record.originDimension(), record.originPos())
                + " · 入档 " + age(record.createdAt())
                + (record.detail().isBlank() ? "" : " · " + record.detail());
    }

    private static CompletableFuture<Suggestions> suggestReturnIds(CommandContext<CommandSourceStack> ctx,
                                                                  SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                suggestions(returnIds(ParcelReturnInbox.get(ctx.getSource().getServer()))), builder);
    }

    private static CompletableFuture<Suggestions> suggestQuarantineIds(CommandContext<CommandSourceStack> ctx,
                                                                      SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                suggestions(quarantineIds(ParcelQuarantine.get(ctx.getSource().getServer()))), builder);
    }

    private static List<UUID> returnIds(ParcelReturnInbox inbox) {
        return inbox.records().stream().map(ParcelReturnInbox.Record::parcelId).toList();
    }

    private static List<UUID> quarantineIds(ParcelQuarantine library) {
        return library.records().stream().map(ParcelQuarantine.Record::id).toList();
    }

    /** Offers both the list index and the short UUID prefix accepted by {@link #resolve}. */
    private static List<String> suggestions(List<UUID> ordered) {
        List<String> out = new ArrayList<>();
        if (ordered.size() > MAX_SUGGESTIONS) {
            return out;
        }
        for (int i = 0; i < ordered.size(); i++) {
            out.add(Integer.toString(i + 1));
            out.add(shortId(ordered.get(i)));
        }
        return out;
    }

    /** Accepts either the one-based list index or an unambiguous UUID prefix. */
    private static <T> Optional<T> resolve(String token, List<T> ordered, Function<T, UUID> idOf) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String trimmed = token.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            int index = Integer.parseInt(trimmed);
            return index >= 1 && index <= ordered.size()
                    ? Optional.of(ordered.get(index - 1)) : Optional.empty();
        }
        String needle = trimmed.toLowerCase(Locale.ROOT).replace("-", "");
        T match = null;
        for (T candidate : ordered) {
            if (idOf.apply(candidate).toString().replace("-", "").startsWith(needle)) {
                if (match != null) {
                    return Optional.empty();
                }
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    private static int notFound(CommandContext<CommandSourceStack> ctx, String token) {
        return failure(ctx, "找不到匹配的记录：" + token + "（可用列表序号或 UUID 前缀）");
    }

    private static int failure(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message));
        return Command.SINGLE_SUCCESS;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String node(String label) {
        return label == null || label.isBlank() ? "未指定"
                : (label.length() <= 8 ? label : label.substring(0, 8));
    }

    private static String place(String dimension, long packedPos) {
        BlockPos pos = BlockPos.of(packedPos);
        return dimension + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String age(long millis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        return hours < 24 ? hours + "h" : (hours / 24) + "d";
    }

    private AdminCommand() {
    }
}
