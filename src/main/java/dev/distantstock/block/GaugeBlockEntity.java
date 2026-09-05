package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.OrderService;
import dev.distantstock.stock.StockCache;
import net.minecraft.ChatFormatting;
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

public final class GaugeBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private UUID freq;
    private String address = "";
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
        if (freq != null) {
            StockCache.watch(freq);
        }
        catalog = StockCache.size(freq);
        dataLocal = StockCache.isLocal(freq);
        long age = StockCache.ageMs(freq);
        cacheAgeSec = age < 0 ? -1 : (int) (age / 1000);
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, GaugeBlockEntity be) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        be.refreshCache();
        be.sync();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.gauge");
        if (freq == null) {
            GoggleText.line(tip, "goggle.distantstock.untuned");
        } else {
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
        }
        GoggleText.line(tip, "goggle.distantstock.address", address.isBlank() ? "—" : address);
        GoggleText.line(tip, "goggle.distantstock.catalog", catalog);
        if (lastOrder == OrderService.Result.QUEUED) {
            GoggleText.value(tip, "goggle.distantstock.last.order.queued", ChatFormatting.GREEN);
        } else if (lastOrder == OrderService.Result.FAIL || lastOrder == OrderService.Result.NO_PEER) {
            GoggleText.value(tip, "goggle.distantstock.last.order.fail", ChatFormatting.RED);
        } else {
            GoggleText.line(tip, "goggle.distantstock.last.order.none");
        }
        if (sneaking) {
            GoggleText.line(tip, dataLocal ? "goggle.distantstock.data.local" : "goggle.distantstock.data.peer");
            GoggleText.line(tip, "goggle.distantstock.cache_age", cacheAgeSec < 0 ? "—" : cacheAgeSec + "s");
        }
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedLinkers.add(this);
        refreshCache();
    }

    @Override
    public void setRemoved() {
        LoadedLinkers.remove(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.saveAdditional(tag, regs);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        tag.putString("Address", address);
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
        address = tag.getString("Address");
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
