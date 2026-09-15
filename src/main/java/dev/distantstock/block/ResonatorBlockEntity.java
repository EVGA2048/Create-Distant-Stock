package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The resonator's block entity.
 *
 * <p>It holds no state yet. The arms' angle is derived from the level's game time by the renderer
 * rather than stored and synced, because it is pure animation: a field to advance each tick, write
 * to NBT, and ship to every client would be three ways to disagree about a number nobody reads.
 *
 * <p>What will live here is the tower's kinetic input and tier, which is the next stage.
 */
public final class ResonatorBlockEntity extends SmartBlockEntity {

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.ETHER_RESONATOR.get(), pos, state);
    }

    public ResonatorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    /**
     * The arms reach half a block past every side, and the body's crystal stands six pixels above
     * the cube. Without this the model is culled as soon as the block itself leaves the frustum and
     * the tower's top vanishes exactly when it is most of what you can see.
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition).inflate(0.5);
    }
}
