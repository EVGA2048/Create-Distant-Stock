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
 * <p>Both numbers are tied to {@code scripts/gen_monitor_face.py}: it carves the window at the
 * rectangle this transform puts the text in. Change one and the other has to follow.
 */
public final class MonitorFlapRenderer extends FlapDisplayRenderer {
    /** 0.65 of Create's size: four characters span about ten of the face's sixteen pixels. */
    private static final float SCALE = 0.65f;
    /** Where the glyph plane has to sit, measured from Create's own plane, along the panel's normal. */
    private static final float PUSH = 0.40f;

    public MonitorFlapRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(FlapDisplayBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // The way the panel faces, in Create's sense: from the front, into the block.
        Direction back = be.getDirection();
        ms.pushPose();
        ms.translate(0.5f, 0.5f, 0.5f);
        ms.scale(SCALE, SCALE, SCALE);
        ms.translate(-0.5f, -0.5f, -0.5f);
        ms.translate(back.getStepX() * PUSH, 0, back.getStepZ() * PUSH);
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        ms.popPose();
    }
}
