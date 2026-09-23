package dev.distantstock.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.distantstock.block.LoggerBlock;
import dev.distantstock.block.WallSounderBlock;
import dev.distantstock.item.ModItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Failure handling: diagnostic/cache Frogports and the event logger. */
public final class DiagnosticScenes {

    public static void chain(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_diagnostics", "Protecting a Chain Conveyor route");
        scene.configureBasePlate(0, 0, 9);
        scene.showBasePlate();
        scene.idle(10);

        var chain = util.select().fromTo(2, 1, 4, 6, 1, 4);
        var normal = util.select().position(6, 2, 2);
        var diagnostic = util.select().position(2, 2, 2);
        var cache = util.select().position(4, 2, 2);

        scene.world().showSection(chain, Direction.DOWN);
        scene.world().showSection(normal, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(55)
                .text("Normal Frogports keep their normal addresses; Distant Stock does not replace Create routing")
                .pointAt(util.vector().centerOf(6, 2, 2))
                .placeNearTarget();
        scene.idle(65);

        scene.world().showSection(diagnostic, Direction.DOWN);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.BLUE, "diag", diagnostic, 60);
        scene.overlay().showText(60)
                .text("A Diagnostic Frogport probes the route and catches parcels that truly have no destination")
                .pointAt(util.vector().centerOf(2, 2, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.world().showSection(cache, Direction.DOWN);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "cache", cache, 65);
        scene.overlay().showText(65)
                .text("When a real destination stalls, the Cache Frogport temporarily takes its address")
                .pointAt(util.vector().centerOf(4, 2, 2))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showControls(util.vector().centerOf(3, 1, 3), Pointing.DOWN, 40)
                .withItem(new ItemStack(ModItems.EVENT_RECEIPT.get()));
        scene.idle(45);

        scene.overlay().showControls(util.vector().centerOf(4, 2, 2), Pointing.DOWN, 45).rightClick();
        scene.overlay().showText(65)
                .text("Its 54-slot buffer is a real inventory: take any parcel out manually if needed")
                .pointAt(util.vector().centerOf(4, 2, 2))
                .placeNearTarget();
        scene.idle(75);

        scene.overlay().showText(60)
                .text("When the route recovers, untouched parcels are replayed automatically")
                .independent(28);
        scene.idle(70);
    }

    public static void logger(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_logger", "Acknowledging Distant Stock alarms");
        scene.configureBasePlate(0, 0, 8);
        scene.showBasePlate();
        scene.idle(10);

        var wall = util.select().fromTo(2, 1, 4, 6, 2, 4);
        var logger = util.select().position(3, 1, 3);
        var stack = util.select().position(5, 1, 3);
        var sounder = util.select().position(6, 1, 3);
        scene.world().showSection(wall, Direction.DOWN);
        scene.world().showSection(logger, Direction.SOUTH);
        scene.world().showSection(stack, Direction.SOUTH);
        scene.world().showSection(sounder, Direction.SOUTH);
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.RED, "logger", logger, 60);
        scene.overlay().showText(60)
                .text("The Logger records active warnings and faults for the network it watches")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showControls(util.vector().centerOf(3, 1, 3), Pointing.DOWN, 45).rightClick();
        scene.world().modifyBlock(util.grid().at(3, 1, 3),
                state -> state.setValue(LoggerBlock.STATUS, LoggerBlock.Status.ERROR_ACK), false);
        scene.world().modifyBlock(util.grid().at(6, 1, 3),
                state -> state.setValue(WallSounderBlock.LIT, false), false);
        scene.overlay().showText(60)
                .text("Acknowledge to silence the alarm; acknowledgement does not erase the fault")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget();
        scene.idle(70);

        scene.overlay().showControls(util.vector().centerOf(3, 1, 3), Pointing.DOWN, 45)
                .withItem(new ItemStack(ModItems.LOGGER_PAPER_ROLL.get()));
        scene.overlay().showText(65)
                .text("AC remains until the incident slip is printed; without paper you may only silence it")
                .pointAt(util.vector().centerOf(3, 1, 3))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.BLUE, "andon", stack.add(sounder), 60);
        scene.overlay().showText(60)
                .text("Stack lights and sounders turn those same conditions into a room-scale Andon")
                .pointAt(util.vector().centerOf(5, 1, 3))
                .placeNearTarget();
        scene.idle(70);
    }

    private DiagnosticScenes() {}
}
