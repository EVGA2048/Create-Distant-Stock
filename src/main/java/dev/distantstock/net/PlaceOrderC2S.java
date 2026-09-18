package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.OrderService;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.OrderDestination;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.TowerActivation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PlaceOrderC2S(List<Line> lines, UUID receivingDockGroupId) implements CustomPacketPayload {
    public record Line(String itemId, int count) {
    }

    public static final Type<PlaceOrderC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "place_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Line> LINE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Line::itemId,
            ByteBufCodecs.VAR_INT, Line::count,
            Line::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceOrderC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, LINE_CODEC), PlaceOrderC2S::lines,
            UUIDUtil.STREAM_CODEC, PlaceOrderC2S::receivingDockGroupId,
            PlaceOrderC2S::new);

    public PlaceOrderC2S(List<Line> lines) {
        this(lines, dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID);
    }

    @Override
    public Type<PlaceOrderC2S> type() {
        return TYPE;
    }

    /**
     * The group the order goes to, or the reason there is not one, through {@link OrderDestination} —
     * where the rule lives rather than here, so it can be tested without a player clicking a screen.
     * See that class for what each answer means, including why an id nobody recognises is refused
     * instead of falling back to the default group.
     */
    private static Resolution resolveGroup(Player p, UUID asked) {
        if (p == null || p.level().getServer() == null) {
            return new Resolution(null, "gui.distantstock.order_fail");
        }
        OrderDestination.Answer answer = OrderDestination.resolve(
                p.level().getServer(), p.getUUID(), asked);
        return switch (answer.kind()) {
            case UNKNOWN -> new Resolution(null, "gui.distantstock.group.unknown");
            case REFUSED -> new Resolution(null, "gui.distantstock.group.closed");
            // 没选组：以前这一档是"发到本服的默认组"，也就是发出去谁都不认 —— 玩家报的"进虚空"。
            case NO_GROUP -> new Resolution(null, "gui.distantstock.group.none");
            default -> new Resolution(answer.group(), "");
        };
    }

    /**
     * Where the goods will come out, in words, for the player who just ordered them.
     *
     * <p>Which server a group lives on decides which server the goods are delivered on, and that is
     * the one thing about a cross-server order the player cannot see anywhere else: the field holds
     * a name, both kinds of name look alike, and the difference only shows up when the goods come
     * out somewhere they were not expected. Said out loud at the one moment it is still free to say.
     */
    private static Component describeDestination(Player p, UUID group) {
        if (p.level().getServer() == null) {
            return Component.literal("");
        }
        DockGroup local = DockGroupDirectory.get(p.level().getServer()).find(group).orElse(null);
        if (local != null) {
            return Component.translatable("gui.distantstock.where.local", local.name());
        }
        return dev.distantstock.routing.RemoteGroups.get(p.level().getServer()).find(group)
                .map(remote -> (Component) Component.translatable("gui.distantstock.where.remote",
                        remote.display()))
                .orElseGet(() -> Component.literal(""));
    }

    /** 一个目的地，或者没有目的地的理由：error 为空串表示这个组能用。 */
    private record Resolution(UUID group, String error) {
    }


    public static void handle(PlaceOrderC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p.containerMenu instanceof RequesterMenu menu)) {
                return;
            }
            if (!menu.tuned(p)) {
                p.displayClientMessage(Component.translatable("gui.distantstock.untuned"), true);
                return;
            }
            if (msg.lines == null || msg.lines.isEmpty()) {
                p.displayClientMessage(Component.translatable("gui.distantstock.need_item"), true);
                return;
            }
            GaugeBlockEntity desk = menu.gauge(p);
            if (desk != null && !TowerActivation.active(desk.getLevel(), desk.getBlockPos())) {
                // Read-only gate: a request desk no tower carries still opens, still shows the
                // stock it can see, and still lets its address be read — it just will not place an
                // order. Blinding the readout as well would leave a player with a dark machine and
                // nothing to compare it against, and the desk is the one place in the field where
                // the reason is legible.
                p.displayClientMessage(Component.translatable("gui.distantstock.uncharged"), true);
                return;
            }
            Resolution resolved = resolveGroup(p, msg.receivingDockGroupId);
            UUID group = resolved.group();
            if (group == null) {
                // The field on the screen and this packet can disagree: the screen only offers
                // groups the player may reach, a packet offers whatever it was built with. The one
                // that decides is this side, so an order naming a system the player is not in — or
                // one nobody has ever heard of — is refused rather than quietly turned into a
                // delivery somewhere else.
                p.displayClientMessage(Component.translatable(resolved.error()), true);
                return;
            }
            UUID freq = menu.freq(p);
            String address = menu.address(p);
            // 第二个地址跟着订单走：它在对面写不到包裹上，只能由下单这一侧带过去。
            String homeAddress = menu.homeAddress(p);
            List<LinkQueues.Line> items = new ArrayList<>();
            for (Line line : msg.lines) {
                if (line.count > 0 && line.itemId != null && !line.itemId.isBlank()) {
                    items.add(new LinkQueues.Line(line.itemId, line.count));
                }
            }
            OrderService.Result result = p instanceof ServerPlayer serverPlayer
                    ? OrderService.place(serverPlayer.getServer(), menu.networkId(p), freq, address,
                    group, items, homeAddress)
                    : OrderService.Result.FAIL;
            GaugeBlockEntity be = menu.gauge(p);
            if (be != null) {
                be.lastOrder(result);
            }
            p.displayClientMessage(result == OrderService.Result.QUEUED
                    ? Component.translatable("gui.distantstock.queued_at", describeDestination(p, group))
                    : Component.translatable(switch (result) {
                        case EMPTY -> "gui.distantstock.need_item";
                        case NO_PEER -> "gui.distantstock.no_peer";
                        default -> "gui.distantstock.order_fail";
                    }), true);
        });
    }
}
