package dev.distantstock;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.client.RemoteGaugeRenderer;
import dev.distantstock.client.SignalPanelRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
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
            // The casing's connected texture attaches by swapping its baked model, and a swap that
            // silently did not happen leaves a perfectly ordinary-looking block with no connection
            // logic at all. Nothing else in the game reports that, so it is asserted here.
            for (var state : ModBlocks.TOWER_CASING.get().getStateDefinition().getPossibleStates()) {
                if (!(mc.getBlockRenderer().getBlockModel(state)
                        instanceof com.simibubi.create.foundation.block.connected.CTModel)) {
                    throw new AssertionError("Distant casing is not using a connected-texture model: " + state);
                }
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
            var gaugeField = RemoteGaugeRenderer.class.getDeclaredField("MODELS");
            gaugeField.setAccessible(true);
            var gaugePartials = (Map<?, ?>) gaugeField.get(null);
            for (var entry : gaugePartials.entrySet()) {
                try { verify(((PartialModel) entry.getValue()).get(), mc); }
                catch (AssertionError failure) { throw new AssertionError("Remote gauge model: " + entry.getKey(), failure); }
            }
            verify(com.simibubi.create.AllPartialModels.FACTORY_PANEL_WITH_BULB.get(), mc);
            // A sprite reaches the block atlas one of two ways: a baked model names it, or an atlas
            // definition lists it. The fluids ship as texture-only models and the dock's lift is
            // drawn straight from its renderer with no model at all, so both depend on
            // assets/minecraft/atlases/blocks.json. An unstitched sprite is not an error anywhere
            // else in the game; it simply draws as the purple-black checkerboard, which is why this
            // is the only place the mistake shows up as a failure instead of a screenshot.
            var atlas = mc.getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            var missing = atlas.getSprite(
                    net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()).contents().name();
            var expected = new java.util.ArrayList<ResourceLocation>();
            for (String fluid : new String[]{"ether", "molten_amethyst"}) {
                for (String kind : new String[]{"still", "flow"}) {
                    expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "fluid/" + fluid + "_" + kind));
                }
            }
            expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/dock_cap"));
            // The tower's models are generated from the art handoff, so a texture renamed or dropped
            // there reaches the game as a page of checkerboard with nothing upstream to catch it.
            for (String tower : new String[]{"andesite", "axis", "axis_top", "bearing", "bearing_top",
                    "brass", "cap", "casing", "casing_active", "casing_inactive", "core", "crystal",
                    "ct_active", "ct_inactive", "fluid", "fluid_port_a", "frame", "gearbox", "iron",
                    "polished", "shell"}) {
                expected.add(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/tower/" + tower));
            }
            int sprites = 0;
            for (var name : expected) {
                if (atlas.getSprite(name).contents().name().equals(missing)) {
                    throw new AssertionError("Sprite was not stitched into the block atlas: " + name);
                }
                sprites++;
            }
            LogUtils.getLogger().info("DISTANTSTOCK_ATLAS_SPRITES_OK: {} sprites stitched", sprites);
            LogUtils.getLogger().info("DISTANTSTOCK_CLIENT_SMOKE_PASSED: {} block states, {} quarter-lamp models, {} remote gauge models, factory panel, client mixin",
                    states, partials.size(), gaugePartials.size());
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
