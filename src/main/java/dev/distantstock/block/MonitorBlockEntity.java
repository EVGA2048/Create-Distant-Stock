package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.distantstock.link.LinkSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MonitorBlockEntity be) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        LinkSnapshot.View v = LinkSnapshot.view();
        be.localTps = v.localTps();
        be.localMspt = v.localMspt();
        be.peerUp = v.peerUp();
        be.peerTps = v.peerTps();
        be.backlog = v.orderDepth() + v.packageDepth();
        be.rtt = (int) v.peerRttMs();
        be.role = v.selfId();
        be.fails = v.peerFails();
        be.inFlight = v.inFlight();
        be.setChanged();
        level.sendBlockUpdated(pos, state, state, 3);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
        GoggleText.title(tip, "block.distantstock.monitor");
        GoggleText.line(tip, "goggle.distantstock.local_tps", fmt(localTps), fmt(localMspt));
        if (peerUp) {
            GoggleText.line(tip, "goggle.distantstock.peer_tps", fmt(peerTps));
        } else {
            GoggleText.value(tip, "goggle.distantstock.peer_down", ChatFormatting.RED);
        }
        GoggleText.line(tip, "goggle.distantstock.pressure", backlog, rtt < 0 ? "—" : rtt);
        if (sneaking) {
            GoggleText.line(tip, "goggle.distantstock.role", role);
            GoggleText.line(tip, "goggle.distantstock.fails", fails);
            GoggleText.line(tip, "goggle.distantstock.in_flight", inFlight);
        }
        return true;
    }

    private static String fmt(double n) {
        return String.format(Locale.ROOT, "%.1f", n);
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
