package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeRenderer;
import dev.distantstock.DistantStock;
import dev.distantstock.block.LoggerBlock;
import dev.distantstock.block.LoggerBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/** Renders the Logger's two miniature tubes with Create's own nixie glyph renderer. */
public final class LoggerRenderer implements BlockEntityRenderer<LoggerBlockEntity> {
    private static final PartialModel RECEIPT_FOLD = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/logger_receipt_fold"));
    private static final PartialModel RECEIPT_TICKET = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/logger_receipt_ticket"));

    public LoggerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(LoggerBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) return;
        String code = be.displayCode();
        if (code.length() < 2) code = (code + " ").substring(0, 2);
        else if (code.length() > 2) code = code.substring(0, 2);

        Direction facing = be.getBlockState().hasProperty(LoggerBlock.FACING)
                ? be.getBlockState().getValue(LoggerBlock.FACING) : Direction.NORTH;

        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        pose.translate(-.5, -.5, -.5);

        // The miniature tubes occupy x=4..9 and x=10..15, with the character plane one pixel
        // behind their front glass. drawTube is Create's own glowing glyph code; using it here is
        // what keeps letters and digits visually identical to real Create nixies.
        // Model-space X is mirrored from the player's view on the front face.  The old order drew
        // "OK" as "KO" in-world, so the first glyph belongs in the higher-X tube.
        drawCharacter(be, pose, buffers, code.substring(0, 1), 12.5f / 16f);
        drawCharacter(be, pose, buffers, code.substring(1, 2), 6.5f / 16f);
        renderReceipt(be, partialTick, pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }

    private static void renderReceipt(LoggerBlockEntity be, float partialTick, PoseStack pose,
                                      MultiBufferSource buffers, int packedLight, int packedOverlay) {
        float extension = be.receiptExtension(partialTick);
        if (extension <= 0.001f) return;

        // This method is called while the pose already carries the logger's exact block-facing
        // transform. The old renderer computed a second facing transform for paper only; east/west
        // were consequently mirrored relative to the printer. Keep the feed fold fixed at the slot.
        CachedBuffers.partial(RECEIPT_FOLD, be.getBlockState())
                .light(packedLight)
                .overlay(packedOverlay)
                .renderInto(pose, buffers.getBuffer(RenderType.translucent()));

        // The hanging sheet grows downward from the slot at y=4/16. Scaling around that pivot keeps
        // its top edge glued to the printer: .5 is literally half a ticket, 1 is the full slip.
        pose.pushPose();
        pose.translate(0, 4f / 16f, 0);
        pose.scale(1f, extension, 1f);
        pose.translate(0, -4f / 16f, 0);
        CachedBuffers.partial(RECEIPT_TICKET, be.getBlockState())
                .light(packedLight)
                .overlay(packedOverlay)
                .renderInto(pose, buffers.getBuffer(RenderType.translucent()));
        pose.popPose();
    }

    private static void drawCharacter(LoggerBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                                      String glyph, float x) {
        pose.pushPose();
        // Lift the glyphs roughly one character height.  The Blockbench sockets sit noticeably
        // above Create's default Nixie baseline; the previous baseline left the text in the lower
        // half of the glass.
        pose.translate(x, 12.5f / 16f, 9.95f / 16f);
        float scale = 0.035f;
        pose.scale(scale, -scale, scale);
        NixieTubeRenderer.drawTube(pose, buffers, glyph, 4.5f, DyeColor.ORANGE,
                be.getLevel().getRandom());
        pose.popPose();
    }
}
