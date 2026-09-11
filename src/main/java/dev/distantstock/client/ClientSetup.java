package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlockEntities;
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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ClientSetup {
    @EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Screens {
        @SubscribeEvent
        public static void screens(RegisterMenuScreensEvent e) {
            e.register(ModMenus.REQUESTER.get(), RequesterScreen::new);
        }

        /**
         * Create draws the packager hatch and tray from a Flywheel visual, and its renderer bails out
         * whenever Flywheel visualization is available. Our block entity type has no visual by default, so
         * the hatch never appeared; register Create's own visual for our type to get both back.
         */
        @SubscribeEvent
        public static void visualizers(FMLClientSetupEvent e) {
            SimpleBlockEntityVisualizer.builder(ModBlockEntities.REMOTE_PACKAGER.get())
                    .factory((context, be, partialTick) -> new PackagerVisual<>(context, be, partialTick))
                    // The renderer still draws the packaged box outside the Flywheel check.
                    .neverSkipVanillaRender()
                    .apply();
        }

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerBlockEntityRenderer(ModBlockEntities.REMOTE_PACKAGER.get(), PackagerRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.DOCK.get(), DockRenderer::new);
            e.registerBlockEntityRenderer(ModBlockEntities.SIGNAL_PANEL.get(), SignalPanelRenderer::new);
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
