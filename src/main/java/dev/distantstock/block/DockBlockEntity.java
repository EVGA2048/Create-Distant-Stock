package dev.distantstock.block;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkQueues;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.PackageCodec;
import dev.distantstock.link.ParcelEscrow;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockMode;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteRouteData;
import dev.distantstock.routing.RouteResolution;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class DockBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {
    public static final int SLOTS = 9;
    public static final int MAX_PRIORITY = 5;
    public static final int TRANSMIT_TICKS = 30;
    /** How long a blocked fallback face keeps reporting itself after the last refused release. */
    private static final long BLOCKED_LINGER_TICKS = 200;
    /** How often a jammed dock reminds the player with a sound. */
    private static final long BLOCKED_ALARM_TICKS = 120;

    private final ItemStackHandler receivedInv = inventory(this::contentsChanged);
    private final ItemStackHandler outboundInv = inventory(this::contentsChanged);
    /** Items and parcels the dock cannot handle, waiting for room below the fallback face. */
    private final ItemStackHandler fallbackInv = fallbackInventory(this::contentsChanged);

    /** First nine slots extract received parcels; last nine insert outbound parcels. */
    final IItemHandler automation = new IItemHandler() {
        @Override
        public int getSlots() {
            return SLOTS * 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot < SLOTS ? receivedInv.getStackInSlot(slot) : outboundInv.getStackInSlot(slot - SLOTS);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < SLOTS || !PackageItem.isPackage(stack) || !canSend()) {
                return stack;
            }
            return outboundInv.insertItem(slot - SLOTS, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot >= SLOTS || !canReceive()) {
                return ItemStack.EMPTY;
            }
            return receivedInv.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= SLOTS && canSend() && PackageItem.isPackage(stack);
        }
    };

    /**
     * The bottom face serves both directions: hoppers and chutes below can pull received parcels
     * out, and insert outbound parcels in. Received and outbound caches remain separate internally.
     */
    final IItemHandler bottomFace = new IItemHandler() {
        @Override
        public int getSlots() {
            return SLOTS * 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot < SLOTS ? receivedInv.getStackInSlot(slot) : outboundInv.getStackInSlot(slot - SLOTS);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < SLOTS || !PackageItem.isPackage(stack) || !canSend()) {
                return stack;
            }
            return outboundInv.insertItem(slot - SLOTS, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot >= SLOTS || !canReceive()) {
                return ItemStack.EMPTY;
            }
            return receivedInv.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= SLOTS && canSend() && PackageItem.isPackage(stack);
        }
    };

    private UUID freq;
    private String address = "";
    private DockMode mode = DockMode.RECEIVE;
    private UUID groupId = DockGroupDirectory.DEFAULT_GROUP_ID;
    private boolean linkUp;
    private int backlogOrders;
    private int inFlight;
    private long transmitStartedAt = -1;
    private String faultNote = "";
    private String fallbackNote = "";
    private UUID defaultDestinationNode;
    private UUID defaultReceivingGroupId = DockGroupDirectory.DEFAULT_GROUP_ID;
    private int priority;
    private String sendError = "";
    private long refusedAt;
    private boolean fallbackRefused;
    private long lastAlarmAt = Long.MIN_VALUE / 2;
    private boolean pushStalled;

    public DockBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new DockModeBehaviour(this));
        behaviours.add(new DockPriorityBehaviour(this));
    }

    /** Switches the routing direction without touching the tuned frequency or the local address. */
    public void setMode(DockMode newMode) {
        if (newMode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (mode == newMode) {
            return;
        }
        mode = newMode;
        sendError = "";
        clearFault();
        sync();
    }

    public UUID freq() {
        return freq;
    }

    public String address() {
        return address;
    }

    public DockMode mode() {
        return mode;
    }

    public UUID groupId() {
        return groupId;
    }

    public int priority() {
        return priority;
    }

    public UUID defaultDestinationNode() {
        return defaultDestinationNode;
    }

    public UUID defaultReceivingGroupId() {
        return defaultReceivingGroupId;
    }

    /** Where plain packages without a route of their own are sent. */
    public Optional<RemoteRoute> defaultRoute() {
        return defaultDestinationNode == null
                ? Optional.empty()
                : Optional.of(RemoteRoute.create(defaultDestinationNode, defaultReceivingGroupId));
    }

    public void setDefaultDestination(UUID node, UUID group) {
        defaultDestinationNode = node;
        defaultReceivingGroupId = group == null ? DockGroupDirectory.DEFAULT_GROUP_ID : group;
        sendError = "";
        sync();
    }

    public void clearDefaultDestination() {
        setDefaultDestination(null, null);
    }

    public void setPriority(int value) {
        priority = Math.max(0, Math.min(MAX_PRIORITY, value));
        sync();
    }

    public boolean canSend() {
        return mode == DockMode.SEND || mode == DockMode.BIDIRECTIONAL;
    }

    public boolean canReceive() {
        return mode == DockMode.RECEIVE || mode == DockMode.BIDIRECTIONAL;
    }

    public boolean isExport() {
        return canSend();
    }

    public boolean isImport() {
        return canReceive();
    }

    public boolean isFull() {
        return full(receivedInv);
    }

    public boolean isOutboundFull() {
        return full(outboundInv);
    }

    public int usedSlots() {
        return used(receivedInv);
    }

    public int outboundSlots() {
        return used(outboundInv);
    }

    public ItemStack transmittingStack() {
        for (int slot = 0; slot < outboundInv.getSlots(); slot++) {
            ItemStack stack = outboundInv.getStackInSlot(slot);
            if (PackageItem.isPackage(stack)) {
                return stack.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    public float transmitProgress(float partialTicks) {
        if (level == null || transmitStartedAt < 0 || transmittingStack().isEmpty()) {
            return -1;
        }
        return Math.min(1, Math.max(0,
                (level.getGameTime() + partialTicks - transmitStartedAt) / TRANSMIT_TICKS));
    }

    public void setExport(UUID freq) {
        this.freq = freq;
        mode = DockMode.SEND;
        sync();
    }

    public void setImport(String address) {
        freq = null;
        this.address = address == null ? "" : address;
        mode = DockMode.RECEIVE;
        sync();
    }

    public void setBidirectional(UUID freq, String address) {
        this.freq = freq;
        this.address = address == null ? "" : address;
        mode = DockMode.BIDIRECTIONAL;
        sync();
    }

    /** Default destination for parcels that carry no route of their own. Null means "not configured". */
    public void setGroupId(UUID groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        this.groupId = groupId;
        sync();
    }

    public void rejected() {
        sync();
    }

    public boolean insert(ItemStack pkg) {
        return canReceive() && insertInto(receivedInv, pkg);
    }

    private boolean insertOutbound(ItemStack pkg) {
        return canSend() && insertInto(outboundInv, pkg);
    }

    /** Hands an item or parcel to the fallback face. False when the buffer is already full. */
    public boolean offerFallback(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < fallbackInv.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = fallbackInv.insertItem(slot, remaining, false);
        }
        if (!remaining.isEmpty()) {
            noteReleaseRefused();
            sync();
            return false;
        }
        sync();
        return true;
    }

    public boolean hasFallbackRoom(int stacks) {
        int free = 0;
        for (int slot = 0; slot < fallbackInv.getSlots(); slot++) {
            if (fallbackInv.getStackInSlot(slot).isEmpty()) {
                free++;
            }
        }
        return free >= stacks;
    }

    public int fallbackSlots() {
        return used(fallbackInv);
    }

    /** Records the last reason something was pushed out of the fallback face. */
    public void noteFallback(String note) {
        String next = note == null ? "" : note;
        if (!next.equals(fallbackNote)) {
            fallbackNote = next;
            sync();
        }
    }

    /** Marks a hard problem that needs a human; cleared by a successful cycle or a player interaction. */
    public void noteFault(String note) {
        String next = note == null ? "" : note;
        if (!next.equals(faultNote)) {
            faultNote = next;
            sync();
        }
    }

    public void clearFault() {
        noteFault("");
    }

    /** Called by the return pump when it wanted to release a parcel here but the buffer was full. */
    public void noteReleaseRefused() {
        fallbackRefused = true;
        if (level != null) {
            refusedAt = level.getGameTime();
        }
    }

    /** True while the fallback face holds something that cannot leave the dock. */
    private boolean fallbackStuck() {
        if (pushStalled && !fallbackEmpty()) {
            return true;
        }
        return fallbackRefused && level != null
                && level.getGameTime() - refusedAt < BLOCKED_LINGER_TICKS;
    }

    public Component modeMessage() {
        Component text = Component.translatable(switch (mode) {
            case SEND -> "goggle.distantstock.mode.export";
            case RECEIVE -> "goggle.distantstock.mode.import";
            case BIDIRECTIONAL -> "goggle.distantstock.mode.bidirectional";
        });
        if (canSend() && freq != null) {
            text = text.copy().append(Component.literal(" " + RequesterData.shortFreq(freq))
                    .withStyle(ChatFormatting.AQUA));
        }
        if (canReceive()) {
            text = text.copy().append(Component.literal(" " + (address.isBlank() ? "*" : address))
                    .withStyle(ChatFormatting.WHITE));
        }
        if (canSend() && defaultDestinationNode != null) {
            text = text.copy().append(Component.literal(" -> "
                            + RequesterData.shortFreq(defaultDestinationNode))
                    .withStyle(ChatFormatting.GOLD));
        }
        return text;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity be) {
        be.tick();
        if (level.isClientSide) {
            return;
        }
        if (level.getGameTime() % 10 == 0) {
            LinkSnapshot.View snapshot = LinkSnapshot.view();
            be.linkUp = snapshot.linkUp() || (!snapshot.transerverAttached()
                    && !dev.distantstock.config.StockConfig.hasPeer());
            be.backlogOrders = LinkSnapshot.orderDepth;
            be.inFlight = LinkSnapshot.inFlight;
            if (be.canSend()) {
                be.pullAdjacent(level, pos);
            }
            be.drainFallback(level, pos);
            be.updateVisual();
            be.setChanged();
        }

        if (!be.canSend() || !be.linkUp || !be.sendError.isBlank() || be.transmittingStack().isEmpty()) {
            if (be.transmitStartedAt >= 0) {
                be.transmitStartedAt = -1;
                be.sync();
            }
            be.updateVisual();
            return;
        }
        if (be.transmitStartedAt < 0) {
            be.transmitStartedAt = level.getGameTime();
            be.sync();
            be.updateVisual();
            return;
        }
        if (level.getGameTime() - be.transmitStartedAt >= TRANSMIT_TICKS) {
            be.transmitStartedAt = -1;
            be.ship(level);
            be.sync();
            be.updateVisual();
        }
    }

    private void pullAdjacent(Level level, BlockPos pos) {
        if (isOutboundFull()) {
            return;
        }
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN) {
                // Never pull back what the fallback face just handed over.
                continue;
            }
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
                    pos.relative(direction), direction.getOpposite());
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!PackageItem.isPackage(handler.getStackInSlot(slot))) {
                    continue;
                }
                ItemStack taken = handler.extractItem(slot, 1, false);
                if (taken.isEmpty()) {
                    continue;
                }
                if (!insertOutbound(taken)) {
                    handler.insertItem(slot, taken, false);
                    continue;
                }
                if (isOutboundFull()) {
                    return;
                }
            }
        }
    }

    /** Pushes the fallback face contents into whatever accepts items below the dock. */
    private void drainFallback(Level level, BlockPos pos) {
        if (fallbackEmpty()) {
            pushStalled = false;
            return;
        }
        var below = level.getCapability(Capabilities.ItemHandler.BLOCK, pos.below(), Direction.UP);
        if (below == null) {
            pushStalled = true;
            return;
        }
        boolean moved = false;
        for (int slot = 0; slot < fallbackInv.getSlots(); slot++) {
            ItemStack stack = fallbackInv.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            for (int target = 0; target < below.getSlots() && !remaining.isEmpty(); target++) {
                remaining = below.insertItem(target, remaining, false);
            }
            if (remaining.getCount() != stack.getCount()) {
                moved = true;
                fallbackInv.setStackInSlot(slot, remaining);
            }
        }
        pushStalled = !moved && !fallbackEmpty();
    }

    private void ship(Level level) {
        for (int slot = 0; slot < outboundInv.getSlots(); slot++) {
            ItemStack stack = outboundInv.getStackInSlot(slot);
            if (!PackageItem.isPackage(stack)) {
                continue;
            }
            String nbt = PackageCodec.encode(stack, level.registryAccess());
            if (nbt.isEmpty()) {
                continue;
            }
            String destinationAddress = PackageItem.getAddress(stack);
            Optional<RemoteRoute> packageRoute = RemoteRouteData.read(stack);
            Optional<RemoteRoute> orderRoute = Optional.empty();
            if (packageRoute.isEmpty() && PackageItem.hasOrderData(stack) && level.getServer() != null) {
                orderRoute = OrderRouteDirectory.get(level.getServer()).find(PackageItem.getOrderId(stack));
            }
            Optional<RemoteRoute> route = RouteResolution.resolve(packageRoute, orderRoute, defaultRoute());
            if (route.isEmpty()) {
                if (dev.distantstock.link.TranserverBridge.attachedApi() == null) {
                    // Legacy peers without Transerver still resolve the other side by address themselves.
                    if (LinkQueues.offerOutboundPackage(new LinkQueues.Parcel(
                            nbt, destinationAddress, "", DockGroupDirectory.DEFAULT_GROUP_ID))) {
                        outboundInv.extractItem(slot, 1, false);
                        dev.distantstock.link.LinkClient.wake();
                    }
                    break;
                }
                // No route at all: hold the parcel and report it. A target is never guessed.
                sendError = "goggle.distantstock.send.no_route";
                break;
            }
            if (!route.equals(packageRoute)) {
                RemoteRouteData.write(stack, route.get());
                nbt = PackageCodec.encode(stack, level.registryAccess());
                if (nbt.isEmpty()) {
                    continue;
                }
            }
            if (level.getServer() != null) {
                try {
                    ParcelEscrow.get(level.getServer()).hold(
                            stack, destinationAddress, route.get().destinationNodeId().toString(),
                            route.get().receivingDockGroupId(),
                            level.dimension().location().toString(), worldPosition, level.registryAccess());
                    outboundInv.extractItem(slot, 1, false);
                    // Route consumed: the escrow now owns the parcel and its destination.
                    if (PackageItem.hasOrderData(stack)) {
                        OrderRouteDirectory.get(level.getServer()).consume(PackageItem.getOrderId(stack));
                    }
                    sendError = "";
                } catch (IllegalArgumentException ignored) {
                }
            }
            break;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.dock");
        GoggleText.line(tip, switch (mode) {
            case SEND -> "goggle.distantstock.mode.export";
            case RECEIVE -> "goggle.distantstock.mode.import";
            case BIDIRECTIONAL -> "goggle.distantstock.mode.bidirectional";
        });
        GoggleText.line(tip, "goggle.distantstock.status." + status().getSerializedName());
        if (canSend() && freq != null) {
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
            GoggleText.line(tip, "goggle.distantstock.backlog", backlogOrders, inFlight);
        }
        if (canReceive()) {
            GoggleText.line(tip, "goggle.distantstock.address", address.isBlank() ? "*" : address);
            if (isFull()) {
                GoggleText.line(tip, "goggle.distantstock.slots.full");
            } else {
                GoggleText.line(tip, "goggle.distantstock.slots", usedSlots(), SLOTS);
            }
            GoggleText.line(tip, "goggle.distantstock.priority", priority);
        }
        if (canSend()) {
            if (defaultDestinationNode == null) {
                GoggleText.line(tip, "goggle.distantstock.target.none");
            } else {
                GoggleText.line(tip, "goggle.distantstock.target",
                        RequesterData.shortFreq(defaultDestinationNode),
                        RequesterData.shortFreq(defaultReceivingGroupId));
            }
            GoggleText.line(tip, "goggle.distantstock.outbound", outboundSlots(), SLOTS);
        }
        if (!sendError.isBlank()) {
            GoggleText.value(tip, sendError, ChatFormatting.RED);
        }
        if (fallbackSlots() > 0) {
            GoggleText.line(tip, "goggle.distantstock.fallback.slots", fallbackSlots(), SLOTS);
        }
        if (!fallbackNote.isBlank()) {
            GoggleText.line(tip, "goggle.distantstock.fallback.last", Component.translatable(fallbackNote));
        }
        if (!faultNote.isBlank()) {
            GoggleText.value(tip, faultNote, ChatFormatting.RED);
        }
        GoggleText.value(tip, linkUp ? "goggle.distantstock.link.up" : "goggle.distantstock.link.down",
                linkUp ? ChatFormatting.GREEN : ChatFormatting.RED);
        return true;
    }

    /** Current lamp state, derived from the live dock. */
    public DockStatus status() {
        BlockState state = getBlockState();
        return state.hasProperty(DockBlock.STATUS) ? state.getValue(DockBlock.STATUS) : DockStatus.INACTIVE;
    }

    private DockStatus resolveStatus() {
        if (!faultNote.isBlank()) {
            return DockStatus.FAULT;
        }
        if (pushStalled && !fallbackEmpty()) {
            return DockStatus.BLOCKED;
        }
        if (!sendError.isBlank()) {
            return DockStatus.BLOCKED;
        }
        if (fallbackStuck()) {
            return DockStatus.BLOCKED;
        }
        if (transmitStartedAt >= 0 && !transmittingStack().isEmpty()) {
            return DockStatus.SENDING;
        }
        if (!linkUp) {
            return DockStatus.INACTIVE;
        }
        return DockStatus.STANDBY;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedDocks.add(this);
        if (level != null && !level.isClientSide) {
            DockGroupDirectory.get(level.getServer());
        }
    }

    @Override
    public void destroy() {
        LoadedDocks.remove(this);
        super.destroy();
    }

    @Override
    public void remove() {
        LoadedDocks.remove(this);
        super.remove();
    }

    @Override
    public void onChunkUnloaded() {
        LoadedDocks.remove(this);
        super.onChunkUnloaded();
    }

    /** Spills every stored item when the dock is broken, so breaking it can never destroy a parcel. */
    public void spillContents() {
        if (level == null || level.isClientSide) {
            return;
        }
        for (ItemStackHandler inventory : List.of(receivedInv, outboundInv, fallbackInv)) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, worldPosition, stack);
                    inventory.setStackInSlot(slot, ItemStack.EMPTY);
                }
            }
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        tag.putString("Address", address);
        tag.putString("Mode", mode.name().toLowerCase(Locale.ROOT));
        tag.putUUID("DockGroup", groupId);
        tag.put("ReceivedInv", receivedInv.serializeNBT(registries));
        tag.put("OutboundInv", outboundInv.serializeNBT(registries));
        tag.put("FallbackInv", fallbackInv.serializeNBT(registries));
        tag.putBoolean("LinkUp", linkUp);
        tag.putLong("TransmitStartedAt", transmitStartedAt);
        tag.putString("FaultNote", faultNote);
        tag.putString("FallbackNote", fallbackNote);
        if (defaultDestinationNode != null) {
            tag.putUUID("DefaultDestination", defaultDestinationNode);
        }
        tag.putUUID("DefaultGroup", defaultReceivingGroupId);
        tag.putInt("Priority", priority);
        tag.putString("SendError", sendError);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        address = tag.getString("Address");
        mode = readMode(tag.getString("Mode"), freq != null ? DockMode.SEND : DockMode.RECEIVE);
        groupId = tag.hasUUID("DockGroup") ? tag.getUUID("DockGroup") : DockGroupDirectory.DEFAULT_GROUP_ID;
        if (tag.contains("ReceivedInv")) {
            receivedInv.deserializeNBT(registries, tag.getCompound("ReceivedInv"));
        }
        if (tag.contains("OutboundInv")) {
            outboundInv.deserializeNBT(registries, tag.getCompound("OutboundInv"));
        }
        if (tag.contains("FallbackInv")) {
            fallbackInv.deserializeNBT(registries, tag.getCompound("FallbackInv"));
        }
        if (tag.contains("Inv") && !tag.contains("ReceivedInv") && !tag.contains("OutboundInv")) {
            (mode == DockMode.SEND ? outboundInv : receivedInv)
                    .deserializeNBT(registries, tag.getCompound("Inv"));
        }
        linkUp = tag.getBoolean("LinkUp");
        transmitStartedAt = tag.contains("TransmitStartedAt") ? tag.getLong("TransmitStartedAt") : -1;
        faultNote = tag.getString("FaultNote");
        fallbackNote = tag.getString("FallbackNote");
        defaultDestinationNode = tag.hasUUID("DefaultDestination") ? tag.getUUID("DefaultDestination") : null;
        defaultReceivingGroupId = tag.hasUUID("DefaultGroup")
                ? tag.getUUID("DefaultGroup") : DockGroupDirectory.DEFAULT_GROUP_ID;
        priority = Math.max(0, Math.min(MAX_PRIORITY, tag.getInt("Priority")));
        sendError = tag.getString("SendError");
    }

    private void updateVisual() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(DockBlock.STATUS)) {
            return;
        }
        DockStatus previous = state.getValue(DockBlock.STATUS);
        DockStatus next = resolveStatus();
        if (previous != next) {
            level.setBlock(worldPosition, state.setValue(DockBlock.STATUS, next), 3);
            if (next == DockStatus.FAULT || next == DockStatus.BLOCKED) {
                playAlarm(next);
                lastAlarmAt = level.getGameTime();
            }
        } else if (next == DockStatus.BLOCKED
                && level.getGameTime() - lastAlarmAt >= BLOCKED_ALARM_TICKS) {
            // Keep reminding while the dock is jammed, instead of only ringing once when it happens.
            playAlarm(next);
            lastAlarmAt = level.getGameTime();
        }
    }

    private void playAlarm(DockStatus status) {
        if (level == null) {
            return;
        }
        // A soft mechanical flap for a jammed dock, and Create's deny blip only for real faults.
        (status == DockStatus.FAULT ? AllSoundEvents.DENY : AllSoundEvents.FUNNEL_FLAP)
                .playOnServer(level, worldPosition);
    }

    private void sync() {
        setChanged();
        updateVisual();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void contentsChanged() {
        sendError = "";
        setChanged();
        updateVisual();
    }

    private boolean fallbackEmpty() {
        return used(fallbackInv) == 0;
    }

    private boolean insertInto(ItemStackHandler inventory, ItemStack packageStack) {
        ItemStack remaining = packageStack.copy();
        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        if (remaining.getCount() < packageStack.getCount()) {
            sync();
        }
        return remaining.isEmpty();
    }

    /** Unlike the parcel buffers this one holds plain items too, so it keeps the default stack limit. */
    private static ItemStackHandler fallbackInventory(Runnable changed) {
        return new ItemStackHandler(SLOTS) {
            @Override
            protected void onContentsChanged(int slot) {
                changed.run();
            }
        };
    }

    private static ItemStackHandler inventory(Runnable changed) {
        return new ItemStackHandler(SLOTS) {
            @Override
            protected void onContentsChanged(int slot) {
                changed.run();
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return PackageItem.isPackage(stack);
            }
        };
    }

    private static boolean full(ItemStackHandler inventory) {
        return used(inventory) >= inventory.getSlots();
    }

    private static int used(ItemStackHandler inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static DockMode readMode(String value, DockMode fallback) {
        try {
            return DockMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
