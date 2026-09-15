package dev.distantstock.routing;

import dev.distantstock.block.TowerTier;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The one place a monitor is allowed to change what a tower does.
 *
 * <p>Everything a screen can ask for goes through here, and every check runs before the single
 * write. There is no step after the write that can fail, so a request can never half-apply: either
 * the file holds the whole new setting or it holds exactly what it held before.
 *
 * <p><b>Per tower, not per system.</b> A monitor shows a system and the operator sets each member
 * of it on its own. Two members of one system hold two different radii and two different switches,
 * and changing one must not move the other — a system is a group of towers that happen to reach
 * each other, not one machine with a single set of dials.
 *
 * <p><b>The ceiling is the tower's own tier.</b> A radius is only ever lowered: a tower pays for the
 * square its height buys and the operator decides how much of that to spend. Asking for more is
 * refused rather than clamped, because a silent clamp looks exactly like success and the operator
 * would go on believing the tower covers ground it does not.
 *
 * <p><b>The budget is the server's, not the tower's.</b> Forced chunks cost the server whatever
 * asked for them, so the number that decides a refusal is the total across every tower rather than
 * the one being edited. It is compared against what the file would hold <em>after</em> the write,
 * since the tower's own old entry is being replaced rather than added to.
 */
public final class TowerSelection {
    /** What came of a request. Every value maps to one line of feedback for the operator. */
    public enum Result {
        /** The setting was stored; the loader spends it on its next beat. */
        APPLIED,
        /** No tower carries this monitor, so it has nothing to configure. */
        NO_TOWER,
        /** The tower asked about is not one of the ones this monitor watches. */
        NOT_A_MEMBER,
        /** The radius is above what this tower's tier pays for. */
        TOO_WIDE,
        /** The server's budget cannot cover the whole result of this request. */
        NO_BUDGET
    }

    /** Stores the setting with the budget read from the config. */
    public static Result apply(Level level, BlockPos monitor, TowerSystem.TowerId tower,
                               TowerDirectory.Settings next) {
        return apply(level, monitor, tower, next, StockConfig.towerMaxSelectionChunks());
    }

    /**
     * The whole decision, with the budget handed in.
     *
     * <p>Public because the budget is the one input a test cannot arrange for: filling a real save to
     * its ceiling would take hundreds of towers, and lowering it in the config would lower it for
     * every other case running in the same test server.
     */
    public static Result apply(Level level, BlockPos monitor, TowerSystem.TowerId tower,
                               TowerDirectory.Settings next, int maxChunks) {
        if (level == null || level.isClientSide || monitor == null || tower == null) {
            return Result.NO_TOWER;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return Result.NO_TOWER;
        }
        TowerReadout readout = TowerReadout.survey(level, monitor);
        if (!readout.attached()) {
            // Nothing carries this monitor, so there is no tower whose entry this could be. A
            // request that arrived anyway gets the same answer the greyed-out screen gives.
            return Result.NO_TOWER;
        }
        List<TowerReadout.Member> members = readout.members();
        if (members.stream().noneMatch(member -> member.pos() == tower.packedPos())) {
            // A monitor may only configure the towers it can see. Without this a crafted packet
            // would reach every tower on the server through one screen.
            return Result.NOT_A_MEMBER;
        }

        TowerDirectory directory = TowerDirectory.get(server);
        if (next.radius() >= 0 && next.radius() > TowerDirectory.Settings.ceiling(tierOf(members, tower))) {
            return Result.TOO_WIDE;
        }

        int after = 0;
        for (TowerReadout.Member member : members) {
            TowerSystem.TowerId id = new TowerSystem.TowerId(readout.dimension(), member.pos());
            TowerDirectory.Settings setting = id.equals(tower) ? next : directory.settings(id);
            if (setting.loading()) {
                after += TowerDirectory.Settings.chunks(setting.radiusFor(tierOf(members, id)));
            }
        }
        if (after > maxChunks) {
            return Result.NO_BUDGET;
        }
        directory.setSettings(tower, next);
        return Result.APPLIED;
    }

    /**
     * The tier a member reported, or null for one the world no longer holds.
     *
     * <p>A member with no tier has a ceiling of nothing, so only a radius of zero is accepted for
     * it. That is the right answer for a tower that has been taken apart: it is not going to keep
     * loading anything, and its setting is about to be dropped along with it.
     */
    private static TowerTier tierOf(List<TowerReadout.Member> members, TowerSystem.TowerId tower) {
        for (TowerReadout.Member member : members) {
            if (member.pos() != tower.packedPos()) {
                continue;
            }
            try {
                return member.tier().isBlank() ? null : TowerTier.valueOf(member.tier());
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return null;
    }

    private TowerSelection() {
    }
}
