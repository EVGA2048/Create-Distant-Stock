package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.item.RequesterFind;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenRequesterC2S() implements CustomPacketPayload {
    public static final Type<OpenRequesterC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "open_requester"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequesterC2S> STREAM_CODEC =
            StreamCodec.unit(new OpenRequesterC2S());

    @Override
    public Type<OpenRequesterC2S> type() {
        return TYPE;
    }

    public static void handle(OpenRequesterC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = RequesterFind.find(player);
            if (!(stack.getItem() instanceof RequesterItem)) {
                return;
            }
            InteractionHand hand = RequesterFind.preferredHand(player);
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new RequesterMenu(id, inv, hand),
                    Component.translatable("gui.distantstock.title")
            ), buf -> {
                buf.writeBoolean(false);
                buf.writeEnum(hand);
            });
        });
    }
}
