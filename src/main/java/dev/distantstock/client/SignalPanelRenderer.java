package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.distantstock.DistantStock;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.item.SignalLampPanelItem;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public final class SignalPanelRenderer extends SmartBlockEntityRenderer<SignalPanelBlockEntity> {
    private static final Map<String, PartialModel> LAMPS = new HashMap<>();

    static {
        for (SignalLampPanelItem.Material material : SignalLampPanelItem.Material.values()) {
            for (SignalLampPanelItem.Color color : SignalLampPanelItem.Color.values()) {
                for (String state : new String[]{"off", "on"}) {
                    String key = material.name().toLowerCase() + "_" + color.name().toLowerCase()
                            + "_" + state;
                    LAMPS.put(key, PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                            DistantStock.MODID, "block/signal_panel/" + key)));
                }
            }
        }
    }

    public SignalPanelRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(SignalPanelBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        if (!be.panelDataReady()) {
            return;
        }
        BlockState state = be.getBlockState();
        for (var entry : be.panels.entrySet()) {
            FactoryPanelBehaviour behaviour = entry.getValue();
            if (!behaviour.isActive()) {
                continue;
            }
            ItemStack lampStack = be.lampStack(entry.getKey());
            SignalLampPanelItem lamp = SignalLampPanelItem.from(lampStack);
            if (lamp != null) {
                renderLamp(state, entry.getKey(), lamp, be.lampSignal(entry.getKey()), ms, buffer, light, overlay);
                for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                    FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
                }
                continue;
            }
            renderPartial(AllPartialModels.FACTORY_PANEL_WITH_BULB, state, entry.getKey(), ms,
                    buffer, light, overlay, RenderType.cutout());
            if (behaviour.getAmount() > 0) {
                FactoryPanelRenderer.renderBulb(behaviour, partialTicks, ms, buffer, light, overlay);
            }
            for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
            }
            for (FactoryPanelConnection connection : behaviour.targetedByLinks.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
            }
        }
    }

    private static void renderLamp(BlockState state, FactoryPanelBlock.PanelSlot slot,
                                   SignalLampPanelItem lamp, int strength, PoseStack ms,
                                   MultiBufferSource buffer, int light, int overlay) {
        boolean lit = strength > 0;
        SignalLampPanelItem.Color color = lamp.color();
        if (lamp.material() == SignalLampPanelItem.Material.BRASS && lit) {
            color = strength <= 5 ? SignalLampPanelItem.Color.RED
                    : strength <= 10 ? SignalLampPanelItem.Color.ORANGE
                    : strength < 15 ? SignalLampPanelItem.Color.GREEN
                    : SignalLampPanelItem.Color.CYAN;
        }
        String key = lamp.material().name().toLowerCase() + "_" + color.name().toLowerCase()
                + "_" + (lit ? "on" : "off");
        PartialModel model = LAMPS.get(key);
        renderPartial(model, state, slot, ms, buffer, lit ? 0xF000F0 : light, overlay,
                lit ? RenderType.cutout() : RenderType.translucent());
    }

    private static void renderPartial(PartialModel model, BlockState state,
                                      FactoryPanelBlock.PanelSlot slot, PoseStack ms,
                                      MultiBufferSource buffer, int light, int overlay,
                                      RenderType renderType) {
        float xRot = FactoryPanelBlock.getXRot(state) + (float) (Math.PI / 2);
        float yRot = FactoryPanelBlock.getYRot(state);
        SuperByteBuffer rendered = CachedBuffers.partial(model, state);
        rendered.rotateCentered(yRot, Direction.UP);
        rendered.rotateCentered(xRot, Direction.EAST);
        rendered.rotateCentered((float) Math.PI, Direction.UP);
        rendered.translate(slot.xOffset * .5, 0, slot.yOffset * .5);
        rendered.light(light);
        rendered.overlay(overlay);
        rendered.renderInto(ms, buffer.getBuffer(renderType));
    }
}
