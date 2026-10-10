package dev.distantstock.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Guards the Resonant Quartz suit's balance.
 *
 * <p>The suit is sold on one number -- roughly 170 effective health once the four pieces are worn
 * and charged -- and that number is the product of four independent figures: the material's
 * toughness, the leggings' multiplier, the helmet's projectile multiplier and the set multiplier.
 * Any one of them can be changed in a vacuum and nothing anywhere will complain, which is exactly
 * how a balance target silently drifts away. So the composition is recomputed here from the same
 * constants the game uses and checked against the published table.
 *
 * <p>The tooltip is checked too. Its numbers are baked into static language lines, so they are a
 * second copy of the constants and will happily lie after a change; this reads both language files
 * and fails if a stated percentage no longer matches the constant behind it.
 */
public final class EtherCasingStatsCheck {
    /** Damage figures a wearer actually meets: an arrow, a sword, a heavy blow, a buffed crit. */
    private static final float[] HITS = {6.0F, 10.0F, 20.0F, 40.0F};
    private static final float FULL_PROTECTION_EPF = 16.0F;   // Protection IV on all four pieces

    public static void main(String[] args) {
        float armor = EtherCasingBalance.TOTAL_DEFENSE;
        float toughness = EtherCasingBalance.TOUGHNESS;

        // The armour points are capped inside CombatRules, so anything past twenty is decoration.
        require(armor <= CombatRules.MAX_ARMOR,
                "armour points above " + CombatRules.MAX_ARMOR + " do nothing; got " + armor);

        // The published table. Charged is the suit's normal state in play; the bare material is here
        // so a change to toughness shows up as a change to the floor as well as the ceiling.
        System.out.println("  effective health (player has 20):");
        expect("bare material", armor, toughness, List.of(), 0.0F, 81, 71, 56, 38);
        expect("leggings only", armor, toughness,
                List.of(EtherCasingBalance.LEGGINGS_ABSORB), 0.0F, 124, 110, 85, 59);
        expect("charged set", armor, toughness, charged(), 0.0F, 248, 220, 171, 118);
        expect("charged set + Protection IV", armor, toughness, charged(), FULL_PROTECTION_EPF,
                689, 611, 475, 329);
        // And the comparison the whole thing exists to beat.
        expect("netherite + Protection IV", 20.0F, 3.0F, List.of(), FULL_PROTECTION_EPF,
                193, 161, 113, 71);

        checkTooltips();
        System.out.println("Resonant Quartz stat checks PASSED: charged set is 171 effective health"
                + " against a 20 damage hit, " + (int) toughness + " toughness, " + (int) armor
                + " armour points, and both language files agree with the constants.");
    }

    private static List<Float> charged() {
        return List.of(EtherCasingBalance.LEGGINGS_ABSORB, EtherCasingBalance.CHARGED_ABSORB);
    }

    /**
     * Effective health: how much raw damage the wearer can absorb before dying, i.e. twenty health
     * divided by the fraction that gets through. It is quoted per hit size on purpose -- armour is
     * far more effective against small hits, so a single figure would be meaningless.
     */
    private static float effectiveHealth(float damage, float armor, float toughness,
                                         List<Float> layers, float protectionEpf) {
        float after = absorb(damage, armor, toughness);
        after = CombatRules.getDamageAfterMagicAbsorb(after, protectionEpf);
        for (float layer : layers) {
            after *= layer;
        }
        return 20.0F / (after / damage);
    }

    /**
     * Re-implemented rather than called, because the real one needs a living entity and a damage
     * source. Every term is taken from CombatRules' own public constants so this cannot quietly
     * disagree with the game about the shape of the curve.
     */
    private static float absorb(float damage, float armor, float toughness) {
        float divisor = CombatRules.BASE_ARMOR_TOUGHNESS + toughness / 4.0F;
        float applied = Mth.clamp(armor - damage / divisor,
                armor * CombatRules.MIN_ARMOR_RATIO, CombatRules.MAX_ARMOR);
        return damage * (1.0F - applied / CombatRules.ARMOR_PROTECTION_DIVIDER);
    }

    private static void expect(String name, float armor, float toughness, List<Float> layers,
                               float protectionEpf, int... expected) {
        require(expected.length == HITS.length, "table row needs one figure per hit size");
        StringBuilder row = new StringBuilder(String.format("  %-28s", name));
        for (int i = 0; i < HITS.length; i++) {
            float actual = effectiveHealth(HITS[i], armor, toughness, layers, protectionEpf);
            require(Math.abs(actual - expected[i]) <= 1.0F,
                    name + ": effective health against " + (int) HITS[i] + " damage is "
                            + Math.round(actual) + ", the table says " + expected[i]);
            row.append(String.format("  %2d 伤害 → %3d", (int) HITS[i], Math.round(actual)));
        }
        System.out.println(row);
    }

    /**
     * The tooltips are the only place the player is told what the suit does, and the numbers in them
     * are written by hand. Derive what each line must contain from the constant and look for it.
     */
    private static void checkTooltips() {
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            JsonObject lang = read(file);
            state(lang, file, "leggings_bonus", reduction(EtherCasingBalance.LEGGINGS_ABSORB));
            state(lang, file, "boots_bonus", gainPercent(EtherCasingBalance.BOOTS_SPEED));
            state(lang, file, "chestplate_bonus", gain(EtherCasingBalance.CHEST_STRENGTH));
            state(lang, file, "helmet_bonus", gain(EtherCasingBalance.HELMET_OXYGEN),
                    reduction(EtherCasingBalance.HELMET_PROJECTILE_ABSORB));
            state(lang, file, "charged", reduction(EtherCasingBalance.CHARGED_ABSORB), "+10%", "+2");
        }
        System.out.println("  tooltips  two files, every stated figure matches its constant");
    }

    private static void state(JsonObject lang, String file, String suffix, String... fragments) {
        String key = "item.distantstock.ether_casing_armor." + suffix;
        require(lang.has(key), file + " is missing " + key);
        String line = lang.get(key).getAsString();
        for (String fragment : fragments) {
            require(line.contains(fragment),
                    file + " " + suffix + " says " + line + ", which lacks " + fragment);
        }
    }

    /** "35%" for a multiplier of 0.65 -- what the tooltip has to state. */
    private static String reduction(float absorbed) {
        return Math.round((1.0F - absorbed) * 100.0F) + "%";
    }

    /** "+4" for a flat bonus of four -- attack damage and oxygen are both quoted this way. */
    private static String gain(double value) {
        return "+" + (value == Math.rint(value) ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 100.0) / 100.0));
    }

    /** "+25%" for a multiplier of 0.25 -- movement speed is quoted as a percentage. */
    private static String gainPercent(double value) {
        return "+" + Math.round(value * 100.0) + "%";
    }

    private static JsonObject read(String fileName) {
        Path path = Path.of("src/main/resources/assets/distantstock/lang", fileName);
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path.toAbsolutePath(), e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private EtherCasingStatsCheck() {
    }
}
