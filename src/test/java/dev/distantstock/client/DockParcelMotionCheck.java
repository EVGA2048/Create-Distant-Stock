package dev.distantstock.client;

public final class DockParcelMotionCheck {
    public static void main(String[] args) {
        for (float width : new float[]{.5f, .75f, 1, 1.5f}) {
            for (float height : new float[]{.5f, .75f, 1, 1.5f}) {
                var idle = DockParcelMotion.frame(width, height, -1, -1);
                require(idle.baseY() == DockParcelMotion.TRAY_Y, "idle must rest on tray");
                float last = idle.baseY();
                for (int i = 0; i <= 300; i++) {
                    float progress = i / 300f;
                    var send = DockParcelMotion.frame(width, height, progress, -1);
                    var receive = DockParcelMotion.frame(width, height, -1, 1 - progress);
                    require(Math.abs(send.baseY() - receive.baseY()) < .000001f, "receive must reverse send");
                    require(send.baseY() >= last, "send must rise monotonically");
                    require(width * send.scale() <= .500001f, "parcel exceeds portal opening");
                    float top = send.baseY() + Math.min(height, send.clipY()) * send.scale();
                    require(top <= DockParcelMotion.PORTAL_Y + .000001f, "parcel crosses lid");
                    require(send.baseY() >= DockParcelMotion.TRAY_Y, "parcel crosses tray");
                    last = send.baseY();
                }
                require(DockParcelMotion.frame(width, height, 1, -1).clipY() == 0, "sent parcel must disappear");
            }
        }
        System.out.println("Dock motion checks PASSED: 16 sizes, 301 frames, reversible motion and clearance.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
