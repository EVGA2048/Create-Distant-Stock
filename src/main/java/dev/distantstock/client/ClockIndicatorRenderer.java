package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;

/** The exact 2x2 transparent bulb shape used by Create's Factory Gauge, recoloured blue/red. */
public final class ClockIndicatorRenderer {
    private static final PartialModel BLUE = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/clock/indicator_blue"));
    private static final PartialModel RED = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/clock/indicator_red"));

    public static void registerModels() {
        // Class loading registers the partials before model baking.
    }

    public static void render(boolean twentyFourHour, BlockState state, PoseStack pose,
                              MultiBufferSource buffers, int overlay) {
        CachedBuffers.partial(twentyFourHour ? RED : BLUE, state)
                .light(LightTexture.FULL_BRIGHT)
                .overlay(overlay)
                .renderInto(pose, buffers.getBuffer(RenderType.translucent()));
    }

    private ClockIndicatorRenderer() {
    }
}
