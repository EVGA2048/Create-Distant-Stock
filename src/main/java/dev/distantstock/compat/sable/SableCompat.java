package dev.distantstock.compat.sable;

import dev.distantstock.DistantStock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sable implementation of {@link SableBridge}, loaded only when Sable is installed.
 *
 * <p>Coverage is computed in global space, inside a space shared by the tower and its devices.
 * Sable keeps a sub-level's blocks at plot coordinates that are nowhere near where the ship is
 * drawn; comparing those coordinates directly would say a tower and a dock on the same ship are
 * thousands of blocks apart. {@code distanceSquaredWithSubLevels} projects each endpoint through
 * its sub-level's pose first, and {@link #init()}'s space key keeps a ship's coverage separate
 * from the overworld's so a moving tower cannot gate a ground factory.
 *
 * <p>Loading is a different problem. NeoForge's {@code TicketController} keys tickets by chunk
 * position; pointing it at plot coordinates would fight Sable's own plot chunk holders. A tower on
 * a sub-level therefore asks Sable for a {@link SubLevelLoadingTicketType} on the sub-level itself.
 * The ticket is stored by sub-level UUID in Sable's saved data, so it survives a restart and can
 * bring the ship back without a player nearby.
 *
 * <p>The ticket type is a static field on purpose: Sable resolves saved ticket names through a
 * registry populated by {@code SubLevelLoadingTicketType.create}, and the class must have been
 * initialised before Sable reads that save. {@link #init()} is called from the mod constructor.
 */
public final class SableCompat {
    private static final Logger LOG = LogManager.getLogger();

    private static final SubLevelLoadingTicketType<BlockPos> TOWER_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "tower"),
                    BlockPos.CODEC);

    public static void init() {
        SableBridge.install(new SableBridge.Impl() {
            @Override
            public Vec3 toGlobal(Level level, Vec3 pos) {
                return Sable.HELPER.projectOutOfSubLevel(level, pos);
            }

            @Override
            public double distanceSq(Level level, Vec3 first, Vec3 second) {
                return Sable.HELPER.distanceSquaredWithSubLevels(level, first, second);
            }

            @Override
            public String spaceKey(Level level, BlockPos pos) {
                String dimension = level.dimension().location().toString();
                SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
                return subLevel == null ? dimension : dimension + "#" + subLevel.getUniqueId();
            }

            @Override
            public boolean keepLoaded(Level level, BlockPos tower) {
                return ticket(level, tower, true);
            }

            @Override
            public void release(Level level, BlockPos tower) {
                ticket(level, tower, false);
            }
        });
        LOG.info("[DistantStock/Sable] sub-level projection and tower loading enabled");
    }

    /**
     * Adds or removes the tower's ticket; answers whether this tower is in a sub-level at all.
     *
     * <p>A true answer with no container is deliberate: the caller must not fall back to NeoForge
     * chunk forcing just because Sable could not hand us its container this tick. The ship simply
     * stays under Sable's own loading rules until the container is available.
     */
    private static boolean ticket(Level level, BlockPos tower, boolean add) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(level, tower);
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return false;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null) {
            return true;
        }
        try {
            if (add) {
                container.addForceLoadTicket(serverSubLevel, TOWER_TICKET, tower.immutable());
            } else {
                container.removeForceLoadTicket(serverSubLevel, TOWER_TICKET, tower.immutable());
            }
        } catch (RuntimeException exception) {
            LOG.warn("[DistantStock/Sable] could not {} sub-level ticket for tower at {}",
                    add ? "add" : "remove", tower, exception);
        }
        return true;
    }

    private SableCompat() {
    }
}
