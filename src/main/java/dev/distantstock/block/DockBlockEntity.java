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
import dev.distantstock.routing.RemoteRouteData;
import dev.distantstock.link.ParcelEscrow;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerBilling;
import dev.distantstock.routing.DockMode;
import dev.distantstock.routing.OrderRouteDirectory;
import dev.distantstock.routing.RemoteRoute;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RouteResolution;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
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

public final class DockBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation, WorldlyContainer {
    public static final int SLOTS = 1;
    public static final int MAX_PRIORITY = 5;
    public static final int TRANSMIT_TICKS = 30;
    /** How long a blocked fallback face keeps reporting itself after the last refused release. */
    private static final long BLOCKED_LINGER_TICKS = 200;
    /** How often a jammed dock reminds the player with a sound. */
    private static final long BLOCKED_ALARM_TICKS = 120;
    /** The reason a parcel is held when its tower cannot pay for it. */
    private static final String ETHER_ERROR = "goggle.distantstock.send.no_ether";
    /**
     * How long a failed payment keeps reporting itself before the dock tries again.
     *
     * <p>Without it the dock would either retry twenty times a second or, like the no-route case,
     * wait for a player to touch its inventory. The first is noise; the second is worse, because
     * the tower being refilled is a reason to send, not a reason to keep holding.
     */
    private static final long ETHER_RETRY_TICKS = 200;

    private final ItemStackHandler receivedInv = inventory(this::contentsChanged);
    private final ItemStackHandler outboundInv = inventory(this::contentsChanged);
    /** Items and parcels the dock cannot handle, waiting for room below the fallback face. */
    private final ItemStackHandler fallbackInv = fallbackInventory(this::contentsChanged);

    /** One exposed package slot; received parcels may be extracted, outgoing parcels inserted. */
    final IItemHandler automation = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? (receivedInv.getStackInSlot(0).isEmpty()
                    ? outboundInv.getStackInSlot(0) : receivedInv.getStackInSlot(0)) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || !PackageItem.isPackage(stack) || occupied()) {
                return stack;
            }
            return outboundInv.insertItem(0, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || !canReceive() || receiving()) {
                return ItemStack.EMPTY;
            }
            return receivedInv.extractItem(0, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && PackageItem.isPackage(stack);
        }
    };

    /** Takes one parcel into the bay, answering whether it fit. The bay holds exactly one. */
    public boolean acceptParcel(ItemStack stack) {
        return PackageItem.isPackage(stack)
                && automation.insertItem(0, stack.copyWithCount(1), false).isEmpty();
    }

    /**
     * The bottom face serves both directions: hoppers and chutes below can pull received parcels
     * out, and insert outbound parcels in. Received and outbound caches remain separate internally.
     */
    final IItemHandler bottomFace = new IItemHandler() {
        @Override
        public int getSlots() {
            return automation.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return automation.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return automation.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return automation.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return automation.isItemValid(slot, stack);
        }
    };

    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty() { return !occupied(); }
    @Override public ItemStack getItem(int slot) { return automation.getStackInSlot(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return automation.extractItem(slot, amount, false); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return automation.extractItem(slot, 1, false); }
    @Override public void setItem(int slot, ItemStack stack) {
        if (slot == 0 && !stack.isEmpty()) automation.insertItem(0, stack, false);
    }
    @Override public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getCenter()) <= 64;
    }
    @Override public void clearContent() {
        receivedInv.setStackInSlot(0, ItemStack.EMPTY);
        outboundInv.setStackInSlot(0, ItemStack.EMPTY);
        fallbackInv.setStackInSlot(0, ItemStack.EMPTY);
    }
    @Override public int[] getSlotsForFace(Direction side) { return new int[]{0}; }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return automation.isItemValid(slot, stack); }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot == 0 && canReceive() && !receiving() && !receivedInv.getStackInSlot(0).isEmpty();
    }

    private UUID freq;
    private RemoteNetworkId networkId;
    private String address = "";
    private DockMode mode = DockMode.RECEIVE;
    private UUID groupId = DockGroupDirectory.DEFAULT_GROUP_ID;
    private boolean linkUp;
    private int backlogOrders;
    private int inFlight;
    private long transmitStartedAt = -1;
    private long receiveStartedAt = -1;
    /**
     * Ten minutes of parcel counts, for the monitor. Counting lives here because this is the only
     * place that sees both ends of a transfer: a parcel leaves one dock and arrives at another, and
     * neither the tower above them nor the escrow between them knows both halves.
     */
    private final dev.distantstock.routing.DockTraffic traffic = new dev.distantstock.routing.DockTraffic();
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
    /** When the tower last failed to pay for a parcel, for the retry window. */
    private long etherRefusedAt = Long.MIN_VALUE / 2;

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

    /**
     * Whether this dock may send, which now also means "a tower carries it".
     *
     * <p>The gate lives here because this is where every path already asks: shipping, pulling a
     * parcel from a neighbour, handing one over, and the selection that picks a dock for an
     * incoming parcel all read one of these two methods. It is deliberately cheap — the answer is
     * a hash lookup in a snapshot, rebuilt once a second, not a search.
     *
     * <p>A world without towers answers yes for everything, which is the promise: adding towers to
     * a save must not stop the docks that were already there.
     */
    public boolean canSend() {
        return (mode == DockMode.SEND || mode == DockMode.BIDIRECTIONAL)
                && TowerActivation.active(level, worldPosition);
    }

    public boolean canReceive() {
        return receivingMode() && TowerActivation.active(level, worldPosition);
    }

    /** The tuned direction alone, without the tower gate. */
    private boolean receivingMode() {
        return mode == DockMode.RECEIVE || mode == DockMode.BIDIRECTIONAL;
    }

    public boolean isExport() {
        return canSend();
    }

    public boolean isImport() {
        return canReceive();
    }

    public boolean isFull() {
        return occupied();
    }

    public boolean isOutboundFull() {
        return occupied();
    }

    private boolean occupied() {
        return used(receivedInv) + used(outboundInv) + used(fallbackInv) > 0;
    }

    public boolean receiving() {
        return level != null && receiveStartedAt >= 0
                && level.getGameTime() - receiveStartedAt < TRANSMIT_TICKS;
    }

    public float receiveProgress(float partialTicks) {
        return receiveStartedAt < 0 || level == null ? -1
                : Math.min(1, Math.max(0, (level.getGameTime() + partialTicks - receiveStartedAt) / TRANSMIT_TICKS));
    }

    public ItemStack displayedStack() {
        for (ItemStackHandler inv : List.of(receivedInv, outboundInv, fallbackInv)) {
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                if (PackageItem.isPackage(inv.getStackInSlot(slot))) return inv.getStackInSlot(slot).copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
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

    public void setNetwork(RemoteNetworkId network) {
        this.networkId = network;
        this.freq = network == null ? null : network.createFrequency();
        mode = network == null ? DockMode.RECEIVE : DockMode.SEND;
        sendError = "";
        sync();
    }

    /** Removes the Create network binding without discarding the dock's address or group. */
    public void clearNetwork() {
        networkId = null;
        freq = null;
        if (mode == DockMode.SEND) {
            mode = DockMode.RECEIVE;
        } else if (mode == DockMode.BIDIRECTIONAL) {
            mode = DockMode.RECEIVE;
        }
        sendError = "";
        clearFault();
        sync();
    }

    /**
     * Transfers one received parcel only after the player's inventory can accept it whole.
     *
     * <p>Deliberately does not ask {@link #canReceive()}. The gate is about what the machine does
     * on the network, and a dock standing outside its tower's reach still has to be able to hand a
     * parcel back by hand — otherwise the only way at it is to break the block, and a tower going
     * dark would be a way to lose goods rather than a way to pause a factory.
     */
    public boolean takeReceived(net.minecraft.world.entity.player.Player player) {
        if (player == null || !receivingMode() || receiving()) return false;
        for (int slot = 0; slot < receivedInv.getSlots(); slot++) {
            ItemStack parcel = receivedInv.getStackInSlot(slot);
            if (parcel.isEmpty() || !canFit(player, parcel)) continue;
            ItemStack taken = receivedInv.extractItem(slot, 1, false);
            if (taken.isEmpty()) return false;
            if (player.getInventory().add(taken)) {
                sync();
                return true;
            }
            receivedInv.setStackInSlot(slot, taken);
            return false;
        }
        return false;
    }

    /**
     * Takes a parcel back out of a dock that cannot send it.
     *
     * <p>The way out of a jammed dock. A parcel that is refused — no receiving group, no route, a
     * blocked fallback face — stays in the dock for ever otherwise, and the only other way to get it
     * back is to break the block. The player asked for exactly this: sneaking with an empty hand did
     * nothing, so the parcel was stuck with no way to reach it.
     *
     * <p>Not while a send is in flight, though. During the transmit window the parcel belongs to the
     * transport, and pulling it out mid-handover is how a parcel gets duplicated.
     */
    public boolean takeStuck(net.minecraft.world.entity.player.Player player) {
        if (player == null || !transmittingStack().isEmpty()) {
            return false;
        }
        if (status() != DockStatus.BLOCKED && status() != DockStatus.FAULT) {
            return false;
        }
        return handOver(outboundInv, player) || handOver(fallbackInv, player);
    }

    /** One slot's worth, into the player's inventory, or nothing if it does not fit. */
    private static boolean handOver(ItemStackHandler inventory,
                                    net.minecraft.world.entity.player.Player player) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack parcel = inventory.getStackInSlot(slot);
            if (parcel.isEmpty() || !canFit(player, parcel)) {
                continue;
            }
            ItemStack taken = inventory.extractItem(slot, 1, false);
            if (taken.isEmpty()) {
                return false;
            }
            if (player.getInventory().add(taken)) {
                return true;
            }
            inventory.setStackInSlot(slot, taken);
            return false;
        }
        return false;
    }

    private static boolean canFit(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing.isEmpty()) remaining -= stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    public void setExport(UUID freq) {
        this.networkId = null;
        this.freq = freq;
        mode = DockMode.SEND;
        sync();
    }

    public void setImport(String address) {
        networkId = null;
        freq = null;
        this.address = address == null ? "" : address;
        mode = DockMode.RECEIVE;
        sync();
    }

    public void setBidirectional(UUID freq, String address) {
        this.networkId = null;
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
        if (!canReceive() || occupied() || pkg.getCount() != 1 || !insertInto(receivedInv, pkg)) return false;
        receiveStartedAt = level == null ? -1 : level.getGameTime();
        if (level != null && !level.isClientSide) {
            traffic.noteReceived(level.getGameTime());
        }
        sync();
        return true;
    }

    private boolean insertOutbound(ItemStack pkg) {
        return canSend() && !occupied() && pkg.getCount() == 1 && insertInto(outboundInv, pkg);
    }

    /**
     * A dock group's name, or a short form rather than nothing when the file cannot be read.
     *
     * <p>Read from the directory every time the goggles are drawn. That is a map lookup on a file
     * that is already in memory, and the name it returns is the one the player recognises.
     */
    private String groupName(java.util.UUID group) {
        if (group == null) {
            return "—";
        }
        if (level == null || level.getServer() == null) {
            return RequesterData.shortFreq(group);
        }
        return dev.distantstock.routing.DockGroupDirectory.get(level.getServer())
                .find(group)
                .map(dev.distantstock.routing.DockGroup::name)
                .orElse(RequesterData.shortFreq(group));
    }

    /** Hands an item or parcel to the fallback face. False when the buffer is already full. */
    public boolean offerFallback(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (occupied() || (PackageItem.isPackage(stack) && stack.getCount() > 1)) return false;
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
        return stacks == 0 || (stacks == 1 && !occupied());
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
        if (be.receiveStartedAt >= 0 && !be.receiving()) {
            be.receiveStartedAt = -1;
            be.sync();
        }
        if (ETHER_ERROR.equals(be.sendError)
                && level.getGameTime() - be.etherRefusedAt >= ETHER_RETRY_TICKS) {
            // The tower was empty, not wrong: try again now that it has had time to be refilled.
            be.sendError = "";
            be.sync();
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

        if (!be.canSend() || !be.linkUp || !be.sendError.isBlank() || be.transmittingStack().isEmpty()
                || used(be.receivedInv) > 0 || !be.fallbackEmpty()) {
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
                // Legacy peers without Transerver resolve the other side by address themselves, but
                // only when the parcel actually carries one. Handing a parcel with neither route nor
                // address to the queue would drop it, so it has to stay here and report instead.
                //
                // The legacy link has to actually be running, and that is not the same question as
                // whether a parcel has an address. A packager's parcel always has one, so on the
                // default transport mode — Transerver, no HTTP link — this branch used to take a
                // parcel that had nowhere to go, remove it from the dock and post it into a queue
                // nothing drains. The parcel was gone and the dock was empty, which reads exactly
                // like being sent into the void, because that is what it was.
                if (dev.distantstock.link.TranserverBridge.attachedApi() == null
                        && dev.distantstock.config.StockConfig.useLegacy()
                        && dev.distantstock.config.StockConfig.hasPeer()
                        && !destinationAddress.isBlank()) {
                    // Pay before the queue takes the parcel: once it is in there, there is no way to
                    // take it back, and a parcel that leaves without paying is the one outcome the
                    // billing rules do not allow. Nothing between the two calls can fail but the
                    // queue being full, and that case is the refund below.
                    if (!TowerBilling.charge(level, worldPosition)) {
                        noteEtherRefused(level);
                        break;
                    }
                    if (LinkQueues.offerOutboundPackage(new LinkQueues.Parcel(
                            nbt, destinationAddress, "", DockGroupDirectory.DEFAULT_GROUP_ID))) {
                        outboundInv.extractItem(slot, 1, false);
                        noteTraffic(level);
                        dev.distantstock.link.LinkClient.wake();
                    } else {
                        TowerBilling.refund(level, worldPosition);
                    }
                    break;
                }
                // No route at all: hold the parcel and report it. A target is never guessed.
                sendError = "goggle.distantstock.send.no_route";
                break;
            }
            // A route that names no group is not a destination either, and this is the case the old
            // check missed. Pointing a terminal at a dock writes whatever group the terminal holds,
            // and a terminal holding none writes the *default* group — the one every dock belongs to
            // and which means "whoever is listening". A player who set an address and nothing else
            // therefore had a parcel posted to a wildcard group on some node, and it landed in
            // whichever receiving dock matched while the dock reported nothing, because a route
            // existed.
            //
            // Only for parcels leaving this node: a parcel staying here still has the local default
            // group to land in, which is the ordinary in-server case and always has been. The order
            // path draws the same line in TranserverOrderService.destinationNode — an order that
            // named no group wants its goods back where they came from.
            if (!dev.distantstock.link.TranserverBridge.isLocal(route.get().destinationNodeId().toString())
                    && DockGroupDirectory.DEFAULT_GROUP_ID.equals(route.get().receivingDockGroupId())) {
                sendError = "goggle.distantstock.send.no_group";
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
                if (!TowerBilling.charge(level, worldPosition)) {
                    noteEtherRefused(level);
                    break;
                }
                try {
                    ParcelEscrow.get(level.getServer()).hold(
                            stack, destinationAddress, route.get().destinationNodeId().toString(),
                            route.get().receivingDockGroupId(),
                            level.dimension().location().toString(), worldPosition,
                            level.getGameTime(), level.registryAccess());
                    outboundInv.extractItem(slot, 1, false);
                    noteTraffic(level);
                    // Other Create packagers/fragments of this order may still be on their way.
                    OrderRouteDirectory.get(level.getServer()).packageEscrowed(stack);
                    sendError = "";
                } catch (IllegalArgumentException ignored) {
                    // The escrow refused the parcel, so it never left. The ether goes back with it.
                    TowerBilling.refund(level, worldPosition);
                }
            }
            break;
        }
    }

    /**
     * Tells the tower above this dock that something is crossing it.
     *
     * <p>Only at the two points where the parcel has actually left the dock. Pinging on the way in
     * would light the tower for a parcel that is still sitting in the slot, and the light is
     * supposed to mean "in flight", not "busy".
     */
    private void noteTraffic(Level level) {
        if (level.isClientSide) {
            return;
        }
        traffic.noteSent(level.getGameTime());
        dev.distantstock.routing.TowerSystem.TowerId carrier =
                TowerActivation.carrier(level, worldPosition);
        if (carrier != null) {
            TowerBeacon.ping(level, BlockPos.of(carrier.packedPos()));
        }
    }

    /** The last ten minutes of this dock's parcels, for the monitor's readout. */
    public TowerActivation.Traffic traffic() {
        return traffic.window(level == null ? 0 : level.getGameTime());
    }

    /**
     * Holds the parcel and says why, in the two places a player looks: the goggle readout and the
     * lamp, which reports a send error as blocked. Neither the parcel nor the ether moves.
     */
    private void noteEtherRefused(Level level) {
        etherRefusedAt = level.getGameTime();
        sendError = ETHER_ERROR;
        sync();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.dock");
        // First, before every number that only means something when the dock is on. A dock outside
        // every tower's reach reports zero throughput, an empty backlog and a target it never
        // reaches, and an operator reading that list top to bottom would go looking at the network
        // before they thought to look at the tower.
        if (!TowerActivation.active(level, worldPosition)) {
            GoggleText.value(tip, "goggle.distantstock.tower.inactive", net.minecraft.ChatFormatting.RED);
        }
        GoggleText.line(tip, switch (mode) {
            case SEND -> "goggle.distantstock.mode.export";
            case RECEIVE -> "goggle.distantstock.mode.import";
            case BIDIRECTIONAL -> "goggle.distantstock.mode.bidirectional";
        });
        GoggleText.line(tip, "goggle.distantstock.status." + status().getSerializedName());
        // Which system this dock belongs to, by name. Two systems on one server are two names in a
        // list, and without this line the only way to tell which one a dock is in is to remember.
        GoggleText.line(tip, "goggle.distantstock.group", groupName(groupId));
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
                // The system by name, not by a uuid prefix: a player typed a name to choose it and
                // has no way to map a prefix back to one.
                GoggleText.line(tip, "goggle.distantstock.target",
                        RequesterData.shortFreq(defaultDestinationNode),
                        groupName(defaultReceivingGroupId));
            }
            GoggleText.line(tip, "goggle.distantstock.outbound", outboundSlots(), SLOTS);
        }
        if (!sendError.isBlank()) {
            // Gold rather than red: the dock is not broken, it is waiting for the operator to say
            // where the parcel goes, and the lamp is blinking orange for the same reason.
            GoggleText.value(tip, sendError, ChatFormatting.GOLD);
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
        // The other way a dock leaves the world. Breaking it by hand goes through the block's
        // playerWillDestroy first, but a wrench in sneak mode, an explosion, a piston and /setblock
        // all remove the block without ever calling it — and every one of them takes the parcels in
        // these three slots with it unless they are dropped here. The two together are safe to
        // have: the first one empties the slots, so the second finds nothing and drops nothing.
        spillContents();
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
        if (networkId != null) {
            tag.put("RemoteNetwork", networkId.save());
        }
        tag.putString("Address", address);
        tag.putString("Mode", mode.name().toLowerCase(Locale.ROOT));
        tag.putUUID("DockGroup", groupId);
        tag.put("ReceivedInv", receivedInv.serializeNBT(registries));
        tag.put("OutboundInv", outboundInv.serializeNBT(registries));
        tag.put("FallbackInv", fallbackInv.serializeNBT(registries));
        tag.putBoolean("LinkUp", linkUp);
        tag.putLong("TransmitStartedAt", transmitStartedAt);
        tag.putLong("ReceiveStartedAt", receiveStartedAt);
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
        networkId = tag.contains("RemoteNetwork")
                ? RemoteNetworkId.read(tag.getCompound("RemoteNetwork")).orElse(null) : null;
        if (networkId != null) {
            freq = networkId.createFrequency();
        }
        address = tag.getString("Address");
        mode = readMode(tag.getString("Mode"), freq != null ? DockMode.SEND : DockMode.RECEIVE);
        groupId = tag.hasUUID("DockGroup") ? tag.getUUID("DockGroup") : DockGroupDirectory.DEFAULT_GROUP_ID;
        if (tag.contains("ReceivedInv")) {
            // ItemStackHandler restores the saved Size. Keep legacy extra slots intact;
            // occupied() blocks all new input until those parcels have been drained.
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
        receiveStartedAt = tag.contains("ReceiveStartedAt") ? tag.getLong("ReceiveStartedAt") : -1;
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
        if (level == null || !level.isClientSide) sync();
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
