package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.distantstock.DistantStock;
import dev.distantstock.block.LampReadings;
import dev.distantstock.block.LampState;
import dev.distantstock.block.SignalLampModels;
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
    public SignalPanelRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** Force partial registration before the first model bake, not on renderer construction. */
    public static void registerModels() {
        SignalLampModels.init();
    }

    /** The lamp art, shared with the panel type that draws a lamp on somebody else's board. */
    private static Map<String, PartialModel> lamps() {
        return SignalLampModels.all();
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
                renderLamp(be, entry.getKey(), lamp, partialTicks, ms, buffer, light, overlay);
                for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                    FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
                }
                continue;
            }
            // Every panel keeps its own art even when it shares a board: a remote gauge stays blue,
            // a gauge from another mod stays that mod's. housingFor answers for all of them.
            boolean remote = be.isRemoteGauge(entry.getKey());
            renderPartial(RemoteGaugeRenderer.housingFor(be, behaviour),
                    state, entry.getKey(), ms, buffer, light, overlay, RenderType.cutout());
            if (behaviour.getAmount() > 0) {
                if (remote) {
                    RemoteGaugeRenderer.renderRemoteBulb(behaviour, partialTicks, ms, buffer, light, overlay);
                } else {
                    FactoryPanelRenderer.renderBulb(behaviour, partialTicks, ms, buffer, light, overlay);
                }
            }
            for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
            }
            for (FactoryPanelConnection connection : behaviour.targetedByLinks.values()) {
                FactoryPanelRenderer.renderPath(behaviour, connection, partialTicks, ms, buffer, light, overlay);
            }
        }
    }

    /** Blink phases, roughly a stack light's slow "standby" and fast "emergency" rates. */
    private static final int SLOW_BLINK_TICKS = 20;
    private static final int FAST_BLINK_TICKS = 8;

    private static void renderLamp(SignalPanelBlockEntity be, FactoryPanelBlock.PanelSlot slot,
                                   SignalLampPanelItem lamp, float partialTicks, PoseStack ms,
                                   MultiBufferSource buffer, int light, int overlay) {
        boolean lit = be.lampSignal(slot) > 0;
        SignalLampPanelItem.Color color = lamp.color();
        if (lamp.material() == SignalLampPanelItem.Material.BRASS) {
            // The brass lamp is an andon light: its colour reports the worst connected gauge.
            LampState level = be.lampState(slot);
            if (level == null) {
                lit = false;
            } else {
                color = LampReadings.colorFor(level);
                lit = blinkOn(be, partialTicks, level.blink());
            }
        }
        PartialModel model = SignalLampModels.lamp(lamp.material(), color, lit);
        renderPartial(model, be.getBlockState(), slot, ms, buffer, lit ? 0xF000F0 : light, overlay,
                lit ? RenderType.cutout() : RenderType.translucent());
    }

    private static boolean blinkOn(SignalPanelBlockEntity be, float partialTicks, LampState.Blink blink) {
        if (blink == LampState.Blink.NONE || be.getLevel() == null) {
            return true;
        }
        long period = blink == LampState.Blink.FAST ? FAST_BLINK_TICKS : SLOW_BLINK_TICKS;
        return ((be.getLevel().getGameTime() + (long) partialTicks) / period) % 2 == 0;
    }

    static void renderPartial(PartialModel model, BlockState state,
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
