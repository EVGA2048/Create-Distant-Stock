package dev.distantstock;

import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.EtherCasingMediumServer;
import dev.distantstock.item.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What a complete Resonant Quartz suit does to damage on its way in.
 *
 * <p>Two rules live in {@code EtherCasingStatsServer.incomingDamage} and neither is visible in a
 * screenshot: fire is turned aside outright, and everything else is multiplied down. Both are
 * checked here through the real hurt path -- a mock player wearing the real pieces, hit with real
 * damage sources -- because what could go wrong is not the arithmetic but the wiring: an event
 * handler that never fires, a full-set check that is off by one slot, or an immunity that quietly
 * becomes "no damage of any kind".
 *
 * <p>The wearer is a plain {@code Player} rather than a {@code ServerPlayer} on purpose. Making a
 * ServerPlayer in a game test means placing it in the level, which sends packets; the pieces of the
 * suit, and the damage sequence, are player-agnostic.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class EtherCasingArmorGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theFullSetTurnsFireAndLavaAside(GameTestHelper h) {
        Player player = wear(h, GameType.SURVIVAL);
        h.assertTrue(EtherCasingArmorItem.hasFullSet(player), "the mock wearer is not in a full suit");
        // Nothing has been fed to these pieces, and that is the point: the immunity is the shell's,
        // the charged tiers are what the medium buys.
        h.assertTrue(!EtherCasingMediumServer.fullPoweredSet(player),
                "a fresh suit should hold no medium at all");

        turnedAside(h, player, h.getLevel().damageSources().inFire(), "standing in fire");
        turnedAside(h, player, h.getLevel().damageSources().lava(), "lava");
        turnedAside(h, player, h.getLevel().damageSources().hotFloor(), "a magma block");

        // Three pieces is not a suit. The same hit that did nothing a moment ago now lands -- which
        // is also what proves the three assertions above were about the armour and not about the
        // damage source being inert in a game test.
        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        float before = player.getHealth();
        h.assertTrue(player.hurt(h.getLevel().damageSources().inFire(), 6.0F),
                "fire was turned aside without the boots");
        h.assertTrue(player.getHealth() < before,
                "fire was accepted but cost no health: " + before + " -> " + player.getHealth());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theFireImmunityIsNotAnImmunityToEverything(GameTestHelper h) {
        Player player = wear(h, GameType.SURVIVAL);
        float before = player.getHealth();
        h.assertTrue(player.hurt(h.getLevel().damageSources().generic(), 8.0F),
                "the suit turned aside ordinary damage");
        float taken = before - player.getHealth();
        h.assertTrue(taken > 0.0F, "ordinary damage cost nothing");
        // The leggings' reduction has to still be in the path: the fire branch returns early, and an
        // early return that swallowed every source would look exactly like this test passing.
        h.assertTrue(taken < 8.0F,
                "the leggings absorbed nothing: " + taken + " of 8 damage got through");
        h.succeed();
    }

    private static void turnedAside(GameTestHelper h, Player player, DamageSource source, String what) {
        float before = player.getHealth();
        h.assertTrue(!player.hurt(source, 6.0F), "the full suit let " + what + " through");
        h.assertTrue(player.getHealth() == before,
                what + " cost health through a full suit: " + before + " -> " + player.getHealth());
    }

    private static Player wear(GameTestHelper h, GameType mode) {
        Player player = h.makeMockPlayer(mode);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.ETHER_CASING_HELMET.get()));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.ETHER_CASING_CHESTPLATE.get()));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.ETHER_CASING_LEGGINGS.get()));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.ETHER_CASING_BOOTS.get()));
        return player;
    }

    private EtherCasingArmorGameTests() {
    }
}
