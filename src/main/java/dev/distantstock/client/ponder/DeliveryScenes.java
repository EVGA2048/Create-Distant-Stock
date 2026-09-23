package dev.distantstock.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.distantstock.block.DockBlock;
import dev.distantstock.block.DockStatus;
import dev.distantstock.item.ModItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Ponder scenes for the physical parcel path. Short, visual, and task-oriented. */
public final class DeliveryScenes {

    public static void export(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_export", "Sending a Distant Parcel");
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.95f);
        scene.showBasePlate();
        scene.idle(10);

        var storage = util.select().fromTo(1, 1, 3, 3, 2, 5);
        var belt = util.select().fromTo(2, 1, 3, 6, 1, 3);
        var dock = util.select().position(7, 1, 3);
        var drive = util.select().position(2, 1, 4);

        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutlineWithText(storage, 65)
                .text("Orders are packed at the warehouse, exactly like Create logistics")
                .pointAt(util.vector().centerOf(2, 2, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(drive, Direction.DOWN);
        scene.world().showSection(belt, Direction.WEST);
        scene.world().showSection(dock, Direction.WEST);
        scene.idle(20);

        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        scene.world().createItemOnBelt(util.grid().at(2, 1, 3), Direction.UP, parcel);
        scene.overlay().showText(65)
                .text("Move the sealed parcel to a Distant Dock with normal Create logistics")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.world().removeItemsFromBelt(util.grid().at(6, 1, 3));
        scene.world().modifyBlock(util.grid().at(7, 1, 3),
                state -> state.setValue(DockBlock.STATUS, DockStatus.SENDING), false);
        scene.overlay().showOutline(PonderPalette.BLUE, "dock", dock, 60);
        scene.overlay().showText(60)
                .text("The dock takes one parcel and sends it through the Distant Stock link")
                .pointAt(util.vector().centerOf(7, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showOutline(PonderPalette.RED, "fallback", dock, 65);
        scene.overlay().showText(65)
                .text("Keep the underside clear: returned or rejected parcels leave from here")
                .pointAt(util.vector().blockSurface(util.grid().at(7, 1, 3), Direction.DOWN))
                .placeNearTarget();
        scene.idle(75);
    }

    public static void receive(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_import", "Receiving a Distant Parcel");
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.95f);
        scene.showBasePlate();
        scene.idle(10);

        var dock = util.select().position(2, 2, 3);
        var output = util.select().fromTo(2, 1, 3, 6, 1, 3);
        var chest = util.select().position(7, 1, 3);

        scene.world().showSection(dock, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().centerOf(2, 2, 3), Pointing.DOWN, 45)
                .rightClick().withItem(new ItemStack(ModItems.REQUESTER.get()));
        scene.overlay().showText(70)
                .text("Bind the receiving dock with a tuned Requester")
                .pointAt(util.vector().centerOf(2, 2, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(80);

        scene.world().modifyBlock(util.grid().at(2, 2, 3),
                state -> state.setValue(DockBlock.STATUS, DockStatus.STANDBY), false);
        scene.world().showSection(output, Direction.WEST);
        scene.world().showSection(chest, Direction.WEST);
        scene.idle(20);
        scene.overlay().showText(65)
                .text("Arriving parcels leave through the bottom and can enter any normal item line")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.GREEN, "storage", chest, 55);
        scene.overlay().showText(55)
                .text("From here the parcel is local again")
                .pointAt(util.vector().centerOf(7, 1, 3))
                .placeNearTarget();
        scene.idle(65);
    }

    private DeliveryScenes() {}
}
