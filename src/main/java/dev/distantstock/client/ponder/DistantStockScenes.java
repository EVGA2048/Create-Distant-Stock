package dev.distantstock.client.ponder;

import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockStatus;
import dev.distantstock.block.TowerCasingBlock;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;

/**
 * The structures behind these scenes live in {@code assets/distantstock/ponder/*.nbt} and are
 * written by {@code scripts/gen_ponder_structures.py}; the wording lives in
 * {@code distantstock.ponder.<title>.text_N}. All three have to agree, so every layout is spelled
 * out in the comment above its scene.
 *
 * Every placement is one that actually works. A scene is the only place most players ever learn how
 * a machine goes together, so it must not show a layout that would quietly do nothing in a world.
 */
public final class DistantStockScenes {

    /**
     * export.nbt: chest(3,2,4) - packager(3,2,3) - funnel(3,2,2) over belt(1..5,1,2) running east
     * into dock(6,1,2); cogwheel(1,1,3) drives it, andesite plinths under (3,1,3) and (3,1,4).
     *
     * The reveals follow the goods: the packing station first, then the dock that receives them,
     * and the belt that joins the two last of all. A belt is the one piece here that only reads
     * correctly once both of its ends have somewhere to be, so it goes in after them rather than
     * dangling across an empty floor for half the scene.
     */
    public static void export(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_export", "Sending goods from a warehouse");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().fromTo(3, 1, 2, 3, 2, 4), Direction.DOWN);
        scene.overlay().showText(95)
                .text("Every parcel begins in storage. Set a Distant Packager against the chest and "
                        + "turn it to face the line: when an order arrives it seals the goods into a "
                        + "Distant Parcel and hands it on by itself.")
                .pointAt(util.vector().centerOf(3, 2, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.world().showSection(util.select().position(6, 1, 2), Direction.DOWN);
        scene.overlay().showText(85)
                .text("This is the dock, the one door the goods leave through. It holds a single "
                        + "parcel at a time, so everything upstream of it is a queue rather than a "
                        + "buffer.")
                .pointAt(util.vector().centerOf(6, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(1, 1, 2, 5, 1, 2), Direction.DOWN);
        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN);
        scene.overlay().showText(90)
                .text("A belt joins the two. Keep the run flat and let its last block point into "
                        + "the dock: the parcel is handed over as it arrives, with nobody standing "
                        + "there to feed it in.")
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.world().modifyBlock(util.grid().at(6, 1, 2),
                state -> state.setValue(DockBlock.STATUS, DockStatus.SENDING), false);
        scene.overlay().showText(95)
                .text("With a parcel aboard, the lift rises and passes it through the ether "
                        + "surface. The two stock networks stay separate throughout: only the "
                        + "sealed parcel crosses between them.")
                .pointAt(util.vector().centerOf(6, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.RED, "fallback", util.select().position(6, 1, 2), 80);
        scene.overlay().showText(100)
                .text("The underside is the fallback face, and it cannot be seen from here. Leave it "
                        + "clear, or a parcel that comes back will have nowhere to go.")
                .pointAt(util.vector().blockSurface(util.grid().at(6, 1, 2), Direction.DOWN))
                .placeNearTarget();
        scene.idle(105);
        scene.markAsFinished();
    }

    /**
     * import.nbt: dock(2,2,2) - hopper(2,1,2) below it - belt(3..5,1,2) running east into
     * chest(6,1,2); cogwheel(3,1,3) drives it.
     */
    public static void receive(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_import", "Receiving on the far server");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().position(2, 2, 2), Direction.DOWN);
        scene.overlay().showText(90)
                .text("On the server that receives, the dock is given a delivery address instead of "
                        + "a destination. Sneak-click it with a Requester to write one in.")
                .pointAt(util.vector().centerOf(2, 2, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.world().showSection(util.select().fromTo(2, 1, 2, 5, 1, 2), Direction.DOWN);
        scene.world().showSection(util.select().position(3, 1, 3), Direction.DOWN);
        scene.overlay().showText(95)
                .text("A hopper gathers what the dock releases from underneath, and the belt carries "
                        + "it on to storage. The same two blocks serve the other direction as well, "
                        + "which is why the fallback face is worth keeping clear.")
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.world().showSection(util.select().position(6, 1, 2), Direction.DOWN);
        scene.overlay().showText(90)
                .text("The far end is an ordinary chest. Nothing here knows what the goods cost on "
                        + "the other server, and nothing there knows what became of them here; the "
                        + "parcel is the only thing that travelled.")
                .pointAt(util.vector().centerOf(6, 1, 2))
                .placeNearTarget();
        scene.idle(95);
        scene.markAsFinished();
    }

    /**
     * tune.nbt: dock(1,1,3) facing south - gauge(5,1,3) facing south.
     *
     * The two stand well apart. They are separate pieces of furniture with separate jobs, and an
     * earlier version had them touching, which made them read as one machine.
     */
    public static void tune(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_tune", "Tuning and ordering");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().position(5, 1, 3), Direction.DOWN);
        scene.overlay().showText(85)
                .text("The Request Desk is the Requester in furniture form. It carries the same "
                        + "network and the same address, so an order written here is an order the "
                        + "packing line can read.")
                .pointAt(util.vector().centerOf(5, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN);
        scene.overlay().showText(90)
                .text("Sneak-click a dock with a tuned terminal and it joins the system you have "
                        + "selected; click it plainly and it sends to that system. The desk reports "
                        + "what it did in the line above your hotbar.")
                .pointAt(util.vector().centerOf(1, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.overlay().showText(85)
                .text("The desk is a place to stand and write, not a machine on the goods path. Give "
                        + "it its own spot beside the line, where the belt will not have to route "
                        + "around it later.")
                .independent(32);
        scene.idle(90);
        scene.markAsFinished();
    }

    /**
     * replenish.nbt: cardboard wall x2..6 / y1..2 / z4, remote gauge(3,1,3), distant redstone
     * requester(5,1,3), hopper(2,1,2), dock(2,2,2), chest(6,1,2).
     *
     * <p>Two machines and the door their goods come through. The scene is about the gesture that
     * points them at a warehouse, so the panels are not written into the structure: what a board
     * looks like with a filter on it is Create's business, and the text says what to put there.
     */
    public static void replenish(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_replenish", "Ordering from another server");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().position(3, 1, 3), Direction.DOWN);
        scene.overlay().showText(95)
                .text("A Distant Gauge is a factory gauge that orders for itself. Put a panel on the "
                        + "board, set its item and the amount you want to keep, and that panel will "
                        + "watch the number for you.")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(95)
                .text("Right-click that panel with a tuned Requester to bind it to that requester's "
                        + "warehouse and receiving group; sneak-click to unbind. Each of the four "
                        + "panels is bound on its own, so one board can draw from four places.")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(95)
                .text("When the amount falls short of the target it orders the difference. One order "
                        + "at a time: the next one waits until the stock arrives or the first is "
                        + "written off, and a single order never asks for more than a stack.")
                .independent(30);
        scene.idle(100);

        scene.world().showSection(util.select().position(5, 1, 3), Direction.DOWN);
        scene.overlay().showText(95)
                .text("The Distant Redstone Requester is the same idea on a pulse. Give it its nine "
                        + "items and an address in its own screen, bind it the same way, and one "
                        + "rising edge sends one order.")
                .pointAt(util.vector().centerOf(5, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.world().showSection(util.select().fromTo(2, 1, 2, 2, 2, 2), Direction.DOWN);
        scene.overlay().showText(95)
                .text("Both order into a receiving group, the same way an order from the desk does, "
                        + "so the goods arrive at a dock on this side. An unbound Requester is just a "
                        + "redstone requester, and an unbound gauge is just a gauge.")
                .pointAt(util.vector().centerOf(2, 2, 2))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("Neither one spends anything without a tower carrying it, so build the tower "
                        + "first.")
                .independent(30);
        scene.idle(85);
        scene.markAsFinished();
    }

    /** status.nbt: stone wall x3..6 / y1..2 / z4, monitor(4,1,3) facing north, dock(4,1,2). */
    public static void status(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_status", "Reading the link");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().fromTo(3, 1, 4, 6, 2, 4), Direction.DOWN);
        scene.world().showSection(util.select().position(4, 1, 3), Direction.DOWN);
        scene.world().showSection(util.select().position(4, 1, 2), Direction.DOWN);
        scene.overlay().showText(90)
                .text("The glass lid is the link light. It stays dull while the far side is out of "
                        + "reach, and lights when the two ends find each other, so a glance from "
                        + "across the room is enough to tell whether the line is up.")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.world().modifyBlock(util.grid().at(4, 1, 2),
                state -> state.setValue(DockBlock.STATUS, DockStatus.STANDBY), false);
        scene.overlay().showText(85)
                .text("The wall monitor keeps a longer record: which links are up, how much each "
                        + "side is carrying, and the coordinates of any link that has dropped.")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showText(95)
                .text("Clearing a fault is deliberate. A dock that has given up waits at red until "
                        + "someone right-clicks it, so a failure cannot be mistaken for an idle "
                        + "machine and quietly forgotten.")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(110)
                .text("The lamp, in order of how much it wants your attention: dark for no network, "
                        + "green for standing by, cyan for a parcel on its way up, orange for cargo "
                        + "waiting on a blocked fallback face, and red for a fault.")
                .independent(36);
        scene.idle(115);

        scene.overlay().showOutline(PonderPalette.RED, "fallback", util.select().position(4, 1, 2), 90);
        scene.overlay().showText(110)
                .text("Everything the far server will not accept leaves through the underside and "
                        + "waits there. Give it a container or a belt to fall into; with nothing "
                        + "below, the dock holds the cargo and blinks orange until you make room.")
                .pointAt(util.vector().blockSurface(util.grid().at(4, 1, 2), Direction.DOWN))
                .placeNearTarget();
        scene.idle(115);
        scene.markAsFinished();
    }

    /**
     * tower.nbt: shaft (4,1,3) - core (4,2,3) with the 3x3 casing skirt on the same layer -
     * couplers (4,3..7,3) - resonator (4,8,3), plus a lever (3,3,3) on a skirt corner.
     *
     * The mast stops at five segments, which is tier I. Seventeen would push the cap out of frame,
     * and "taller is better" was never something a picture could carry anyway — that is the text's
     * job. What the scene has to guarantee is that every block in it can actually be placed that way.
     *
     * The shots follow the order a player would build in: power first, then the base, then the mast,
     * then the cap, and only after that the tier, the stress and the skirt's window. The tower is the
     * only thing in this mod that stands up, so it is the only scene that pulls the camera up.
     */
    public static void tower(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_tower", "Raising an interlink tower");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(8);

        // Power enters from underneath: the core only takes a shaft on its bottom face, so the
        // shaft stands directly below it.
        scene.world().showSection(util.select().position(4, 1, 3), Direction.DOWN);
        scene.overlay().showText(85)
                .text("A tower is driven from below: one vertical shaft, set directly under the "
                        + "base. The underside is the only face that takes rotation, so a shaft run "
                        + "in from the side meets nothing. Speed is not the price — a medium "
                        + "network will do; what a tower spends is stress.")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        // The base and its skirt land together. The core goes down first so the player gets a look
        // at it before the casings close around it.
        scene.world().showSection(util.select().position(4, 2, 3), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(3, 2, 2, 5, 2, 4)
                .substract(util.select().position(4, 2, 3)), Direction.DOWN);
        scene.overlay().showText(95)
                .text("Above the shaft goes the Interlink Tower Base — the one part of a tower that "
                        + "turns, and the one that keeps the count. Close the base off with a 3x3 "
                        + "ring of Distant Casing: the casing has no say in whether a tower "
                        + "stands, but it is the base's face.")
                .pointAt(util.vector().centerOf(4, 2, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.world().showSection(util.select().fromTo(4, 3, 3, 4, 7, 3), Direction.UP);
        scene.overlay().showText(85)
                .text("Stack Interlink Tower Couplers upward, and keep the mast unbroken from the "
                        + "base: one gap, or one segment of another block, and there is no tower at "
                        + "all. Five segments is the first tier.")
                .pointAt(util.vector().centerOf(4, 5, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().position(4, 8, 3), Direction.UP);
        scene.overlay().showText(95)
                .text("Cap the mast with an Ether Resonator and the tower is up. The column over "
                        + "the cap is its state lamp: grey while the tower is unfinished or standing "
                        + "still, cyan while it works, and a deeper, self-lit blue while a parcel "
                        + "crosses it.")
                .pointAt(util.vector().centerOf(4, 8, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        // Tier: five segments is all the frame can usefully count, and the rest is text.
        scene.overlay().showOutline(PonderPalette.BLUE, "mast", util.select().fromTo(4, 3, 3, 4, 7, 3), 90);
        scene.overlay().showText(90)
                .text("Height is rank: five couplers is tier I, two more takes the next step, and "
                        + "seventeen couplers is the last of them, tier VII.")
                .pointAt(util.vector().centerOf(4, 5, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.overlay().showText(105)
                .text("A tier decides three things: how many distant devices the tower carries (8 "
                        + "at tier I, 128 at VII), how far from the base they may stand (32 blocks "
                        + "out to 144), and how large a square of chunks it keeps loaded (1x1 up to "
                        + "7x7).")
                .independent(30);
        scene.idle(110);

        // Stress: the tower's draw lands on the shaft underneath and grows with every tier. A shaft
        // that cannot turn, or a network that is overloaded, leaves the light dark.
        scene.overlay().showOutline(PonderPalette.RED, "drive", util.select().position(4, 1, 3), 90);
        scene.overlay().showText(90)
                .text("The shaft below feeds the tower rotation, and taller towers draw more of it: "
                        + "256 at tier I, roughly doubling a step to 16384 at VII. Fall below a "
                        + "medium speed, or overstress the network, and the light goes out.")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(95);

        scene.world().showSection(util.select().position(3, 3, 3), Direction.DOWN);
        scene.overlay().showText(85)
                .text("Pull the lever and the casing it lights turns its centre into a see-through "
                        + "window, spreading along the casings it touches. It is decoration only — "
                        + "the tower works exactly the same.")
                .pointAt(util.vector().centerOf(3, 3, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(20);
        scene.world().toggleRedstonePower(util.select().position(3, 3, 3));
        // The window is the casing's own state rather than something redstone sets directly: the
        // signal spreads between connected casings and each lit one has to be told. Setting the whole
        // skirt here is what the world looks like a second later.
        scene.world().modifyBlocks(util.select().fromTo(3, 2, 2, 5, 2, 4),
                state -> state.hasProperty(TowerCasingBlock.POWERED)
                        ? state.setValue(TowerCasingBlock.POWERED, true)
                        : state, false);
        scene.idle(65);
        scene.markAsFinished();
    }

    private DistantStockScenes() {
    }
}
