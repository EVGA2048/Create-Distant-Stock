package dev.distantstock.item;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Remote gauge item.
 *
 * <p>Unlike Create's {@code FactoryPanelBlockItem}, this item may be placed before it is tuned to a
 * local Create logistics network. The distant source is configured after placement, so making the
 * local Create frequency a hard placement prerequisite creates a circular setup flow.
 *
 * <p>It still extends {@link LogisticallyLinkedBlockItem}: right-clicking a Create logistics link
 * before placement remains the convenient way to preselect the local inventory network the gauge
 * will monitor.
 */
public final class RemoteGaugeItem extends LogisticallyLinkedBlockItem {
    public RemoteGaugeItem(Block block, Item.Properties properties) {
        super(block, properties);
    }
}
