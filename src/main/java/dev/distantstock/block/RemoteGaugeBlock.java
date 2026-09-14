package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;
import java.util.List;

/** Wall-mounted factory panel, deliberately separate from the requester desk. */
public final class RemoteGaugeBlock extends FactoryPanelBlock {
    public RemoteGaugeBlock(Properties properties) { super(properties); }

    @Override
    public BlockEntityType<? extends FactoryPanelBlockEntity> getBlockEntityType() {
        return ModBlockEntities.REMOTE_GAUGE.get();
    }

    /**
     * Peels one occupied slot off per hit, so the block is never destroyed carrying more than
     * one.
     *
     * Create's block entity pops a factory gauge for every panel past the first when it is
     * destroyed, with no creative check, and its own one-slot-per-hit routine reads the slot
     * from {@code player.pick}, which misses whenever the crosshair lands on a free slot
     * because those carry no hitbox. Together those made a remote gauge drop Create's block in
     * creative mode. Draining here leaves the destroy pass empty and hands back our own item.
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity be
                && be.activePanels() > 1) {
            List<PanelSlot> active = Arrays.stream(PanelSlot.values())
                    .filter(slot -> be.panels.get(slot).isActive())
                    .toList();
            var hit = player.pick(player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1, 1, false);
            PanelSlot aimed = getTargetedSlot(pos, state, hit.getLocation());
            be.removePanel(active.contains(aimed) ? aimed : active.get(0));
            if (!player.isCreative()) {
                player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.REMOTE_GAUGE.get()));
            }
            be.sendData();
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
