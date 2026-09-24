package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The centre of a tower's 3x3 base, and the only part of a tower that turns.
 *
 * <p>Driven from underneath and nowhere else. The 3x3 skirt sits at the same level as this block,
 * so a shaft coming in from the side would have to pass through casing — and, more to the point,
 * a tower is meant to be fed from below, the way a gearbox under a floor drives what stands on it.
 *
 * <p>Nothing about the tier lives here. How tall the mast is, and therefore what the tower can
 * carry, is the block entity's; this is only the shape and the shaft.
 */
public final class TowerCoreBlock extends KineticBlock implements IBE<TowerCoreBlockEntity> {
    public static final MapCodec<TowerCoreBlock> CODEC = simpleCodec(TowerCoreBlock::new);

    public TowerCoreBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends KineticBlock> codec() {
        return CODEC;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction side) {
        // Create asks both blocks whether they meet, so returning true here for anything but the
        // underside would let a shaft join through the skirt.
        return side == Direction.DOWN;
    }

    /**
     * The same bar the chunk loaders set: thirty rpm.
     *
     * <p>Deliberately low. A tower's cost is its stress draw, which runs to five figures at the top
     * of the table — how fast it has to spin is not where the difficulty should live.
     */
    @Override
    public IRotate.SpeedLevel getMinimumRequiredSpeedLevel() {
        return IRotate.SpeedLevel.MEDIUM;
    }

    @Override
    public Class<TowerCoreBlockEntity> getBlockEntityClass() {
        return TowerCoreBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TowerCoreBlockEntity> getBlockEntityType() {
        return ModBlockEntities.TOWER_CORE.get();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return TowerControl.open(level, pos, player);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!oldState.is(this)) {
            invalidateSkirtCapabilities(level, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                         boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // Do this while the old core still exists. Capability listeners only need the invalidation
            // signal; their next query happens after the world has settled on the new state.
            invalidateSkirtCapabilities(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    static void invalidateSkirtCapabilities(Level level, BlockPos corePos) {
        // Never turn capability invalidation into chunk loading. This method runs from the core
        // block entity's load/unload lifecycle as well as placement/removal. Calling
        // Level#getBlockState across a chunk boundary while a chunk is unloading can synchronously
        // request that chunk again and stall the server thread. An unloaded casing cannot retain a
        // live BlockCapabilityCache, so only chunks that are already resident need invalidation.
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos casingPos = corePos.offset(dx, 0, dz);
                var chunk = server.getChunkSource().getChunkNow(
                        casingPos.getX() >> 4, casingPos.getZ() >> 4);
                if (chunk != null && chunk.getBlockState(casingPos).is(ModBlocks.TOWER_CASING.get())) {
                    server.invalidateCapabilities(casingPos);
                }
            }
        }
    }
}
