package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import dev.distantstock.item.SignalLampPanelItem;
import dev.distantstock.item.ModItems;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkHealth;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SignalPanelBlockEntity extends FactoryPanelBlockEntity implements IHaveGoggleInformation {
    private int lampSignal;
    private boolean panelDataReady;
    private final EnumSet<FactoryPanelBlock.PanelSlot> remoteGauges = EnumSet.noneOf(FactoryPanelBlock.PanelSlot.class);
    /** The remote gauge panels on this board, and the orders they file. See {@link RemoteOrderBook}. */
    private final RemoteOrderBook orders = new RemoteOrderBook(this);

    public SignalPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIGNAL_PANEL.get(), pos, state);
        setLazyTickRate(2);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        panels = new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
        redraw = true;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour panel = new SignalLampAwarePanelBehaviour(this, slot);
            panels.put(slot, panel);
            behaviours.add(panel);
        }
        behaviours.add(advancements = new AdvancementBehaviour(this, AllAdvancements.FACTORY_GAUGE));
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null) {
            return;
        }
        if (!level.isClientSide) {
            sampleLampNetworks();
            // The remote gauge panels on this board order from the same beat the lamps are sampled
            // on; the pace that matters is decided inside the order book.
            orders.tickOrders();
        }
        int next = level.getBestNeighborSignal(worldPosition);
        if (next == lampSignal) {
            return;
        }
        lampSignal = next;
        if (!level.isClientSide) {
            sendData();
        }
    }

    /** How many offline link positions a bound lamp carries for the goggle readout. */
    public static final int REPORTED_MISSING_LINKS = 3;
    /** Create's logistics live on the server thread, so bound lamps resample once a second. */
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    /**
     * Seconds a gauge may spend promising before the lamp calls it stuck: one minute.
     *
     * <p>Long on purpose. A promise covers the time a parcel takes to be packed, carried and booked
     * in, which in a big factory is tens of seconds of normal operation; a shorter fuse would turn
     * every large order into an alarm and the lamp would stop meaning anything.
     */
    private static final int STUCK_PROMISE_SAMPLES = 60;

    /** How many items one lamp can watch at once. */
    public static final int MONITOR_SLOTS = 8;

    private final Map<FactoryPanelBlock.PanelSlot, UUID> lampNetworks =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
    /** Consecutive samples each brass lamp's source has spent promising without delivering. */
    private final Map<FactoryPanelBlock.PanelSlot, Integer> stuckPromises =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
    private final Map<FactoryPanelBlock.PanelSlot, NetworkHealth> lampHealth =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
    private final Map<FactoryPanelBlock.PanelSlot, SimpleContainer> monitors =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
    private final Map<FactoryPanelBlock.PanelSlot, List<LampState>> monitorStates =
            new EnumMap<>(FactoryPanelBlock.PanelSlot.class);

    /**
     * The items a lamp watches. A plain container rather than a list, so the monitor screen can
     * expose these as ordinary slots and get JEI dragging and shift-clicking for free.
     */
    public SimpleContainer monitor(FactoryPanelBlock.PanelSlot slot) {
        return monitors.computeIfAbsent(slot, key -> new SimpleContainer(MONITOR_SLOTS) {
            @Override
            public void setChanged() {
                super.setChanged();
                SignalPanelBlockEntity.this.sync();
            }
        });
    }

    /** One state per watched item, in slot order. Empty when the lamp watches nothing. */
    public List<LampState> monitorStates(FactoryPanelBlock.PanelSlot slot) {
        return monitorStates.getOrDefault(slot, List.of());
    }

    /** Points a lamp slot at a logistics frequency, or clears it with null. */
    public void setLampNetwork(FactoryPanelBlock.PanelSlot slot, UUID freq) {
        if (freq == null) {
            lampNetworks.remove(slot);
            lampHealth.remove(slot);
        } else {
            lampNetworks.put(slot, freq);
            lampHealth.put(slot, CreateStock.health(freq, REPORTED_MISSING_LINKS));
        }
        sync();
    }

    public UUID lampNetwork(FactoryPanelBlock.PanelSlot slot) {
        return lampNetworks.get(slot);
    }

    private void sampleLampNetworks() {
        if (level.getGameTime() % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }
        boolean changed = sampleStuckPromises();
        if (lampNetworks.isEmpty()) {
            if (changed) {
                sync();
            }
            return;
        }
        for (var entry : lampNetworks.entrySet()) {
            NetworkHealth next = CreateStock.health(entry.getValue(), REPORTED_MISSING_LINKS);
            NetworkHealth previous = lampHealth.put(entry.getKey(), next);
            if (!next.equals(previous)) {
                changed = true;
            }
            if (sampleMonitor(entry.getKey(), entry.getValue())) {
                changed = true;
            }
        }
        if (changed) {
            sync();
        }
    }

    /**
     * Counts how long each brass lamp's worst gauge has been saying "covered, not delivered".
     *
     * <p>One sample a second, and the count is what the lamp reads. Any other state resets it, so
     * the number means consecutive seconds of the same promise and not a total that never clears.
     */
    private boolean sampleStuckPromises() {
        boolean changed = false;
        // The slots whose lamp is a brass one, plus the slots counted last second so a lamp that
        // was swapped out does not leave its count behind.
        java.util.Set<FactoryPanelBlock.PanelSlot> brass = java.util.EnumSet.noneOf(
                FactoryPanelBlock.PanelSlot.class);
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            SignalLampPanelItem lamp = SignalLampPanelItem.from(behaviour.getFilter());
            if (lamp != null && lamp.material() == SignalLampPanelItem.Material.BRASS) {
                brass.add(slot);
            }
        }
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            boolean watched = brass.contains(slot);
            boolean waiting = watched && rawLampState(slot) == LampState.ACT;
            int next = nextStuckSamples(stuckPromises.getOrDefault(slot, 0), waiting,
                    STUCK_PROMISE_SAMPLES * 2);
            Integer previous = stuckPromises.get(slot);
            if (next == 0) {
                if (previous != null) {
                    stuckPromises.remove(slot);
                    changed = true;
                }
                continue;
            }
            if (previous == null || previous != next) {
                stuckPromises.put(slot, next);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * The counter's whole rule: one more while the promise is outstanding, nothing at all when it
     * is not, and a ceiling so a lamp left alone for a week does not count to infinity.
     *
     * <p>Pure and public because it is the part of the alert that can be wrong in a way nobody would
     * notice — a counter that never reset would light the lamp on a factory that has been fine since
     * the one time it was not.
     */
    public static int nextStuckSamples(int current, boolean waiting, int cap) {
        if (!waiting) {
            return 0;
        }
        return Math.min(Math.max(0, current) + 1, Math.max(1, cap));
    }

    /** How many consecutive samples this slot's promise has been outstanding. */
    public int promiseStuckSamples(FactoryPanelBlock.PanelSlot slot) {
        return stuckPromises.getOrDefault(slot, 0);
    }

    /** Re-reads every watched item's stock and promise count. True when something moved. */
    private boolean sampleMonitor(FactoryPanelBlock.PanelSlot slot, UUID freq) {
        SimpleContainer container = monitor(slot);
        List<LampState> next = new ArrayList<>(MONITOR_SLOTS);
        for (int i = 0; i < MONITOR_SLOTS; i++) {
            ItemStack item = container.getItem(i);
            next.add(item.isEmpty() ? null : itemLevel(freq, item));
        }
        return !next.equals(monitorStates.put(slot, next));
    }

    /** Stock on hand means good; an outstanding promise means it is coming; otherwise it is short. */
    private static LampState itemLevel(UUID freq, ItemStack item) {
        int[] counts = CreateStock.itemStock(freq, item);
        if (counts[0] > 0) {
            return LampState.ALL_GOOD;
        }
        return counts[1] > 0 ? LampState.ACT : LampState.WARN;
    }

    /** The most urgent state among the watched items, or null when none is configured. */
    private LampState worstMonitored(FactoryPanelBlock.PanelSlot slot) {
        LampState worst = null;
        for (LampState state : monitorStates(slot)) {
            worst = LampState.worst(worst, state);
        }
        return worst;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int lampSignal(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        if (behaviour == null || !behaviour.isActive() || level == null) {
            return 0;
        }
        boolean connected = false;
        boolean satisfied = false;
        for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection);
            if (source == null) {
                continue;
            }
            connected = true;
            if (source.satisfied || source.redstonePowered) {
                satisfied = true;
            }
        }
        if (lampInverted(slot)) {
            // An inverted lamp is a shortage alarm: attached and short is exactly when it lights.
            return connected && !satisfied ? 15 : 0;
        }
        return satisfied ? 15 : 0;
    }

    /** How urgent one connected source gauge looks, using the fields Create syncs to the client. */
    private static LampState sourceLevel(FactoryPanelBehaviour gauge) {
        // One ladder for every lamp, ours and the ones on other mods' boards. See LampReadings.
        return LampReadings.ofGauge(gauge);
    }

    /**
     * Andon state of a brass lamp slot: the most urgent connected gauge wins. Null means no source
     * gauge is attached, which keeps the lamp dark instead of claiming everything is fine.
     */
    public LampState lampState(FactoryPanelBlock.PanelSlot slot) {
        LampState raw = rawLampState(slot);
        if (raw == LampState.ACT && promiseStuckSamples(slot) >= STUCK_PROMISE_SAMPLES) {
            // The network says it has covered the shortfall and the goods still have not moved.
            // "Coming" and "coming, honestly" look identical from the panel's own fields; the only
            // thing that tells them apart is how long it has been saying it, so that is what is
            // counted — once a second, in the sampler, and kept where the client can read it.
            return LampState.WARN_URGENT;
        }
        return raw;
    }

    /**
     * The lamp's state before the stuck-promise escalation, which is what the sampler counts on.
     *
     * <p>Counting on the escalated value would be a loop that never ends: the moment the counter
     * crossed the threshold the state would stop being ACT, the sampler would see a change and
     * reset the counter, and the lamp would drop back to ACT to start over — a warning that blinks
     * instead of one that stays lit.
     */
    private LampState rawLampState(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        if (behaviour == null || !behaviour.isActive() || level == null) {
            return null;
        }
        if (lampNetworks.containsKey(slot)) {
            // Bound to a frequency, so the network itself is the sensor: no gauges, no wiring.
            NetworkHealth net = lampHealth.get(slot);
            LampState overall = networkLevel(net == null ? NetworkHealth.UNKNOWN : net);
            if (overall == LampState.FATAL) {
                // Without a reachable network every per-item reading is just "no stock", which
                // would masquerade as a shortage. The missing network is the real problem.
                return overall;
            }
            LampState watched = worstMonitored(slot);
            return watched != null ? watched : overall;
        }
        LampState worst = null;
        for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection);
            if (source != null) {
                worst = LampState.worst(worst, sourceLevel(source));
            }
        }
        return worst;
    }

    /** A bound network reports through the same andon ladder as wired gauges. */
    private static LampState networkLevel(NetworkHealth net) {
        return LampReadings.ofNetwork(net);
    }

    /** The sampled health of a bound lamp, or null when the slot reads gauges instead. */
    public NetworkHealth lampHealth(FactoryPanelBlock.PanelSlot slot) {
        return lampNetworks.containsKey(slot) ? lampHealth.get(slot) : null;
    }

    /** Lamp slots carry no amount, so their value setting stores the andesite lamp's mode instead. */
    public boolean lampInverted(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        return behaviour != null && behaviour.isActive() && behaviour.count != 0;
    }

    public ItemStack lampStack(FactoryPanelBlock.PanelSlot slot) {
        FactoryPanelBehaviour behaviour = panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            return ItemStack.EMPTY;
        }
        ItemStack filter = behaviour.getFilter();
        return SignalLampPanelItem.from(filter) == null ? ItemStack.EMPTY : filter;
    }

    public boolean isLamp(FactoryPanelBlock.PanelSlot slot) {
        return !lampStack(slot).isEmpty();
    }

    public boolean isRemoteGauge(FactoryPanelBlock.PanelSlot slot) {
        return remoteGauges.contains(slot) && panels.get(slot).isActive();
    }

    public void setRemoteGauge(FactoryPanelBlock.PanelSlot slot, boolean remote) {
        if (remote) remoteGauges.add(slot);
        else remoteGauges.remove(slot);
        if (!remote) {
            // The slot is a lamp or a plain gauge now, so whatever it was pointed at goes with it.
            orders.forget(slot);
        }
        redraw = true;
        sendData();
    }

    /** This panel's warehouse, or null when it is only a gauge. */
    public RemoteBinding binding(FactoryPanelBlock.PanelSlot slot) {
        return orders.binding(slot);
    }

    public boolean bind(FactoryPanelBlock.PanelSlot slot, dev.distantstock.routing.RemoteNetworkId network,
                        java.util.UUID receivingGroup, String address) {
        orders.bind(slot, network, receivingGroup, address);
        return true;
    }

    /**
     * 同一件事，但整份绑定一起写 —— 手势那条路（手持终端点面板）从终端拿到的是四个字段，见
     * {@code BindPanelFromTerminalC2S}。
     */
    public void bind(FactoryPanelBlock.PanelSlot slot, RemoteBinding binding) {
        orders.bind(slot, binding);
    }

    public void unbind(FactoryPanelBlock.PanelSlot slot) {
        orders.unbind(slot);
    }

    public int outstanding(FactoryPanelBlock.PanelSlot slot) {
        return orders.outstanding(slot);
    }

    /** The ordering beat, run from the block's ticker alongside the lamp sampling. */
    public void tickOrders() {
        orders.tickOrders();
    }

    public ItemStack panelItem(FactoryPanelBlock.PanelSlot slot) {
        if (isLamp(slot)) return lampStack(slot).copyWithCount(1);
        return isRemoteGauge(slot) ? new ItemStack(ModItems.REMOTE_GAUGE.get())
                : new ItemStack(BuiltInRegistries.BLOCK.get(
                        ResourceLocation.fromNamespaceAndPath("create", "factory_gauge")));
    }

    @Override
    public boolean removePanel(FactoryPanelBlock.PanelSlot slot) {
        boolean removed = super.removePanel(slot);
        if (removed) remoteGauges.remove(slot);
        return removed;
    }

    /**
     * Client-side placement creates the block entity before the server sends
     * the installed lamp stack. Until that packet arrives, Create's default
     * active panel must not be rendered as a factory gauge.
     */
    public boolean panelDataReady() {
        return level != null && (!level.isClientSide || panelDataReady);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        orders.goggleLines().forEach(tip::add);
        boolean found = false;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            ItemStack lamp = lampStack(slot);
            SignalLampPanelItem item = SignalLampPanelItem.from(lamp);
            if (item == null) {
                continue;
            }
            if (!found) {
                tip.add(Component.empty());
                tip.add(Component.translatable(onlyLamps() ? "goggle.distantstock.signal_lamp.title"
                        : "goggle.distantstock.signal_panel").withStyle(ChatFormatting.WHITE));
                found = true;
            }
            Component name = lamp.get(DataComponents.CUSTOM_NAME);
            tip.add(Component.literal("  ").append(name == null ? lamp.getHoverName() : name)
                    .withStyle(ChatFormatting.GRAY));
            int strength = lampSignal(slot);
            tip.add(Component.translatable("goggle.distantstock.signal_lamp.detail",
                            Component.translatable("goggle.distantstock.signal_lamp.material."
                                    + item.material().name().toLowerCase()),
                            Component.translatable("goggle.distantstock.signal_lamp.color."
                                    + item.color().name().toLowerCase()), strength)
                    .withStyle(strength > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
            if (item.material() == SignalLampPanelItem.Material.BRASS) {
                appendAndonReadout(tip, slot);
            }
        }
        return found;
    }

    /**
     * The andon readout: which state the brass lamp is showing, and why. This is the whole point of
     * the goggles here — a colour on the wall says something is wrong, this says what.
     */
    private void appendAndonReadout(List<Component> tip, FactoryPanelBlock.PanelSlot slot) {
        LampState level = lampState(slot);
        tip.add(Component.translatable("goggle.distantstock.lamp.state."
                        + (level == null ? "none" : level.name().toLowerCase()))
                .withStyle(stateStyle(level)));
        NetworkHealth net = lampHealth(slot);
        if (net == null) {
            return;
        }
        if (!net.known()) {
            tip.add(Component.translatable("goggle.distantstock.lamp.net.unknown")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        tip.add(Component.translatable("goggle.distantstock.lamp.net.links",
                        net.loadedLinks(), net.totalLinks())
                .withStyle(net.offline() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
        if (net.locked()) {
            tip.add(Component.translatable("goggle.distantstock.lamp.net.locked")
                    .withStyle(ChatFormatting.RED));
        }
        for (BlockPos missing : net.missing()) {
            tip.add(Component.literal("    " + missing.getX() + ", " + missing.getY() + ", "
                    + missing.getZ()).withStyle(ChatFormatting.DARK_RED));
        }
        if (net.offline() > net.missing().size()) {
            tip.add(Component.translatable("goggle.distantstock.lamp.net.more",
                    net.offline() - net.missing().size()).withStyle(ChatFormatting.DARK_RED));
        }
        appendWatchedItems(tip, slot);
    }

    /**
     * The per-item breakdown. This is the monitor's detail view without opening its screen, which
     * is why the lamp is useful before anyone configures a list at all.
     */
    private void appendWatchedItems(List<Component> tip, FactoryPanelBlock.PanelSlot slot) {
        SimpleContainer container = monitor(slot);
        List<LampState> states = monitorStates(slot);
        for (int i = 0; i < MONITOR_SLOTS; i++) {
            ItemStack item = container.getItem(i);
            if (item.isEmpty()) {
                continue;
            }
            LampState level = i < states.size() ? states.get(i) : null;
            tip.add(Component.literal("  ").append(item.getHoverName())
                    .append("  ")
                    .append(Component.translatable("goggle.distantstock.lamp.short."
                            + (level == null ? "none" : level.name().toLowerCase())))
                    .withStyle(stateStyle(level)));
        }
    }

    private static ChatFormatting stateStyle(LampState level) {
        if (level == null) {
            return ChatFormatting.DARK_GRAY;
        }
        return switch (level) {
            case IDLE, ALL_GOOD -> ChatFormatting.GREEN;
            case ACT -> ChatFormatting.AQUA;
            case WARN -> ChatFormatting.GOLD;
            case WARN_URGENT, FATAL -> ChatFormatting.RED;
        };
    }

    /** True when every occupied slot holds a lamp; an empty panel is not a lamp panel. */
    private boolean onlyLamps() {
        boolean any = false;
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            any = true;
            if (SignalLampPanelItem.from(behaviour.getFilter()) == null) {
                return false;
            }
        }
        return any;
    }

    @Override
    public void destroy() {
        if (level == null || level.isClientSide) {
            super.destroy();
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            FactoryPanelBehaviour behaviour = panels.get(slot);
            if (behaviour == null || !behaviour.isActive()) {
                continue;
            }
            ItemStack filter = behaviour.getFilter();
            ItemStack drop = panelItem(slot);
            drops.add(drop);
            behaviour.disable();
        }

        // FactoryPanelBlockEntity normally drops extra factory gauges. All slots are
        // disabled here so its drop pass stays empty; each real slot is returned above.
        super.destroy();
        for (ItemStack drop : drops) {
            Block.popResource(level, worldPosition, drop);
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("LampSignal", lampSignal);
        CompoundTag kinds = new CompoundTag();
        for (var slot : remoteGauges) kinds.putBoolean(slot.name(), true);
        tag.put("RemoteGaugeSlots", kinds);
        CompoundTag networks = new CompoundTag();
        for (var entry : lampNetworks.entrySet()) {
            networks.putUUID(entry.getKey().name(), entry.getValue());
        }
        tag.put("LampNetworks", networks);
        // The sampled health is derived, but recomputing it needs Create's logistics, which only
        // exist on the server, so the reading travels with the block update instead.
        CompoundTag health = new CompoundTag();
        for (var entry : lampHealth.entrySet()) {
            NetworkHealth net = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putBoolean("Known", net.known());
            row.putInt("Loaded", net.loadedLinks());
            row.putInt("Total", net.totalLinks());
            row.putBoolean("Idle", net.idle());
            row.putBoolean("Locked", net.locked());
            int[] positions = new int[net.missing().size() * 3];
            for (int i = 0; i < net.missing().size(); i++) {
                positions[i * 3] = net.missing().get(i).getX();
                positions[i * 3 + 1] = net.missing().get(i).getY();
                positions[i * 3 + 2] = net.missing().get(i).getZ();
            }
            row.putIntArray("Missing", positions);
            health.put(entry.getKey().name(), row);
        }
        tag.put("LampHealth", health);
        CompoundTag watches = new CompoundTag();
        for (var entry : monitors.entrySet()) {
            CompoundTag row = new CompoundTag();
            ContainerHelper.saveAllItems(row, entry.getValue().getItems(), registries);
            List<LampState> states = monitorStates.get(entry.getKey());
            int[] ordinals = new int[states == null ? 0 : states.size()];
            for (int i = 0; i < ordinals.length; i++) {
                // -1 keeps the "empty slot" holes so the states stay aligned with the item slots.
                ordinals[i] = states.get(i) == null ? -1 : states.get(i).ordinal();
            }
            row.putIntArray("States", ordinals);
            watches.put(entry.getKey().name(), row);
        }
        tag.put("LampWatches", watches);
        CompoundTag stuck = new CompoundTag();
        for (var entry : stuckPromises.entrySet()) {
            stuck.putInt(entry.getKey().name(), entry.getValue());
        }
        tag.put("StuckPromises", stuck);
        orders.write(tag);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        stuckPromises.clear();
        CompoundTag stuck = tag.getCompound("StuckPromises");
        for (FactoryPanelBlock.PanelSlot slot : FactoryPanelBlock.PanelSlot.values()) {
            if (stuck.contains(slot.name())) {
                stuckPromises.put(slot, stuck.getInt(slot.name()));
            }
        }
        orders.read(tag, registries, clientPacket);
        lampSignal = tag.getInt("LampSignal");
        remoteGauges.clear();
        CompoundTag kinds = tag.getCompound("RemoteGaugeSlots");
        for (var slot : FactoryPanelBlock.PanelSlot.values()) {
            if (kinds.getBoolean(slot.name())) remoteGauges.add(slot);
        }
        lampNetworks.clear();
        CompoundTag networks = tag.getCompound("LampNetworks");
        for (var slot : FactoryPanelBlock.PanelSlot.values()) {
            if (networks.hasUUID(slot.name())) {
                lampNetworks.put(slot, networks.getUUID(slot.name()));
            }
        }
        lampHealth.clear();
        CompoundTag health = tag.getCompound("LampHealth");
        for (var slot : FactoryPanelBlock.PanelSlot.values()) {
            if (!health.contains(slot.name())) {
                continue;
            }
            CompoundTag row = health.getCompound(slot.name());
            int[] positions = row.getIntArray("Missing");
            List<BlockPos> missing = new ArrayList<>();
            for (int i = 0; i + 2 < positions.length; i += 3) {
                missing.add(new BlockPos(positions[i], positions[i + 1], positions[i + 2]));
            }
            lampHealth.put(slot, new NetworkHealth(row.getBoolean("Known"), row.getInt("Loaded"),
                    row.getInt("Total"), row.getBoolean("Idle"), row.getBoolean("Locked"), List.copyOf(missing)));
        }
        monitors.clear();
        monitorStates.clear();
        CompoundTag watches = tag.getCompound("LampWatches");
        for (var slot : FactoryPanelBlock.PanelSlot.values()) {
            if (!watches.contains(slot.name())) {
                continue;
            }
            CompoundTag row = watches.getCompound(slot.name());
            SimpleContainer container = monitor(slot);
            ContainerHelper.loadAllItems(row, container.getItems(), registries);
            int[] ordinals = row.getIntArray("States");
            List<LampState> states = new ArrayList<>(ordinals.length);
            for (int ordinal : ordinals) {
                states.add(ordinal < 0 || ordinal >= LampState.values().length
                        ? null : LampState.values()[ordinal]);
            }
            monitorStates.put(slot, states);
        }
        panelDataReady = true;
    }

    private static final class SignalLampAwarePanelBehaviour extends FactoryPanelBehaviour {
        private final SignalPanelBlockEntity owner;

        private SignalLampAwarePanelBehaviour(SignalPanelBlockEntity be, FactoryPanelBlock.PanelSlot slot) {
            super(be, slot);
            owner = be;
        }

        private boolean isLampSlot() {
            return SignalLampPanelItem.from(getFilter()) != null;
        }

        private boolean isLampInputBlocked() {
            return !owner.panelDataReady() || isLampSlot();
        }

        /**
         * A lamp has no amount, but the andesite one still owns a value panel: the same board
         * picks its mode. The mode lives in {@code count}, which is otherwise unused on a lamp
         * slot and already persisted and synced by Create.
         */
        @Override
        public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
            if (!isLampSlot()) {
                return super.createBoard(player, hitResult);
            }
            return new ValueSettingsBoard(
                    Component.translatable("gui.distantstock.lamp.mode"),
                    1, 1,
                    List.of(Component.translatable("gui.distantstock.lamp.mode.row")),
                    new ValueSettingsFormatter(settings -> Component.translatable(
                            settings.value() == 0 ? "gui.distantstock.lamp.mode.normal"
                                    : "gui.distantstock.lamp.mode.inverted")));
        }

        @Override
        public ValueSettings getValueSettings() {
            return isLampSlot() ? new ValueSettings(0, lampInverted() ? 1 : 0) : super.getValueSettings();
        }

        @Override
        public void setValueSettings(Player player, ValueSettings settings, boolean ctrlDown) {
            if (!isLampSlot()) {
                super.setValueSettings(player, settings, ctrlDown);
                return;
            }
            count = Math.clamp(settings.value(), 0, 1);
            owner.sendData();
        }

        @Override
        public boolean isCountVisible() {
            return isLampSlot() || super.isCountVisible();
        }

        /**
         * Create's gauge label reports "no target amount set" while count is zero, which on a lamp
         * just means the normal mode. A lamp names itself instead.
         */
        @Override
        public net.minecraft.network.chat.MutableComponent getLabel() {
            return isLampSlot() ? getFilter().getHoverName().copy() : super.getLabel();
        }

        /** The box shows the mode name where a gauge would show its amount. */
        @Override
        public net.minecraft.network.chat.MutableComponent getCountLabelForValueBox() {
            return isLampSlot()
                    ? Component.translatable(lampInverted() ? "gui.distantstock.lamp.mode.inverted"
                            : "gui.distantstock.lamp.mode.normal")
                    : super.getCountLabelForValueBox();
        }

        @Override
        public net.minecraft.network.chat.MutableComponent getAmountTip() {
            return isLampSlot() ? Component.translatable("gui.distantstock.lamp.mode.tip")
                    : super.getAmountTip();
        }

        /**
         * A brass lamp reads the network and its gauges and has no mode to set, so its value
         * panel is meaningless and must not open at all. The andesite lamp keeps its board:
         * that is where the normal/inverted toggle lives.
         */
        private boolean isBrassLampSlot() {
            SignalLampPanelItem lamp = SignalLampPanelItem.from(getFilter());
            return lamp != null && lamp.material() == SignalLampPanelItem.Material.BRASS;
        }

        @Override
        public boolean acceptsValueSettings() {
            return owner.panelDataReady() && !isBrassLampSlot();
        }

        /** A lamp slot owns its value panel now, so it only swallows input until the data arrives. */
        @Override
        public boolean bypassesInput(net.minecraft.world.item.ItemStack stack) {
            return !owner.panelDataReady() || isBrassLampSlot() || super.bypassesInput(stack);
        }

        private boolean lampInverted() {
            return count != 0;
        }

        @Override
        public void tick() {
            if (!isLampInputBlocked()) {
                super.tick();
            }
        }

        @Override
        public void lazyTick() {
            if (!isLampInputBlocked()) {
                super.lazyTick();
            }
        }
    }
}
