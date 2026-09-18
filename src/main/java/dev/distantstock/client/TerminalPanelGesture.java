package dev.distantstock.client;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterFind;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.net.BindPanelFromTerminalC2S;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 手持终端点面板那一下：绑上，而不是开界面。
 *
 * <p>这是所有远仓设备共用的那一套手势（港、请求器、我们自己的仪表板都是它），这里补的是面板这一类。
 * 判据只有一条：**手上（主手或副手）拿着终端**。没拿就是普通右键，照常开界面 —— 一个手势一个意思，
 * 玩家不用记"按哪个键才是绑定"。
 *
 * <p>放在客户端包里是因为调用它的地方（面板行为的 {@code displayScreen}）只在客户端跑，而它引用的
 * {@code PacketDistributor} 在服务端不存在。同样的懒加载把戏见 {@link RemoteGaugeScreen}。
 */
public final class TerminalPanelGesture {

    /**
     * 这一下要不要当成"绑定"。
     *
     * <p>收的是 {@code SmartBlockEntity}（面板行为身上那个字段就这个类型），里面自己认一遍是不是
     * 工厂仪表板 —— 三处调用点（我们的仪表板、信号灯板、Deployer 那边的面板行为）因此都不用写转型。
     *
     * @return true 表示我们已经接手了（包发出去了），调用方**不要**再开界面
     */
    public static boolean bindInsteadOfScreen(Player player,
                                              net.minecraft.world.level.block.entity.BlockEntity board,
                                              FactoryPanelBlock.PanelSlot slot) {
        if (!(board instanceof FactoryPanelBlockEntity panelBoard) || player == null || slot == null) {
            return false;
        }
        InteractionHand hand = RequesterFind.preferredHand(player);
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof RequesterItem)) {
            return false;
        }
        if (!RequesterData.tuned(held)) {
            // 拿着没调谐的终端点面板：说清楚为什么不绑，并且**不开界面** —— 界面里也没有调谐的地方。
            RequesterItem.sayUntuned(player);
            return true;
        }
        PacketDistributor.sendToServer(new BindPanelFromTerminalC2S(
                panelBoard.getBlockPos(), slot.ordinal(), player.isShiftKeyDown(), hand.ordinal()));
        return true;
    }

    private TerminalPanelGesture() {
    }
}
