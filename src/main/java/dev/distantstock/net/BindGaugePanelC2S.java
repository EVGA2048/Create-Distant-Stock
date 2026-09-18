package dev.distantstock.net;

import dev.distantstock.DistantStock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Points one remote gauge panel at a destination and an address, from the panel's own screen.
 *
 * <p>Until now the only way to do this was the gesture — hold a tuned terminal and click the panel —
 * which meant the cross-server half of a remote gauge existed nowhere a player could see it. The
 * screen this comes from is the same {@code FactoryPanelScreen} Create draws, with these two fields
 * added below it, so the panel stops looking like an ordinary factory gauge with a secret.
 *
 * <p>The destination travels as a <b>name</b>, like everywhere else in this mod: players never see
 * or type a UUID, and the server turns the name into an identity. A name nobody has used becomes a
 * new group, which is the same rule the request desk follows — asking a player to create a system
 * and then select it would be asking them to do one thing twice.
 */
public record BindGaugePanelC2S(BlockPos pos, int slot, String destination, String address)
        implements CustomPacketPayload {
    public static final Type<BindGaugePanelC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "bind_gauge_panel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BindGaugePanelC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BindGaugePanelC2S::pos,
                    ByteBufCodecs.VAR_INT, BindGaugePanelC2S::slot,
                    ByteBufCodecs.STRING_UTF8, BindGaugePanelC2S::destination,
                    ByteBufCodecs.STRING_UTF8, BindGaugePanelC2S::address,
                    BindGaugePanelC2S::new);

    @Override
    public Type<BindGaugePanelC2S> type() {
        return TYPE;
    }

    /**
     * Writes the destination and address onto whichever kind of board this is.
     *
     * <p>Three shapes reach here and all three end in the same place: our distant gauge board, our
     * signal panel, and — when Deployer is installed — a panel of ours living on somebody else's
     * board. The first two are plain block entities; the third lives in a package that must not be
     * class-loaded on a pack without Deployer, so it is named only inside the guard.
     */
    private static boolean rebind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                  java.util.UUID group, String address) {
        // group == null 表示"保持原来那个组"：界面上的目的地可以是空的（没选）或者认不出来的名字，
        // 那两种情况都不该连累同一个包里的地址（玩家 2026-09-18：「UI 里面保留的设置也都没了」）。
        if (board instanceof dev.distantstock.block.RemoteGaugeBlockEntity gauge) {
            var current = gauge.binding(slot);
            if (current == null) {
                return false;
            }
            gauge.bind(slot, current.network(), group == null ? current.receivingGroup() : group,
                    address);
            return true;
        }
        if (board instanceof dev.distantstock.block.SignalPanelBlockEntity signal) {
            var current = signal.binding(slot);
            if (current == null) {
                return false;
            }
            signal.bind(slot, current.network(), group == null ? current.receivingGroup() : group,
                    address);
            return true;
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.rebind(board, slot, group, address);
    }

    public static void handle(BindGaugePanelC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player.level().getBlockEntity(msg.pos) instanceof FactoryPanelBlockEntity board)) {
                return;
            }
            FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.PanelSlot.values()[Math.clamp(
                    msg.slot, 0, FactoryPanelBlock.PanelSlot.values().length - 1)];
            if (!board.panels.get(slot).isActive()) {
                return;
            }
            if (player.level().getServer() == null) {
                return;
            }
            DockGroupDirectory directory = DockGroupDirectory.get(player.level().getServer());
            String wanted = msg.destination == null ? "" : msg.destination.trim();
            // 目的地是空的、或者名字认不出来，都只表示"这一格没改" —— **组保持原样，地址照存**。
            //
            // 以前这两种情况都是整包丢掉，于是玩家改完地址一关界面，地址也没了（2026-09-18
            // 「UI 里面保留的设置也都没了」）。而"认不出来"最常见的原因恰恰是刚重启过：对面的公告
            // 还没到，RemoteGroups 里还没有那一行。
            java.util.UUID group = null;
            if (!wanted.isEmpty()) {
                DockGroup existing = directory.findByName(wanted).orElse(null);
                if (existing != null && existing.id().equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
                    // 玩家把「默认收货港组」这个名字打进来了。它不是目的地，是"还没选组"那个占位 ——
                    // 选了它等于没选，货出去谁都不认（玩家报的"进虚空"）。界面上那一行已经点不出来
                    // 了（GroupPicker 里滤掉），这里再拦一道：两侧同一条规则。
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.group.none"), true);
                } else if (existing != null && !existing.admits(player.getUUID())) {
                    // Same refusal as the desk and the dock: pointing at somebody's system fills their
                    // docks with your parcels, which is theirs to allow.
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.group.closed"), true);
                } else if (existing != null) {
                    group = existing.id();
                } else {
                    // A destination on another server, learned from that server's announcement. It is
                    // passed through as it stands — the directory holding it, and every dock answering
                    // to it, are on the far end — and it is never turned into a group here: a local copy
                    // would be a group with no docks whose name shadows the real one.
                    var remote = dev.distantstock.routing.RemoteGroups.get(player.level().getServer())
                            .findByName(wanted).orElse(null);
                    if (remote == null) {
                        player.displayClientMessage(Component.translatable(
                                "gui.distantstock.group.unknown_name", wanted), true);
                    } else if (!remote.admits(player.getUUID())) {
                        // 和本服的组同一句拒绝。客户端已经把那行画灰了，这一句是给改过的客户端准备的：
                        // 名单是随公告过来的，而这是**唯一**判得了的地方（订单上没有玩家，对面判不了）。
                        player.displayClientMessage(Component.translatable(
                                "gui.distantstock.group.not_admitted", remote.name()), true);
                    } else {
                        group = remote.group();
                    }
                }
            }
            if (rebind(board, slot, group, msg.address)) {
                return;
            }
            // Nothing here carries a warehouse yet. The gesture is what names one — it is a real
            // logistics network on a real node, and a text field cannot express it.
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.remote_gauge.needs_terminal"), true);
        });
    }
}
