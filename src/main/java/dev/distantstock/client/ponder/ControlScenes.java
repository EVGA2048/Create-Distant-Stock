package dev.distantstock.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.distantstock.item.ModItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Terminal, monitor and automatic ordering scenes. */
public final class ControlScenes {

    public static void tune(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_tune", "Binding Distant Stock devices");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(10);

        var requester = new ItemStack(ModItems.REQUESTER.get());
        var dock = util.select().position(2, 1, 3);
        var desk = util.select().position(5, 1, 3);

        scene.world().showSection(dock, Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(util.vector().centerOf(2, 1, 3), Pointing.DOWN, 50)
                .rightClick().withItem(requester);
        scene.overlay().showText(65)
                .text("The Requester carries the Distant Stock network and receiving group you selected")
                .pointAt(util.vector().centerOf(2, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(desk, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutlineWithText(desk, 70)
                .text("A Request Desk uses the same network settings, but stays off the item line")
                .pointAt(util.vector().centerOf(5, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showText(55)
                .text("Configure first; then bind the machines that should use that destination")
                .independent(28);
        scene.idle(65);
    }

    public static void status(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_status", "Reading Distant Stock status");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(10);

        var wall = util.select().fromTo(3, 1, 4, 6, 2, 4);
        var monitor = util.select().position(4, 1, 3);
        var dock = util.select().position(4, 1, 2);
        scene.world().showSection(wall, Direction.DOWN);
        scene.world().showSection(monitor, Direction.SOUTH);
        scene.world().showSection(dock, Direction.SOUTH);
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.GREEN, "monitor", monitor, 65);
        scene.overlay().showText(65)
                .text("Use the Monitor for link, traffic and tower status")
                .pointAt(util.vector().centerOf(4, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.BLUE, "dock", dock, 55);
        scene.overlay().showText(55)
                .text("The dock itself is the quick status light: idle, active, blocked or faulted")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget();
        scene.idle(65);

        scene.overlay().showControls(util.vector().centerOf(4, 1, 2), Pointing.DOWN, 45).rightClick();
        scene.overlay().showText(55)
                .text("When a fault requires acknowledgement, clear it deliberately at the device")
                .pointAt(util.vector().centerOf(4, 1, 2))
                .placeNearTarget();
        scene.idle(65);
    }

    public static void replenish(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_replenish", "Automatic remote ordering");
        scene.configureBasePlate(0, 0, 9);
        scene.showBasePlate();
        scene.idle(10);

        var wall = util.select().fromTo(2, 1, 5, 7, 2, 5);
        var gauge = util.select().position(3, 1, 4);
        var redstone = util.select().position(6, 1, 4);
        var dock = util.select().position(2, 2, 2);
        var receiving = util.select().fromTo(2, 1, 2, 7, 1, 2);
        scene.world().showSection(wall, Direction.DOWN);
        scene.world().showSection(gauge, Direction.SOUTH);
        scene.idle(15);

        ItemStack item = new ItemStack(Items.IRON_INGOT);
        scene.overlay().showControls(util.vector().centerOf(3, 1, 4), Pointing.DOWN, 45)
                .rightClick().withItem(item);
        scene.overlay().showText(60)
                .text("A Distant Gauge watches one item and maintains the amount you set")
                .pointAt(util.vector().centerOf(3, 1, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showControls(util.vector().centerOf(3, 1, 4), Pointing.DOWN, 45)
                .rightClick().withItem(new ItemStack(ModItems.REQUESTER.get()));
        scene.overlay().showText(60)
                .text("Bind each gauge slot to a tuned Requester to choose its remote warehouse")
                .pointAt(util.vector().centerOf(3, 1, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.world().showSection(redstone, Direction.SOUTH);
        scene.idle(10);
        scene.overlay().showOutlineWithText(redstone, 65)
                .text("The Distant Redstone Requester sends its configured order on a rising edge")
                .pointAt(util.vector().centerOf(6, 1, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().showSection(receiving, Direction.WEST);
        scene.world().showSection(dock, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, "receive", dock, 60);
        scene.overlay().showText(60)
                .text("Both order into the receiving group; the goods arrive through its dock")
                .pointAt(util.vector().centerOf(2, 2, 2))
                .placeNearTarget();
        scene.idle(70);
    }

    private ControlScenes() {}
}
