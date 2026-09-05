package dev.distantstock.server;

import com.mojang.brigadier.Command;
import dev.distantstock.DistantStock;
import dev.distantstock.net.AdminConfigS2C;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class AdminCommand {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("distantstock")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    PacketDistributor.sendToPlayer(player, AdminConfigS2C.fromConfig());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private AdminCommand() {
    }
}
