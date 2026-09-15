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
                .then(Commands.literal("group")
                        .executes(AdminCommand::groupList)
                        .then(Commands.literal("list").executes(AdminCommand::groupList))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(AdminCommand::groupCreate))))
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
     * 所有港组：名字、uuid、当前有多少个已加载的港属于它。
     *
     * <p>港组是收件侧的路由单位，而在此之前游戏里根本造不出第二个组（create/rename 一个调用者都没有），
     * 所以这条命令是管理员第一次能看到「组」这个对象长什么样。
     */
    private static int groupList(CommandContext<CommandSourceStack> ctx) {
        List<DockGroup> groups = DockGroupDirectory.get(ctx.getSource().getServer()).all();
        ctx.getSource().sendSuccess(() -> Component.literal("远仓港组：" + groups.size()
                + "（/distantstock dock group|send-to <组名> 把准星前的港加进组或指向组；点 uuid 可复制）"), false);
        for (DockGroup group : groups) {
            int loaded = LoadedDocks.allInGroup(group.id()).size();
            ctx.getSource().sendSuccess(() -> Component.literal("· " + group.name()
                    + " · 已加载的港 " + loaded + " · ").append(clickableId(group.id())), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int groupCreate(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        final DockGroup created;
        try {
            created = DockGroupDirectory.get(server).create(name);
        } catch (IllegalArgumentException invalid) {
            // DockGroup 只拒绝空名和超长名，原因直接回给管理员比一句「创建失败」有用。
            return failure(ctx, "组名不合法：" + invalid.getMessage());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("已创建港组「" + created.name() + "」· ")
                .append(clickableId(created.id())), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 把玩家准星正对的那个港加进指定港组。没瞄到港就明确说出来，不静默。 */
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
        Optional<DockGroup> group = resolveGroup(ctx, StringArgumentType.getString(ctx, "group"));
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
