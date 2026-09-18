package dev.distantstock.net;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.DistantStock;
import dev.distantstock.block.RemoteBinding;
import dev.distantstock.block.RemoteGaugeBlockEntity;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 手持终端点一下仪表面板：**绑上**，而不是打开界面。
 *
 * <p>玩家 2026-09-17 报的「右键会被打开界面，导致无法用便携式远仓终端去配置他」。界面的入口是
 * Deployer 那条路（面板行为自己的 {@code displayScreen}），它在**任何**一次短右键里都会开屏 ——
 * 手里拿着什么它不管。我们自己的仪表方块有一条"拿终端右键＝绑定"的路，可那只覆盖我们自己的板子；
 * 面板装在别人的板子上（Deployer 那条路）时，玩家手上那把终端根本没有走到的地方。
 *
 * <p>所以这里补上那一格：客户端认出"手上是终端"就把这一下交给服务器，服务器**自己**去玩家手上取
 * 那把终端来读（网络 / 接收港组 / 两个地址），客户端说什么都不作数 —— 绑定这种东西不该由客户端
 * 报参数。潜行＝解绑，和港、请求器、我们自己的仪表板是同一套手势。
 *
 * <p>三种板子走同一个入口：我们的仪表板、信号灯板、以及 Deployer 装在别人板子上的面板。
 */
public record BindPanelFromTerminalC2S(BlockPos pos, int slot, boolean unbind, int hand)
        implements CustomPacketPayload {
    public static final Type<BindPanelFromTerminalC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "bind_panel_from_terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BindPanelFromTerminalC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BindPanelFromTerminalC2S::pos,
                    ByteBufCodecs.VAR_INT, BindPanelFromTerminalC2S::slot,
                    ByteBufCodecs.BOOL, BindPanelFromTerminalC2S::unbind,
                    ByteBufCodecs.VAR_INT, BindPanelFromTerminalC2S::hand,
                    BindPanelFromTerminalC2S::new);

    @Override
    public Type<BindPanelFromTerminalC2S> type() {
        return TYPE;
    }

    public static void handle(BindPanelFromTerminalC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player.level().getBlockEntity(msg.pos) instanceof FactoryPanelBlockEntity board)) {
                return;
            }
            FactoryPanelBlock.PanelSlot[] slots = FactoryPanelBlock.PanelSlot.values();
            FactoryPanelBlock.PanelSlot slot = slots[Math.clamp(msg.slot, 0, slots.length - 1)];
            if (!board.panels.get(slot).isActive()) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.remote_gauge.no_panel"), true);
                return;
            }
            ItemStack held = player.getItemInHand(handOf(msg.hand));
            if (!(held.getItem() instanceof RequesterItem) || !RequesterData.tuned(held)) {
                // 手上一把没调谐的终端：说清楚，别装作绑好了。
                RequesterItem.sayUntuned(player);
                return;
            }
            if (msg.unbind) {
                if (unbind(board, slot)) {
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.remote_gauge.unbound"), true);
                }
                return;
            }
            RemoteNetworkId network = RequesterData.network(held).orElse(null);
            if (network == null) {
                return;
            }
            // 终端身上带的不止网络：货从哪个港出来、包裹写什么门牌，都在这儿。缺了港组的面板会往
            // 没人接的地方下单，所以这四个一起写。
            RemoteBinding binding = new RemoteBinding(network,
                    RequesterData.receivingGroup(held).orElse(null),
                    RequesterData.address(held), RequesterData.homeAddress(held));
            if (bind(board, slot, binding)) {
                player.displayClientMessage(Component.translatable(
                        "gui.distantstock.remote_gauge.bound", network.shortLabel()), true);
            }
        });
    }

    private static InteractionHand handOf(int ordinal) {
        InteractionHand[] hands = InteractionHand.values();
        return hands[Math.clamp(ordinal, 0, hands.length - 1)];
    }

    /**
     * 绑哪块板子都行，但 Deployer 那三种面板只能在一个**不加载 Deployer 就跑不起来**的类里点名，
     * 所以那一句写在守卫后面（和 {@code BindGaugePanelC2S} 同一个写法）。
     */
    private static boolean bind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                RemoteBinding binding) {
        if (board instanceof RemoteGaugeBlockEntity gauge) {
            gauge.bind(slot, binding);
            return true;
        }
        if (board instanceof SignalPanelBlockEntity signal) {
            signal.bind(slot, binding);
            return true;
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.bind(board, slot, binding);
    }

    private static boolean unbind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        if (board instanceof RemoteGaugeBlockEntity gauge) {
            gauge.unbind(slot);
            return true;
        }
        if (board instanceof SignalPanelBlockEntity signal) {
            signal.unbind(slot);
            return true;
        }
        return net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.unbind(board, slot);
    }
}
