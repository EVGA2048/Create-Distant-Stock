package dev.distantstock.block;

import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.net.OpenMonitorS2C;
import dev.distantstock.routing.TowerReadout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shared right-click entry point for every authored block in one Distant Tower. */
public final class TowerControl {
    public static InteractionResult open(Level level, BlockPos clicked, Player player) {
        BlockPos core = TowerStructure.coreForPart(level, clicked).orElse(null);
        if (core == null) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new OpenMonitorS2C(core,
                    LinkSnapshot.view(TowerReadout.survey(level, core)), true));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private TowerControl() {
    }
}
