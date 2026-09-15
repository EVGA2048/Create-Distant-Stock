package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.net.LinkSnapshotS2C;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.CreateStock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

public final class MonitorBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private double localTps = 20;
    private double localMspt = 50;
    private boolean peerUp;
    private double peerTps;
    private int backlog;
    private int rtt = -1;
    private String role = "host";
    private int fails;
    private int inFlight;
    private java.util.UUID freq;
    private RemoteNetworkId networkId;
    private int deviceCount;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
    }

    public RemoteNetworkId networkId() {
        return networkId;
    }

    public void setNetwork(RemoteNetworkId networkId) {
        this.networkId = networkId;
        this.freq = networkId == null ? null : networkId.createFrequency();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setFrequency(java.util.UUID freq) {
        this.freq = freq;
        this.networkId = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MonitorBlockEntity be) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        LinkSnapshot.View v = LinkSnapshot.view();
        be.localTps = v.localTps();
        be.localMspt = v.localMspt();
        be.peerUp = v.linkUp();
        be.peerTps = v.peerTps();
        be.backlog = v.orderDepth() + v.packageDepth();
        be.rtt = (int) v.peerRttMs();
        be.role = v.selfId();
        be.fails = v.peerFails();
        be.inFlight = v.inFlight();
        be.deviceCount = be.freq == null ? 0 : CreateStock.deviceCount(be.freq);
        MonitorBlock.Status status = MonitorBlock.Status.fromTps(be.localTps);
        if (state.getValue(MonitorBlock.STATUS) != status) {
            level.setBlock(pos, state.setValue(MonitorBlock.STATUS, status), 3);
        }
        be.setChanged();
        BlockState current = be.getBlockState();
        level.sendBlockUpdated(pos, current, current, 3);
        LinkSnapshotS2C update = new LinkSnapshotS2C(pos, v);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 32 * 32) {
                PacketDistributor.sendToPlayer(player, update);
            }
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.monitor");
        // The tower carries this device or it does not; either way that is the first thing to say,
        // because everything under it reads as a fault when the answer is no.
        if (!dev.distantstock.routing.TowerActivation.active(level, worldPosition)) {
            GoggleText.value(tip, "goggle.distantstock.tower.inactive", net.minecraft.ChatFormatting.RED);
        }
        if (networkId == null && freq == null) {
            GoggleText.line(tip, "goggle.distantstock.untuned");
        } else {
            GoggleText.line(tip, "goggle.distantstock.freq", RequesterData.shortFreq(freq));
        }
        GoggleText.line(tip, "goggle.distantstock.local_tps", fmt(localTps), fmt(localMspt));
        if (peerUp) {
            GoggleText.line(tip, "goggle.distantstock.peer_tps", fmt(peerTps));
        } else {
            GoggleText.value(tip, "goggle.distantstock.peer_down", ChatFormatting.RED);
        }
        GoggleText.line(tip, "goggle.distantstock.pressure", backlog, rtt < 0 ? "—" : rtt);
        GoggleText.line(tip, "goggle.distantstock.devices", deviceCount);
        return true;
    }

    private static String fmt(double n) {
        return String.format(Locale.ROOT, "%.1f", n);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LoadedDevices.add(this);
    }

    @Override
    public void onChunkUnloaded() {
        LoadedDevices.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        LoadedDevices.remove(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.saveAdditional(tag, regs);
        tag.putDouble("Tps", localTps);
        tag.putDouble("Mspt", localMspt);
        tag.putBoolean("PeerUp", peerUp);
        tag.putDouble("PeerTps", peerTps);
        tag.putInt("Backlog", backlog);
        tag.putInt("Rtt", rtt);
        tag.putString("Role", role);
        tag.putInt("Fails", fails);
        tag.putInt("InFlight", inFlight);
        tag.putInt("DeviceCount", deviceCount);
        if (freq != null) {
            tag.putUUID("Freq", freq);
        }
        if (networkId != null) {
            tag.put("RemoteNetwork", networkId.save());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.loadAdditional(tag, regs);
        localTps = tag.getDouble("Tps");
        localMspt = tag.getDouble("Mspt");
        peerUp = tag.getBoolean("PeerUp");
        peerTps = tag.getDouble("PeerTps");
        backlog = tag.getInt("Backlog");
        rtt = tag.getInt("Rtt");
        role = tag.getString("Role");
        fails = tag.getInt("Fails");
        inFlight = tag.getInt("InFlight");
        deviceCount = tag.getInt("DeviceCount");
        freq = tag.hasUUID("Freq") ? tag.getUUID("Freq") : null;
        networkId = tag.contains("RemoteNetwork")
                ? RemoteNetworkId.read(tag.getCompound("RemoteNetwork")).orElse(null) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        return saveWithoutMetadata(regs);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
