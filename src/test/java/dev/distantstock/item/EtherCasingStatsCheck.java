package dev.distantstock.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;

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
        checkLoreFormatting();
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
     *
     * <p>The lines are Create's own description shape -- a {@code summary} plus
     * {@code condition}/{@code behaviour} pairs the player unfolds by holding Shift -- and they are
     * keyed per piece, under the piece's own item id.
     */
    private static void checkTooltips() {
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            JsonObject lang = read(file);
            boolean zh = file.startsWith("zh");
            // Each piece's own line is the one its constants belong to.
            piece(lang, file, "helmet", zh,
                    gain(EtherCasingBalance.HELMET_OXYGEN),
                    reduction(EtherCasingBalance.HELMET_PROJECTILE_ABSORB));
            // The attack line is a share of the weapon rather than a flat addition, and it is
            // withheld until something is in hand -- so it has to read that way. "+4" on a
            // chestplate is a promise about a fist, and that is the promise the scaling exists to
            // break.
            piece(lang, file, "chestplate", zh,
                    gainPercent(EtherCasingBalance.CHEST_STRENGTH), zh ? "武器" : "weapon");
            piece(lang, file, "leggings", zh, reduction(EtherCasingBalance.LEGGINGS_ABSORB));
            // The boots carry one percentage and one named effect, so the effect is checked by name:
            // "one level of Jump Boost" is what the constant buys, and a line that stopped saying so
            // would be promising something unstated.
            piece(lang, file, "boots", zh,
                    gainPercent(EtherCasingBalance.BOOTS_SPEED), zh ? "跳跃提升" : "Jump Boost");
            // The reserve line is the one that stayed in appendHoverText, because it is a number.
            state(lang, file, "item.distantstock.ether_casing_armor.reserve");
        }
        System.out.println("  tooltips  four pieces, two files, every stated figure matches its constant");
    }

    /**
     * Every description line has to survive {@code String.format}, because that is what
     * {@code I18n.get} does to it before it is ever drawn -- inside a try/catch that turns a bad line
     * into "Format error: ...". A literal percent sign is what goes wrong in practice: it has to be
     * written {@code %%}, and one that is not costs the player the whole line.
     *
     * <p>The client smoke catches that in whatever language it runs in, which is English; this is
     * the check that runs over every language file the mod ships, at build time.
     */
    private static void checkLoreFormatting() {
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            JsonObject lang = read(file);
            int lines = 0;
            for (String key : lang.keySet()) {
                if (!key.contains(".tooltip.")) continue;
                String line = lang.get(key).getAsString();
                String rendered;
                try {
                    // No arguments, exactly as the game builds these: a description line is static
                    // text, so a format specifier in one is a bug rather than an omission.
                    rendered = String.format(line);
                } catch (IllegalFormatException e) {
                    throw new AssertionError(file + " " + key + " cannot be formatted: " + line, e);
                }
                require(!rendered.contains("Format error"),
                        file + " " + key + " renders as " + rendered);
                lines++;
            }
            require(lines > 0, file + " has no description lines at all");
            System.out.println("  lore      " + file + ": " + lines + " lines format cleanly");
        }
    }

    /**
     * One piece's description: every line present in both files, and the ones that state a figure
     * checked against the constant behind it.
     */
    private static void piece(JsonObject lang, String file, String id, boolean zh, String... own) {
        String prefix = "item.distantstock.ether_casing_" + id + ".tooltip.";
        // A key that loses its twin in the other language file is a piece showing a raw translation
        // id to whoever hovers it, in one language only, so all seven lines are checked for presence.
        for (String suffix : List.of("summary", "condition1", "behaviour1", "condition2", "behaviour2",
                "condition3", "behaviour3")) {
            state(lang, file, prefix + suffix);
        }
        // The set-wide line is a four-piece promise and has to say so; EtherCasingArmorGameTests is
        // what proves the rule does what the line says. The charged line is shared by all four and
        // carries three figures.
        state(lang, file, prefix + "condition1", zh ? "全套" : "Full set");
        state(lang, file, prefix + "behaviour1", zh ? "火焰" : "fire");
        state(lang, file, prefix + "behaviour2", own);
        state(lang, file, prefix + "behaviour3", reduction(EtherCasingBalance.CHARGED_ABSORB), "+10%",
                gainPercent(EtherCasingBalance.CHARGED_STRENGTH));
    }

    private static void state(JsonObject lang, String file, String key, String... fragments) {
        require(lang.has(key), file + " is missing " + key);
        // Matched case-insensitively: what is being checked is that the line makes a claim, and
        // sentence position is not part of the claim.
        String line = lang.get(key).getAsString().toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            require(line.contains(fragment.toLowerCase(Locale.ROOT)),
                    file + " " + key + " says " + line + ", which lacks " + fragment);
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
