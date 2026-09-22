package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlock;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DiagnosticFrogportBlock extends FrogportBlock {
    public DiagnosticFrogportBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public Class<FrogportBlockEntity> getBlockEntityClass() {
        return FrogportBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FrogportBlockEntity> getBlockEntityType() {
        return ModBlockEntities.DIAGNOSTIC_FROGPORT.get();
    }
}
