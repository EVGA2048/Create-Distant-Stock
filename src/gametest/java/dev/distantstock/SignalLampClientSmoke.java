package dev.distantstock;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.client.SignalPanelRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.Arrays;
import java.util.Map;

/** Opt-in client smoke test: bake real assets and load client mixins, then close without opening a save. */
@EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
public final class SignalLampClientSmoke {
    private static boolean done;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("distantstock.clientSmoke") || done) return;
        var mc = Minecraft.getInstance();
        if (mc.getOverlay() != null || mc.screen == null || mc.getModelManager().getMissingModel() == null) return;
        done = true;
        try {
            if (Arrays.stream(FactoryPanelConnectionHandler.class.getDeclaredMethods())
                    .noneMatch(m -> m.getName().contains("distantstock$lampOutput"))) {
                throw new AssertionError("Client lamp connection mixin was not applied");
            }
            int states = 0;
            for (var block : java.util.List.of(ModBlocks.CYAN_INDICATOR_LAMP.get(), ModBlocks.ORANGE_INDICATOR_LAMP.get(),
                    ModBlocks.RED_INDICATOR_LAMP.get(), ModBlocks.GREEN_INDICATOR_LAMP.get(),
                    ModBlocks.WHITE_INDICATOR_LAMP.get(), ModBlocks.BRASS_INDICATOR_LAMP.get())) {
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    verify(mc.getBlockRenderer().getBlockModel(state), mc);
                    states++;
                }
            }
            var field = SignalPanelRenderer.class.getDeclaredField("LAMPS");
            field.setAccessible(true);
            var partials = (Map<?, ?>) field.get(null);
            for (var entry : partials.entrySet()) {
                try { verify(((PartialModel) entry.getValue()).get(), mc); }
                catch (AssertionError failure) { throw new AssertionError("Quarter model: " + entry.getKey(), failure); }
            }
            verify(com.simibubi.create.AllPartialModels.FACTORY_PANEL_WITH_BULB.get(), mc);
            LogUtils.getLogger().info("DISTANTSTOCK_CLIENT_SMOKE_PASSED: {} block states, {} quarter-lamp models, factory panel, client mixin",
                    states, partials.size());
        } catch (Throwable failure) {
            LogUtils.getLogger().error("DISTANTSTOCK_CLIENT_SMOKE_FAILED", failure);
        } finally {
            mc.stop();
        }
    }

    private static void verify(BakedModel model, Minecraft mc) {
        if (model == null || model == mc.getModelManager().getMissingModel()) throw new AssertionError("Missing baked model");
        int quads = 0;
        for (int i = 0; i <= 6; i++) {
            for (var quad : model.getQuads(null, i == 6 ? null : Direction.values()[i], RandomSource.create(42))) {
                if (quad.getSprite().contents().name().getPath().contains("missingno")) throw new AssertionError("Missing texture");
                quads++;
            }
        }
        if (quads == 0) throw new AssertionError("Unexpected empty model");
    }
}
