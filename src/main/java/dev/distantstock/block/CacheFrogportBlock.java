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
        if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog) {
            frog.use(player);
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
