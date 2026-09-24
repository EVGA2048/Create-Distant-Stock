package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.OrderService;
import dev.distantstock.stock.StockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;
import dev.distantstock.routing.RemoteNetworkId;

public final class GaugeBlockEntity extends FlapDisplayBlockEntity implements IHaveGoggleInformation {
    private UUID freq;
    private RemoteNetworkId networkId;
    private UUID distantNetworkId = dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
    private boolean hasDistantNetworkId;
    private String address = "";
    private String homeAddress = "";
    /**
     * Where a desk's orders come out, as a dock group id; null means the default group.
     *
     * <p>A desk had no group of its own, so the screen's group field wrote to whatever the player
     * happened to be holding — an empty hand wrote nothing at all and the desk kept sending every
     * order to the default system while the field showed something else. The group belongs to the
     * machine that places the order.
     */
    private UUID receivingGroup;
    private OrderService.Result lastOrder;
    /** Short command-accepted flash for the bulb on top of the desk; deliberately not persisted. */
    private int requestPulseTicks;
    /** Transient mechanical status board. Old saves and freshly loaded desks always resume at RDY. */
    private DeskDisplay display = DeskDisplay.RDY;
    private int displayTicks;
    private OrderService.Result pendingDisplayResult;
    private int catalog;
    private boolean dataLocal;
    private int cacheAgeSec = -1;

    public GaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GAUGE.get(), pos, state);
        updateSpeed = false;
    }

    private void tickRequestDisplay() {
        if (level == null || level.isClientSide || displayTicks <= 0) return;
        displayTicks--;
        if (displayTicks > 0) return;
        if (display == DeskDisplay.SND && pendingDisplayResult != null) {
            showOrderResult(pendingDisplayResult);
            return;
        }
        if (display == DeskDisplay.OK || display == DeskDisplay.ERR) {
            showDisplay(DeskDisplay.RDY);
        }
    }

    public enum DeskDisplay {
        RDY, SND, OK, ERR
    }

    public DeskDisplay displayState() {
        return display;
    }

    @Override
    public void updateControllerStatus() {
        isController = true;
        xSize = 1;
        ySize = 1;
        if (lines == null) initDefaultSections();
    }

    @Override
    public Direction getDirection() {
        BlockState state = getBlockState();
        return state.hasProperty(GaugeBlock.FACING)
                ? state.getValue(GaugeBlock.FACING).getOpposite() : Direction.NORTH;
    }

    private void showDisplay(DeskDisplay next) {
        if (next == null) next = DeskDisplay.RDY;
        display = next;
        updateControllerStatus();
        applyTextManually(0, Component.literal("REQ"));
        applyTextManually(1, Component.literal(next.name()));
    }

    /** Starts the visible send phase before OrderService performs the actual request. */
    public void orderStarted() {
        if (level == null || level.isClientSide) return;
        pendingDisplayResult = null;
        displayTicks = 6;
        showDisplay(DeskDisplay.SND);
    }

    public UUID freq() {
        return freq;
    }

    public String address() {
        return address;
    }

    public void setFreq(UUID freq) {
        this.freq = freq;
        this.networkId = null;
        this.distantNetworkId = dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        this.hasDistantNetworkId = false;
        sync();
    }

    public RemoteNetworkId networkId() {
        return networkId;
    }

    public void setNetwork(RemoteNetworkId networkId) {
        this.networkId = networkId;
        this.freq = networkId == null ? null : networkId.createFrequency();
        this.distantNetworkId = dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        this.hasDistantNetworkId = false;
        sync();
    }

    public void setNetwork(RemoteNetworkId networkId, UUID distantNetworkId) {
        this.networkId = networkId;
        this.freq = networkId == null ? null : networkId.createFrequency();
        this.distantNetworkId = distantNetworkId == null
                ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID
                : distantNetworkId;
        this.hasDistantNetworkId = distantNetworkId != null;
        sync();
    }

    public UUID distantNetworkId() {
        return distantNetworkId;
    }

    public boolean hasDistantNetworkId() {
        return hasDistantNetworkId;
    }

    /** Pair the desk with a Distant Stock network without selecting a warehouse yet. */
    public void setDistantNetworkContext(UUID scope) {
        distantNetworkId = scope == null
                ? dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID : scope;
        hasDistantNetworkId = scope != null
                && dev.distantstock.routing.DistantNetworkDirectory.isFormalId(scope);
        sync();
    }

    /** Keep the Distant Stock context but forget which member warehouse this desk reads/orders. */
    public void clearWarehouseBinding() {
        freq = null;
        networkId = null;
        sync();
    }

    /** The group this desk's orders are addressed to; the default group when never chosen. */
    public UUID receivingGroup() {
        return receivingGroup == null
                ? dev.distantstock.routing.DockGroupDirectory.DEFAULT_GROUP_ID : receivingGroup;
    }

    /** Points this desk at a group, or back at the default when given null. */
    public void setReceivingGroup(UUID group) {
        this.receivingGroup = group;
        sync();
    }

    /** 货回到本端以后要穿的地址；空白 = 这件货不换门牌。见 RequesterData.HOME_ADDRESS。 */
    public String homeAddress() {
        return homeAddress;
    }

    public void setHomeAddress(String homeAddress) {
        this.homeAddress = homeAddress == null ? "" : homeAddress;
        sync();
    }

    public void setAddress(String address) {
        this.address = address == null ? "" : address;
        sync();
    }

    public void lastOrder(OrderService.Result result) {
        this.lastOrder = result;
        if (result == OrderService.Result.QUEUED) {
            pulseRequestLamp();
        }
        if (display == DeskDisplay.SND && displayTicks > 0) {
            pendingDisplayResult = result;
        } else {
            showOrderResult(result);
        }
        refreshCache();
        sync();
    }

    private void showOrderResult(OrderService.Result result) {
        pendingDisplayResult = null;
        displayTicks = 30;
        showDisplay(result == OrderService.Result.QUEUED ? DeskDisplay.OK : DeskDisplay.ERR);
    }

    private void pulseRequestLamp() {
        if (level == null || level.isClientSide) return;
        requestPulseTicks = 4;
        BlockState state = getBlockState();
        if (state.hasProperty(GaugeBlock.LIT) && !state.getValue(GaugeBlock.LIT)) {
            level.setBlock(worldPosition, state.setValue(GaugeBlock.LIT, true), 3);
        }
    }

    public void refreshCache() {
        if (freq == null) {
            catalog = 0;
            dataLocal = false;
            cacheAgeSec = -1;
            return;
        }
        StockCache.watch(freq);
        if (networkId != null) {
            StockCache.watch(networkId);
            // 刚设过或刚换过网络：把「对面说不认识它」的退避清掉，下一次就问。
            StockCache.clearRefusal(networkId);
        }
        catalog = networkId == null ? StockCache.size(freq) : StockCache.size(networkId);
        dataLocal = StockCache.isLocal(freq);
        long age = StockCache.ageMs(freq);
        cacheAgeSec = age < 0 ? -1 : (int) (age / 1000);
    }

    public static void clientTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, GaugeBlockEntity be) {
        be.tick();
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, GaugeBlockEntity be) {
        be.tick();
        be.tickRequestLamp();
        be.tickRequestDisplay();
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        be.refreshCache();
        be.sync();
    }

    private void tickRequestLamp() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(GaugeBlock.LIT)) {
            return;
        }
        if (requestPulseTicks > 0) {
            requestPulseTicks--;
            if (requestPulseTicks == 0 && state.getValue(GaugeBlock.LIT)) {
                level.setBlock(worldPosition, state.setValue(GaugeBlock.LIT, false), 3);
            }
            return;
        }
        // LIT is transient. A save/reload or an interrupted tick must never leave the command lamp
        // glowing as a false status indication.
        if (state.getValue(GaugeBlock.LIT)) {
            level.setBlock(worldPosition, state.setValue(GaugeBlock.LIT, false), 3);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.gauge");
        // The tower carries this device or it does not; either way that is the first thing to say,
        // because everything under it reads as a fault when the answer is no.
        if (!dev.distantstock.routing.TowerActivation.active(level, worldPosition)) {
            GoggleText.value(tip, "goggle.distantstock.tower.inactive", net.minecraft.ChatFormatting.RED);
        }
        if (freq == null) {
            GoggleText.line(tip, "goggle.distantstock.untuned");
        } else {
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
        }
        GoggleText.line(tip, "goggle.distantstock.address", address.isBlank() ? "—" : address);
        GoggleText.line(tip, "goggle.distantstock.catalog", catalog);
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedDocks.add(this);
        refreshCache();
        if (level != null && !level.isClientSide) {
            displayTicks = 0;
            pendingDisplayResult = null;
            showDisplay(DeskDisplay.RDY);
        }
    }

    @Override
    public void onChunkUnloaded() {
        LoadedDocks.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    public void invalidate() {
        LoadedDocks.remove(this);
        super.invalidate();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.write(tag, regs, clientPacket);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        if (networkId != null) {
            tag.put("RemoteNetwork", networkId.save());
        }
        if (hasDistantNetworkId) {
            tag.putUUID("DistantNetwork", distantNetworkId);
        }
        tag.putString("Address", address);
        tag.putString("HomeAddress", homeAddress);
        if (receivingGroup != null) {
            tag.putUUID("ReceivingGroup", receivingGroup);
        }
        if (lastOrder != null) {
            tag.putString("LastOrder", lastOrder.name());
        }
        tag.putInt("Catalog", catalog);
        tag.putBoolean("Local", dataLocal);
        tag.putInt("CacheAge", cacheAgeSec);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.read(tag, regs, clientPacket);
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        networkId = tag.contains("RemoteNetwork")
                ? RemoteNetworkId.read(tag.getCompound("RemoteNetwork")).orElse(null) : null;
        hasDistantNetworkId = tag.hasUUID("DistantNetwork");
        distantNetworkId = hasDistantNetworkId ? tag.getUUID("DistantNetwork")
                : dev.distantstock.routing.DistantNetworkDirectory.LEGACY_NETWORK_ID;
        address = tag.getString("Address");
        // 缺键读回空串：老存档里的请求台只有一个地址，这正是它当时的样子。
        homeAddress = tag.getString("HomeAddress");
        receivingGroup = tag.hasUUID("ReceivingGroup") ? tag.getUUID("ReceivingGroup") : null;
        if (tag.contains("LastOrder")) {
            try {
                lastOrder = OrderService.Result.valueOf(tag.getString("LastOrder"));
            } catch (Exception ignored) {
            }
        }
        catalog = tag.getInt("Catalog");
        dataLocal = tag.getBoolean("Local");
        cacheAgeSec = tag.contains("CacheAge") ? tag.getInt("CacheAge") : -1;
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
