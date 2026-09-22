package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class DiagnosticFrogportBlockEntity extends FrogportBlockEntity implements IHaveGoggleInformation {
    private final IItemHandler quarantineExtraction = new IItemHandler() {
        @Override
        public int getSlots() {
            return inventory.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // The underside is a quarantine OUTPUT only. Packages enter only from the chain.
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return inventory.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return inventory.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };

    private String diagnosticPhase = "starting";
    private String diagnosticAddress = "";
    private String lastResult = "none";
    private String lastResultAddress = "";
    private int planIndex;
    private int planTotal;
    private int targetCount;
    private int faultCount;
    private boolean cacheActive;
    private long nextActionTick;
    private long probeSentTick;
    private long probeDeadlineTick;
    private UUID createFrequency;
    /** Durable unresolved no-route addresses, independent of the transient quarantine inventory. */
    private final Set<String> unresolvedBadAddresses = new LinkedHashSet<>();
    /** Last tick a faulted address was observed on a live Factory Gauge. */
    private final Map<String, Long> gaugeBackedBadAddresses = new LinkedHashMap<>();

    public DiagnosticFrogportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DIAGNOSTIC_FROGPORT.get(), pos, state);
        acceptsPackages = true;
    }

    @Override
    public String getFilterString() {
        return ChainDiagnostics.diagnosticAddress(worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ChainDiagnostics.register(this);
    }

    @Override
    public void startAnimation(ItemStack stack, boolean depositing) {
        if (!depositing && !PingPackageData.isPing(stack)) {
            ChainDiagnostics.unroutableCaptured(this, stack);
        }
        super.startAnimation(stack, depositing);
    }

    @Override
    public void tryPullingFromOwnAndAdjacentInventories() {
        // A diagnostic frog emits only probes created by ChainDiagnostics. It must never steal a
        // player's ordinary parcels from a chest below it and accidentally put them on the network.
    }

    @Override
    protected void tryPushingToAdjacentInventories() {
        // Create's normal Frogport pushes through PackagePortAutomationInventoryWrapper. That
        // wrapper only allows extraction when a package address matches this port's filter. A
        // diagnostic Frogport intentionally holds *unroutable* packages whose business address can
        // never match its private __distantstock_diag__/... filter, so the vanilla path would leave
        // them stuck forever. Use the raw 18-slot inventory for outbound transfer only, preserving
        // the normal Frogport behaviour of handing received packages to the inventory/Packager
        // directly below it. The pull path remains disabled, so nothing below can be sucked upward.
        if (level == null || level.isClientSide || isAnimationInProgress()) return;
        IItemHandler below = getAdjacentInventory(Direction.DOWN);
        if (below == null) return;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack candidate = inventory.extractItem(slot, 1, true);
            if (candidate.isEmpty() || PingPackageData.isPing(candidate)) continue;

            ItemStack remainder = ItemHandlerHelper.insertItemStacked(below, candidate, false);
            if (!remainder.isEmpty()) continue;

            inventory.extractItem(slot, 1, false);
            level.blockEntityChanged(worldPosition);
        }
    }

    public boolean sendProbe(ItemStack stack) {
        if (level == null || level.isClientSide || target == null || stack == null || stack.isEmpty()
                || isAnimationInProgress()) return false;
        if (!target.export(level, worldPosition, stack, true)) return false;
        super.startAnimation(stack, true);
        return true;
    }

    public int quarantinedCount() {
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) count++;
        }
        return count;
    }

    public String diagnosticPhase() {
        return diagnosticPhase;
    }

    public String diagnosticAddress() {
        return diagnosticAddress;
    }

    public int diagnosticPlanIndex() {
        return planIndex;
    }

    public int diagnosticPlanTotal() {
        return planTotal;
    }

    public long diagnosticNextActionTick() {
        return nextActionTick;
    }

    public String diagnosticLastResult() {
        return lastResult;
    }

    public UUID createFrequency() {
        return createFrequency;
    }

    public void setCreateFrequency(UUID createFrequency) {
        if (Objects.equals(this.createFrequency, createFrequency)) return;
        this.createFrequency = createFrequency;
        syncDiagnosticState();
    }

    public Set<String> unresolvedBadAddresses() {
        return Set.copyOf(unresolvedBadAddresses);
    }

    public boolean badAddressWasGaugeBacked(String address) {
        return address != null && gaugeBackedBadAddresses.containsKey(address);
    }

    public long badAddressGaugeLastSeen(String address) {
        return address == null ? Long.MIN_VALUE : gaugeBackedBadAddresses.getOrDefault(address, Long.MIN_VALUE);
    }

    public void rememberBadAddress(String address, boolean gaugeBacked) {
        if (address == null || address.isBlank()) return;
        boolean changed = unresolvedBadAddresses.add(address);
        if (gaugeBacked && level != null) {
            Long old = gaugeBackedBadAddresses.put(address, level.getGameTime());
            changed |= old == null || old.longValue() != level.getGameTime();
        }
        if (changed) syncDiagnosticState();
    }

    public void noteBadAddressGaugeBacked(String address) {
        if (address == null || address.isBlank() || !unresolvedBadAddresses.contains(address)) return;
        if (level == null) return;
        long now = level.getGameTime();
        Long old = gaugeBackedBadAddresses.put(address, now);
        if (old == null || old.longValue() != now) syncDiagnosticState();
    }

    public void clearBadAddress(String address) {
        if (address == null) return;
        boolean changed = unresolvedBadAddresses.remove(address);
        changed |= gaugeBackedBadAddresses.remove(address) != null;
        if (changed) syncDiagnosticState();
    }

    public void setDiagnosticPlan(String phase, String address, int index, int total, int targets,
                                  long nextTick, long sentTick, long deadlineTick) {
        phase = phase == null ? "idle" : phase;
        address = address == null ? "" : address;
        boolean changed = !Objects.equals(diagnosticPhase, phase)
                || !Objects.equals(diagnosticAddress, address)
                || planIndex != index || planTotal != total || targetCount != targets
                || nextActionTick != nextTick || probeSentTick != sentTick
                || probeDeadlineTick != deadlineTick;
        diagnosticPhase = phase;
        diagnosticAddress = address;
        planIndex = Math.max(0, index);
        planTotal = Math.max(0, total);
        targetCount = Math.max(0, targets);
        nextActionTick = Math.max(0, nextTick);
        probeSentTick = Math.max(0, sentTick);
        probeDeadlineTick = Math.max(0, deadlineTick);
        if (changed) syncDiagnosticState();
    }

    public void setDiagnosticHealth(int faults, boolean cache) {
        faults = Math.max(0, faults);
        if (faultCount == faults && cacheActive == cache) return;
        faultCount = faults;
        cacheActive = cache;
        syncDiagnosticState();
    }

    public void recordDiagnosticResult(String result, String address) {
        result = result == null ? "none" : result;
        address = address == null ? "" : address;
        if (Objects.equals(lastResult, result) && Objects.equals(lastResultAddress, address)) return;
        lastResult = result;
        lastResultAddress = address;
        syncDiagnosticState();
    }

    private void syncDiagnosticState() {
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        GoggleText.title(tooltip, "block.distantstock.diagnostic_frogport");

        tooltip.add((createFrequency == null
                        ? Component.translatable("goggle.distantstock.diagnostic.network_unbound")
                        : Component.translatable("goggle.distantstock.diagnostic.network",
                                dev.distantstock.item.RequesterData.shortFreq(createFrequency)))
                .withStyle(createFrequency == null ? ChatFormatting.RED : ChatFormatting.AQUA));

        tooltip.add(Component.translatable("goggle.distantstock.diagnostic.state",
                        Component.translatable("goggle.distantstock.diagnostic.phase." + diagnosticPhase))
                .withStyle(ChatFormatting.GRAY));

        if (planTotal > 0) {
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.plan",
                            Math.max(1, planIndex), planTotal)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!diagnosticAddress.isBlank()) {
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.target", diagnosticAddress)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (targetCount > 0) {
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.receivers", targetCount)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        long now = level == null ? 0 : level.getGameTime();
        if (probeDeadlineTick > now && probeSentTick > 0
                && ("waiting".equals(diagnosticPhase) || "recovery".equals(diagnosticPhase))) {
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.ping_elapsed",
                            seconds(now - probeSentTick), seconds(probeDeadlineTick - now))
                    .withStyle(ChatFormatting.YELLOW));
        } else if (nextActionTick > now) {
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.next",
                            seconds(nextActionTick - now))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltip.add(Component.translatable("goggle.distantstock.diagnostic.faults", faultCount)
                .withStyle(faultCount > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GREEN));
        tooltip.add(Component.translatable("goggle.distantstock.diagnostic.cache",
                        Component.translatable(cacheActive
                                ? "goggle.distantstock.common.yes"
                                : "goggle.distantstock.common.no"))
                .withStyle(cacheActive ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("goggle.distantstock.diagnostic.quarantine",
                        quarantinedCount(), inventory.getSlots())
                .withStyle(quarantinedCount() > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));

        if (!"none".equals(lastResult)) {
            Component result = Component.translatable("goggle.distantstock.diagnostic.result." + lastResult);
            tooltip.add(Component.translatable("goggle.distantstock.diagnostic.last", result,
                            lastResultAddress.isBlank() ? "-" : lastResultAddress)
                    .withStyle(ChatFormatting.GRAY));
        }
        return true;
    }

    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%.1f s", Math.max(0, ticks) / 20.0);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("DiagnosticPhase", diagnosticPhase);
        tag.putString("DiagnosticAddress", diagnosticAddress);
        tag.putString("DiagnosticLastResult", lastResult);
        tag.putString("DiagnosticLastAddress", lastResultAddress);
        tag.putInt("DiagnosticPlanIndex", planIndex);
        tag.putInt("DiagnosticPlanTotal", planTotal);
        tag.putInt("DiagnosticTargetCount", targetCount);
        tag.putInt("DiagnosticFaultCount", faultCount);
        tag.putBoolean("DiagnosticCacheActive", cacheActive);
        tag.putLong("DiagnosticNextAction", nextActionTick);
        tag.putLong("DiagnosticProbeSent", probeSentTick);
        tag.putLong("DiagnosticProbeDeadline", probeDeadlineTick);
        if (createFrequency != null) tag.putUUID("CreateFrequency", createFrequency);

        ListTag badAddresses = new ListTag();
        for (String address : unresolvedBadAddresses) badAddresses.add(StringTag.valueOf(address));
        tag.put("UnresolvedBadAddresses", badAddresses);

        ListTag gaugeBacked = new ListTag();
        for (var entry : gaugeBackedBadAddresses.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Address", entry.getKey());
            row.putLong("LastSeen", entry.getValue());
            gaugeBacked.add(row);
        }
        tag.put("GaugeBackedBadAddresses", gaugeBacked);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        diagnosticPhase = tag.contains("DiagnosticPhase") ? tag.getString("DiagnosticPhase") : "starting";
        diagnosticAddress = tag.getString("DiagnosticAddress");
        lastResult = tag.contains("DiagnosticLastResult") ? tag.getString("DiagnosticLastResult") : "none";
        lastResultAddress = tag.getString("DiagnosticLastAddress");
        planIndex = Math.max(0, tag.getInt("DiagnosticPlanIndex"));
        planTotal = Math.max(0, tag.getInt("DiagnosticPlanTotal"));
        targetCount = Math.max(0, tag.getInt("DiagnosticTargetCount"));
        faultCount = Math.max(0, tag.getInt("DiagnosticFaultCount"));
        cacheActive = tag.getBoolean("DiagnosticCacheActive");
        nextActionTick = Math.max(0, tag.getLong("DiagnosticNextAction"));
        probeSentTick = Math.max(0, tag.getLong("DiagnosticProbeSent"));
        probeDeadlineTick = Math.max(0, tag.getLong("DiagnosticProbeDeadline"));
        createFrequency = tag.hasUUID("CreateFrequency") ? tag.getUUID("CreateFrequency") : null;

        unresolvedBadAddresses.clear();
        ListTag badAddresses = tag.getList("UnresolvedBadAddresses", Tag.TAG_STRING);
        for (int i = 0; i < badAddresses.size(); i++) unresolvedBadAddresses.add(badAddresses.getString(i));

        gaugeBackedBadAddresses.clear();
        ListTag gaugeBacked = tag.getList("GaugeBackedBadAddresses", Tag.TAG_COMPOUND);
        for (int i = 0; i < gaugeBacked.size(); i++) {
            CompoundTag row = gaugeBacked.getCompound(i);
            String address = row.getString("Address");
            if (!address.isBlank()) gaugeBackedBadAddresses.put(address, row.getLong("LastSeen"));
        }
    }

    public IItemHandler exposedItemHandler() {
        return itemHandler;
    }

    public IItemHandler quarantineExtractionHandler() {
        return quarantineExtraction;
    }
}
