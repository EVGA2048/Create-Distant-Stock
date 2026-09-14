package dev.distantstock.client.ponder;

import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockStatus;
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
                .text("Right-click a dock with a tuned Requester to make it a destination for goods, "
                        + "and sneak-click to give it a delivery address. The desk reports what it "
                        + "did in the line above your hotbar.")
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

    private DistantStockScenes() {
    }
}
