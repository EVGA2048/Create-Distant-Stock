package dev.distantstock.block;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.TowerActivation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * Reuses Create's complete packing/link/redstone implementation and changes only the parcel skin.
 * Remote-terminal routing is stamped for every Create packager by PackagerBlockEntityMixin; this
 * subclass only selects the blue Distant Stock package item. transmuteCopy preserves that metadata.
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

    /**
     * Reject the order before Create enters performPackageRequests().
     *
     * <p>Create flashes the stock link and then calls attemptToSend up to 100 times while the
     * request list remains non-empty. Returning early from attemptToSend when the tower is inactive
     * therefore looks like a successful request animation but silently drops the temporary request
     * list after the loop. Report the machine as unavailable here instead; both Create's native
     * broadcast path and Distant Stock's routed request path check this before performing requests.
     */
    @Override
    public boolean isTooBusyFor(LogisticallyLinkedBehaviour.RequestType type) {
        return !TowerActivation.active(level, worldPosition) || super.isTooBusyFor(type);
    }

    private ItemStack asRemotePackage(ItemStack stack) {
        if (stack.isEmpty() || !PackageItem.isPackage(stack)
                || stack.getItem() == ModItems.REMOTE_PACKAGE.get()) {
            return stack;
        }
        return stack.transmuteCopy(ModItems.REMOTE_PACKAGE.get());
    }

    /**
     * Whether this node has a machine that could pack an order for that network.
     *
     * <p>This is the answer to the failure a player cannot see: a terminal on one server offers
     * every network the other server announces, an order goes out, is accepted, and then nothing
     * ever arrives — because the far end has no packager that will make a parcel for it. There is
     * no error at either end, because neither end did anything wrong: the request was valid, and
     * there was simply nothing there to fulfil it.
     *
     * <p><b>问的是 Create 的物流链接，不是打包机自己。</b>打包机身上没有网络 —— 挂网络的是它旁边那台
     * 仓储链接（{@code PackagerLinkBlockEntity}），链接指向打包机，打包机才知道自己属于哪张网。
     * 一开始这里查的是打包机自己的行为（{@code getBehaviour(LogisticallyLinkedBehaviour.TYPE)}），
     * 那个查询永远返回 null，于是**每一张网络**都被标成「对面无打包机」——包括本机的。玩家报的
     * "对面有打包机也显示没有"就是这个。
     *
     * <p>所以走 Create 自己的索引：{@code getAllPresent(freq, false)} 给出这张网上所有链接，
     * 每个链接再用 {@code getPackager()} 问它指向哪台打包机 —— 和 Create 发货时找打包机走的是同一条
     * 关系。远仓终端订单的路由属性由 {@code PackagerBlockEntityMixin} 统一盖到所有 Create 包裹上，
     * 所以普通打包机和远仓打包机都能履约；二者的区别只在包裹外观（普通纸箱 / 蓝色远仓纸箱）。
     *
     * <p>This method answers only "does the Create network have a connected packager?". Tower
     * activation is a separate runtime condition checked by {@link #isTooBusyFor}; folding it into
     * discovery made a perfectly real remote packager appear in the UI as "no packager" whenever a
     * tower snapshot was stale/offline, which is both misleading and impossible to diagnose.
     */
    public static boolean canPack(UUID frequency) {
        if (frequency == null) {
            return false;
        }
        for (LogisticallyLinkedBehaviour behaviour
                : LogisticallyLinkedBehaviour.getAllPresent(frequency, false)) {
            if (!(behaviour.blockEntity instanceof PackagerLinkBlockEntity link)) {
                continue;
            }
            if (link.getPackager() instanceof PackagerBlockEntity) {
                return true;
            }
        }
        return false;
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
