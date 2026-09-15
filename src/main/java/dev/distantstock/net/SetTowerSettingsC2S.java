package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.routing.TowerDirectory;
import dev.distantstock.routing.TowerSelection;
import dev.distantstock.routing.TowerSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One tower's settings, from the monitor that is showing them.
 *
 * <p>The whole record travels rather than a delta: the screen holds both switches and the radius at
 * once, so "this is what the tower should read as" is one value and one write. Which dial moved is
 * the screen's business; the server only has to decide whether the record it was handed may be
 * stored, and {@link TowerSelection} is the one place that answers that.
 *
 * <p><b>Nothing on this side is trusted.</b> The packet says which tower and what it should read
 * as, and the server then asks the world whether that monitor can see that tower at all, whether
 * the radius fits the tier, and whether the server can still pay for the chunks. A rejected request
 * writes nothing, so there is no state a client can push the file into that a screen could not
 * have asked for.
 *
 * @param monitor where the screen was opened. Its own position, not the tower's: the server uses it
 *                to find the system the operator is looking at, which is what proves the tower is
 *                one of the ones on that screen
 * @param tower   the tower being configured, packed, in the monitor's dimension
 */
public record SetTowerSettingsC2S(BlockPos monitor, long tower, int radius, boolean loading,
                                  boolean carrying) implements CustomPacketPayload {
    public static final Type<SetTowerSettingsC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_tower_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTowerSettingsC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetTowerSettingsC2S::monitor,
                    ByteBufCodecs.VAR_LONG, SetTowerSettingsC2S::tower,
                    ByteBufCodecs.VAR_INT, SetTowerSettingsC2S::radius,
                    ByteBufCodecs.BOOL, SetTowerSettingsC2S::loading,
                    ByteBufCodecs.BOOL, SetTowerSettingsC2S::carrying,
                    SetTowerSettingsC2S::new);

    /**
     * How far the operator may be standing from the monitor and still work it.
     *
     * <p>The screen has no menu, so being able to open one proves nothing about where the player is
     * when the buttons are pressed. The periodic snapshot already stops reaching past thirty-two
     * blocks; this is a little further, so a screen left open while its operator walks off keeps
     * working and a crafted packet for a monitor across the world does not.
     */
    private static final double REACH = 64 * 64;

    @Override
    public Type<SetTowerSettingsC2S> type() {
        return TYPE;
    }

    public static void handle(SetTowerSettingsC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || msg.monitor == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (player.distanceToSqr(msg.monitor.getX() + 0.5, msg.monitor.getY() + 0.5,
                    msg.monitor.getZ() + 0.5) > REACH) {
                return;
            }
            // A negative radius is the file's own "nobody chose", so anything below it is folded
            // into the same answer rather than stored as a second spelling of it.
            int radius = Math.max(-1, msg.radius);
            TowerSystem.TowerId tower = new TowerSystem.TowerId(
                    level.dimension().location().toString(), msg.tower);
            TowerSelection.Result result = TowerSelection.apply(level, msg.monitor, tower,
                    new TowerDirectory.Settings(radius, msg.loading, msg.carrying));
            player.displayClientMessage(Component.translatable(switch (result) {
                case APPLIED -> "gui.distantstock.tower.applied";
                case NO_TOWER -> "gui.distantstock.tower.no_tower";
                case NOT_A_MEMBER -> "gui.distantstock.tower.not_member";
                case TOO_WIDE -> "gui.distantstock.tower.too_wide";
                case NO_BUDGET -> "gui.distantstock.tower.no_budget";
            }), true);
        });
    }
}
