package dev.distantstock.mixin;

import dev.distantstock.item.EtherCasingArmorItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/** Resonant medium is sacrificial maintenance stock: it is spent before casing durability. */
@Mixin(ItemStack.class)
public abstract class ItemStackMediumDurabilityMixin {
    private static final int MEDIUM_PER_DURABILITY = 4;

    @ModifyVariable(
            method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int distantstock$absorbArmorWear(int amount, int original, ServerLevel level,
                                             LivingEntity entity, Consumer<Item> onBreak) {
        ItemStack self = (ItemStack) (Object) this;
        if (!(self.getItem() instanceof EtherCasingArmorItem) || amount <= 0) return amount;
        int reserve = EtherCasingArmorItem.reserve(self);
        int prevent = Math.min(amount, reserve / MEDIUM_PER_DURABILITY);
        if (prevent > 0) EtherCasingArmorItem.consumeReserve(self, prevent * MEDIUM_PER_DURABILITY);
        return amount - prevent;
    }
}
