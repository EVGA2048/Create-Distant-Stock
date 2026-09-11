package dev.distantstock.client.ponder;

import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockStatus;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;

public final class DistantStockScenes {
    public static void export(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_export", "Warehouse export");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().fromTo(1, 1, 2, 2, 1, 2), Direction.DOWN);
        scene.overlay().showText(70)
                .text("The warehouse can use the pale-blue Distant Packager. It keeps Create packing logic and produces Distant Parcels.")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(util.select().fromTo(3, 1, 2, 4, 1, 2), Direction.WEST);
        scene.overlay().showText(80)
                .text("Feed sealed packages from the side or back. A hopper, funnel or belt can sit against any face.")
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(85);

        scene.overlay().showOutline(PonderPalette.GREEN, "dock", util.select().position(4, 1, 2), 70);
        scene.overlay().showText(80)
                .text("The cabin faces the operator. Leave the neighbouring faces free for logistics.")
                .pointAt(util.vector().blockSurface(util.grid().at(4, 1, 2), Direction.SOUTH))
                .placeNearTarget();
        scene.idle(85);

        scene.overlay().showText(80)
                .text("Never pipe out of the bottom face: that one is the fallback face for returns and for "
                        + "items the other server cannot accept.")
                .pointAt(util.vector().blockSurface(util.grid().at(4, 1, 2), Direction.DOWN))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(85);

        scene.world().modifyBlock(util.grid().at(4, 1, 2),
                state -> state.setValue(DockBlock.STATUS, DockStatus.SENDING), false);
        scene.overlay().showText(70)
                .text("A parcel in the bay means the dock has something queued to send. It is not a shared stock network.")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(80);
        scene.markAsFinished();
    }

    public static void receive(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_import", "Outer-server receive");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().position(2, 1, 2), Direction.DOWN);
        scene.overlay().showText(70)
                .text("On the other server, sneak-click the dock with a requester to set receive mode and an address.")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(util.select().fromTo(3, 1, 2, 4, 1, 2), Direction.WEST);
        scene.overlay().showText(80)
                .text("Pull sealed parcels out with a hopper or funnel. Distant Parcels remain compatible with Create logistics.")
                .pointAt(util.vector().centerOf(3, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(85);

        scene.overlay().showText(70)
                .text("This is another JVM. The two stock networks stay separate; only the sealed parcel hops.")
                .independent(40)
                .attachKeyFrame();
        scene.idle(75);
        scene.markAsFinished();
    }

    public static void tune(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_tune", "Tune and request");
        scene.configureBasePlate(0, 0, 6);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().position(2, 1, 2), Direction.DOWN);
        scene.overlay().showText(70)
                .text("Open the portable requester and join an unlocked warehouse Stock Link. No IP is typed in the block.")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(util.select().position(1, 1, 3), Direction.DOWN);
        scene.overlay().showText(70)
                .text("Right-click a dock with the tuned requester to set export. Sneak-click sets receive.")
                .pointAt(util.vector().centerOf(1, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showText(70)
                .text("The request desk copies the same network and address. It sits beside the line, not on the parcel path.")
                .pointAt(util.vector().centerOf(2, 1, 2))
                .placeNearTarget();
        scene.idle(75);
        scene.markAsFinished();
    }

    public static void status(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("distant_status", "Link and faults");
        scene.configureBasePlate(0, 0, 6);
        scene.showBasePlate();
        scene.idle(8);

        scene.world().showSection(util.select().fromTo(1, 1, 3, 3, 1, 3), Direction.DOWN);
        scene.world().showSection(util.select().position(4, 1, 2), Direction.DOWN);
        scene.overlay().showText(70)
                .text("Aether rails stay dull when the link is down. Online only brightens the glass, not the whole machine.")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().modifyBlock(util.grid().at(4, 1, 2),
                state -> state.setValue(DockBlock.STATUS, DockStatus.STANDBY), false);
        scene.overlay().showText(70)
                .text("The wall monitor shows local and peer load. Sending a request is not the same as a parcel arriving.")
                .pointAt(util.vector().blockSurface(util.grid().at(3, 1, 3), Direction.SOUTH))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showText(80)
                .text("If the bay is full, the peer retries. Keep the dock chunk loaded and the address matched.")
                .independent(32);
        scene.idle(80);

        scene.overlay().showText(90)
                .text("Lamp: off = not on the network, green = standby, cyan blinking = dispatching, "
                        + "orange blinking = the fallback face is blocked, red = fault.")
                .independent(36);
        scene.idle(90);

        scene.overlay().showText(100)
                .text("The face underneath is the fallback face. Put a chute there: items the other server "
                        + "cannot accept come out of it instead of vanishing. Without room below, the dock "
                        + "blinks orange and holds them.")
                .independent(36);
        scene.idle(100);
        scene.markAsFinished();
    }

    private DistantStockScenes() {
    }
}
