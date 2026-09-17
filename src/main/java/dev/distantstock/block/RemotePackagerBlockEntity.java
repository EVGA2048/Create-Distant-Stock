package dev.distantstock.block;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRouteData;
import dev.distantstock.routing.TowerActivation;
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

    /**
     * A packager outside its tower's reach does not pack.
     *
     * <p>Nothing is consumed and no box is made — the requests stay outstanding, and Create's
     * factory panels re-issue what they are still waiting for on their own interval, so an order
     * placed at a dark machine waits instead of disappearing. The alternative, letting it pack and
     * trusting the dock to hold the result, would put boxes in a tray nobody can see and leave the
     * panel reporting a fulfilled order.
     */
    @Override
    public void attemptToSend(List<PackagingRequest> requests) {
        if (!TowerActivation.active(level, worldPosition)) {
            return;
        }
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
        var directory = OrderRouteDirectory.get(level.getServer());
        int orderId = PackageItem.getOrderId(stack);
        directory.find(orderId).ifPresent(route -> RemoteRouteData.write(stack, route,
                dev.distantstock.link.RouteLabels.describe(level.getServer(), route),
                // 过海以后要穿的地址。留空 = 这件货不过海，或者发它的那版只有一个地址。
                directory.homeAddress(orderId)));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedDevices.add(this);
    }

    @Override
    public void onChunkUnloaded() {
        LoadedDevices.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    public void destroy() {
        LoadedDevices.remove(this);
        super.destroy();
    }

    @Override
    public void remove() {
        LoadedDevices.remove(this);
        super.remove();
    }
}
