package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortAutomationInventoryWrapper;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.foundation.item.SmartInventory;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.menu.CacheFrogportMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.Locale;

public final class CacheFrogportBlockEntity extends FrogportBlockEntity implements IHaveGoggleInformation {
    /** Three vanilla Frogports worth of emergency headroom, still compact enough for one 6x9 UI. */
    public static final int CACHE_SLOTS = 54;

    private String takeoverAddress = "";
    private long nextReleaseTick;
    private int releaseDelaySeconds = 5;
    private boolean lastRedstonePowered;
    private int pendingRedstoneReleases;

    public CacheFrogportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CACHE_FROGPORT.get(), pos, state);
        // PackagePortBlockEntity hard-codes 18 slots.  A cache exists specifically to absorb a
        // burst while the real receivers are frozen, so give it a real six-row buffer instead of
        // pretending eighteen emergency parcels are enough.  Keep Create's package-only validator
        // and automation wrapper semantics.
        inventory = new SmartInventory(CACHE_SLOTS, this, (slot, stack) -> PackageItem.isPackage(stack));
        itemHandler = new PackagePortAutomationInventoryWrapper(inventory, this);
        acceptsPackages = true;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return CacheFrogportMenu.server(id, playerInventory, this);
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
        releaseDelaySeconds = normalizeReleaseDelaySeconds(seconds);
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
    public void destroy() {
        // FROZEN is runtime routing state. If the mitigation block disappears, thaw the original
        // Frogports *before* Create deregisters this block, otherwise the public address can remain
        // stolen by a fault that no longer has a cache to serve it.
        if (level != null && !level.isClientSide) {
            ChainDiagnostics.cacheRemoved(this);
        }
        ChainDiagnostics.unregister(this);
        super.destroy();
    }

    /**
     * Incoming chain parcels are emergency traffic. Store them immediately instead of serialising
     * every catch behind Frogport's 10-tick mouth animation. ChainConveyor already checks
     * {@link #isBackedUp()} before calling this method, so a full cache remains proper back-pressure.
     */
    @Override
    public void startAnimation(ItemStack stack, boolean depositing) {
        if (depositing || stack == null || stack.isEmpty() || !PackageItem.isPackage(stack)
                || level == null || level.isClientSide) {
            super.startAnimation(stack, depositing);
            return;
        }

        ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), false);
        if (!remainder.isEmpty()) {
            // This should only be reachable if another insertion filled the final slot between
            // ChainConveyor's isBackedUp() check and this call. Preserve the parcel rather than
            // silently deleting it.
            drop(remainder);
        }
        setChanged();
        level.blockEntityChanged(worldPosition);
        sendData();
    }

    @Override
    protected void tryPushingToAdjacentInventories() {
        // A container below is an explicit operator-provided offload path. Anything it accepts has
        // left cache custody and will therefore not be auto-replayed later; its package address is
        // untouched, so a normal Frogport can put it back on the chain at any time.
        if (level == null || level.isClientSide || isAnimationInProgress()) return;
        IItemHandler below = getAdjacentInventory(Direction.DOWN);
        if (below == null) return;

        boolean changed = false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack candidate = inventory.extractItem(slot, 1, true);
            if (candidate.isEmpty()) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(below, candidate, false);
            if (!remainder.isEmpty()) continue;
            inventory.extractItem(slot, 1, false);
            changed = true;
        }
        if (changed) {
            setChanged();
            level.blockEntityChanged(worldPosition);
            sendData();
        }
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
        if (releaseDelaySeconds == 0) return; // redstone mode is handled by rising-edge logic in tick()
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
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        GoggleText.title(tooltip, "block.distantstock.cache_frogport");

        String phase;
        ChatFormatting phaseColor;
        if (!takeoverAddress.isBlank()) {
            phase = "takeover";
            phaseColor = ChatFormatting.YELLOW;
        } else if (cachedCount() > 0) {
            phase = "replay";
            phaseColor = ChatFormatting.AQUA;
        } else {
            phase = "idle";
            phaseColor = ChatFormatting.DARK_GREEN;
        }
        GoggleText.value(tooltip, "goggle.distantstock.cache.state", phaseColor,
                Component.translatable("goggle.distantstock.cache.phase." + phase));

        if (!takeoverAddress.isBlank()) {
            GoggleText.line(tooltip, "goggle.distantstock.cache.address", takeoverAddress);
        }
        GoggleText.value(tooltip, "goggle.distantstock.cache.inventory",
                cachedCount() >= inventory.getSlots() ? ChatFormatting.RED : ChatFormatting.GRAY,
                cachedCount(), inventory.getSlots());

        if (releaseDelaySeconds == 0) {
            GoggleText.line(tooltip, "goggle.distantstock.cache.release_redstone");
            if (pendingRedstoneReleases > 0) {
                GoggleText.line(tooltip, "goggle.distantstock.cache.pending_redstone",
                        pendingRedstoneReleases);
            }
        } else {
            GoggleText.line(tooltip, "goggle.distantstock.cache.release_interval", releaseDelaySeconds);
            if (level != null && takeoverAddress.isBlank() && cachedCount() > 0) {
                long remaining = Math.max(0, nextReleaseTick - level.getGameTime());
                GoggleText.line(tooltip, "goggle.distantstock.cache.next_release", seconds(remaining));
            }
        }
        return true;
    }

    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.1f s", Math.max(0, ticks) / 20.0);
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
                ? normalizeReleaseDelaySeconds(tag.getInt("ReleaseDelaySeconds"))
                : 5;
        lastRedstonePowered = tag.getBoolean("LastRedstonePowered");
        pendingRedstoneReleases = Math.max(0, Math.min(CACHE_SLOTS, tag.getInt("PendingRedstoneReleases")));
    }

    private static int normalizeReleaseDelaySeconds(int seconds) {
        int[] allowed = {0, 5, 10, 15, 30};
        int best = allowed[0];
        int bestDistance = Math.abs(seconds - best);
        for (int candidate : allowed) {
            int distance = Math.abs(seconds - candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }
}
