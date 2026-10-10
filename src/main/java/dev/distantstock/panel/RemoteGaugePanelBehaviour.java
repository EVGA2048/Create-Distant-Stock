package dev.distantstock.panel;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelSupportBehaviour;
import com.simibubi.create.content.redstone.displayLink.source.FactoryGaugeDisplaySource;
import dev.distantstock.block.RemoteGaugeModels;
import dev.distantstock.block.RemoteOrderSlot;
import dev.distantstock.item.ModItems;
import dev.distantstock.routing.TowerActivation;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.data.IntAttached;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.logistics.board.connection.PanelConnectionBuilder;
import net.liukrast.deployer.lib.logistics.board.connection.StockConnection;
import net.liukrast.deployer.lib.registry.DeployerPanelConnections;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;

/**
 * A remote gauge as a panel <em>type</em>: the same machine, able to sit on anybody's board.
 *
 * <p>Everything a gauge is, Create already provides — the filter, the amount, the value box, the
 * reading of the network the board is attached to — because Deployer's base class is Create's own
 * panel behaviour. What is added here is the half that is ours: where the goods come from when the
 * reading is too low. That is {@link RemoteOrderSlot}, the same object the board's four slots use,
 * so a remote gauge on somebody else's board counts exactly like a remote gauge on ours.
 *
 * <p>The pace is the panel's own, and it is the same pace the board uses: once a second, and only
 * while a tower carries this position. A panel that ordered on every tick would drain a warehouse;
 * one that ordered without a tower would spend stock nothing is able to move.
 */
public class RemoteGaugePanelBehaviour extends CreateStatePanelBehaviour {
    /**
     * The text a display link shows for a factory gauge. Create's registered instance sits behind
     * Registrate, which this project does not compile against; {@code createEntry} keeps no state,
     * so a private instance says the same thing.
     */
    private static final FactoryGaugeDisplaySource GAUGE_STATUS = new FactoryGaugeDisplaySource();

    private final RemoteOrderSlot orders;
    /** Distant Stock network joined by this panel, independent of its selected source warehouse. */
    private java.util.UUID distantNetworkScope;

    public RemoteGaugePanelBehaviour(PanelType<?> type, FactoryPanelBlockEntity board,
                                     FactoryPanelBlock.PanelSlot slot) {
        super(type, board, slot);
        this.orders = new RemoteOrderSlot(board, slot);
    }

    /** Where this panel orders from. Never null; a panel with no binding simply does not order. */
    public RemoteOrderSlot orders() {
        return orders;
    }

    public java.util.UUID distantNetworkScope() {
        if (distantNetworkScope != null) return distantNetworkScope;
        var binding = orders.binding();
        return binding != null && binding.distantNetworkKnown()
                ? binding.distantNetworkId() : null;
    }

    public void setDistantNetworkScope(java.util.UUID scope) {
        distantNetworkScope = scope != null
                && dev.distantstock.routing.DistantNetworkDirectory.isFormalId(scope) ? scope : null;
        var binding = orders.binding();
        if (binding != null && distantNetworkScope != null
                && binding.distantNetworkKnown()
                && !distantNetworkScope.equals(binding.distantNetworkId())) {
            orders.unbind();
        }
        blockEntity.setChanged();
        blockEntity.sendData();
    }

    /**
     * The same connections a factory gauge has under Deployer, so the remote gauge on somebody
     * else's board wires the way the one on our board does.
     *
     * <p>Without them the panel was a dead end: a factory gauge could not use it as an ingredient,
     * and an Extra Gauges logic or number gauge could neither pause it nor set its amount, nor read
     * it back. The stock connection names the filter item, as a factory gauge's does, so a recipe
     * pointing at this panel counts the stock it watches.
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void addConnections(PanelConnectionBuilder builder) {
        builder.registerBoth((DeferredHolder) DeployerPanelConnections.STOCK_CONNECTION,
                () -> filter.item().isEmpty() ? null : StockConnection.itemStack(filter.item()));
        builder.registerBoth(DeployerPanelConnections.REDSTONE, () -> satisfied && count != 0);
        builder.registerBoth(DeployerPanelConnections.NUMBERS, () -> (float) getLevelInStorage());
        builder.registerBoth(DeployerPanelConnections.STRING, () -> {
            IntAttached<MutableComponent> entry = GAUGE_STATUS.createEntry(getWorld(), getPanelPosition());
            return entry == null ? null : entry.getFirst() + entry.getValue().getString();
        });
    }

    /**
     * What arrives on those connections, applied the way Create applies it to a factory gauge.
     *
     * <p>Deployer seals {@code checkForRedstoneInput} on its panel types and calls this instead, so
     * a panel type that does not read its inputs here ignores them all.
     */
    @Override
    public void notifiedFromInput() {
        if (!active) {
            return;
        }
        boolean changed = false;
        List<Boolean> powered = getAllValues(DeployerPanelConnections.REDSTONE.get());
        boolean shouldPower = powered != null && powered.stream().anyMatch(b -> b);
        for (FactoryPanelConnection connection : targetedByLinks.values()) {
            if (!getWorld().isLoaded(connection.from.pos())) {
                return;
            }
            FactoryPanelSupportBehaviour link = linkAt(getWorld(), connection);
            if (link == null) {
                return;
            }
            shouldPower |= link.shouldPanelBePowered();
        }
        if (shouldPower != redstonePowered) {
            redstonePowered = shouldPower;
            changed = true;
        }
        List<Float> numbers = getAllValues(DeployerPanelConnections.NUMBERS.get());
        if (numbers != null && !numbers.isEmpty()) {
            int total = (int) numbers.stream().reduce(0f, Float::sum).floatValue();
            if (total != count) {
                count = total;
                changed = true;
            }
        }
        List<String> strings = getAllValues(DeployerPanelConnections.STRING.get());
        if (strings != null && !strings.isEmpty()) {
            String address = String.join("", strings);
            if (!address.equals(recipeAddress)) {
                recipeAddress = address;
                changed = true;
            }
        }
        if (changed) {
            blockEntity.notifyUpdate();
        }
    }

    @Override
    public Item getItem() {
        return ModItems.REMOTE_GAUGE.get();
    }

    @Override
    public PartialModel getModel(FactoryPanelBlock.PanelState state, FactoryPanelBlock.PanelType type) {
        return RemoteGaugeModels.panel(type == FactoryPanelBlock.PanelType.PACKAGER,
                state == FactoryPanelBlock.PanelState.ACTIVE);
    }

    /**
     * Opens the panel's own screen: Create's, with the two cross-server fields added below it.
     *
     * <p>Without this a remote gauge opens exactly what a factory gauge opens, and its cross-server
     * half is reachable only by a gesture and visible only through goggles — which is why it read as
     * "just an ordinary factory gauge with no options".
     *
     * <p>Client only, like Create's own: {@code ScreenOpener} does not exist on a server, so the
     * class that names it is loaded only on the client.
     */
    @Override
    public void displayScreen(net.minecraft.world.entity.player.Player player) {
        // 手上拿着终端时这一下是**绑定**，不是开界面 —— 港、请求器、我们自己的仪表板都是这么分的。
        // 少了这一句，装在别人板子上的这一格面板就永远配不了：它的界面会先弹出来，而终端那条路
        // （方块自己的 {@code useItemOn}）根本走不到这儿。玩家 2026-09-17 报的就是这个。
        if (dev.distantstock.client.TerminalPanelGesture.bindInsteadOfScreen(player, blockEntity, slot)) {
            return;
        }
        dev.distantstock.client.RemoteGaugeScreen.open(this);
    }

    @Override
    public void tick() {
        if (!dev.distantstock.block.RemoteGaugeBlockEntity.localNetworkConfigured(network)) {
            return;
        }
        super.tick();
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide || !isActive()) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        if (!TowerActivation.active(level, blockEntity.getBlockPos())) {
            return;
        }
        orders.tick();
    }

    @Override
    public void lazyTick() {
        if (!dev.distantstock.block.RemoteGaugeBlockEntity.localNetworkConfigured(network)) {
            return;
        }
        super.lazyTick();
    }

    @Override
    public void easyWrite(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyWrite(tag, registries, clientPacket);
        CompoundTag ours = new CompoundTag();
        orders.save(ours);
        if (distantNetworkScope != null) {
            ours.putUUID("DistantNetworkScope", distantNetworkScope);
        }
        tag.put("DistantStock", ours);
    }

    @Override
    public void easyRead(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyRead(tag, registries, clientPacket);
        if (!tag.hasUUID("Freq")) {
            // Saved before the frequency was kept (see CreateStatePanelBehaviour). The constructor's
            // random one is a network nobody tuned, so the panel waits to be tuned instead.
            network = dev.distantstock.block.RemoteGaugeBlockEntity.UNCONFIGURED_LOCAL_NETWORK;
        }
        if (tag.contains("DistantStock")) {
            CompoundTag ours = tag.getCompound("DistantStock");
            orders.load(ours, clientPacket);
            distantNetworkScope = ours.hasUUID("DistantNetworkScope")
                    ? ours.getUUID("DistantNetworkScope")
                    : orders.binding() != null && orders.binding().distantNetworkKnown()
                    ? orders.binding().distantNetworkId() : null;
        }
    }
}
