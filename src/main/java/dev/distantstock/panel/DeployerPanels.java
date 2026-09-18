package dev.distantstock.panel;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.DistantStock;
import dev.distantstock.routing.RemoteNetworkId;
import net.liukrast.deployer.lib.logistics.board.AbstractPanelBehaviour;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.registry.DeployerRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

/**
 * Distant Stock's panels as types Create: Deployer knows about, so they can live on any board.
 *
 * <p>Without Deployer a panel of ours is a whole block: a remote gauge board is the block, and its
 * four slots are the most a player can have. With it, a remote gauge is a <em>kind</em> of panel —
 * it can sit in one slot of a factory gauge board, beside a plain gauge or beside a gauge from
 * another mod, and the board underneath stays whoever's it was.
 *
 * <p><b>Every reference to Deployer is in this package, and nothing here is loaded unless Deployer
 * is present.</b> Callers check {@code ModList.isLoaded("deployer")} first and never name these
 * types in their own signatures, so on a pack without Deployer the JVM never resolves them and the
 * mod keeps working with the two blocks it has always had.
 *
 * <p>What callers outside this package need is all static methods here rather than a handle on a
 * behaviour: a caller that held one would have to name its type, which is the one thing that would
 * break the pack that does not have Deployer.
 */
public final class DeployerPanels {
    private static final DeferredRegister<PanelType<?>> PANELS =
            DeferredRegister.create(DeployerRegistries.PANEL_KEY, DistantStock.MODID);

    public static final DeferredHolder<PanelType<?>, PanelType<RemoteGaugePanelBehaviour>> REMOTE_GAUGE =
            PANELS.register("remote_gauge",
                    () -> new PanelType<>(RemoteGaugePanelBehaviour::new, RemoteGaugePanelBehaviour.class));

    public static final DeferredHolder<PanelType<?>, PanelType<SignalLampPanelBehaviour>> SIGNAL_LAMP =
            PANELS.register("signal_lamp",
                    () -> new PanelType<>(SignalLampPanelBehaviour::new, SignalLampPanelBehaviour.class));

    private DeployerPanels() {
    }

    public static void register(IEventBus bus) {
        PANELS.register(bus);
    }

    // ------------------------------------------------------------------ what any board can ask

    /**
     * Puts a remote gauge into an empty slot of any board, whoever's board it is.
     *
     * <p>The steps are Deployer's own — {@code PanelBlockItem.applyToSlot} does exactly these — and
     * they are repeated rather than borrowed because borrowing would mean keeping an unregistered
     * {@code BlockItem} around purely to call one method on it. Everything used here is Create's
     * public surface: enable the behaviour, give it its network, attach it, put it in the slot, and
     * tell the board to redraw and resync.
     *
     * @return false when the slot is taken or already in use, in which case nothing changed
     */
    public static boolean install(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                  UUID network) {
        AbstractPanelBehaviour behaviour = create(board, slot, REMOTE_GAUGE.get());
        if (behaviour == null) {
            return false;
        }
        behaviour.setNetwork(network);
        return attach(board, slot, behaviour);
    }

    /**
     * Puts a signal lamp into an empty slot of any board, carrying the lamp item it was placed with.
     *
     * <p>A lamp is a panel whose filter holds a lamp item, so the item comes with it: which material
     * and which colour the light is, is the filter, exactly as it is on our own lamp board.
     */
    public static boolean installLamp(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                      net.minecraft.world.item.ItemStack lamp) {
        AbstractPanelBehaviour behaviour = create(board, slot, SIGNAL_LAMP.get());
        if (!(behaviour instanceof SignalLampPanelBehaviour lampBehaviour)) {
            return false;
        }
        // No network: a lamp starts wired to whatever is pointed at it, and is bound to a frequency
        // only when the player says so with a stock link.
        lampBehaviour.setFilter(lamp.copyWithCount(1));
        lampBehaviour.count = 0;
        return attach(board, slot, behaviour);
    }

    /** A behaviour of this type for this slot, or null when the slot is not free. */
    private static AbstractPanelBehaviour create(FactoryPanelBlockEntity board,
                                                 FactoryPanelBlock.PanelSlot slot,
                                                 PanelType<?> type) {
        FactoryPanelBehaviour existing = board.panels.get(slot);
        if (existing == null || existing.isActive()) {
            return null;
        }
        AbstractPanelBehaviour behaviour = type.create(board, slot);
        if (behaviour != null) {
            behaviour.active = true;
        }
        return behaviour;
    }

    private static boolean attach(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                  AbstractPanelBehaviour behaviour) {
        board.attachBehaviourLate(behaviour);
        board.panels.put(slot, behaviour);
        board.redraw = true;
        board.lastShape = null;
        board.notifyUpdate();
        return true;
    }

    /**
     * The model an outside panel wants for itself, or null when it has not got one.
     *
     * <p>Asked by the renderers of our own boards. A board of ours can hold another mod's panel —
     * Deployer puts them there and the placement gesture hands them over — and the panel knows what
     * it looks like: Create's model wrapper asks exactly this question for panels on Create's
     * boards. A slot drawn with our housing instead was the bug the player reported, an Extra
     * Gauges gauge wearing remote-gauge blue.
     *
     * <p>Asked through here rather than from the renderer because naming Deployer's types anywhere
     * else would break the pack that does not have Deployer installed. A plain Create panel is not
     * one of Deployer's either, so it answers null and the caller draws Create's own housing.
     */
    public static dev.engine_room.flywheel.lib.model.baked.PartialModel modelOf(
            FactoryPanelBehaviour behaviour, FactoryPanelBlock.PanelState state,
            FactoryPanelBlock.PanelType type) {
        return behaviour instanceof AbstractPanelBehaviour panel ? panel.getModel(state, type) : null;
    }

    /** Whether the given slot of the given board holds a remote gauge of ours. */
    public static boolean holdsRemoteGauge(FactoryPanelBlockEntity board,
                                           FactoryPanelBlock.PanelSlot slot) {
        return board.panels.get(slot) instanceof RemoteGaugePanelBehaviour;
    }

    /** Whether the given slot of the given board holds one of our signal lamps. */
    public static boolean holdsSignalLamp(FactoryPanelBlockEntity board,
                                          FactoryPanelBlock.PanelSlot slot) {
        return board.panels.get(slot) instanceof SignalLampPanelBehaviour;
    }

    /**
     * Binds the lamp in this slot to a Create network, or back to its wired gauges when freq is null.
     *
     * @return false when that slot holds no lamp of ours
     */
    public static boolean bindLamp(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                   UUID freq) {
        if (board.panels.get(slot) instanceof SignalLampPanelBehaviour lamp) {
            lamp.setLampNetwork(freq);
            return true;
        }
        return false;
    }

    /** The create network the lamp in this slot reports on, or null when it reads its gauges. */
    public static UUID lampNetwork(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        return board.panels.get(slot) instanceof SignalLampPanelBehaviour lamp ? lamp.lampNetwork()
                : null;
    }

    /**
     * Points the remote gauge in this slot at a warehouse.
     *
     * @return false when that slot holds no remote gauge, so a caller can fall through to whatever
     *         else the click might mean
     */
    public static boolean bind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                               RemoteNetworkId network, UUID receivingGroup, String address) {
        if (network == null || !(board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote)) {
            return false;
        }
        remote.orders().bind(new dev.distantstock.block.RemoteBinding(network, receivingGroup, address));
        return true;
    }

    /**
     * Re-points an existing binding at a different destination and address, keeping its warehouse.
     *
     * <p>The warehouse is the one thing a player cannot type: it is a Create logistics network on
     * some node, and the only way to name it is to stand in front of it with a tuned terminal. So
     * the gesture keeps that job and the screen takes the other two — which is the pair a player
     * actually changes, and the pair nobody could reach before.
     *
     * @return false when this slot holds no bound remote gauge, so the caller can say why
     */
    public static boolean rebind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                                 java.util.UUID receivingGroup, String address) {
        if (!(board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote)) {
            return false;
        }
        dev.distantstock.block.RemoteBinding current = remote.orders().binding();
        if (current == null) {
            return false;
        }
        // receivingGroup == null = 保持原来那个组（界面上那一格空的，或者名字认不出来）。
        remote.orders().bind(new dev.distantstock.block.RemoteBinding(current.network(),
                receivingGroup == null ? current.receivingGroup() : receivingGroup, address,
                current.homeAddress()));
        return true;
    }

    /**
     * 把手势那一下绑上去：整份绑定一起写，网络也算数。
     *
     * <p>和 {@link #rebind} 的区别是那个只改"港组 + 地址"—— 界面上的两个框只能表达这两样。手持终端
     * 那条路不一样：终端身上带着完整的四个字段（网络、港组、两个地址），玩家换仓库是整台换掉。
     */
    public static boolean bind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot,
                               dev.distantstock.block.RemoteBinding binding) {
        if (!(board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote)) {
            return false;
        }
        remote.orders().bind(binding);
        return true;
    }

    /** The address this panel's goods carry, or an empty string when it has no binding. */
    public static String addressOf(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        if (!(board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote)) {
            return "";
        }
        dev.distantstock.block.RemoteBinding binding = remote.orders().binding();
        return binding == null ? "" : binding.address();
    }

    /** Unbinds the remote gauge in this slot, leaving it an ordinary factory gauge. */
    public static boolean unbind(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        if (!(board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote)) {
            return false;
        }
        remote.orders().unbind();
        return true;
    }

    /** How much the remote gauge in this slot has asked for and not yet seen arrive. */
    public static int outstandingIn(FactoryPanelBlockEntity board, FactoryPanelBlock.PanelSlot slot) {
        return board.panels.get(slot) instanceof RemoteGaugePanelBehaviour remote
                ? remote.orders().outstanding()
                : 0;
    }
}
