package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayRenderer;
import dev.distantstock.block.FlapClockBlock;
import dev.distantstock.block.FlapClockBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** One real Create flap line compressed cleanly into a half-height, one-block-wide clock. */
public final class FlapClockRenderer extends FlapDisplayRenderer {
    private static final float X_SCALE = 0.47f;

    /**
     * Create's own glyph plane starts well inside our custom wall panel. The front face is opaque.
     * The test28 sign flip overshot by a full block plus roughly two model pixels. Measured from that
     * real screenshot, moving back 18/16 block lands at +0.45 along getDirection(): just outside
     * the clock face instead of a whole block in front.
     */
    private static final float FRONT_PUSH = 0.20f;

    /** 24h position is already visually correct. A one-digit 12h hour is shifted right by half a glyph. */
    private static final float LEFT_SHIFT_24 = 2.75f / 16f;
    private static final float LEFT_SHIFT_12 = 2.0f / 16f;

    public FlapClockRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(FlapDisplayBlockEntity raw, float partialTicks, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        FlapClockBlockEntity be = (FlapClockBlockEntity) raw;
        Direction facing = be.getBlockState().getValue(FlapClockBlock.FACING);
        Direction back = be.getDirection();
        Direction visualLeft = facing.getClockWise();

        pose.pushPose();
        pose.translate(back.getStepX() * FRONT_PUSH, -0.25f, back.getStepZ() * FRONT_PUSH);
        float horizontalShift = be.twentyFourHour() ? LEFT_SHIFT_24 : LEFT_SHIFT_12;
        pose.translate(visualLeft.getStepX() * horizontalShift, 0, visualLeft.getStepZ() * horizontalShift);
        // The logical Create board is two blocks wide; squeeze it to one physical block without
        // changing its vertical proportions or flip animation.
        pose.scale(X_SCALE, 1.0f, 1.0f);
        super.renderSafe(be, partialTicks, pose, buffers, light, overlay);
        pose.popPose();

        // Mode lamp stays exactly where test26 put it.
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        pose.translate(-.5, -.5, -.5);
        ClockIndicatorRenderer.render(be.twentyFourHour(), be.getBlockState(), pose, buffers, overlay);
        pose.popPose();
    }
}
