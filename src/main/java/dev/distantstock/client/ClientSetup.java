package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.client.ponder.DistantStockPonderPlugin;
import dev.distantstock.item.ManualItem;
import dev.distantstock.menu.ModMenus;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ClientSetup {
    @EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Screens {
        @SubscribeEvent
        public static void screens(RegisterMenuScreensEvent e) {
            e.register(ModMenus.REQUESTER.get(), RequesterScreen::new);
        }

        @SubscribeEvent
        public static void ponder(FMLClientSetupEvent e) {
            PonderIndex.addPlugin(new DistantStockPonderPlugin());
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
