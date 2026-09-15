package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The cap of a tower.
 *
 * <p>It holds no state of its own — how tall the tower is and whether it is turning are the base's,
 * and the arms' angle is derived from the level's game time by the renderer rather than stored,
 * because a number nobody reads is three ways to disagree. What does live here is the light: which
 * of its three appearances the column above the cap is wearing right now, which is a fact about the
 * world that the client cannot work out for itself.
 */
public final class ResonatorBlockEntity extends SmartBlockEntity {
    /** How often the light re-reads the tower, in ticks. Four times a second is imperceptible. */
    private static final int REFRESH_TICKS = 5;

    /** What the light column is showing. */
    public enum Beam {
        /** No tower under this cap, or one that is not turning. */
        DORMANT,
        /** A tower at work with nothing crossing it. */
        IDLE,
        /** A tower with a parcel in flight through it. */
        ACTIVE;
    }

    private Beam beam = Beam.DORMANT;

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.ETHER_RESONATOR.get(), pos, state);
    }

    public ResonatorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /** Called from the block's ticker on both sides, the way the rest of this mod's blocks do it. */
    public static void tick(Level level, BlockPos pos, BlockState state, ResonatorBlockEntity be) {
        be.tick();
        if (level.getGameTime() % REFRESH_TICKS == 0) {
            be.refreshBeam();
        }
    }

    /**
     * Decide the light's appearance.
     *
     * <p>Read from the world rather than pushed in from the base: the base can be unloaded, broken
     * or replaced without this block hearing about it, and the light is the one thing a player looks
     * at to tell whether a tower is alive. Asking is cheap and cannot get out of date.
     */
    private void refreshBeam() {
        Beam next = Beam.DORMANT;
        BlockPos core = TowerStructure.coreUnder(level, worldPosition).orElse(null);
        if (core != null && TowerStructure.running(level, core)) {
            next = TowerBeacon.busy(level, core) ? Beam.ACTIVE : Beam.IDLE;
        }
        if (next == beam) {
            return;
        }
        beam = next;
        notifyUpdate();
    }

    public Beam beam() {
        return beam;
    }

    /**
     * The arms reach half a block past every side, and the light column stands six pixels above the
     * cube. Without this the model is culled as soon as the block itself leaves the frustum and the
     * tower's top vanishes exactly when it is most of what you can see.
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition).inflate(0.5);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Beam", beam.ordinal());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        Beam[] values = Beam.values();
        int ordinal = tag.getInt("Beam");
        beam = ordinal >= 0 && ordinal < values.length ? values[ordinal] : Beam.DORMANT;
    }
}
