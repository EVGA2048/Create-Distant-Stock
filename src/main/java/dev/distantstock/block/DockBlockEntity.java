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

    /**
     * 三个格子朝外：收到的一件、卡住的发出件、回退面上的东西。
     *
     * <p>以前只有一个格子，而且只能从「收到」那一格取 —— 于是**卡住的包裹和回退面上的东西，
     * 溜槽和漏斗都看不见也拿不走**，只能玩家自己空手右键。玩家把智能溜槽放在下面想自动回收，
     * 结果什么也拿不到，报的正是这个。
     *
     * <p>插入仍然只有 0 号格（等于「交给这个港发出去」），行为和以前一模一样；多出来的是可以取走
     * 的东西 —— 修机器的自动化不该比玩家手动能做的更少。
     */
    final IItemHandler automation = new IItemHandler() {
        /** 0 收到 / 1 发出 / 2 回退面。 */
        private static final int SLOTS = 3;

        @Override
        public int getSlots() {
            return SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return switch (slot) {
                case 0 -> receivedInv.getStackInSlot(0);
                case 1 -> outboundIsStranded() ? outboundInv.getStackInSlot(0) : ItemStack.EMPTY;
                case 2 -> fallbackInv.getStackInSlot(0);
                default -> ItemStack.EMPTY;
            };
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 这里挡的是**塔的硬门槛**那一条，不是模式：不在一座正在运行的塔的范围内的港，传送带和
            // 漏斗也不许往里塞东西。少了这一句，硬门槛就漏了半条 —— 玩家手工递进去会被拒，机器递进去
            // 却能进，而"没塔不能用"的意思显然包括机器那一半。
            //
            // 模式**不在这里**判。把一个包裹塞进收货港是本来就有的一条路：它进发出格、发不出去、
            // 于是被判成"卡住"（见 outboundIsStranded），玩家空手右键或溜槽随时能拿走。按 canSend()
            // 挡会把这条路一起堵死，而它跟有没有塔毫无关系。
            if (slot != 0 || !PackageItem.isPackage(stack) || occupied() || !carriedByTower()) {
                return stack;
            }
            return outboundInv.insertItem(0, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return switch (slot) {
                case 0 -> canReceive() && !receiving()
                        ? receivedInv.extractItem(0, amount, simulate) : ItemStack.EMPTY;
                case 1 -> outboundIsStranded()
                        ? outboundInv.extractItem(0, amount, simulate) : ItemStack.EMPTY;
                case 2 -> fallbackInv.extractItem(0, amount, simulate);
                default -> ItemStack.EMPTY;
            };
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && PackageItem.isPackage(stack) && carriedByTower();
        }
    };

    /**
     * 发出格里那件东西是不是"走不了"了 —— 手动取件和溜槽取件共用这一条规矩。
     *
     * <p>"卡住"以前只认 BLOCKED/FAULT 两种灯，太窄了：**收货模式的港永远不会发它**（玩家把包裹
     * 塞进一个收货港），**没接网络的港也发不出去**（灯是 INACTIVE），这两种情况下包裹就那样躺在
     * 里面，玩家空手右键拿不出来、溜槽也拿不走 —— 报的就是"异常包裹取不走"。
     *
     * <p>真正要保护的是另一件事：一件**正在等港发出去**的包裹不能被顺走，否则"放进去、它自己会走"
     * 这件事就不成立了，而且传输窗口里取走会复制。所以判据是"它还有没有机会出去"，不是"灯是什么
     * 颜色"。两处调用必须是同一个判断：手动能拿、溜槽拿不走，是比"都拿不走"更让人困惑的状态。
     */
    public boolean outboundIsStranded() {
        if (transmitting() || outboundInv.getStackInSlot(0).isEmpty()) {
            return false;
        }
        if (!canSend()) {
            // 收货（或只收货）的港：这个包裹在这里没有出路。
            return true;
        }
        DockStatus status = status();
        return status == DockStatus.BLOCKED || status == DockStatus.FAULT
                || status == DockStatus.INACTIVE;
    }

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

    // 原版漏斗走的是 Container 这条路，视图必须和上面那个 IItemHandler 是同一个 —— 否则
    // 智能溜槽能拿走的、普通漏斗拿不走，同一台机器两种脾气。
    @Override public int getContainerSize() { return automation.getSlots(); }
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
    @Override public int[] getSlotsForFace(Direction side) { return new int[]{0, 1, 2}; }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return automation.isItemValid(slot, stack); }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        // 直接问那一格：能模拟取出就是能取。写死条件的话，三个格子就要写三遍，而且迟早漏一个。
        return !automation.extractItem(slot, 1, true).isEmpty();
    }

    private UUID freq;
    private RemoteNetworkId networkId;
    private DockMode mode = DockMode.RECEIVE;
    private UUID groupId = DockGroupDirectory.DEFAULT_GROUP_ID;
    /**
     * 组名和组里的港数，**只给客户端看的副本**。
     *
     * <p>服务端从不读它们，读的时候现算（{@link #groupDisplayName} / {@link #groupDockCount}）；
     * 客户端只读它们。分区明确是为了不让"上一次同步过来的值"有机会被当成答案。
     */
    private String syncedGroupName;
    private int syncedGroupDocks;
    /** 同上，发货目的地的组名（「发往」那一行画的是它）。 */
    private String syncedTargetGroupName;
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

    /** Switches the routing direction without touching the tuned frequency or the receiving group. */
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
     * Whether a running tower carries this dock. The one gate every path shares.
     *
     * <p>It is deliberately cheap — a hash lookup in a snapshot rebuilt once a second, not a search.
     *
     * <p><b>A save with no tower in range has no working docks.</b> That is the rule the user set
     * (2026-09-17): distant machinery has to stand inside a running tower's range, the way Create's
     * machines want rotation. It used to answer yes for everything, on the theory that adding towers
     * must not break the docks a save already had; the theory was wrong — it made the tower optional
     * decoration for the very machines whose whole point is that they reach across a server.
     */
    public boolean carriedByTower() {
        return TowerActivation.active(level, worldPosition);
    }

    /** Whether this dock may send: the mode says so, and a tower is carrying it. */
    public boolean canSend() {
        return (mode == DockMode.SEND || mode == DockMode.BIDIRECTIONAL) && carriedByTower();
    }

    public boolean canReceive() {
        return receivingMode() && carriedByTower();
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

    /** 发出格里那件包裹（动画和读数都用它）。 */
    public ItemStack transmittingStack() {
        for (int slot = 0; slot < outboundInv.getSlots(); slot++) {
            ItemStack stack = outboundInv.getStackInSlot(slot);
            if (PackageItem.isPackage(stack)) {
                return stack.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 现在是不是正把它交出去 —— 传输窗口开着。
     *
     * <p>**不要拿 {@link #transmittingStack()} 当这个用**：它只是"发出格里有没有东西"，而一件刚
     * 放进去、还没轮到结算的包裹也在那一格里。以前两处取件（手动和溜槽）都拿它当"传输中"，于是
     * 一件躺在发出格里的卡住包裹**永远取不出来** —— 玩家报的"智能溜槽取不走异常包裹"就是这个，
     * 而手动那一侧其实也一样坏，只是没人试。
     *
     * <p>窗口本身的定义在 {@code transmitStartedAt >= 0}，和状态灯、动画用的是同一个判断。
     */
    public boolean transmitting() {
        return transmitStartedAt >= 0 && !transmittingStack().isEmpty();
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

    /** Removes the Create network binding without discarding the dock's receiving group. */
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
            // 指名给人的包裹只有那个人能拿走。见 ParcelAddressing：地址写 @名字 就是"给某人的"，
            // 这也是这个语法唯一真正的用处 —— 否则它只是又一个名字。
            if (!dev.distantstock.routing.ParcelAddressing.mayTake(level == null ? null : level.getServer(),
                    parcel, player)) {
                continue;
            }
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
        if (player == null || transmitting()) {
            return false;
        }
        if (!outboundIsStranded() && fallbackInv.getStackInSlot(0).isEmpty()) {
            // 没有走不了的发出件，也没有回退面上的东西：没什么可救的。
            return false;
        }
        var server = level == null ? null : level.getServer();
        // 卡住的包裹同一个规矩：指名给谁的，只有谁能拿走 —— 但不指名的那种仍然谁都能救出来，
        // 一台卡住的机器不该因为多了一个语法就变得没人能修。
        if (handOver(outboundInv, player, server) ) {
            return true;
        }
        return handOver(fallbackInv, player, server);
    }

    /**
     * 这个港里有没有一件指名给别人、因此你拿不走的包裹；有就返回那个名字，没有返回 null。
     *
     * <p>只用来把话说清楚：拿不走这件事本身已经在 {@link #takeReceived} 里发生了，玩家需要知道
     * 的是"为什么"，而不是面对一个没反应的空手右键。
     */
    public String heldForSomeoneElse(net.minecraft.world.entity.player.Player player) {
        var server = level == null ? null : level.getServer();
        for (ItemStackHandler inventory : new ItemStackHandler[]{receivedInv, outboundInv, fallbackInv}) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack parcel = inventory.getStackInSlot(slot);
                String name = dev.distantstock.routing.ParcelAddressing.addressee(parcel);
                if (!name.isEmpty()
                        && !dev.distantstock.routing.ParcelAddressing.mayTake(server, parcel, player)) {
                    return name;
                }
            }
        }
        return null;
    }

    /** One slot's worth, into the player's inventory, or nothing if it does not fit. */
    private static boolean handOver(ItemStackHandler inventory,
                                    net.minecraft.world.entity.player.Player player,
                                    net.minecraft.server.MinecraftServer server) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack parcel = inventory.getStackInSlot(slot);
            if (parcel.isEmpty() || !canFit(player, parcel)) {
                continue;
            }
            if (!dev.distantstock.routing.ParcelAddressing.mayTake(server, parcel, player)) {
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

    public void setImport() {
        networkId = null;
        freq = null;
        mode = DockMode.RECEIVE;
        sync();
    }

    public void setBidirectional(UUID freq) {
        this.networkId = null;
        this.freq = freq;
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
     * A group name for the readout: the synced copy on the client, the live lookup on the server.
     *
     * <p>It used to be the live lookup in both places, and on the client that could only ever return
     * the uuid prefix — there is no server there, and no directory to look in. So a dock that had
     * just been added to 甲服仓库 drew 「fce02bd0」, and the count beside it drew 0 for the same kind
     * of reason. Both are facts about the server, and both now travel with the block entity.
     */
    public String knownGroupName() {
        return known(syncedGroupName, groupId);
    }

    /** 「发往」那一行的组名。同一个道理：客户端只能读同步过来的那一份。 */
    private String knownTargetGroupName() {
        return known(syncedTargetGroupName, defaultReceivingGroupId);
    }

    private String known(String synced, java.util.UUID group) {
        if (level != null && !level.isClientSide) {
            return groupName(group);
        }
        return synced == null || synced.isEmpty() ? RequesterData.shortFreq(group) : synced;
    }

    /**
     * The dock count the readout draws. See {@link #knownGroupName}.
     *
     * <p>Public with its sibling above because they are the two values the goggle asks for, and the
     * case worth testing — a block entity with no server behind it — cannot be reached from a game
     * test any other way: the runner has a server level, and the whole bug lived on the other side
     * of that line.
     */
    public int knownGroupDocks() {
        return level != null && !level.isClientSide ? groupDockCount() : syncedGroupDocks;
    }

    /**
     * A dock group's name, or a short form rather than nothing when the file cannot be read.
     *
     * <p>Server-side only. On the client there is no directory to read and the answer would be the
     * uuid prefix — see {@link #knownGroupName}.
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
            // 组名和港数每十刻对一次，变了就再同步一次。只同步一次是不够的：它们会**自己**变 ——
            // 组主改个名字、同组的另一台港被拆掉或加进来，这一台什么都不知道，而护目镜上那一行
            // 就一直写着上一次的答案。十刻和上面那一批读数同一个节拍。
            String currentName = be.groupName(be.groupId);
            int currentDocks = be.groupDockCount();
            if (!currentName.equals(be.syncedGroupName) || currentDocks != be.syncedGroupDocks
                    || !be.groupName(be.defaultReceivingGroupId).equals(be.syncedTargetGroupName)) {
                be.sync();
            }
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
                RemoteRouteData.write(stack, route.get(),
                        dev.distantstock.link.RouteLabels.describe(level.getServer(), route.get()));
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
        // 接收港组：这个港收谁的包裹，也是它唯一的收件条件。写在最上面几行里，因为戴护目镜看港
        // 的人第一个问题就是"我这个港到底挂在哪个组上"—— 以前这里只有一行「系统：X」，而收件
        // 条件那一行写的是地址，于是"组决定落哪个港"这件事在界面上根本看不出来。
        //
        // 组里几个港一起写出来：一个组里只有一个港，和一组里有五个港，是两个完全不同的东西
        // （前者没有冗余，后者按优先级挑空的那个），而这个区别只看名字是看不出来的。
        GoggleText.line(tip, "goggle.distantstock.group", knownGroupName(), knownGroupDocks());
        if (canSend() && freq != null) {
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
            GoggleText.line(tip, "goggle.distantstock.backlog", backlogOrders, inFlight);
        }
        if (canReceive()) {
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
                        knownTargetGroupName());
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
        if (clientPacket) {
            // 护目镜那两行组名和港数是**客户端**画的（Create 的护目镜在客户端收集），而客户端没有
            // server：组名查不到目录、港数走不过 deliverable 的"服务端才算"，于是一个刚加进 A服港组
            // 的港会显示「fce02bd0（本组 0 个港）」—— 名字是 uuid 前八位，数字永远是 0。和当年那个
            // 「不计费」是同一个坑：**服务端才知道的事，读数必须跟着方块实体同步过去，不能在客户端现算。**
            if (level != null && !level.isClientSide) {
                // 顺手记下来，好让 serverTick 里那个"变了没有"的比较有个基准；客户端写盘时不重算，
                // 它手里只有同步过来的那一份。
                syncedGroupName = groupName(groupId);
                syncedGroupDocks = groupDockCount();
                syncedTargetGroupName = groupName(defaultReceivingGroupId);
            }
            tag.putString("GroupName", syncedGroupName == null ? "" : syncedGroupName);
            tag.putInt("GroupDocks", syncedGroupDocks);
            tag.putString("TargetGroupName",
                    syncedTargetGroupName == null ? "" : syncedTargetGroupName);
        }
    }

    /** 这个组现在有几台已加载的港。同上：只有服务端数得对。 */
    private int groupDockCount() {
        return level == null || level.isClientSide ? 0 : LoadedDocks.countInGroup(groupId);
    }

    /**
     * 把一份客户端更新读进来 —— 客户端收到方块更新时走的就是这条路。
     *
     * <p>公开是因为它就是"这块方块把新状态交给客户端"这件事本身，而那条路只有在**背后没有服务端**
     * 的方块实体上跑一遍才验得了（game test 跑在有服务端的世界里）。见
     * {@code DockGameTests.theGoggleNamesTheGroupAndCountsItsDocks}。
     */
    public void loadClientUpdate(CompoundTag tag, HolderLookup.Provider registries) {
        read(tag, registries, true);
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
        // "Address" is no longer read. A dock's own address stopped selecting which parcels it takes
        // when the group became the only thing that picks one — the tag stays behind in old saves and
        // is ignored, because a dock that used to take only half of its group's traffic now takes all
        // of it, which is what the group always said it would do.
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
        // 客户端只读这两个；服务端那份由上面两个方法现算，不从盘上读回来 —— 盘上那份是上一次
        // 同步过去的旧值，拿它当答案就等于把"上一次"当成"现在"。
        if (clientPacket || level == null || level.isClientSide) {
            syncedGroupName = tag.contains("GroupName") ? tag.getString("GroupName") : null;
            syncedGroupDocks = tag.getInt("GroupDocks");
            syncedTargetGroupName = tag.contains("TargetGroupName")
                    ? tag.getString("TargetGroupName") : null;
        }
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
