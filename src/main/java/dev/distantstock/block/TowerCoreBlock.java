package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

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
}
