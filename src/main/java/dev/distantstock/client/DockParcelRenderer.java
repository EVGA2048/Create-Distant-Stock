package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import java.util.ArrayList;
import java.util.List;

/** Draw actual package quads clipped at the portal, never above the lid. */
public final class DockParcelRenderer implements BlockEntityRenderer<DockBlockEntity> {
    public DockParcelRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(DockBlockEntity be, float partialTicks, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        var parcel = be.displayedStack();
        if (parcel.isEmpty() || be.getLevel() == null) return;
        float send = be.transmitProgress(partialTicks);
        float receive = be.receiveProgress(partialTicks);
        var frame = DockParcelMotion.frame(PackageItem.getWidth(parcel), PackageItem.getHeight(parcel), send, receive);
        float baseY = frame.baseY();
        float scale = frame.scale();
        float clipY = frame.clipY();
        if (clipY <= 0) return;
        var model = Minecraft.getInstance().getItemRenderer().getModel(parcel, be.getLevel(), null, 0);
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        pose.pushPose();
        pose.translate(.5, baseY, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - be.getBlockState().getValue(DockBlock.FACING).toYRot()));
        pose.scale(scale, scale, scale);
        pose.translate(-.5, 0, -.5);
        RandomSource random = RandomSource.create(42);
        for (int face = 0; face <= 6; face++) {
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, face == 6 ? null : Direction.values()[face], random)) {
                drawClipped(quad, clipY, pose, out, light);
            }
        }
        pose.popPose();
    }

    private record Vertex(float x, float y, float z, float u, float v) {
        Vertex intersect(Vertex other, float yLimit) {
            float t = (yLimit - y) / (other.y - y);
            return new Vertex(x + (other.x - x) * t, yLimit, z + (other.z - z) * t,
                    u + (other.u - u) * t, v + (other.v - v) * t);
        }
    }

    private static void drawClipped(BakedQuad quad, float limit, PoseStack pose, VertexConsumer out, int light) {
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        List<Vertex> vertices = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int p = i * stride;
            vertices.add(new Vertex(Float.intBitsToFloat(data[p]), Float.intBitsToFloat(data[p + 1]),
                    Float.intBitsToFloat(data[p + 2]), Float.intBitsToFloat(data[p + 4]), Float.intBitsToFloat(data[p + 5])));
        }
        List<Vertex> clipped = new ArrayList<>(5);
        Vertex previous = vertices.get(3);
        for (Vertex current : vertices) {
            if ((previous.y <= limit) != (current.y <= limit)) clipped.add(previous.intersect(current, limit));
            if (current.y <= limit) clipped.add(current);
            previous = current;
        }
        // Triangle fans encoded as degenerate quads for Minecraft's QUADS buffer.
        for (int i = 1; i + 1 < clipped.size(); i++) {
            emit(clipped.get(0), quad.getDirection(), pose, out, light);
            emit(clipped.get(i), quad.getDirection(), pose, out, light);
            emit(clipped.get(i + 1), quad.getDirection(), pose, out, light);
            emit(clipped.get(i + 1), quad.getDirection(), pose, out, light);
        }
    }

    private static void emit(Vertex v, Direction normal, PoseStack pose, VertexConsumer out, int light) {
        out.addVertex(pose.last().pose(), v.x, v.y, v.z).setColor(255, 255, 255, 255)
                .setUv(v.u, v.v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(), normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }
}
