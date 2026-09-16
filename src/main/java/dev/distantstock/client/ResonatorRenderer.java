package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.distantstock.DistantStock;
import dev.distantstock.block.ResonatorBlockEntity;
import dev.distantstock.block.TowerStructure;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    /**
     * Degrees per tick. Slow enough to read as idle machinery rather than a fan.
     *
     * <p>Was 1.5, which is a full turn every twelve seconds — fast enough that a player watching it
     * reads a fan or a warning beacon rather than a machine idling under load. Reported from play.
     */
    private static final float SPEED = 0.35f;

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

    /**
     * Forces this class to load, and with it the two partial models above, before anything is baked.
     *
     * <p>This is not ceremony. A partial model is registered by the act of asking for it, and a
     * model that was never registered is never baked — asking for one afterwards hands back the
     * missing model, which is the purple-and-black cube. The statics here were only ever touched
     * when the renderer was first constructed, which happens when a player first looks at a
     * resonator: an entire tower's cap drawn as a spinning checkerboard cube, and nothing in the
     * log to say why.
     */
    public static void registerModels() {
    }

    public ResonatorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * Far enough to see the beam from where it is meant to be seen.
     *
     * <p>A block entity renderer is culled by distance like any other, and the default is sixty-four
     * blocks — which would make a landmark for the far side of the world a thing you have to walk up
     * to before it appears.
     */
    @Override
    public int getViewDistance() {
        return 256;
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
        drawSkyBeam(be, beam, pose, buffers);
        // Assembled is not enough. A mast with no shaft under it is a complete structure and a
        // machine that is not running, and a rotor turning above it would be telling the player the
        // tower is working when nothing is driving it — the one thing this renderer exists to make
        // legible. Assembled only decides whether the arms are there at all.
        BlockPos cap = be.getBlockPos();
        if (!TowerStructure.assembled(be.getLevel(), cap)
                || TowerStructure.coreUnder(be.getLevel(), cap).stream()
                        .noneMatch(core -> TowerStructure.running(be.getLevel(), core))) {
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
     * The column into the sky, seen from across the world.
     *
     * <p>Vanilla's own beacon beam, drawn from the top of the cap to the build limit: the machine
     * already says what it is doing on its own surface, and what a tower needs is a marker that can
     * be found from a hill two hundred blocks away. It appears when the tower is running and not
     * before — a beam over a mast with no shaft under it would be a landmark pointing at a machine
     * that is not working.
     *
     * <p>The colour follows the cap's own state, so the flash that says "a parcel just crossed here"
     * is visible at the distance the beam was built for. Vanilla's beam texture is in the entity
     * atlas and needs no registration of ours.
     */
    private static void drawSkyBeam(ResonatorBlockEntity be, ResonatorBlockEntity.Beam beam,
                                    PoseStack pose, MultiBufferSource buffers) {
        if (be.getLevel() == null || beam == ResonatorBlockEntity.Beam.DORMANT) {
            return;
        }
        float[] colour = BEAM_COLOUR[beam.ordinal()];
        int height = be.getLevel().getMaxBuildHeight() - be.getBlockPos().getY() - 1;
        if (height <= 0) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5, 1.0, 0.5);
        // Drawn as four walls of a square column rather than with vanilla's beacon renderer: that
        // helper is built around the beacon block entity's own scale and scrolling, and a column of
        // our own costs four quads and lets the colour answer to the tower's state directly.
        // Vanilla's beacon render type, which is a texture of its own rather than a sprite in an
        // atlas: it is set to repeat, so the column tiles up the sky instead of stretching one image
        // over two hundred blocks.
        VertexConsumer out = buffers.getBuffer(RenderType.beaconBeam(BEACON_BEAM, true));
        float half = 0.22f;

        // The texture scrolls upward, so a beam reads as moving even while the machine turns slowly.
        float scroll = (be.getLevel().getGameTime() % 40) / 40.0f;
        for (int face = 0; face < 4; face++) {
            float nx = face == 0 ? -1 : face == 1 ? 1 : 0;
            float nz = face == 2 ? -1 : face == 3 ? 1 : 0;
            float ax = -nz * half;
            float az = nx * half;
            wall(out, pose, ax, az, nx, nz, height, scroll, colour);
        }
        pose.popPose();
    }

    private static void wall(VertexConsumer out, PoseStack pose, float ax, float az,
                             float nx, float nz, int height, float scroll, float[] colour) {
        float u0 = 0.0f;
        float u1 = 1.0f;
        float vBase = 0.0f;
        float vSpan = 1.0f;
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        // Two quads per wall, so the column is not one enormously stretched texture.
        int segments = 4;
        float segment = height / (float) segments;
        for (int i = 0; i < segments; i++) {
            float y0 = i * segment;
            float y1 = y0 + segment;
            float vv0 = vBase + (i + scroll) % 1.0f * vSpan;
            float vv1 = vBase + ((i + scroll) % 1.0f + 1.0f) * vSpan;
            out.addVertex(pose.last(), ax, y1, az).setColor(colour[0], colour[1], colour[2], colour[3])
                    .setUv(u1, vv1).setLight(light).setNormal(pose.last(), nx, 0, nz);
            out.addVertex(pose.last(), -ax, y1, -az).setColor(colour[0], colour[1], colour[2], colour[3])
                    .setUv(u0, vv1).setLight(light).setNormal(pose.last(), nx, 0, nz);
            out.addVertex(pose.last(), -ax, y0, -az).setColor(colour[0], colour[1], colour[2], colour[3])
                    .setUv(u0, vv0).setLight(light).setNormal(pose.last(), nx, 0, nz);
            out.addVertex(pose.last(), ax, y0, az).setColor(colour[0], colour[1], colour[2], colour[3])
                    .setUv(u1, vv0).setLight(light).setNormal(pose.last(), nx, 0, nz);
        }
    }

    /**
     * The beam's colour per state: dark and absent when dormant, pale cyan at rest, and a deep
     * saturated blue with more body while a parcel is crossing — the difference has to read from
     * the ground at the base of a tower thirty blocks tall.
     */
    private static final float[][] BEAM_COLOUR = {
            {0.42f, 0.48f, 0.52f, 0.0f},
            // 待机与工作态都往白里走、往亮里走：原来那两条蓝在夜空里几乎看不出是一道光柱。
            {0.86f, 0.93f, 1.0f, 0.45f},
            {0.97f, 0.99f, 1.0f, 0.78f},
    };

    /** Vanilla's beam texture: a bright vertical streak, already stitched into the block atlas. */
    private static final ResourceLocation BEACON_BEAM =
            ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");

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
