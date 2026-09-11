package dev.distantstock.block;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRouteData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Reuses Create's complete packing/link/redstone implementation and changes
 * only the parcel skin. ItemStack.transmuteCopy preserves all data
 * components, including the address and package contents.
 */
public final class RemotePackagerBlockEntity extends PackagerBlockEntity {
    public RemotePackagerBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.REMOTE_PACKAGER.get(), pos, state);
    }

    public RemotePackagerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void attemptToSend(List<PackagingRequest> requests) {
        super.attemptToSend(requests);
        boolean changed = false;
        if (!heldBox.isEmpty()) {
            ItemStack remote = asRemotePackage(heldBox);
            changed = remote != heldBox;
            heldBox = remote;
        }
        for (BigItemStack queued : queuedExitingPackages) {
            ItemStack remote = asRemotePackage(queued.stack);
            changed |= remote != queued.stack;
            queued.stack = remote;
        }
        if (changed) {
            notifyUpdate();
        }
    }

    private ItemStack asRemotePackage(ItemStack stack) {
        if (stack.isEmpty() || !PackageItem.isPackage(stack)
                || stack.getItem() == ModItems.REMOTE_PACKAGE.get()) {
            return stack;
        }
        ItemStack remote = stack.transmuteCopy(ModItems.REMOTE_PACKAGE.get());
        attachRoute(remote);
        return remote;
    }

    private void attachRoute(ItemStack stack) {
        if (level == null || level.isClientSide || level.getServer() == null
                || !PackageItem.hasOrderData(stack) || RemoteRouteData.read(stack).isPresent()) {
            return;
        }
        OrderRouteDirectory.get(level.getServer()).find(PackageItem.getOrderId(stack))
                .ifPresent(route -> RemoteRouteData.write(stack, route));
    }
}
