package dev.distantstock.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import dev.distantstock.item.RequesterData;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
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

/**
 * A link monitor whose face is a real Create flap display.
 *
 * <p>It used to be a plain block entity with a picture of a flip board painted on its front. The
 * picture never changed, and a monitor showing the same frozen numbers while the link underneath
 * them moved was the complaint. So the furniture became the real thing: this extends Create's own
 * flap display, which is what gives the board its glyphs, its flip animation and the click of the
 * segments at no cost — the animation is Create's, driven on the client from the text the server
 * sends, exactly as a display board's is.
 *
 * <p>Two things had to be arranged. Create only lets its <em>own</em> display block be a controller
 * ({@code updateControllerStatus} gives up on anything that is not a {@code FlapDisplayBlock}), so
 * the size is declared here instead. And the board is not driven by a shaft: a block that is not
 * {@code IRotate} answers "speed requirement fulfilled" with true, which is what lets the flaps
 * turn while {@code updateSpeed} keeps rotation propagation away from us entirely.
 *
 * <p>What it shows is one reading at a time, two lines, turning over every few seconds — see
 * {@link #pages()}. A single block of flap display is four characters wide and two lines tall, so
 * "TPS" over "20.0" is the shape everything is cut to.
 */
public final class MonitorBlockEntity extends FlapDisplayBlockEntity implements IHaveGoggleInformation {
    private double localTps = 20;
    private double localMspt = 50;
    private boolean peerUp;
    private double peerTps;
    /** Whether a peer has said anything recently, as opposed to the link merely answering. */
    private boolean peerFresh;
    private int backlog;
    private int rtt = -1;
    private String role = "host";
    private int fails;
    private int inFlight;
    private java.util.UUID freq;
    private RemoteNetworkId networkId;
    private int deviceCount;

    /** How long one reading stays up before the board turns to the next. */
    private static final int PAGE_TICKS = 100;
    /** Which reading is on the board, so the text is only pushed when it actually changes. */
    private int shownPage = -1;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
        // Nothing turns this block, and nothing should try: without this Create's rotation
        // propagator would look for a shaft to attach to on every load.
        updateSpeed = false;
    }

    /**
     * Declares the board's size, which Create would otherwise read off its own display block.
     *
     * <p>Called by the base class every ten ticks. The size is what the layout is built from: one
     * block, so one character cell wide and two lines tall, which is where the four-character lines
     * come from.
     */
    @Override
    public void updateControllerStatus() {
        isController = true;
        xSize = 1;
        ySize = 1;
        if (lines == null) {
            initDefaultSections();
        }
    }

    /**
     * Which way the board faces, in Create's sense — the direction its front looks away from.
     *
     * <p>Overridden because Create reads the direction from a property of its own display block,
     * which our block does not carry. The renderer turns the glyph plane by this.
     */
    @Override
    public net.minecraft.core.Direction getDirection() {
        BlockState state = getBlockState();
        return state.hasProperty(MonitorBlock.FACING)
                ? state.getValue(MonitorBlock.FACING).getOpposite() : net.minecraft.core.Direction.NORTH;
    }

    /**
     * The readings the board turns through, label over value.
     *
     * <p>Four characters a line is the whole budget: a single block of flap display is that wide, and
     * anything longer would overflow the face rather than be clipped. So each page is one reading,
     * named on the top line and given on the bottom, and the board cycles instead of cramming.
     */
    private java.util.List<String[]> pages() {
        return java.util.List.of(
                new String[]{"TPS", short3(localTps)},
                new String[]{"MSPT", short3(localMspt)},
                new String[]{"PING", rtt < 0 ? "----" : Integer.toString(Math.min(rtt, 9999))},
                new String[]{"BACK", Integer.toString(Math.min(backlog, 9999))},
                new String[]{"PEER", peerUp && peerFresh ? short3(peerTps) : "----"},
                new String[]{"DEV", Integer.toString(Math.min(deviceCount, 9999))});
    }

    /** A reading in at most four characters, which is one line of the board. */
    private static String short3(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.length() <= 4 ? text : text.substring(0, 4);
    }

    /**
     * Which of the four link states this moment is.
     *
     * <p>Waiting and broken are told apart on purpose: a peer announces every forty-five seconds, so
     * a monitor that has not heard from one yet is the ordinary state of a link that was just
     * plugged in, and showing the fault colour for it would send an operator looking for a fault
     * that is not there. A failure the transport actually reported is a different thing.
     */
    private static MonitorBlock.Link linkState(LinkSnapshot.View v) {
        if (!v.linkUp()) {
            return MonitorBlock.Link.OFF;
        }
        if (v.peerFresh()) {
            return MonitorBlock.Link.ONLINE;
        }
        return v.peerFails() > 0 ? MonitorBlock.Link.FAULT : MonitorBlock.Link.SYNCING;
    }

    /** Turns the board to the reading this moment calls for, if it is not already showing it. */
    private void showPage(long gameTime) {
        java.util.List<String[]> pages = pages();
        int page = (int) ((gameTime / PAGE_TICKS) % pages.size());
        if (page == shownPage) {
            return;
        }
        shownPage = page;
        // Only on change: pushing the same text again would replay the flip and the click for
        // nothing, once a tick, for ever.
        applyTextManually(0, Component.literal(pages.get(page)[0]));
        applyTextManually(1, Component.literal(pages.get(page)[1]));
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

    /**
     * The monitor's own beat. The flap animation is Create's and runs in {@code super.tick()}, on
     * both sides; everything below is the server's half — the numbers, the lamp and the screen.
     */
    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        BlockState state = getBlockState();
        // With this monitor's own tower readout, not the bare link view. The overload that takes one
        // existed from the start and had no callers, so the tower half of the screen was sent as
        // TowerReadout.NONE every time and the page read "not on a tower" for ever. Nothing failed:
        // the packet was well formed, it just carried the answer for a monitor standing nowhere.
        LinkSnapshot.View v = LinkSnapshot.view(TowerReadout.survey(level, worldPosition));
        localTps = v.localTps();
        localMspt = v.localMspt();
        peerUp = v.linkUp();
        peerTps = v.peerTps();
        peerFresh = v.peerFresh();
        backlog = v.orderDepth() + v.packageDepth();
        rtt = (int) v.peerRttMs();
        role = v.selfId();
        fails = v.peerFails();
        inFlight = v.inFlight();
        deviceCount = freq == null ? 0 : CreateStock.deviceCount(freq);
        // The board turns to the next reading on its own beat, which is slower than this one.
        showPage(level.getGameTime());
        MonitorBlock.Status status = MonitorBlock.Status.fromTps(localTps);
        MonitorBlock.Link link = linkState(v);
        if (state.getValue(MonitorBlock.STATUS) != status
                || state.getValue(MonitorBlock.LINK) != link) {
            level.setBlock(worldPosition,
                    state.setValue(MonitorBlock.STATUS, status).setValue(MonitorBlock.LINK, link), 3);
        }
        setChanged();
        BlockState current = getBlockState();
        level.sendBlockUpdated(worldPosition, current, current, 3);
        LinkSnapshotS2C update = new LinkSnapshotS2C(worldPosition, v);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 32 * 32) {
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
        if (peerUp && !peerFresh) {
            // The link is up but the other server has not said what it is doing — a broadcast is
            // seconds apart, and one missed is not an outage. Saying "0.0 TPS" here would send an
            // operator to look for a broken server that is running fine.
            GoggleText.line(tip, "goggle.distantstock.peer_silent");
        } else if (peerUp) {
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

    /**
     * Create's half of leaving the world, because {@code setRemoved} is final on this base class.
     *
     * <p>{@code invalidate} is called from the same place and is what Create itself overrides, so
     * this is the hook that is actually reachable — and it is called on every path a block entity
     * can leave by, which is what the device list needs.
     */
    @Override
    public void invalidate() {
        LoadedDevices.remove(this);
        super.invalidate();
    }

    /**
     * Persistence through Create's hooks rather than the vanilla ones.
     *
     * <p>{@code SmartBlockEntity} makes {@code saveAdditional} final and routes it here, so this is
     * the only place a subclass may add its own keys; the flap text and layout are written by
     * {@code super} either way. The keys below are the ones this block has always used, so a
     * monitor placed before any of this keeps what it knew.
     */
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.write(tag, regs, clientPacket);
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
    protected void read(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.read(tag, regs, clientPacket);
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
    public void initialize() {
        super.initialize();
        // The base class calls this from its lazy tick too, but the board has to know its size
        // before the first render rather than ten ticks into the world.
        updateControllerStatus();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        return saveWithoutMetadata(regs);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
