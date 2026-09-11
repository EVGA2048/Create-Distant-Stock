package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Hit target for the dock's value settings. The dock art has no drawn slot to aim at, so the whole block
 * is one target: the box sits in the middle and {@link #getScale()} covers every face from there. Which
 * panel opens is decided by the clicked side instead of a visible box position.
 */
final class DockValueBox extends ValueBoxTransform.Sided {
    /** Only these sides open the panel this box belongs to. */
    private final boolean horizontalSides;

    DockValueBox(boolean horizontalSides) {
        this.horizontalSides = horizontalSides;
    }

    @Override
    public float getScale() {
        // Radius 1.0 around the block centre: any point of any face of the block is inside it.
        return 2f;
    }

    @Override
    protected Vec3 getSouthLocation() {
        return new Vec3(0.5, 0.5, 0.5);
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction side) {
        return side != null && side.getAxis().isHorizontal() == horizontalSides;
    }
}
