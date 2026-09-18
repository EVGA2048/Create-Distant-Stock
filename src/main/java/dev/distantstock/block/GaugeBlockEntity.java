package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.OrderService;
import dev.distantstock.stock.StockCache;
import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;
import dev.distantstock.routing.RemoteNetworkId;

public final class GaugeBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private UUID freq;
    private RemoteNetworkId networkId;
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
    private int catalog;
    private boolean dataLocal;
    private int cacheAgeSec = -1;

    public GaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GAUGE.get(), pos, state);
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
        sync();
    }

    public RemoteNetworkId networkId() {
        return networkId;
    }

    public void setNetwork(RemoteNetworkId networkId) {
        this.networkId = networkId;
        this.freq = networkId == null ? null : networkId.createFrequency();
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
        refreshCache();
        sync();
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

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, GaugeBlockEntity be) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        be.refreshCache();
        be.updateLit();
        be.sync();
    }

    private void updateLit() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(GaugeBlock.LIT)) {
            return;
        }
        boolean lit = LinkSnapshot.peerUp || !StockConfig.hasPeer();
        if (state.getValue(GaugeBlock.LIT) != lit) {
            level.setBlock(worldPosition, state.setValue(GaugeBlock.LIT, lit), 3);
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
    }

    @Override
    public void setRemoved() {
        LoadedDocks.remove(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.saveAdditional(tag, regs);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        if (networkId != null) {
            tag.put("RemoteNetwork", networkId.save());
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
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.loadAdditional(tag, regs);
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        networkId = tag.contains("RemoteNetwork")
                ? RemoteNetworkId.read(tag.getCompound("RemoteNetwork")).orElse(null) : null;
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

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        return saveWithoutMetadata(regs);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
