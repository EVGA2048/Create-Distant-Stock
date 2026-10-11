package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
 * <p>Except for one rule, which is about all four pieces at once: a complete suit turns fire aside
 * entirely. It is the only absolute here, and it is deliberately not a multiplier -- see
 * {@link #incomingDamage}.
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
    private static final ResourceLocation JUMP_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_boots_jump");

    private static final AttributeModifier SPEED = new AttributeModifier(
            SPEED_ID, EtherCasingBalance.BOOTS_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier JUMP = new AttributeModifier(
            JUMP_ID, EtherCasingBalance.BOOTS_JUMP, AttributeModifier.Operation.ADD_VALUE);
    private static final AttributeModifier STRENGTH = new AttributeModifier(
            STRENGTH_ID, EtherCasingBalance.CHEST_STRENGTH,
            AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    private static final AttributeModifier OXYGEN = new AttributeModifier(
            OXYGEN_ID, EtherCasingBalance.HELMET_OXYGEN, AttributeModifier.Operation.ADD_VALUE);

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        // Server-authoritative, for the reason spelled out in EtherCasingReach: writing the same
        // synced AttributeInstance on the logical client fights the server's attribute packets.
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        applyWornBonuses(player);
    }

    /**
     * The per-slot bonuses, one attribute each, kept in step with what is worn.
     *
     * <p>Split out of the tick handler so a game test can hand a mock wearer the same modifiers
     * without a ServerPlayer -- the guard above is about which side may write, not about what the
     * pieces do, and the two are worth testing separately.
     */
    public static void applyWornBonuses(LivingEntity wearer) {
        update(wearer.getAttribute(Attributes.MOVEMENT_SPEED), SPEED,
                wearing(wearer, EquipmentSlot.FEET));
        update(wearer.getAttribute(Attributes.JUMP_STRENGTH), JUMP,
                wearing(wearer, EquipmentSlot.FEET));
        // Attack power needs both the chest and something to swing: see EtherCasingArmorItem.
        update(wearer.getAttribute(Attributes.ATTACK_DAMAGE), STRENGTH,
                wearing(wearer, EquipmentSlot.CHEST) && EtherCasingArmorItem.swingsWith(wearer));
        update(wearer.getAttribute(Attributes.OXYGEN_BONUS), OXYGEN,
                wearing(wearer, EquipmentSlot.HEAD));
    }

    @SubscribeEvent
    public static void incomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity wearer = event.getEntity();
        // Wearing the four pieces is what turns fire aside, and it takes no medium: the immunity is
        // the shell's, the charged tiers below are what the medium pays for. Fire here is vanilla's
        // own is_fire tag -- campfires, standing in fire, burning, lava, magma blocks and fireballs
        // -- so a wearer walks through the Nether rather than merely surviving it.
        //
        // Cancelling, rather than zeroing the amount, is deliberately the same shape as vanilla's own
        // Fire Resistance check in LivingEntity#hurt: the hit never lands, so there is no hurt sound,
        // no red flash and no invulnerability window, and the wearer still visibly burns. That last
        // part is the point -- flames that do nothing say "the armour did this", where flames that
        // went out would read as a potion.
        if (EtherCasingArmorItem.hasFullSet(wearer)
                && event.getSource().is(DamageTypeTags.IS_FIRE)) {
            event.setCanceled(true);
            return;
        }
        float through = 1.0F;
        if (wearing(wearer, EquipmentSlot.LEGS)) {
            through *= EtherCasingBalance.LEGGINGS_ABSORB;
        }
        if (wearing(wearer, EquipmentSlot.HEAD)
                && event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            through *= EtherCasingBalance.HELMET_PROJECTILE_ABSORB;
        }
        // The charged tier is the whole four-piece set with working medium in every slot, so the
        // suit's best state is also its most expensive one to hold.
        if (EtherCasingMediumServer.fullPoweredSet(wearer)) {
            through *= EtherCasingBalance.CHARGED_ABSORB;
        }
        if (through != 1.0F) {
            event.setAmount(event.getAmount() * through);
        }
    }

    /** Worn in its own slot, not merely carried. */
    static boolean wearing(LivingEntity player, EquipmentSlot slot) {
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
