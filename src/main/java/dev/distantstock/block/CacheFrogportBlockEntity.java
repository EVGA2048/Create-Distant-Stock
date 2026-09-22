package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.diagnostics.ChainDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

public final class CacheFrogportBlockEntity extends FrogportBlockEntity {
    private String takeoverAddress = "";
    private long nextReleaseTick;
    private int releaseDelaySeconds = 5;
    private boolean lastRedstonePowered;
    private int pendingRedstoneReleases;

    public CacheFrogportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CACHE_FROGPORT.get(), pos, state);
        acceptsPackages = true;
    }

    @Override
    public String getFilterString() {
        // Keep a private, unmatchable route while idle so Create still records this Frogport in
        // loopPorts/travelPorts. Returning null makes PackagePortTarget.register() exit before the
        // port is added to the chain topology, which would make diagnostics unable to discover it.
        return takeoverAddress.isBlank()
                ? ChainDiagnostics.cacheIdleAddress(worldPosition)
                : takeoverAddress;
    }

    public String takeoverAddress() {
        return takeoverAddress;
    }

    public int cachedCount() {
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) count++;
        }
        return count;
    }

    public int releaseDelaySeconds() {
        return releaseDelaySeconds;
    }

    public void setReleaseDelaySeconds(int seconds) {
        releaseDelaySeconds = Math.max(0, Math.min(100, seconds));
        pendingRedstoneReleases = 0;
        if (level != null) {
            nextReleaseTick = level.getGameTime();
            // Entering redstone mode while the wire is already high must not invent a pulse.
            lastRedstonePowered = level.hasNeighborSignal(worldPosition);
        }
        setChanged();
        sendData();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        behaviours.add(new CacheFrogportReleaseBehaviour(this));
    }

    public void setTakeoverAddress(String address) {
        String next = address == null ? "" : address;
        if (takeoverAddress.equals(next)) return;
        // Create's filterChanged() asks getFilterString() during deregistration. Changing our
        // dynamic address first would therefore try to remove the NEW route and leave the OLD one
        // advertised forever. Explicitly unregister while the old value is still visible, then
        // register again after switching.
        if (level != null && !level.isClientSide && target != null) {
            target.deregister(this, level, worldPosition);
        }
        takeoverAddress = next;
        if (level != null && !level.isClientSide && target != null) {
            target.register(this, level, worldPosition);
        }
        setChanged();
        sendData();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ChainDiagnostics.register(this);
    }

    @Override
    protected void tryPushingToAdjacentInventories() {
        // Intentionally retain captured parcels in the Frogport's native 18-slot inventory.
        // The stock 2x9 GUI therefore becomes the cache UI and its native backed-up state is our
        // hard capacity limit. Never silently drain the cache into an adjacent inventory.
    }

    @Override
    public void tryPullingFromOwnAndAdjacentInventories() {
        // The cache receives from the chain only; it never imports arbitrary packages from a chest.
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;

        boolean powered = level.hasNeighborSignal(worldPosition);
        if (releaseDelaySeconds == 0) {
            if (powered && !lastRedstonePowered && takeoverAddress.isBlank()) {
                int remaining = Math.max(0, cachedCount() - pendingRedstoneReleases);
                if (remaining > 0) {
                    pendingRedstoneReleases++;
                    setChanged();
                }
            }

            if (pendingRedstoneReleases > 0 && takeoverAddress.isBlank() && !isAnimationInProgress()
                    && releaseOne(level.getGameTime())) {
                pendingRedstoneReleases--;
                setChanged();
                sendData();
            }
        } else if (pendingRedstoneReleases != 0) {
            pendingRedstoneReleases = 0;
            setChanged();
        }

        if (lastRedstonePowered != powered) {
            lastRedstonePowered = powered;
            setChanged();
        }
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null || level.isClientSide || !takeoverAddress.isBlank() || cachedCount() == 0
                || target == null || isAnimationInProgress()) return;
        if (releaseDelaySeconds == 0) return; // redstone-controlled mode; trigger semantics are wired separately
        long now = level.getGameTime();
        if (now < nextReleaseTick) return;
        if (!releaseOne(now)) return;
        nextReleaseTick = now + releaseDelaySeconds * 20L;
        setChanged();
        sendData();
    }

    private boolean releaseOne(long now) {
        if (level == null || target == null || isAnimationInProgress() || !takeoverAddress.isBlank()) return false;
        int sourceSlot = -1;
        ItemStack stack = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack candidate = inventory.getStackInSlot(slot);
            if (candidate.isEmpty()) continue;
            sourceSlot = slot;
            stack = candidate.copyWithCount(1);
            break;
        }
        if (sourceSlot < 0 || stack.isEmpty()) return false;
        if (!target.export(level, worldPosition, stack, true)) return false;
        inventory.extractItem(sourceSlot, 1, false);
        super.startAnimation(stack, true);
        return true;
    }

    public IItemHandler exposedItemHandler() {
        return itemHandler;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("TakeoverAddress", takeoverAddress);
        tag.putLong("NextRelease", nextReleaseTick);
        tag.putInt("ReleaseDelaySeconds", releaseDelaySeconds);
        tag.putBoolean("LastRedstonePowered", lastRedstonePowered);
        tag.putInt("PendingRedstoneReleases", pendingRedstoneReleases);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        takeoverAddress = tag.getString("TakeoverAddress");
        nextReleaseTick = tag.getLong("NextRelease");
        releaseDelaySeconds = tag.contains("ReleaseDelaySeconds")
                ? Math.max(0, Math.min(100, tag.getInt("ReleaseDelaySeconds")))
                : 5;
        lastRedstonePowered = tag.getBoolean("LastRedstonePowered");
        pendingRedstoneReleases = Math.max(0, Math.min(18, tag.getInt("PendingRedstoneReleases")));
    }
}
