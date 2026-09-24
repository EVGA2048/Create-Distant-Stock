package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayRenderer;
import dev.distantstock.block.GaugeBlock;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * Create's real split-flap glyphs fitted into the small sloping status window on the Request Desk.
 * The model's control head is tilted -22.5 degrees from horizontal; Create's renderer starts with
 * a vertical board, so the glyph plane is rotated another 67.5 degrees around the desk-local X
 * axis and then shrunk/translated onto the 7x10-pixel flap area painted into control.png.
 */
public final class GaugeFlapRenderer extends FlapDisplayRenderer {
    private static final float SCALE = .78f;
    private static final float TILT = 67.5f;
    private static final float RIGHT = .19f;
    private static final float FORWARD = .17f;
    private static final float UP = .59f;

    public GaugeFlapRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(FlapDisplayBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        Direction facing = be.getBlockState().hasProperty(GaugeBlock.FACING)
                ? be.getBlockState().getValue(GaugeBlock.FACING) : Direction.NORTH;
        Direction right = facing.getClockWise();
        float yaw = AngleHelper.horizontalAngle(facing);

        ms.pushPose();
        ms.translate(right.getStepX() * RIGHT + facing.getStepX() * FORWARD,
                UP,
                right.getStepZ() * RIGHT + facing.getStepZ() * FORWARD);

        // Scale around the block centre so Create's own one-block/two-line layout stays intact.
        ms.translate(.5f, .5f, .5f);
        ms.scale(SCALE, SCALE, SCALE);
        ms.translate(-.5f, -.5f, -.5f);

        // Conjugating the X tilt by the block yaw keeps the hinge axis attached to the desk when
        // the player rotates it north/east/south/west. Create then applies that same yaw itself.
        ms.translate(.5f, .5f, .5f);
        ms.mulPose(Axis.YP.rotationDegrees(yaw));
        ms.mulPose(Axis.XP.rotationDegrees(TILT));
        ms.mulPose(Axis.YP.rotationDegrees(-yaw));
        ms.translate(-.5f, -.5f, -.5f);

        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        ms.popPose();
    }
}
