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
import dev.distantstock.net.AdminConfigS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
                                        .executes(AdminCommand::quarantineDiscard)))));
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
