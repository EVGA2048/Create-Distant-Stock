package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer;
import com.simibubi.create.content.redstone.link.LinkRenderer;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringRenderer;
import com.simibubi.create.foundation.render.RenderTypes;
import dev.distantstock.block.RemoteGaugeModels;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the remote gauge with its own panel art.
 *
 * Create registers its panel mesh only for its own block, and FactoryPanelRenderer hard-codes
 * Create's textures, so the remote gauge has to draw the housing, the bulb and the connection
 * paths itself. The models under {@code distantstock:block/remote_gauge} inherit Create's geometry
 * and only swap the texture, so the shapes stay identical.
 */
public final class RemoteGaugeRenderer extends FactoryPanelRenderer {
    public RemoteGaugeRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** Force partial registration before the first model bake, not on renderer construction. */
    public static void registerModels() {
        RemoteGaugeModels.init();
    }

    /** The housing for one slot. Create picks the passive/active variant from the configured amount. */
    static PartialModel panelModel(boolean restocker, boolean active) {
        return RemoteGaugeModels.panel(restocker, active);
    }

    /**
     * The housing one slot should be drawn with, whoever's panel it is.
     *
     * <p>Our board can hold other people's panels — Deployer puts them there and the placement
     * gesture hands them over — and drawing one of ours around somebody else's machine was the
     * bug: a player put an Extra Gauges gauge in the second slot and watched both slots turn
     * remote-gauge blue while only one of them was ours. A panel is a picture of the machine
     * behind it, so the slot has to ask who is in it.
     *
     * <p>Three answers, in order: ours, theirs, or Create's plain copper. "Theirs" goes through
     * {@link dev.distantstock.panel.DeployerPanels}, which is the only place allowed to name
     * Deployer's types; without Deployer a foreign panel is a plain Create one and gets plain
     * Create art.
     */
    public static PartialModel housingFor(FactoryPanelBlockEntity be, FactoryPanelBehaviour behaviour) {
        if (dev.distantstock.block.RemoteGaugeBlockEntity.isOurPanel(behaviour)) {
            return panelModel(be.restocker, behaviour.count != 0);
        }
        FactoryPanelBlock.PanelState panelState = behaviour.count == 0
                ? FactoryPanelBlock.PanelState.PASSIVE : FactoryPanelBlock.PanelState.ACTIVE;
        FactoryPanelBlock.PanelType panelType = be.restocker
                ? FactoryPanelBlock.PanelType.PACKAGER : FactoryPanelBlock.PanelType.NETWORK;
        PartialModel theirs = net.neoforged.fml.ModList.get().isLoaded("deployer")
                ? dev.distantstock.panel.DeployerPanels.modelOf(behaviour, panelState, panelType)
                : null;
        if (theirs != null) {
            return theirs;
        }
        if (be.restocker) {
            return behaviour.count == 0 ? AllPartialModels.FACTORY_PANEL_RESTOCKER
                    : AllPartialModels.FACTORY_PANEL_RESTOCKER_WITH_BULB;
        }
        return behaviour.count == 0 ? AllPartialModels.FACTORY_PANEL
                : AllPartialModels.FACTORY_PANEL_WITH_BULB;
    }

    @Override
    protected void renderSafe(FactoryPanelBlockEntity be, float partialTicks, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        for (var entry : be.panels.entrySet()) {
            FactoryPanelBehaviour behaviour = entry.getValue();
            if (!behaviour.isActive()) {
                continue;
            }
            SignalPanelRenderer.renderPartial(housingFor(be, behaviour), state,
                    entry.getKey(), pose, buffers, light, overlay, RenderType.cutout());
        }
        // FactoryPanelRenderer.renderSafe would draw these two for us, but it also draws Create's own
        // bulb texture, so they are called directly. Order matches Create's: everything the panels
        // sit behind, then the bulbs, then the connection paths.
        FilteringRenderer.renderOnBlockEntity(be, partialTicks, pose, buffers, light, overlay);
        LinkRenderer.renderOnBlockEntity(be, partialTicks, pose, buffers, light, overlay);
        for (var entry : be.panels.entrySet()) {
            FactoryPanelBehaviour behaviour = entry.getValue();
            if (!behaviour.isActive()) {
                continue;
            }
            if (behaviour.getAmount() > 0) {
                // The bulb is part of the housing's art, so it follows the same question: a foreign
                // panel's bulb is Create's texture, not ours.
                if (dev.distantstock.block.RemoteGaugeBlockEntity.isOurPanel(behaviour)) {
                    renderRemoteBulb(behaviour, partialTicks, pose, buffers, light, overlay);
                } else {
                    FactoryPanelRenderer.renderBulb(behaviour, partialTicks, pose, buffers, light, overlay);
                }
            }
            for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, pose, buffers, light, overlay);
            }
            for (FactoryPanelConnection connection : behaviour.targetedByLinks.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, pose, buffers, light, overlay);
            }
        }
    }

    /**
     * Create's FactoryPanelRenderer.renderBulb with the remote gauge's own bulb texture. Only the
     * partial changes, so the transform and the additive glow pass are kept identical.
     */
    static void renderRemoteBulb(FactoryPanelBehaviour behaviour, float partialTicks, PoseStack ms,
                                 MultiBufferSource buffer, int light, int overlay) {
        BlockState state = behaviour.blockEntity.getBlockState();
        float xRot = FactoryPanelBlock.getXRot(state) + (float) (Math.PI / 2);
        float yRot = FactoryPanelBlock.getYRot(state);
        float glow = behaviour.bulb.getValue(partialTicks);
        PartialModel partial = RemoteGaugeModels.bulb(
                !behaviour.redstonePowered && !behaviour.isMissingAddress());
        CachedBuffers.partial(partial, state)
                .rotateCentered(yRot, Direction.UP)
                .rotateCentered(xRot, Direction.EAST)
                .rotateCentered((float) Math.PI, Direction.UP)
                .translate(behaviour.slot.xOffset * 0.5, 0.0, behaviour.slot.yOffset * 0.5)
                .light(glow > 0.125F ? 0xF000F0 : light)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderType.translucent()));
        if (glow < 0.125F) {
            return;
        }
        float pulse = Mth.clamp((float) (1.0 - 2.0 * Math.pow(glow - 0.75F, 2.0)), -1.0F, 1.0F);
        int color = (int) (200.0F * pulse);
        CachedBuffers.partial(partial, state)
                .rotateCentered(yRot, Direction.UP)
                .rotateCentered(xRot, Direction.EAST)
                .rotateCentered((float) Math.PI, Direction.UP)
                .translate(behaviour.slot.xOffset * 0.5, 0.0, behaviour.slot.yOffset * 0.5)
                .light(0xF000F0)
                .color(color, color, color, 255)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderTypes.additive()));
    }
}
