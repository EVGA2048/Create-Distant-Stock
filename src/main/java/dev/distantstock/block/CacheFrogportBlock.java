package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlock;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class CacheFrogportBlock extends FrogportBlock {
    public CacheFrogportBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }


    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        // Sneak interaction belongs to CacheFrogportReleaseBehaviour's Create-native value
        // settings board. Ordinary empty-hand right click has exactly one meaning: open the 54-slot
        // cache inventory.
        if (player.isShiftKeyDown()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (level.getBlockEntity(pos) instanceof CacheFrogportBlockEntity cache) {
            if (!level.isClientSide) {
                player.openMenu(cache, pos);
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public Class<FrogportBlockEntity> getBlockEntityClass() {
        return FrogportBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FrogportBlockEntity> getBlockEntityType() {
        return ModBlockEntities.CACHE_FROGPORT.get();
    }
}
