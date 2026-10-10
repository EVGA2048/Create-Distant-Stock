package dev.distantstock.item;

/**
 * Every number the Resonant Quartz suit is balanced on, in one place and in plain Java.
 *
 * <p>Deliberately free of Minecraft types. The material and the event handlers both read from here,
 * and so does {@code EtherCasingStatsCheck} -- which runs as an ordinary JVM program, where merely
 * touching a class that registers a {@code DeferredRegister} throws, because the game is not
 * bootstrapped. Keeping the figures in a class that cannot fail to load is what lets the balance
 * target be tested at all.
 *
 * <p>Read the comments on the two groups before changing anything: the armour points look like the
 * obvious dial and are not.
 */
public final class EtherCasingBalance {
    // --- the material -----------------------------------------------------------------------
    // Armour points are capped. CombatRules.getDamageAfterAbsorb ends in
    // `Mth.clamp(armor - damage / f, armor * 0.2F, 20.0F)`, so a thirty-first point is discarded
    // outright and only the 0.2 floor notices. Twelve points of toughness is the real upgrade: it
    // sits in the denominator as `f = 2 + toughness / 4`, which is what stops a large hit from
    // punching straight through. See ModArmorMaterials for the compared figures.
    public static final int HELMET_DEFENSE = 3;
    public static final int CHESTPLATE_DEFENSE = 8;
    public static final int LEGGINGS_DEFENSE = 6;
    public static final int BOOTS_DEFENSE = 3;
    public static final int BODY_DEFENSE = 12;
    public static final float TOUGHNESS = 12.0F;
    public static final float KNOCKBACK_RESISTANCE = 0.25F;

    public static final int TOTAL_DEFENSE =
            HELMET_DEFENSE + CHESTPLATE_DEFENSE + LEGGINGS_DEFENSE + BOOTS_DEFENSE;

    // --- what each piece gives --------------------------------------------------------------
    // Each is a little above the potion it stands in for; this is end-game gear.
    public static final double BOOTS_SPEED = 0.25;      // Speed I is +20%
    public static final double CHEST_STRENGTH = 4.0;    // between Strength I (+3) and II (+6)
    public static final double HELMET_OXYGEN = 6.0;     // Respiration III is +3

    // --- what gets through, as a fraction ---------------------------------------------------
    // Three independent multipliers, applied before vanilla's armour maths so they compose with it
    // rather than replacing it. Their product with the material is the balance target: a wearer in a
    // charged set survives a 20 damage hit with 171 effective health, against 113 for netherite and
    // Protection IV. Nothing caps the total on purpose -- the suit is meant to be able to stack
    // Protection on top and stand up to a boss alone.
    public static final float LEGGINGS_ABSORB = 0.65F;
    public static final float HELMET_PROJECTILE_ABSORB = 0.70F;
    public static final float CHARGED_ABSORB = 0.50F;

    private EtherCasingBalance() {
    }
}
