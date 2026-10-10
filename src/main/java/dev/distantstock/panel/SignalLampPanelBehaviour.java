package dev.distantstock.panel;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import dev.distantstock.block.LampReadings;
import dev.distantstock.block.LampState;
import dev.distantstock.stock.NetworkHealth;
import dev.distantstock.block.SignalLampModels;
import dev.distantstock.item.SignalLampPanelItem;
import dev.distantstock.stock.CreateStock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.logistics.board.connection.PanelConnectionBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.UUID;

/**
 * A signal lamp as a panel <em>type</em>: the same light, on anybody's board.
 *
 * <p>A lamp is a panel whose filter holds a lamp item, and from there everything else follows. Wired,
 * it reports the worst of the gauges pointing at it. Bound to a frequency, it reports the network
 * itself. Both ladders are {@link LampReadings}, which our own lamp board reads through as well, so
 * one colour means one thing wherever the lamp is standing.
 *
 * <p>A lamp has no amount, but it still owns a value panel: the same board picks the andesite lamp's
 * mode. The mode lives in {@code count}, which is otherwise unused on a lamp and is persisted and
 * synced with the rest of Create's panel state (see {@link CreateStatePanelBehaviour}).
 *
 * <p><b>What a lamp on somebody else's board does not carry is the watch list.</b> The brass lamp's
 * list of watched items, and the screen that edits it, are answered by our own lamp board's block
 * entity, which knows the position the menu was opened on. A lamp elsewhere still reports the state
 * of the network it is bound to — it simply cannot be told which items to look at.
 */
public class SignalLampPanelBehaviour extends CreateStatePanelBehaviour {
    /**
     * The Create frequency this lamp reports on, or null while it reports its wired gauges.
     *
     * <p>Not Create's {@code network}: that one is the frequency the panel itself sits on, which a
     * lamp never orders from, and a field of the same name here would hide it from everything that
     * reads a panel's frequency.
     */
    private UUID boundNetwork;
    /** Sampled on the server; travels to the client with the panel, which cannot sample it. */
    private NetworkHealth health = NetworkHealth.UNKNOWN;
    /** What the housing showed when last synced, so a change can be redrawn. Server only. */
    private Look shown;

    /** The parts of a lamp's look that need its model rebuilt. */
    private record Look(SignalLampPanelItem.Color color, boolean lit) {
    }

    public SignalLampPanelBehaviour(PanelType<?> type, FactoryPanelBlockEntity board,
                                    FactoryPanelBlock.PanelSlot slot) {
        super(type, board, slot);
    }

    /** The lamp in this slot, or null when the filter holds something else. */
    public SignalLampPanelItem lamp() {
        return SignalLampPanelItem.from(getFilter());
    }

    public boolean isLampSlot() {
        return lamp() != null;
    }

    /** The mode, stored in the count Create would otherwise use for an amount. */
    public boolean inverted() {
        return count != 0;
    }

    public UUID lampNetwork() {
        return boundNetwork;
    }

    /** Points this lamp at a Create network, or back at its wired gauges when null. */
    public void setLampNetwork(UUID freq) {
        boundNetwork = freq;
        health = freq == null ? NetworkHealth.UNKNOWN : CreateStock.health(freq, 3);
        refreshLook();
        blockEntity.sendData();
        blockEntity.setChanged();
    }

    /**
     * What this lamp is reporting right now, or null when it is connected to nothing.
     *
     * <p>Null is a real answer: an unwired, unbound lamp is dark, and it must not read as green.
     */
    public LampState lampState() {
        if (boundNetwork != null) {
            // Without a reachable network every per-item reading is just "no stock", which would
            // masquerade as a shortage. The missing network is the real problem.
            return LampReadings.ofNetwork(health);
        }
        return LampReadings.worstFromGauges(blockEntity.getLevel(), this);
    }

    /**
     * Whether the light is on at all, before the blink phase is applied.
     *
     * <p>Brass reports a rung, so it is lit whenever it has one. Andesite is a plain light and works
     * the way it does on our own lamp board: lit while what it watches is satisfied, or — inverted —
     * while it is not.
     */
    public boolean lit() {
        SignalLampPanelItem lamp = lamp();
        if (lamp == null) {
            return false;
        }
        if (lamp.material() == SignalLampPanelItem.Material.BRASS) {
            return lampState() != null;
        }
        if (boundNetwork != null) {
            LampState state = LampReadings.ofNetwork(health);
            boolean healthy = state == LampState.IDLE || state == LampState.ALL_GOOD;
            return inverted() != healthy;
        }
        return LampReadings.wiredLit(blockEntity.getLevel(), this, inverted());
    }

    /** The colour the housing shows: a brass lamp's rung, or an andesite lamp's own colour. */
    private SignalLampPanelItem.Color color(SignalLampPanelItem lamp) {
        LampState level = lamp.material() == SignalLampPanelItem.Material.BRASS ? lampState() : null;
        return level != null ? LampReadings.colorFor(level) : lamp.color();
    }

    /**
     * Rebuilds the board's model when this lamp's look has changed.
     *
     * <p>A panel on somebody else's board is drawn into the board's baked model, not by a renderer
     * that asks every frame, so a change of colour shows only once the board is told to redraw.
     */
    private void refreshLook() {
        SignalLampPanelItem lamp = lamp();
        Look next = lamp == null ? null : new Look(color(lamp), lit());
        if (java.util.Objects.equals(next, shown)) {
            return;
        }
        shown = next;
        if (blockEntity instanceof FactoryPanelBlockEntity board) {
            board.redraw = true;
        }
        blockEntity.sendData();
    }

    @Override
    public void addConnections(PanelConnectionBuilder builder) {
        // A lamp is pointed at, not pointing: gauges connect to it and it reads them. Deployer's
        // connection system carries values between panels, and a lamp has no value to offer.
    }

    /**
     * A logic gauge from another mod tells the panels it points at the moment it flips, rather than
     * waiting for the once-a-second check in {@link #tick()}.
     */
    @Override
    public void notifiedFromInput() {
        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide && isActive()) {
            refreshLook();
        }
    }

    /** The lamp item itself, so the goggles and Deployer's screen name the lamp that is there. */
    @Override
    public Item getItem() {
        SignalLampPanelItem lamp = lamp();
        return lamp != null ? lamp : dev.distantstock.item.ModItems.CYAN_INDICATOR_LAMP.get();
    }

    @Override
    public PartialModel getModel(FactoryPanelBlock.PanelState state, FactoryPanelBlock.PanelType type) {
        SignalLampPanelItem lamp = lamp();
        if (lamp == null) {
            // Only while the first sync is in flight; a lamp always carries its item as the filter.
            return null;
        }
        return SignalLampModels.lamp(lamp.material(), color(lamp), lit());
    }

    @Override
    public void tick() {
        super.tick();
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide || !isActive()) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        if (boundNetwork != null) {
            NetworkHealth next = CreateStock.health(boundNetwork, 3);
            if (!next.equals(health)) {
                health = next;
                blockEntity.sendData();
            }
        }
        // Wired lamps change with the gauges pointing at them, which say nothing to the lamp.
        refreshLook();
    }

    // ------------------------------------------------------------------ the mode value panel

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
    public ValueSettingsBehaviour.ValueSettings getValueSettings() {
        return isLampSlot()
                ? new ValueSettingsBehaviour.ValueSettings(0, inverted() ? 1 : 0)
                : super.getValueSettings();
    }

    @Override
    public void setValueSettings(Player player, ValueSettingsBehaviour.ValueSettings settings,
                                 boolean ctrlDown) {
        if (!isLampSlot()) {
            super.setValueSettings(player, settings, ctrlDown);
            return;
        }
        count = Math.clamp(settings.value(), 0, 1);
        blockEntity.setChanged();
        refreshLook();
        blockEntity.sendData();
    }

    @Override
    public boolean isCountVisible() {
        return isLampSlot() || super.isCountVisible();
    }

    /**
     * Create's gauge label reports "no target amount set" while count is zero, which on a lamp just
     * means the normal mode. A lamp names itself instead.
     */
    @Override
    public MutableComponent getLabel() {
        return isLampSlot() ? getFilter().getHoverName().copy() : super.getLabel();
    }

    @Override
    public MutableComponent getCountLabelForValueBox() {
        return isLampSlot()
                ? Component.translatable(inverted() ? "gui.distantstock.lamp.mode.inverted"
                        : "gui.distantstock.lamp.mode.normal")
                : super.getCountLabelForValueBox();
    }

    @Override
    public MutableComponent getAmountTip() {
        return isLampSlot() ? Component.translatable("gui.distantstock.lamp.mode.tip")
                : super.getAmountTip();
    }

    /** The copy the board drops when this panel is taken off it. */
    @Override
    public List<ItemStack> getItemDrops() {
        ItemStack lampStack = lamp() == null ? ItemStack.EMPTY : getFilter().copyWithCount(1);
        return lampStack.isEmpty() ? super.getItemDrops() : List.of(lampStack);
    }

    /**
     * 手上拿着终端时，这一下是**绑定**而不是开界面 —— 和港、请求器、远仓仪表同一套手势。
     *
     * <p>灯和仪表一样是"指着某台仓库的设备"，而它一旦装在别人的板子上，方块那条路就走不到，
     * 只剩界面这一条 —— 界面里又没有调谐的地方。所以这一句是它在别人板子上唯一能被配置的入口。
     */
    @Override
    public void displayScreen(net.minecraft.world.entity.player.Player player) {
        if (dev.distantstock.client.TerminalPanelGesture.bindInsteadOfScreen(player, blockEntity, slot)) {
            return;
        }
        super.displayScreen(player);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void easyWrite(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyWrite(tag, registries, clientPacket);
        if (boundNetwork != null) {
            tag.putUUID("LampNetwork", boundNetwork);
            if (clientPacket) {
                // Sampling needs Create's logistics, which only exist on the server.
                CompoundTag row = new CompoundTag();
                row.putBoolean("Known", health.known());
                row.putInt("Loaded", health.loadedLinks());
                row.putInt("Total", health.totalLinks());
                row.putBoolean("Idle", health.idle());
                row.putBoolean("Locked", health.locked());
                tag.put("LampHealth", row);
            }
        }
    }

    @Override
    public void easyRead(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.easyRead(tag, registries, clientPacket);
        boundNetwork = tag.hasUUID("LampNetwork") ? tag.getUUID("LampNetwork") : null;
        if (boundNetwork != null && clientPacket && tag.contains("LampHealth")) {
            CompoundTag row = tag.getCompound("LampHealth");
            health = new NetworkHealth(row.getBoolean("Known"), row.getInt("Loaded"),
                    row.getInt("Total"), row.getBoolean("Idle"), row.getBoolean("Locked"), List.of());
        } else if (boundNetwork == null) {
            health = NetworkHealth.UNKNOWN;
        }
    }
}
