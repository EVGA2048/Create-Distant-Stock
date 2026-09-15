package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The casing's block entity, which carries nothing at all.
 *
 * <p>It exists for one reason: Create's pipes only connect to a block that has one. Their check is
 * {@code level.getBlockEntity(pos) != null} before the capability is even asked for, so a block
 * capability registered on a block without a block entity is invisible to every pipe in the game —
 * the port opened, the tank answered, and nothing could be plugged in.
 *
 * <p>So this holds no state, ticks nothing, syncs nothing and saves nothing. Everything the casing
 * knows is in its block state, and everything the port leads to is in the tower core.
 */
public final class TowerCasingBlockEntity extends BlockEntity {
    public TowerCasingBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWER_CASING.get(), pos, state);
    }
}
