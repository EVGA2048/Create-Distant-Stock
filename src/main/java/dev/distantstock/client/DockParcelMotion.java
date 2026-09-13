package dev.distantstock.client;

/** Geometry in block units; independent of Minecraft so the entire motion can be checked. */
public final class DockParcelMotion {
    public static final float PORTAL_Y = 13.35f / 16f;
    public static final float TRAY_Y = 4.05f / 16f;
    public record Frame(float baseY, float scale, float clipY) {}

    public static Frame frame(float width, float height, float send, float receive) {
        float t = Math.clamp(send >= 0 ? send : receive >= 0 ? 1 - receive : 0, 0, 1);
        float eased = t * t * (3 - 2 * t);
        float baseY = TRAY_Y + (PORTAL_Y - TRAY_Y) * eased;
        float scale = Math.min(.5f / Math.max(width, .01f),
                (PORTAL_Y - TRAY_Y - .025f) / Math.max(height, .01f));
        return new Frame(baseY, scale, Math.max(0, (PORTAL_Y - baseY) / scale));
    }

    private DockParcelMotion() {}
}
