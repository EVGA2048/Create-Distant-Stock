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
        if (board instanceof dev.distantstock.block.RemoteGaugeBlockEntity gauge) {
            var current = gauge.binding(slot);
            if (current == null) {
                return false;
            }
            gauge.bind(slot, current.network(), group, address);
            return true;
        }
        if (board instanceof dev.distantstock.block.SignalPanelBlockEntity signal) {
            var current = signal.binding(slot);
            if (current == null) {
                return false;
            }
            signal.bind(slot, current.network(), group, address);
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
            if (wanted.isEmpty()) {
                // An empty destination is the one thing that cannot mean "create a group": it would
                // make a nameless system nobody could ever address again.
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.remote_gauge.no_destination"), true);
                return;
            }
            DockGroup existing = directory.findByName(wanted).orElse(null);
            if (existing != null && !existing.admits(player.getUUID())) {
                // Same refusal as the desk and the dock: pointing at somebody's system fills their
                // docks with your parcels, which is theirs to allow.
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.group.closed"), true);
                return;
            }
            DockGroup group = existing != null ? existing
                    : directory.createFor(wanted, player.getUUID());
            if (rebind(board, slot, group.id(), msg.address)) {
                return;
            }
            // Nothing here carries a warehouse yet. The gesture is what names one — it is a real
            // logistics network on a real node, and a text field cannot express it.
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.remote_gauge.needs_terminal"), true);
        });
    }
}
