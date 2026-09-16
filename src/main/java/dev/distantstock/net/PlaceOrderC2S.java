package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.GaugeBlockEntity;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.OrderService;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.DockGroup;
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
     * The group an order may be delivered into, or null when the player may not reach the one asked
     * for.
     *
     * <p>Three answers, because three things can be true of the id in the packet:
     *
     * <ul>
     *   <li>It names a system and the player is in it — the order goes there.
     *   <li>It names nothing at all. A requester made before the system was deleted still holds its
     *       id, and an unconfigured requester holds none, so both fall back to the default system.
     *       Nobody owns the default, so this is not a door being opened.
     *   <li>It names a system the player is not in. That is the case a lock exists for, and it is
     *       refused: a closed system is not a place to push goods into.
     *   <li>It names a system on another server, learned from a pairing code. Nothing here can
     *       check it — the directory that holds it and every dock that answers to it are on the far
     *       end — so it is passed through as it stands. See {@code TranserverOrderService} for what
     *       the receiving server does with a group that turns out to be one of its own.
     * </ul>
     */
    private static UUID resolveGroup(Player p, UUID asked) {
        if (p == null || p.level().getServer() == null) {
            return null;
        }
        if (asked == null || asked.equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
            return DockGroupDirectory.DEFAULT_GROUP_ID;
        }
        DockGroup group = DockGroupDirectory.get(p.level().getServer()).find(asked).orElse(null);
        if (group == null) {
            return dev.distantstock.routing.RemoteGroups.get(p.level().getServer()).find(asked).isPresent()
                    ? asked : DockGroupDirectory.DEFAULT_GROUP_ID;
        }
        return group.admits(p.getUUID()) ? group.id() : null;
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
            UUID group = resolveGroup(p, msg.receivingDockGroupId);
            if (group == null) {
                // The field on the screen and this packet can disagree: the screen only offers
                // groups the player may reach, a packet offers whatever it was built with. The one
                // that decides is this side, so an order naming a system the player is not in is
                // refused rather than quietly turned into a delivery somewhere else.
                p.displayClientMessage(Component.translatable("gui.distantstock.group.closed"), true);
                return;
            }
            UUID freq = menu.freq(p);
            String address = menu.address(p);
            List<LinkQueues.Line> items = new ArrayList<>();
            for (Line line : msg.lines) {
                if (line.count > 0 && line.itemId != null && !line.itemId.isBlank()) {
                    items.add(new LinkQueues.Line(line.itemId, line.count));
                }
            }
            OrderService.Result result = p instanceof ServerPlayer serverPlayer
                    ? OrderService.place(serverPlayer.getServer(), menu.networkId(p), freq, address,
                    group, items)
                    : OrderService.Result.FAIL;
            GaugeBlockEntity be = menu.gauge(p);
            if (be != null) {
                be.lastOrder(result);
            }
            p.displayClientMessage(Component.translatable(switch (result) {
                case QUEUED -> "gui.distantstock.queued";
                case EMPTY -> "gui.distantstock.need_item";
                case NO_PEER -> "gui.distantstock.no_peer";
                case FAIL -> "gui.distantstock.order_fail";
            }), true);
        });
    }
}
