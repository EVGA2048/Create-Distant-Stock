package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeRenderer;
import dev.distantstock.block.NixieClockBlock;
import dev.distantstock.block.NixieClockBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Five independent Create Nixie tubes: HH:MM, including a real glass envelope for the colon. */
public final class NixieClockRenderer implements BlockEntityRenderer<NixieClockBlockEntity> {
    private static final float GLYPH_SCALE = 0.0215f;
    private static final float GLYPH_Y = 8.85f;
    private static final float GLYPH_Z = 11.35f;

    public NixieClockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NixieClockBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;
        Direction facing = be.getBlockState().getValue(NixieClockBlock.FACING);
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        pose.translate(-.5, -.5, -.5);

        String text = be.displayText();
        int colon = text.indexOf(':');
        if (colon > 0 && colon + 2 < text.length()) {
            String hour = text.substring(0, colon);
            String minute = text.substring(colon + 1);
            if (hour.length() >= 2) {
                drawGlyph(be, pose, buffers, hour.substring(hour.length() - 2, hour.length() - 1), 14.35f);
                drawGlyph(be, pose, buffers, hour.substring(hour.length() - 1), 11.85f);
            } else {
                // 12-hour mode intentionally has no leading zero: the first physical tube stays dark.
                drawGlyph(be, pose, buffers, hour, 11.85f);
            }
            drawGlyph(be, pose, buffers, ":", 9.35f);
            drawGlyph(be, pose, buffers, minute.substring(0, 1), 6.85f);
            drawGlyph(be, pose, buffers, minute.substring(1, 2), 4.35f);
        }
        ClockIndicatorRenderer.render(be.twentyFourHour(), be.getBlockState(), pose, buffers, packedOverlay);
        pose.popPose();
    }

    private static void drawGlyph(NixieClockBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                                  String glyph, float xPixels) {
        pose.pushPose();
        pose.translate(xPixels / 16f, GLYPH_Y / 16f, GLYPH_Z / 16f);
        pose.scale(GLYPH_SCALE, -GLYPH_SCALE, GLYPH_SCALE);
        NixieTubeRenderer.drawTube(pose, buffers, glyph, 4.5f, be.color(), be.getLevel().getRandom());
        pose.popPose();
    }

    @Override public int getViewDistance() { return 64; }
}
