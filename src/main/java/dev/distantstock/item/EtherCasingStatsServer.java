package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * What each piece of the Resonant Quartz suit gives its wearer.
 *
 * <p>Everything here is an attribute or a damage multiplier. Nothing applies a mob effect, because
 * the brief for this suit was explicit that no potion particles may ever appear on the wearer -- a
 * shimmering cloud would give away a suit whose entire point is not being seen.
 *
 * <p>The bonuses are laid out one per piece so that each slot is worth wearing on its own, and they
 * stack into a set. The defence is three independent multipliers applied before vanilla's armour
 * maths, so they compose with armour rather than replacing it; see the note in
 * {@link ModArmorMaterials} for why the material itself spends its budget on toughness instead.
 * Numbers quoted in the tooltip live in the lang files and must be kept in step with the constants
 * here -- {@code EtherCasingStatsCheck} fails the build if they drift apart.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class EtherCasingStatsServer {
    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_boots_speed");
    private static final ResourceLocation STRENGTH_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_chest_strength");
    private static final ResourceLocation OXYGEN_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_helmet_oxygen");

    private static final AttributeModifier SPEED = new AttributeModifier(
            SPEED_ID, EtherCasingBalance.BOOTS_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier STRENGTH = new AttributeModifier(
            STRENGTH_ID, EtherCasingBalance.CHEST_STRENGTH, AttributeModifier.Operation.ADD_VALUE);
    private static final AttributeModifier OXYGEN = new AttributeModifier(
            OXYGEN_ID, EtherCasingBalance.HELMET_OXYGEN, AttributeModifier.Operation.ADD_VALUE);

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        // Server-authoritative, for the reason spelled out in EtherCasingReach: writing the same
        // synced AttributeInstance on the logical client fights the server's attribute packets.
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        update(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, wearing(player, EquipmentSlot.FEET));
        update(player.getAttribute(Attributes.ATTACK_DAMAGE), STRENGTH, wearing(player, EquipmentSlot.CHEST));
        update(player.getAttribute(Attributes.OXYGEN_BONUS), OXYGEN, wearing(player, EquipmentSlot.HEAD));
    }

    @SubscribeEvent
    public static void incomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        float through = 1.0F;
        if (wearing(player, EquipmentSlot.LEGS)) {
            through *= EtherCasingBalance.LEGGINGS_ABSORB;
        }
        if (wearing(player, EquipmentSlot.HEAD)
                && event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            through *= EtherCasingBalance.HELMET_PROJECTILE_ABSORB;
        }
        // The charged tier is the whole four-piece set with working medium in every slot, so the
        // suit's best state is also its most expensive one to hold.
        if (EtherCasingMediumServer.fullPoweredSet(player)) {
            through *= EtherCasingBalance.CHARGED_ABSORB;
        }
        if (through != 1.0F) {
            event.setAmount(event.getAmount() * through);
        }
    }

    /** Worn in its own slot, not merely carried. */
    static boolean wearing(ServerPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        return stack.getItem() instanceof EtherCasingArmorItem armour
                && armour.getType().getSlot() == slot;
    }

    /**
     * Keep a transient modifier in sync with a condition. Ids are compared rather than the modifier
     * instances so that re-creating one with a different value still replaces it correctly.
     */
    public static void update(AttributeInstance attribute, AttributeModifier modifier, boolean enabled) {
        if (attribute == null) return;
        boolean present = attribute.hasModifier(modifier.id());
        if (enabled && !present) {
            attribute.addTransientModifier(modifier);
        } else if (!enabled && present) {
            attribute.removeModifier(modifier.id());
        }
    }

    private EtherCasingStatsServer() {
    }
}
