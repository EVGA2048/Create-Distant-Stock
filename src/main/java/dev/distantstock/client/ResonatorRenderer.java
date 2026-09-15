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
    private static final PartialModel BEAM = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/tower/ether_resonator_beam"));
    /** Degrees per tick. Slow enough to read as idle machinery rather than a fan. */
    private static final float SPEED = 1.5f;

    /**
     * What each state does to the light column, as a multiplier over the authored texture.
     *
     * <p>These are tints, not textures: the column is one sprite at three brightnesses rather than
     * three sprites, so a change of state costs a colour and nothing else. A tower that is not
     * turning goes grey and flat, one that is working shows the cyan it was drawn in, and one with
     * a parcel crossing it goes a deeper, more saturated blue and lights itself — the difference
     * has to be readable from the ground at the base of a tower thirty blocks tall.
     */
    private static final int[] BEAM_TINT = {0x6E7A85, 0xFFFFFF, 0x9FC4FF};
    /** And how solid it is. A dormant column is nearly a ghost; a working one is nearly glass. */
    private static final int[] BEAM_ALPHA = {150, 210, 255};

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(ResonatorBlockEntity be, float partialTick, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState state = be.getBlockState();
        ResonatorBlockEntity.Beam beam = be.beam();
        drawBeam(state, beam, pose, buffers, light, overlay);
        if (!TowerStructure.assembled(be.getLevel(), be.getBlockPos())) {
            return;
        }
        float angle = ((be.getLevel().getGameTime() + partialTick) * SPEED) % 360.0f;
        CachedBuffers.partial(ROTOR, state)
                .rotateCentered(angle, Direction.UP)
                .light(light)
                .overlay(overlay)
                .renderInto(pose, buffers.getBuffer(RenderType.cutout()));
    }

    /**
     * The light column, drawn whatever the tower is doing.
     *
     * <p>A dark column standing over a half-built mast is how a player finds out the tower is not
     * finished, so this is deliberately not conditional on the tower being assembled.
     */
    private static void drawBeam(BlockState state, ResonatorBlockEntity.Beam beam,
                                 PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        int tint = BEAM_TINT[beam.ordinal()];
        // A lit beam ignores the world's light: it is meant to read as a source, not a surface.
        int beamLight = beam == ResonatorBlockEntity.Beam.ACTIVE ? 0xF000F0 : light;
        CachedBuffers.partial(BEAM, state)
                .light(beamLight)
                .color((tint >> 16) & 0xFF, (tint >> 8) & 0xFF, tint & 0xFF,
                        BEAM_ALPHA[beam.ordinal()])
                // Translucent, and it has to be: the column is a light, not a surface. The layer
                // blends and does not write depth, so it still hides behind the tower's solid parts
                // while letting the world show through it — which cutout cannot do at all, since it
                // would throw away the texture's own shading and leave a painted tube.
                .renderInto(pose, buffers.getBuffer(RenderType.translucent()));
    }
}
