package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Names the receiving dock group a requester points at.
 *
 * <p>A name, not an id. The design has players seeing a renameable display name and never a UUID,
 * so the name is what travels and the server is what turns it into an identity. A name nobody has
 * used yet becomes a new group: that is the whole of "creating a system", and it is deliberately
 * the same gesture as selecting one, because a player who has to first create a system and then
 * select it is being asked to do one thing twice.
 *
 * <p>The world half of the pairing is in {@link dev.distantstock.block.DockBlock}: sneak to make a
 * dock join the carried group, plain click to make it send there.
 */
public record SetDockGroupC2S(String name, int action) implements CustomPacketPayload {
    /** Point the requester at this name, making the system if nobody has used the name yet. */
    public static final int SELECT = 0;
    /** Give the requester's current system this name. */
    public static final int RENAME = 1;
    /** Flip whether the named system lets anyone but its owner in. */
    public static final int TOGGLE_OPEN = 2;
    /** Remove the named system. The player has already confirmed; the screen asks first. */
    public static final int DELETE = 3;
    /**
     * 只要一份最新的列表，什么都不改。
     *
     * <p>界面打开时会发这一条。列表是服务器推的，而推的那一次可以和界面创建抢跑 —— 抢输了这份
     * 就永远缺着，表现是"下拉列表点开是空的 / 点了不弹"，而且只有重开界面才可能好。要一次比
     * 赌它送到便宜得多。
     */
    public static final int REFRESH = 4;

    public static final Type<SetDockGroupC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_dock_group"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetDockGroupC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SetDockGroupC2S::name,
                    ByteBufCodecs.VAR_INT, SetDockGroupC2S::action,
                    SetDockGroupC2S::new);

    @Override
    public Type<SetDockGroupC2S> type() {
        return TYPE;
    }

    public static void handle(SetDockGroupC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (player.containerMenu instanceof RequesterMenu menu) {
                menu.writeDockGroup(player, msg.name, msg.action);
                return;
            }
            // **"要一份清单"这件事和菜单无关。**
            //
            // 以前这一条只在玩家开着终端菜单时才做事，于是远仓红石请求器和远仓仪表发出的 REFRESH
            // 石沉大海 —— 它们根本没有 RequesterMenu（远仓仪表那个界面连菜单都没有），点那一格
            // 什么都不会弹出来。玩家 2026-09-18 报的「点一下依旧没有出现选项栏」就是这个：包发出去
            // 了，服务端看了一眼菜单不是终端的，就放下了。
            //
            // 其余动作（选中/改名/上锁/删除）仍然只认终端的菜单：那些改的是"这台设备带着哪个组"，
            // 只有终端那套界面说得清。
            if (msg.action == REFRESH) {
                RequesterMenu.sendGroupList(player, net.minecraft.world.item.ItemStack.EMPTY);
            }
        });
    }
}
