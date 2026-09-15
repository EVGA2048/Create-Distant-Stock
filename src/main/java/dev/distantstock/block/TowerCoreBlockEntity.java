package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The tower's brain: how tall the mast is, what that buys, and what it costs to turn.
 *
 * <p>The tier is not stored, it is read. Every twenty ticks this walks the mast above it and asks
 * {@link TowerTier} what that height is worth. Storing it instead would mean a hundred ways for the
 * number to be wrong — a coupler broken while the block entity is unloaded, a world edited by hand,
 * a crash between the mast changing and the write — and the walk is at most eighteen block lookups
 * four times a second.
 *
 * <p>What is stored is only what was observed last, so a restart does not briefly bill the network
 * for a tier the tower does not have.
 */
public final class TowerCoreBlockEntity extends KineticBlockEntity implements IHaveGoggleInformation {
    /** Four times a second, the same beat the docks use. */
    private static final int RESCAN_TICKS = 20;

    private int couplers;
    private TowerTier tier;

    public TowerCoreBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.TOWER_CORE.get(), pos, state);
    }

    public TowerCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % RESCAN_TICKS != 0) {
            return;
        }
        rescan();
    }

    /** Re-read the mast above and react if the answer moved. */
    private void rescan() {
        TowerStructure.Mast found = TowerStructure.mast(level, worldPosition).orElse(null);
        int height = found == null ? 0 : found.couplers();
        TowerTier reached = found == null ? null : found.tier();
        if (height == couplers && reached == tier) {
            return;
        }
        couplers = height;
        tier = reached;
        // The stress this block draws just changed, and the network only recomputes when told to.
        // Without this the tower reports its old draw until something else disturbs the network.
        networkDirty = true;
        setChanged();
        notifyUpdate();
    }

    /**
     * What this tower draws, which is nothing at all until it is a tower.
     *
     * <p>Overridden rather than registered in {@code BlockStressValues}: that table is keyed by
     * block, and this draw depends on how tall the mast standing on the block is. A fixed entry
     * would have to pick one number for a bare base and a seven-storey tower both.
     */
    @Override
    public float calculateStressApplied() {
        float applied = tier == null ? 0.0f : tier.stress();
        lastStressApplied = applied;
        return applied;
    }

    /**
     * Whether the tower is built and turning.
     *
     * <p>{@code getSpeed()} is already zero when the network is overstressed or the game is frozen,
     * so this is the one question the rest of the mod should ask instead of looking at rotation
     * itself.
     */
    public boolean isRunning() {
        return tier != null && isSpeedRequirementFulfilled();
    }

    /** Couplers counted on the last rescan, for the readout and for the cap notice. */
    public int couplers() {
        return couplers;
    }

    public TowerTier tier() {
        return tier;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.tower_core");
        if (tier == null) {
            GoggleText.line(tip, "goggle.distantstock.tower.unbuilt", couplers, TowerTier.I.couplers());
            return true;
        }
        GoggleText.line(tip, "goggle.distantstock.tower.tier", tier.name(), couplers);
        if (TowerTier.capped(couplers)) {
            GoggleText.line(tip, "goggle.distantstock.tower.capped");
        }
        GoggleText.line(tip, "goggle.distantstock.tower.radius", tier.radius());
        GoggleText.line(tip, "goggle.distantstock.tower.devices", tier.devices());
        if (!isRunning()) {
            GoggleText.value(tip, "goggle.distantstock.tower.stalled", ChatFormatting.RED);
        }
        addStressImpactStats(tip, getSpeed());
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Couplers", couplers);
        if (tier != null) {
            tag.putString("Tier", tier.name());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        couplers = tag.getInt("Couplers");
        tier = null;
        if (tag.contains("Tier")) {
            try {
                tier = TowerTier.valueOf(tag.getString("Tier"));
            } catch (IllegalArgumentException unknown) {
                // A tier this build does not have: leave it null and let the next rescan settle it.
            }
        }
    }
}
