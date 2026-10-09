package dev.distantstock.net;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.distantstock.DistantStock;
import dev.distantstock.item.EtherCasingArmorItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EtherBoostC2S() implements CustomPacketPayload {
    public static final Type<EtherBoostC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "ether_boost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EtherBoostC2S> STREAM_CODEC =
            StreamCodec.unit(new EtherBoostC2S());

    @Override
    public Type<EtherBoostC2S> type() {
        return TYPE;
    }

    public static void handle(EtherBoostC2S message, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isFallFlying()) return;
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (!(chest.getItem() instanceof EtherCasingArmorItem)) return;
            if (!BacktankUtil.hasAirRemaining(chest)) return;

            Vec3 look = player.getLookAngle();
            double boost = 0.4;
            player.push(look.x * boost, look.y * boost, look.z * boost);
            player.hurtMarked = true;

            BacktankUtil.consumeAir(player, chest, 10);
        });
    }
}
