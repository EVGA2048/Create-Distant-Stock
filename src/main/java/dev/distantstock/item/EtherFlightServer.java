package dev.distantstock.item;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.block.TowerTier;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative sustained thrust and slow tower-network recharge. */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class EtherFlightServer {
    /** Gentle per-tick acceleration: unlike the old 0.4 impulse, holding jump feels like a motor. */
    private static final double THRUST = 0.032;
    private static final double MAX_SPEED = 2.15;
    /** Extra powered-flight air drain: 1 every 2 ticks, plus the chestplate's normal glide drain. */
    private static final int BOOST_AIR_PERIOD = 2;
    /** A running tower is only a trickle charger: 2 air per second, nowhere near powered-flight draw. */
    private static final int TOWER_TRICKLE = 2;
    /** How often the tower tops the suit up. Every second, matching the rate above. */
    private static final int TRICKLE_PERIOD = 20;
    private static final DustParticleOptions CYAN = new DustParticleOptions(
            new Vector3f(110 / 255f, 210 / 255f, 200 / 255f), 0.65f);
    private static final DustParticleOptions WHITE = new DustParticleOptions(
            new Vector3f(245 / 255f, 250 / 255f, 255 / 255f), 0.55f);

    private static final Set<UUID> BOOSTING = new HashSet<>();

    public static void setBoosting(ServerPlayer player, boolean boosting) {
        if (player == null) return;
        if (boosting) BOOSTING.add(player.getUUID());
        else BOOSTING.remove(player.getUUID());
    }

    public static boolean isBoosting(ServerPlayer player) {
        return player != null && BOOSTING.contains(player.getUUID());
    }

    @SubscribeEvent
    public static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(ModItems.ETHER_CASING_CHESTPLATE.get())) {
            if (BOOSTING.contains(player.getUUID())) {
                if (!player.isFallFlying() || !BacktankUtil.hasAirRemaining(chest)) {
                    BOOSTING.remove(player.getUUID());
                } else {
                    applyThrust(player, chest);
                }
            }
        } else {
            BOOSTING.remove(player.getUUID());
        }

        if (player.tickCount % TRICKLE_PERIOD == 0) {
            trickleFromTower(player);
        }
    }

    /**
     * Slow passive refill while standing anywhere inside a *running* tower's physical radius.
     *
     * <p>All four pieces, not just the chestplate. The charged tier needs working medium in every
     * slot, and the tower is meant to be the supply the suit is maintained from -- a player who runs
     * on towers rather than canisters would otherwise watch the helmet, leggings and boots run dry
     * once and never come back, and the set would drop out of its charged state for good with
     * nothing on screen to explain why.
     *
     * <p>Deliberately independent of device carrying budgets: a player is not a distant device.
     * This is the "wireless trickle" half of the equipment-dock design.
     */
    private static void trickleFromTower(ServerPlayer player) {
        TowerCoreBlockEntity tower = runningTowerOver(player);
        if (tower == null) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!slot.isArmor()) continue;
            ItemStack piece = player.getItemBySlot(slot);
            if (!(piece.getItem() instanceof EtherCasingArmorItem)) continue;
            int missing = BacktankUtil.maxAir(piece) - BacktankUtil.getAir(piece);
            if (missing <= 0) continue;
            // Molten amethyst is the other medium and a tower has no opinion about it; leave a piece
            // running on it alone rather than quietly topping it up with ether.
            String installed = EtherCasingArmorItem.medium(piece);
            if (!installed.isBlank() && !installed.equals(EtherCasingArmorItem.ETHER_MEDIUM)) continue;
            int supplied = tower.drawEther(Math.min(TOWER_TRICKLE, missing));
            if (supplied > 0) {
                EtherCasingArmorItem.addReserve(piece, supplied, EtherCasingArmorItem.ETHER_MEDIUM);
            }
        }
    }

    private static void applyThrust(ServerPlayer player, ItemStack chest) {
        Vec3 look = player.getLookAngle();
        Vec3 next = player.getDeltaMovement().add(look.scale(THRUST));
        double speed = next.length();
        if (speed > MAX_SPEED) next = next.scale(MAX_SPEED / speed);
        player.setDeltaMovement(next);
        player.hurtMarked = true;

        if ((player.tickCount & 1) == 0 && !EtherCasingStealthServer.isCloaked(player)
                && player.level() instanceof ServerLevel level) {
            Vec3 wake = player.position().add(0, 0.9, 0).subtract(look.scale(0.55));
            level.sendParticles(CYAN, wake.x, wake.y, wake.z, 1,
                    0.12, 0.10, 0.12, 0.008);
            if (player.tickCount % 6 == 0) {
                level.sendParticles(WHITE, wake.x, wake.y, wake.z, 1,
                        0.08, 0.08, 0.08, 0.004);
            }
        }

        if (player.tickCount % BOOST_AIR_PERIOD == 0) {
            BacktankUtil.consumeAir(player, chest, 1);
        }
    }

    private static TowerCoreBlockEntity runningTowerOver(ServerPlayer player) {
        TowerCoreBlockEntity nearest = null;
        long best = Long.MAX_VALUE;
        for (TowerCoreBlockEntity tower : LoadedTowers.all()) {
            TowerTier tier = tower.tier();
            if (tier == null || !tower.isRunning() || tower.getLevel() == null
                    || tower.getLevel() != player.level()) continue;
            long dx = (long) tower.getBlockPos().getX() - player.blockPosition().getX();
            long dy = (long) tower.getBlockPos().getY() - player.blockPosition().getY();
            long dz = (long) tower.getBlockPos().getZ() - player.blockPosition().getZ();
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance <= (long) tier.radius() * tier.radius() && distance < best) {
                nearest = tower;
                best = distance;
            }
        }
        return nearest;
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BOOSTING.remove(event.getEntity().getUUID());
    }

    private EtherFlightServer() {
    }
}

