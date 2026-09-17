package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.RemoteRedstoneRequesterBlockEntity;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 远仓红石请求器屏幕上那两行改完了：接收港组和本端地址。
 *
 * <p>和终端一样，玩家填的是**名字**不是 uuid —— 名字是唯一能被人读、被人写的东西。所以名字在这一侧
 * 解析成组：先问本服的目录，再问对面服务器的公告（{@link RemoteGroups}），两边都要过一遍
 * {@code admits}：一个人的机器不该能往别人的仓库里灌东西。这一句判断只能在这儿做，因为过海的订单上
 * 没有玩家，对面判不了。
 *
 * <p>网络和送货地址不在这条包里：网络是绑定时决定的（拿着调谐过的终端右键），地址框用的是 Create
 * 自己的那一个，各有各的归处。这条只管新加的那两行。
 */
public record SetRequesterTargetC2S(BlockPos pos, String group, String homeAddress)
        implements CustomPacketPayload {
    /** 组名和地址都不会比这个长；和终端那边同一个上限。 */
    private static final int MAX_TEXT = 128;

    public static final Type<SetRequesterTargetC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_requester_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRequesterTargetC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetRequesterTargetC2S::pos,
                    ByteBufCodecs.STRING_UTF8, SetRequesterTargetC2S::group,
                    ByteBufCodecs.STRING_UTF8, SetRequesterTargetC2S::homeAddress,
                    SetRequesterTargetC2S::new);

    @Override
    public Type<SetRequesterTargetC2S> type() {
        return TYPE;
    }

    public static void handle(SetRequesterTargetC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            var server = player.getServer();
            if (server == null
                    || !(player.level().getBlockEntity(msg.pos())
                            instanceof RemoteRedstoneRequesterBlockEntity requester)) {
                return;
            }
            // A packet is not a screen: the fields are capped on the way in as well as on the way out,
            // so a hand-made one cannot put a novel-length address on a parcel.
            if (msg.group().length() > MAX_TEXT || msg.homeAddress().length() > MAX_TEXT) {
                return;
            }
            if (requester.binding() == null) {
                // 没绑定的机器按普通红石请求器工作，屏幕上那两行没有意义。说不说都是一样的结果，
                // 所以给一句，免得玩家以为框填坏了。
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.remote_requester.binding"), true);
                return;
            }

            String typed = msg.group().trim();
            java.util.UUID group = null;
            if (!typed.isEmpty()) {
                DockGroup local = DockGroupDirectory.get(server).findByName(typed).orElse(null);
                if (local != null) {
                    if (!local.admits(player.getUUID())) {
                        player.displayClientMessage(Component.translatable(
                                "gui.distantstock.group.closed"), true);
                        return;
                    }
                    group = local.id();
                } else {
                    var remote = RemoteGroups.get(server).findByName(typed).orElse(null);
                    if (remote == null) {
                        // 一个谁都不认识的名字。留着旧的不动，但要说话：静默丢弃会让玩家以为改成功了。
                        player.displayClientMessage(Component.translatable(
                                "gui.distantstock.group.unknown_name", typed), true);
                        return;
                    }
                    if (!remote.admits(player.getUUID())) {
                        player.displayClientMessage(Component.translatable(
                                "gui.distantstock.group.not_admitted", remote.name()), true);
                        return;
                    }
                    group = remote.group();
                }
            }
            requester.retarget(group, msg.homeAddress().trim());
            player.displayClientMessage(Component.translatable(
                    "gui.distantstock.remote_requester.saved"), true);
        });
    }
}
