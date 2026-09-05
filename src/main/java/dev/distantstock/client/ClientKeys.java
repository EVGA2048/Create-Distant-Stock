package dev.distantstock.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.distantstock.DistantStock;
import dev.distantstock.net.OpenRequesterC2S;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class ClientKeys {
    public static KeyMapping OPEN;

    @EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Register {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            OPEN = new KeyMapping(
                    "key.distantstock.open",
                    KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_H,
                    "key.categories.distantstock");
            e.register(OPEN);
        }
    }

    @EventBusSubscriber(modid = DistantStock.MODID, value = Dist.CLIENT)
    public static final class Input {
        @SubscribeEvent
        public static void key(InputEvent.Key e) {
            if (Minecraft.getInstance().screen != null || OPEN == null) {
                return;
            }
            if (OPEN.consumeClick()) {
                PacketDistributor.sendToServer(new OpenRequesterC2S());
            }
        }
    }

    private ClientKeys() {
    }
}
