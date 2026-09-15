package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.DistantStock;
import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the parcel the dock is holding, clipped at the portal so it never pokes above the lid.
 *
 * The model comes from Create's package partials rather than the item model. Create renders its own
 * in-world boxes from those same partials with {@code RenderType.solid()}; sampling the item model
 * into an entity render type mixed the block atlas with the entity shader and came out unlit.
 */
public final class DockParcelRenderer implements BlockEntityRenderer<DockBlockEntity> {
    /** The remote parcel inherits Create's 12x12 box geometry; only the texture differs. */
    private static final PartialModel REMOTE_PACKAGE = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "item/remote_package_12x12"));

    /**
     * The lift is built here rather than loaded from a model.
     *
     * The plate has to travel independently of the block, so it cannot live in the blockstate, and a
     * model nothing refers to is never pulled into the bake. It is five boxes; drawing them directly
     * removes the whole question. The source art is {@code docs/design/accepted/dock-lift.json}, and
     * {@link #LIFT_BOXES} is that model's element list.
     *
     * Because no model refers to the texture, the atlas would not stitch it either.
     * {@code assets/minecraft/atlases/blocks.json} names it explicitly for that reason.
     */
    private static final ResourceLocation LIFT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/dock_cap");

    /** The lift plate's deck sits at model y=1, so the model is offset down by that much. */
    private static final float LIFT_DECK_Y = 1f / 16f;

    private static final Direction[] FACE_NORMALS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
    };

    public DockParcelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(DockBlockEntity be, float partialTicks, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        float send = be.transmitProgress(partialTicks);
        float receive = be.receiveProgress(partialTicks);
        float facingYRot = be.getBlockState().getValue(DockBlock.FACING).toYRot();
        // Both are drawn inside the dock block, where the block's own light is the inside of a
        // machine. Sample the open face they rise through instead, so they are lit like the room.
        int innerLight = Math.max(light, LevelRenderer.getLightColor(be.getLevel(),
                be.getBlockPos().relative(be.getBlockState().getValue(DockBlock.FACING))));
        VertexConsumer out = buffers.getBuffer(RenderType.solid());

        // Drawn even with nothing in the dock, so the lift reads as part of the machine at rest.
        drawLift(pose, out, innerLight, facingYRot,
                DockParcelMotion.liftTopY(DockParcelMotion.liftT(send, receive)));

        ItemStack parcel = be.displayedStack();
        if (parcel.isEmpty()) {
            return;
        }
        PartialModel partial = AllPartialModels.PACKAGES.get(BuiltInRegistries.ITEM.getKey(parcel.getItem()));
        var model = (partial == null ? REMOTE_PACKAGE : partial).get();
        if (model == null) {
            return;
        }
        var frame = DockParcelMotion.frame(PackageItem.getWidth(parcel), PackageItem.getHeight(parcel),
                send, receive);
        if (frame.clipY() <= 0) {
            return;
        }
        pose.pushPose();
        pose.translate(.5, frame.baseY(), .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - facingYRot));
        pose.scale(frame.scale(), frame.scale(), frame.scale());
        pose.translate(-.5, 0, -.5);
        drawModel(model, frame.clipY(), pose, out, innerLight);
        pose.popPose();
    }

    /**
     * The lift, riding under the parcel.
     *
     * On the way out it travels all the way to the portal, so it is clipped there exactly as the
     * parcel is and never pokes through the lid.
     */
    private void drawLift(PoseStack pose, VertexConsumer out, int light, float facingYRot, float topY) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(LIFT_TEXTURE);
        pose.pushPose();
        pose.translate(.5, topY - LIFT_DECK_Y, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - facingYRot));
        // Everything below this line is in model units: the boxes are 0..16 like a model's, and
        // this is the only place the scale is applied. Dividing in box() as well drew the whole
        // lift at 1/256 of a block, which is why it was never visible.
        pose.scale(1f / 16f, 1f / 16f, 1f / 16f);
        pose.translate(-8, 0, -8);
        for (float[] box : DockParcelMotion.LIFT_BOXES) {
            box(pose, out, light, sprite, box);
        }
        pose.popPose();
    }

    /** One axis-aligned box, six faces, drawn the way the baker would have drawn it. */
    private static void box(PoseStack pose, VertexConsumer out, int light, TextureAtlasSprite sprite,
                            float[] bounds) {
        float uSpan = sprite.getU1() - sprite.getU0();
        float vSpan = sprite.getV1() - sprite.getV0();

        for (int face = 0; face < DockParcelMotion.FACE_CORNERS.length; face++) {
            Direction normal = FACE_NORMALS[face];
            for (int corner = 0; corner < 4; corner++) {
                int[] pick = DockParcelMotion.FACE_CORNERS[face][corner];
                // Bounds are minX,minY,minZ,maxX,maxY,maxZ. Model units, not blocks: the pose
                // already carries the 1/16 scale.
                float x = bounds[pick[0] == 0 ? 0 : 3];
                float y = bounds[pick[1] == 0 ? 1 : 4];
                float z = bounds[pick[2] == 0 ? 2 : 5];
                float[] uv = DockParcelMotion.faceUv(face, bounds, corner);
                out.addVertex(pose.last(), x, y, z)
                        .setColor(255, 255, 255, 255)
                        .setUv(sprite.getU0() + uv[0] * uSpan, sprite.getV0() + uv[1] * vSpan)
                        .setLight(light)
                        .setNormal(pose.last(), normal.getStepX(), normal.getStepY(), normal.getStepZ());
            }
        }
    }

    private static void drawModel(net.minecraft.client.resources.model.BakedModel model, float clipY,
                                  PoseStack pose, VertexConsumer out, int light) {
        RandomSource random = RandomSource.create(42);
        for (int face = 0; face <= 6; face++) {
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(Blocks.AIR.defaultBlockState(),
                    face == 6 ? null : Direction.values()[face], random)) {
                drawClipped(quad, clipY, pose, out, light);
            }
        }
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
            if ((previous.y() <= limit) != (current.y() <= limit)) clipped.add(previous.intersect(current, limit));
            if (current.y() <= limit) clipped.add(current);
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

    /** RenderType.solid() uses the block vertex format, which carries no overlay component. */
    private static void emit(Vertex v, Direction normal, PoseStack pose, VertexConsumer out, int light) {
        out.addVertex(pose.last(), v.x(), v.y(), v.z()).setColor(255, 255, 255, 255)
                .setUv(v.u(), v.v()).setLight(light)
                .setNormal(pose.last(), normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }
}
