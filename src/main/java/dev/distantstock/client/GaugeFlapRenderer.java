package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.distantstock.block.GaugeBlock;
import dev.distantstock.block.GaugeBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * Small status lettering on the Request Desk.
 *
 * <p>This deliberately does NOT extend Create's {@code FlapDisplayRenderer}. GaugeBlockEntity uses
 * FlapDisplayBlockEntity only as a convenient two-line text store; the desk itself is not a kinetic
 * block and its block does not implement IRotate. Create's flap renderer always renders its kinetic
 * cog first and therefore casts the host block to IRotate, which crashes as soon as a real desk is
 * visible in-world. We render the two short labels directly on the authored sloped panel instead.
 */
public final class GaugeFlapRenderer extends SafeBlockEntityRenderer<GaugeBlockEntity> {
    private final Font font;

    public GaugeFlapRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    protected void renderSafe(GaugeBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        Direction facing = be.getBlockState().hasProperty(GaugeBlock.FACING)
                ? be.getBlockState().getValue(GaugeBlock.FACING) : Direction.NORTH;

        ms.pushPose();
        // Work in desk-local coordinates; NORTH is the authored model orientation.
        ms.translate(.5f, 0, .5f);
        ms.mulPose(Axis.YP.rotationDegrees(switch (facing) {
            case SOUTH -> 180f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> 0f;
        }));
        ms.translate(-.5f, 0, -.5f);

        // The control head's top plane is rotated -22.5 degrees around X at y=13/16.
        // Place the glyphs a hair above that plane to avoid z-fighting with control.png.
        ms.translate(.5f, 13f / 16f, .5f);
        ms.mulPose(Axis.XP.rotationDegrees(-22.5f));
        ms.translate(-.5f, -13f / 16f, -.5f);
        ms.translate(.5f, .855f, .205f);
        ms.scale(.0105f, -.0105f, .0105f);

        drawCentered("REQ", -11f, ms, buffer, light);
        drawCentered(be.displayState().name(), 1f, ms, buffer, light);
        ms.popPose();
    }

    private void drawCentered(String text, float y, PoseStack ms, MultiBufferSource buffer, int light) {
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, y, 0xFFE8E2C7, false, ms.last().pose(), buffer,
                Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }
}
