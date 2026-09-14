package dev.distantstock.client;

/** Geometry in block units; independent of Minecraft so the entire motion can be checked. */
public final class DockParcelMotion {
    public static final float PORTAL_Y = 13.35f / 16f;
    /**
     * Where a parcel rests and the lift parks.
     *
     * The dock's own bottom hatch tops out at 3.55/16, so a lift parked down there disappears into
     * the floor and reads as part of the casing. Standing it off by a couple of units keeps the
     * plate clear of the hatch, which is the difference between a machine with a lift and a machine
     * with a slot in the bottom.
     */
    public static final float TRAY_Y = 6f / 16f;
    /** How far the lift plate's top face sits below the parcel, so the box rests on it. */
    public static final float LIFT_DROP = 0.5f / 16f;

    /**
     * How far into a receive the lift meets the parcel, as a fraction of the parcel's own travel.
     *
     * The parcel comes down from the portal and the lift comes up from the tray; they meet here and
     * finish the descent together. Much larger and the lift spends the animation away from the
     * parcel; much smaller and it never visibly goes up to catch anything.
     */
    public static final float CATCH_AT = 0.3f;

    /**
     * The lift stops short of the portal, so it never has to be clipped against the lid.
     *
     * A parcel leaves through the top; a lift does not fit through it. Letting it stop a little
     * under the opening also reads as the parcel being handed off rather than thrown.
     */
    public static final float LIFT_CEILING = 0.85f;

    public record Frame(float baseY, float scale, float clipY) {}

    /** The parcel's own progress, 0 at the tray and 1 at the portal. */
    public static float parcelT(float send, float receive) {
        return Math.clamp(send >= 0 ? send : receive >= 0 ? 1 - receive : 0, 0, 1);
    }

    public static float baseYFor(float t) {
        float eased = Math.clamp(t, 0, 1);
        eased = eased * eased * (3 - 2 * eased);
        return TRAY_Y + (PORTAL_Y - TRAY_Y) * eased;
    }

    /**
     * The lift's progress, on the same 0..1 scale as {@link #parcelT}.
     *
     * Carrying a parcel out, the lift stays under it the whole way. Bringing one in, it does not:
     * it leaves the tray and rises to meet the parcel a {@link #CATCH_AT} of the way in, then rides
     * down with it. That rise is the entire point of having a lift, so it has to be its own curve
     * rather than the parcel's.
     */
    public static float liftT(float send, float receive) {
        return Math.min(LIFT_CEILING, liftTUnclamped(send, receive));
    }

    private static float liftTUnclamped(float send, float receive) {
        float parcel = parcelT(send, receive);
        if (send >= 0 || receive < 0) {
            return parcel;
        }
        float meet = 1 - CATCH_AT;
        return parcel > meet ? (1 - parcel) / CATCH_AT * meet : parcel;
    }

    /** Where the lift plate's top face sits, so a carried parcel's bottom rests on it. */
    public static float liftTopY(float liftT) {
        return baseYFor(liftT) - LIFT_DROP;
    }

    public static Frame frame(float width, float height, float send, float receive) {
        float baseY = baseYFor(parcelT(send, receive));
        float scale = Math.min(.5f / Math.max(width, .01f),
                (PORTAL_Y - TRAY_Y - .025f) / Math.max(height, .01f));
        return new Frame(baseY, scale, Math.max(0, (PORTAL_Y - baseY) / scale));
    }

    private DockParcelMotion() {}
}
