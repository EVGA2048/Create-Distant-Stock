package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Create registers its panel mesh only for its own block; draw ours using the same partial model. */
public final class RemoteGaugeRenderer extends FactoryPanelRenderer {
    public RemoteGaugeRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @Override
    protected void renderSafe(FactoryPanelBlockEntity be, float partialTicks, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        for (var entry : be.panels.entrySet()) {
            if (!entry.getValue().isActive()) continue;
            SignalPanelRenderer.renderPartial(be.restocker ? AllPartialModels.FACTORY_PANEL_RESTOCKER_WITH_BULB
                            : AllPartialModels.FACTORY_PANEL_WITH_BULB,
                    be.getBlockState(), entry.getKey(), pose, buffers, light, overlay, RenderType.cutout());
        }
        super.renderSafe(be, partialTicks, pose, buffers, light, overlay);
    }
}
