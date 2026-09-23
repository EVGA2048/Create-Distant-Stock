package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.WallSounderBlock;
import dev.distantstock.block.WallSounderBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A second, full-bright copy of only the lit lens/filament.
 *
 * <p>The baked block model still supplies the authored V4 translucent lamp.  This layer exists so
 * shader packs cannot shade the luminous part down like ordinary glass; paired with block light 15
 * it also gives bloom-capable shaders a genuinely bright source to work from.
 */
public final class WallSounderRenderer implements BlockEntityRenderer<WallSounderBlockEntity> {
    private static final PartialModel RED_GLOW = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/sounder/red_emissive"));
    private static final PartialModel ORANGE_GLOW = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/sounder/orange_emissive"));

    public static void registerModels() {
        // Loading the class before model baking is the registration.
    }

    public WallSounderRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WallSounderBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState state = be.getBlockState();
        if (!state.getValue(WallSounderBlock.LIT)) return;

        PartialModel model = state.is(ModBlocks.RED_WALL_SOUNDER.get()) ? RED_GLOW : ORANGE_GLOW;
        // Catnip/Flywheel's positive Y rotation is opposite to vanilla blockstate-model Y.
        // Using the blockstate degrees directly makes 0/180 look correct while swapping east and
        // west, which is exactly the detached mirrored glow seen in-game. Create's own dynamic
        // renderers use the same 180 - toYRot() conversion.
        float rotation = rotationRadians(state.getValue(WallSounderBlock.FACING));
        CachedBuffers.partial(model, state)
                .rotateCentered(rotation, Direction.UP)
                .light(LightTexture.FULL_BRIGHT)
                .overlay(packedOverlay)
                .renderInto(pose, buffers.getBuffer(RenderType.translucent()));
    }

    /** Matches vanilla blockstate-model Y rotation in Catnip/Flywheel transform space. */
    public static float rotationRadians(Direction facing) {
        return (float) Math.toRadians(180.0f - facing.toYRot());
    }
}
