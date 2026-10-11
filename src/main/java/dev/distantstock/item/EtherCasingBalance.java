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
    public static final int RESERVE_MULTIPLIER = 4;

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
    public static final double BOOTS_JUMP = 0.10;       // exactly one level of Jump Boost
    public static final double HELMET_OXYGEN = 6.0;     // Respiration III is +3

    /**
     * The chest's share of a swing, as a fraction of the weapon's own damage, not a flat number.
     *
     * <p>It was flat four, and flat is wrong here in a way that only shows up in a fistfight: a
     * player's unarmed damage is one, so four extra points made every pat a sword hit -- five damage
     * a punch, four punches to kill an unarmoured player. Scaling instead means the bonus is only
     * worth what the weapon is: a netherite sword gains about four points, and a bare hand gains
     * nothing at all, because {@code EtherCasingArmorItem.swingsWith} withholds the bonus entirely
     * until something the wearer can swing is in hand.
     *
     * <p>Applied as {@code ADD_MULTIPLIED_BASE}, which multiplies the base plus everything added to
     * it -- item damage included, since vanilla applies a weapon's damage as an {@code ADD_VALUE}
     * modifier. The charged tier's share below is {@code ADD_MULTIPLIED_TOTAL}, so it compounds on
     * top of this one rather than adding to it.
     */
    public static final double CHEST_STRENGTH = 0.60;
    public static final double CHARGED_STRENGTH = 0.15;

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
