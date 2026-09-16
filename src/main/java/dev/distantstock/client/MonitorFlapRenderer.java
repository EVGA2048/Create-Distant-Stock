package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * Create's flap display renderer, shrunk and pushed back so it fits the monitor's window.
 *
 * <p>The board on the monitor's face <em>is</em> a Creaate flap display — the glyphs, the flip
 * animation and the click of the segments are all Create's — but Create draws it for a board of its
 * own shape: its display block has a front plate at z=3/16 with the flap cavity behind it, so the
 * glyph plane it computes lands at 5/16 of the way through the block. The monitor is a flush wall
 * panel whose face is at 13/16, and copying that renderer straight over put the text eight pixels
 * <em>in front of</em> the panel, floating in the air — which is what the first attempt looked like.
 *
 * <p>Two corrections, both here rather than in the geometry: the whole board is scaled to
 * {@link #SCALE} about the block's centre so a four-character line fits the window carved in the
 * face instead of covering the frame, and the plane is pushed back along the direction the panel
 * faces by {@link #PUSH} — measured from where Create puts it to half a pixel in front of our own
 * face, which is where the window is.
 *
 * <p>{@link #SCALE} is chosen so the glyph block lands on the flip area painted in the middle of
 * the face — the painted louvres stay, which is the texture the player asked for, and the text
 * covers them the way a real board's flaps cover its front plate.
 */
public final class MonitorFlapRenderer extends FlapDisplayRenderer {
    /** 0.8 of Create's size: the glyph block covers the flip area painted on the face, no more. */
    private static final float SCALE = 0.8f;
    /**
     * How far the glyph plane is pushed back, in blocks, measured from Create's own plane.
     *
     * <p>Applied <em>before</em> the scale, and that order is the whole of the second attempt: a
     * translate after a scale is measured in the scaled frame, so the first version's 0.40 arrived
     * as 0.40 × 0.65 = 0.26 and left the text floating two and a half pixels in front of the panel.
     * In block units, 0.54 puts the plane at 12.5/16 — half a pixel in front of the face, which is
     * where a real display board keeps its flaps.
     */
    private static final float PUSH = 0.54f;

    public MonitorFlapRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(FlapDisplayBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // The way the panel faces, in Create's sense: from the front, into the block.
        Direction back = be.getDirection();
        ms.pushPose();
        ms.translate(back.getStepX() * PUSH, 0, back.getStepZ() * PUSH);
        ms.translate(0.5f, 0.5f, 0.5f);
        ms.scale(SCALE, SCALE, SCALE);
        ms.translate(-0.5f, -0.5f, -0.5f);
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        ms.popPose();
    }
}
