package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlockEntities;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.fluid.ModFluids;
import dev.distantstock.client.ponder.DistantStockPonderPlugin;
import dev.distantstock.item.ManualItem;
import dev.distantstock.item.ModItems;
import dev.distantstock.menu.ModMenus;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.logistics.packager.PackagerRenderer;
import com.simibubi.create.content.logistics.packager.PackagerVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ClientSetup {
    @EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Screens {
        @SubscribeEvent
        public static void screens(RegisterMenuScreensEvent e) {
            e.register(ModMenus.REQUESTER.get(), RequesterScreen::new);
            e.register(ModMenus.LAMP_MONITOR.get(), LampMonitorScreen::new);
            e.register(ModMenus.REMOTE_REQUESTER.get(), RemoteRedstoneRequesterScreen::new);
        }

        /**
         * Create draws the packager hatch and tray from a Flywheel visual, and its renderer bails out
         * whenever Flywheel visualization is available. Our block entity type has no visual by default, so
         * the hatch never appeared; register Create's own visual for our type to get both back.
         */
        /**
         * Both fluids keep the palette they were authored with. The default water tint would
         * multiply the still texture by the biome colour and darken it.
         */
        @SubscribeEvent
        public static void fluidTextures(RegisterClientExtensionsEvent e) {
            e.registerFluidType(fluid("ether"), ModFluids.ETHER_TYPE.get());
            e.registerFluidType(fluid("molten_amethyst"), ModFluids.MOLTEN_AMETHYST_TYPE.get());
        }

        private static IClientFluidTypeExtensions fluid(String name) {
            return new IClientFluidTypeExtensions() {
                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "fluid/" + name + "_still");
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "fluid/" + name + "_flow");
                }

                @Override
                public int getTintColor() {
                    return 0xFFFFFFFF;
                }
            };
        }

        /**
         * Ether renders translucent; the melt stays solid.
         *
         * Vanilla decides a fluid's render layer from a table that only lists water, and everything
         * else falls through to {@code RenderType.solid()}, which ignores the texture's alpha. That
         * is why ether came out opaque on the ground no matter what the texture said. NeoForge adds
         * a setter for it. Lava is not in that table either, and solid is exactly what a melt wants,
         * so molten amethyst is deliberately left alone.
         */
        @SubscribeEvent
        public static void fluidRenderLayers(FMLClientSetupEvent e) {
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    ModFluids.ETHER.get(), net.minecraft.client.renderer.RenderType.translucent());
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                    ModFluids.ETHER_FLOW.get(), net.minecraft.client.renderer.RenderType.translucent());
        }

        @SubscribeEvent
        public static void visualizers(FMLClientSetupEvent e) {
            SignalPanelRenderer.registerModels();
            RemoteGaugeRenderer.registerModels();
            ResonatorRenderer.registerModels();
            SimpleBlockEntityVisualizer.builder(ModBlockEntities.REMOTE_PACKAGER.get())
                    .factory((context, be, partialTick) -> new PackagerVisual<>(context, be, partialTick))
                    // The renderer still draws the packaged box outside the Flywheel check.
                    .neverSkipVanillaRender()
                    .apply();
        }


        /**
         * Hand the casing's model to Create's connected-texture wrapper.
         *
         * Create normally reaches this through its own registrate builder, and this mod does not use
         * registrate, so the same call is made by hand: a baking-result listener that swaps the model
         * behind every block state of the casing. The sheet size lives in the CT type and the layout
         * is Create's own 16x16 grid, so there is nothing to override on the shift itself.
         */
        @SubscribeEvent
        public static void connectedTextures(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult e) {
            com.simibubi.create.foundation.block.connected.CTTypeRegistry.register(TowerCasingCTType.INSTANCE);
            com.simibubi.create.foundation.model.ModelSwapper.swapModels(e.getModels(),
                    com.simibubi.create.foundation.model.ModelSwapper.getAllBlockStateModelLocations(
                            ModBlocks.TOWER_CASING.get()),
                    model -> new com.simibubi.create.foundation.block.connected.CTModel(
                            model, new TowerCasingCTBehaviour()));
        }

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerBlockEntityRenderer(ModBlockEntities.REMOTE_GAUGE.get(),
                    RemoteGaugeRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.REMOTE_PACKAGER.get(), PackagerRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.DOCK.get(), DockParcelRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.SIGNAL_PANEL.get(), SignalPanelRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.ETHER_RESONATOR.get(), ResonatorRenderer::new);
            // The monitor's face is a flap display, and this is Create's renderer for one: the
            // glyphs, the flip animation and the light they are drawn in all come from it. Nothing
            // of ours is involved, which is the point — a board that looks like a display board
            // because it is one.
            e.registerBlockEntityRenderer(ModBlockEntities.MONITOR.get(),
                    MonitorFlapRenderer::new);
            // 底座里的以太。四个观察窗是模型的一部分，液面是这里画的 —— 机壳通上红石变成窗户
            // 之后才看得见，所以它平时不占任何画面。
            e.registerBlockEntityRenderer(ModBlockEntities.TOWER_CORE.get(), TowerCoreRenderer::new);
        }

        @SubscribeEvent
        public static void ponder(FMLClientSetupEvent e) {
            PonderIndex.addPlugin(new DistantStockPonderPlugin());
            // Create's package entity renders from this item -> partial-model
            // map when Flywheel visualization is active.
            ResourceLocation remoteId = BuiltInRegistries.ITEM.getKey(ModItems.REMOTE_PACKAGE.get());
            AllPartialModels.PACKAGES.put(remoteId,
                    PartialModel.of(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID,
                            "item/remote_package_12x12")));
            AllPartialModels.PACKAGE_RIGGING.put(remoteId,
                    PartialModel.of(ResourceLocation.fromNamespaceAndPath(DistantStock.MODID,
                            "item/remote_package_rigging_12x12")));
        }
    }

    @EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
    public static final class Manual {
        @SubscribeEvent
        public static void lampConnection(PlayerInteractEvent.RightClickBlock e) {
            if (!e.getLevel().isClientSide) return;
            if (!(e.getLevel().getBlockEntity(e.getPos()) instanceof dev.distantstock.block.SignalPanelBlockEntity be)) return;
            var slot = com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.getTargetedSlot(
                    e.getPos(), be.getBlockState(), e.getHitVec().getLocation());
            if (!be.isLamp(slot) || !e.getItemStack().isEmpty()) return;
            // Lamps intentionally bypass value settings, so route connection clicks explicitly.
            if (com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnectionHandler.panelClicked(
                    e.getLevel(), e.getEntity(), be.panels.get(slot))) {
                e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                e.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void use(PlayerInteractEvent.RightClickItem e) {
            if (!e.getLevel().isClientSide) {
                return;
            }
            if (!(e.getItemStack().getItem() instanceof ManualItem)) {
                return;
            }
            Minecraft.getInstance().setScreen(new ManualScreen());
        }
    }

    private ClientSetup() {
    }
}
