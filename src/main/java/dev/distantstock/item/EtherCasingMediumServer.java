package dev.distantstock.item;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.distantstock.DistantStock;
import dev.distantstock.fluid.ModFluids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Auto-feed from backpack canisters and medium-powered passive suit assistance. */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class EtherCasingMediumServer {
    private static final int TRANSFER_PERIOD = 10;
    private static final int TRANSFER_PER_PIECE = 25;
    private static final int ASSIST_PERIOD = 100;

    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_medium_speed");
    private static final ResourceLocation DAMAGE_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_medium_damage");
    private static final AttributeModifier SPEED = new AttributeModifier(
            SPEED_ID, 0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier DAMAGE = new AttributeModifier(
            DAMAGE_ID, 2.0, AttributeModifier.Operation.ADD_VALUE);

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (player.tickCount % TRANSFER_PERIOD == 0) {
            // Chest first so sustained flight does not starve behind three mostly-full pieces.
            refill(player, EquipmentSlot.CHEST);
            refill(player, EquipmentSlot.LEGS);
            refill(player, EquipmentSlot.FEET);
            refill(player, EquipmentSlot.HEAD);
        }

        boolean assisted = fullPoweredSet(player);
        EtherCasingStatsServer.update(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, assisted);
        EtherCasingStatsServer.update(player.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE, assisted);

        if (assisted && player.tickCount % ASSIST_PERIOD == 0 && !player.getAbilities().instabuild) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!slot.isArmor()) continue;
                ItemStack piece = player.getItemBySlot(slot);
                if (piece.getItem() instanceof EtherCasingArmorItem) {
                    EtherCasingArmorItem.consumeReserve(piece, 1);
                }
            }
        }
    }

    public static boolean fullPoweredSet(ServerPlayer player) {
        if (!EtherCasingArmorItem.hasFullSet(player)) return false;
        return EtherCasingArmorItem.hasReserve(player.getItemBySlot(EquipmentSlot.HEAD))
                && EtherCasingArmorItem.hasReserve(player.getItemBySlot(EquipmentSlot.CHEST))
                && EtherCasingArmorItem.hasReserve(player.getItemBySlot(EquipmentSlot.LEGS))
                && EtherCasingArmorItem.hasReserve(player.getItemBySlot(EquipmentSlot.FEET));
    }

    private static void refill(ServerPlayer player, EquipmentSlot slot) {
        ItemStack piece = player.getItemBySlot(slot);
        if (!(piece.getItem() instanceof EtherCasingArmorItem)) return;
        refillFrom(piece, player.getInventory().items);
    }

    /**
     * One piece, topped up from the first tank that can supply the medium it is already running on.
     *
     * <p>Takes the tanks rather than the player so the decision it encodes -- which containers may
     * feed the suit, and with what -- can be exercised without a server player. That decision is the
     * part worth testing: the tanks are a tag now, and a tier missing from the tag would be a tank
     * the armour silently ignores while everything else about it still works.
     *
     * @return the fluid id that was fed, or null when nothing was
     */
    public static String refillFrom(ItemStack piece, Iterable<ItemStack> tanks) {
        int missing = BacktankUtil.maxAir(piece) - BacktankUtil.getAir(piece);
        if (missing <= 0) return null;

        String installed = EtherCasingArmorItem.medium(piece);
        int wanted = Math.min(TRANSFER_PER_PIECE, missing);
        for (ItemStack candidate : tanks) {
            // Any tank, any tier: the family is a tag, so a fourth tier cannot become a tank the
            // suit quietly refuses to drink from.
            if (!candidate.is(dev.distantstock.ModTags.Items.FLUID_TANKS)) continue;
            var handler = candidate.getCapability(Capabilities.FluidHandler.ITEM);
            if (handler == null) continue;
            FluidStack available = handler.getFluidInTank(0);
            String id = EtherCasingArmorItem.mediumIdOf(available);
            if (id == null) continue;
            if (!installed.isBlank() && !installed.equals(id)) continue;

            FluidStack drained = handler.drain(wanted, IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) continue;
            EtherCasingArmorItem.addReserve(piece, drained.getAmount(), id);
            return id;
        }
        return null;
    }

    private EtherCasingMediumServer() {
    }
}
