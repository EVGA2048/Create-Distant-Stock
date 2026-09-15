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
        FactoryPanelBehaviour existing = board.panels.get(slot);
        if (existing == null || existing.isActive()) {
            return false;
        }
        AbstractPanelBehaviour behaviour = REMOTE_GAUGE.get().create(board, slot);
        if (behaviour == null) {
            return false;
        }
        behaviour.active = true;
        behaviour.setNetwork(network);
        board.attachBehaviourLate(behaviour);
        board.panels.put(slot, behaviour);
        board.redraw = true;
        board.lastShape = null;
        board.notifyUpdate();
        return true;
    }

    /** Whether the given slot of the given board holds a remote gauge of ours. */
    public static boolean holdsRemoteGauge(FactoryPanelBlockEntity board,
                                           FactoryPanelBlock.PanelSlot slot) {
        return board.panels.get(slot) instanceof RemoteGaugePanelBehaviour;
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
