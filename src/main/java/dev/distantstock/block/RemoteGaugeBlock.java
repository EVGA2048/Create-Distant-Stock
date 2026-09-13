package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Wall-mounted factory panel, deliberately separate from the requester desk. */
public final class RemoteGaugeBlock extends FactoryPanelBlock {
    public RemoteGaugeBlock(Properties properties) { super(properties); }

    @Override
    public BlockEntityType<? extends FactoryPanelBlockEntity> getBlockEntityType() {
        return ModBlockEntities.REMOTE_GAUGE.get();
    }
}
