package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ResonatorBlockEntity;
import dev.distantstock.block.TowerStructure;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The resonator's arms.
 *
 * <p>They sit at x[-4, 20]: the sweep is wider than the block, so a block model cannot draw them —
 * element rotation is limited to 22.5 degree steps and the block would be culled at its own
 * boundary. So the body is the block model and the arms are this.
 *
 * <p>The angle is not stored. It is the level's game time multiplied by a speed, so every client
 * lands on the same value without syncing anything, and a reload does not restart the arms.
 * Whether they turn at all is the tower's business: a cap that is not sitting on a complete mast
 * stands still.
 */
public final class ResonatorRenderer extends SmartBlockEntityRenderer<ResonatorBlockEntity> {
    private static final PartialModel ROTOR = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/tower/ether_resonator_rotor"));
    /** Degrees per tick. Slow enough to read as idle machinery rather than a fan. */
    private static final float SPEED = 1.5f;

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(ResonatorBlockEntity be, float partialTick, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null || !TowerStructure.assembled(be.getLevel(), be.getBlockPos())) {
            return;
        }
        BlockState state = be.getBlockState();
        float angle = ((be.getLevel().getGameTime() + partialTick) * SPEED) % 360.0f;
        CachedBuffers.partial(ROTOR, state)
                .rotateCentered(angle, Direction.UP)
                .light(light)
                .overlay(overlay)
                .renderInto(pose, buffers.getBuffer(RenderType.cutout()));
    }
}
