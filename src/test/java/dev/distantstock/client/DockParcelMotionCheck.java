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
        lift();
        liftTexture();
        System.out.println("Dock motion checks PASSED: 16 sizes, 301 frames, lift catch, "
                + "reversible motion, clearance and deck uvs.");
    }

    /**
     * The lift has to actually rise to meet an arriving parcel, stay under one being sent, and sit
     * on the tray the rest of the time. Its deck must also never reach the lid.
     */
    private static void lift() {
        require(DockParcelMotion.liftT(-1, -1) == 0, "idle lift must sit on the tray");

        // Incoming: rise to meet the parcel, then ride down with it. Pinned to the closed form rather
        // than sampled for a trend, because the single peak is the whole point and a trend check
        // cannot tell one peak from a plateau.
        float meet = 1 - DockParcelMotion.CATCH_AT;
        float peakT = -1;
        float peakAt = -1;
        for (int i = 0; i <= 300; i++) {
            float receive = i / 300f;
            // The parcel's own progress, which is what the lift's curve is expressed in.
            float parcel = DockParcelMotion.parcelT(-1, receive);
            float t = DockParcelMotion.liftT(-1, receive);
            require(t >= 0 && t <= 1, "lift progress out of range");
            if (t > peakT) {
                peakT = t;
                peakAt = receive;
            }
            if (parcel <= meet) {
                // Past the catch: carrying the parcel the rest of the way down.
                require(Math.abs(t - parcel) < .000001f, "lift must descend with its parcel");
            } else {
                // Rising to the catch, on its own steeper curve.
                float expected = (1 - parcel) * meet / DockParcelMotion.CATCH_AT;
                require(Math.abs(t - expected) < .000001f, "lift must climb to the catch");
            }
            float deck = DockParcelMotion.liftTopY(t);
            // Never above the box coming down, or the deck would pass through the parcel on its way up.
            require(deck <= DockParcelMotion.baseYFor(parcel) + .000001f,
                    "the lift must stay under the parcel it is meeting");
            require(deck <= DockParcelMotion.PORTAL_Y + .000001f, "lift crosses the lid on the way in");
            require(deck >= DockParcelMotion.TRAY_Y - DockParcelMotion.LIFT_DROP - .000001f,
                    "lift sinks below its rest position");
        }
        // One peak, at the moment the two meet, and worth calling a lift.
        require(Math.abs(peakAt - DockParcelMotion.CATCH_AT) < .01f, "the lift peaks at the wrong moment");
        require(peakT >= 2 * DockParcelMotion.CATCH_AT, "lift barely moves");
        require(Math.abs(DockParcelMotion.liftT(-1, DockParcelMotion.CATCH_AT) - meet) < .000001f,
                "the lift must arrive exactly as the parcel does");
        // At the catch the deck is directly under the parcel, exactly as it is on the way out.
        require(Math.abs(DockParcelMotion.liftTopY(meet) - (DockParcelMotion.baseYFor(meet)
                - DockParcelMotion.LIFT_DROP)) < .000001f, "the catch must land under the parcel");

        for (int i = 0; i <= 300; i++) {
            float send = i / 300f;
            float parcel = DockParcelMotion.parcelT(send, -1);
            float t = DockParcelMotion.liftT(send, -1);
            require(Math.abs(t - Math.min(DockParcelMotion.LIFT_CEILING, parcel)) < .000001f,
                    "a sent parcel must ride the lift until the ceiling takes over");
            // Under the parcel the whole way: the deck never rises into the box it is carrying.
            require(DockParcelMotion.liftTopY(t) <= DockParcelMotion.baseYFor(parcel) + .000001f,
                    "the lift must stay under the parcel it carries");
        }
        require(DockParcelMotion.liftT(1, -1) == DockParcelMotion.LIFT_CEILING,
                "the lift must hold at its ceiling while the parcel leaves");
        // The whole point of the ceiling: a lift never has to be clipped against the lid.
        require(DockParcelMotion.liftTopY(DockParcelMotion.LIFT_CEILING) < DockParcelMotion.PORTAL_Y,
                "the ceiling must keep the lift below the portal");
    }

    /**
     * The deck's uv table has to agree with vanilla's corner pairing.
     *
     * A face covering a whole sprite hands vertex k the rectangle corner (0,0), (0,V), (U,V), (U,0)
     * of BlockFaceUV. Walking FACE_CORNERS against that sequence re-derives which axis u and v run
     * along and which way round; the renderer's table has to say the same thing. Checked on a box
     * that is different on all three axes, so a swapped pair or a flipped sign cannot pass by luck.
     */
    private static void liftTexture() {
        // Different on all three axes, so a swapped pair or a flipped sign cannot pass by luck.
        float[] bounds = {3f, 5f, 7f, 11f, 9f, 15f};
        for (int face = 0; face < DockParcelMotion.FACE_CORNERS.length; face++) {
            float[][] uv = new float[4][];
            for (int corner = 0; corner < 4; corner++) {
                uv[corner] = DockParcelMotion.faceUv(face, bounds, corner);
            }
            // Vanilla hands vertex k the rectangle corner (0,0), (0,V), (U,V), (U,0), so the
            // sequence is pinned as soon as one corner gives the opposite pair.
            float u = uv[2][0], v = uv[2][1];
            require(u > 0 && v > 0, "a cuboid face must map to a real rectangle");
            require(same(uv[0], 0, 0) && same(uv[1], 0, v) && same(uv[3], u, 0),
                    "deck face " + face + " does not follow vanilla's corner order");
        }
        // Proportional, not stretched: one turn of the sprite per block.
        float[] lip = DockParcelMotion.LIFT_BOXES[1];
        float[] far = DockParcelMotion.faceUv(1, lip, 2);
        require(Math.abs(Math.max(far[0], far[1]) - 12f / 16f) < 1e-6f,
                "a twelve-unit edge should read three quarters of the tile");
        require(Math.abs(Math.min(far[0], far[1]) - 1f / 16f) < 1e-6f,
                "a one-unit edge should read a sixteenth of the tile");
    }

    private static boolean same(float[] uv, float u, float v) {
        return Math.abs(uv[0] - u) < 1e-5f && Math.abs(uv[1] - v) < 1e-5f;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
