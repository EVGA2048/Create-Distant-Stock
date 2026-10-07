package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Full Resonant Quartz suit reach bonus. Uses its own ADD_VALUE modifier, so Create's Extendo Grip
 * modifiers remain independent and the two bonuses add together naturally.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class EtherCasingReach {
    public static final double BONUS = 2.0;
    private static final ResourceLocation BLOCK_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_block_reach");
    private static final ResourceLocation ENTITY_ID = ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "resonant_quartz_entity_reach");
    private static final AttributeModifier BLOCK_MODIFIER = new AttributeModifier(
            BLOCK_ID, BONUS, AttributeModifier.Operation.ADD_VALUE);
    private static final AttributeModifier ENTITY_MODIFIER = new AttributeModifier(
            ENTITY_ID, BONUS, AttributeModifier.Operation.ADD_VALUE);

    @SubscribeEvent
    public static void playerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        // Attributes are server-authoritative. Mutating the same synced AttributeInstance again on
        // the logical client makes the client fight the server's attribute packets every tick. In
        // a large modpack that can poison interaction/ray-trace state (placement helpers, Ultimine,
        // etc.). Let vanilla/NeoForge sync the server modifier to the client instead.
        if (!(player instanceof ServerPlayer)) return;
        boolean equipped = EtherCasingArmorItem.hasFullSet(player);
        update(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE), BLOCK_MODIFIER, equipped);
        update(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE), ENTITY_MODIFIER, equipped);
    }

    private static void update(AttributeInstance attribute, AttributeModifier modifier, boolean equipped) {
        if (attribute == null) return;
        boolean present = attribute.hasModifier(modifier.id());
        if (equipped && !present) {
            attribute.addTransientModifier(modifier);
        } else if (!equipped && present) {
            attribute.removeModifier(modifier.id());
        }
    }

    private EtherCasingReach() {
    }
}
