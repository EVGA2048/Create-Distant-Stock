package dev.distantstock.block;

import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Create's packager casing with a remote-package block entity underneath. */
public final class RemotePackagerBlock extends PackagerBlock {
    public RemotePackagerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntityType<? extends PackagerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.REMOTE_PACKAGER.get();
    }
}
