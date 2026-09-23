package dev.distantstock.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.distantstock.block.TowerCasingBlock;
import dev.distantstock.item.ModItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** The tower scene teaches construction order, not a spreadsheet of tier values. */
public final class TowerScenes {
    public static void tower(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("distant_tower", "Building an Interlink Tower");
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.8f);
        scene.setSceneOffsetY(-1f);
        scene.showBasePlate();
        scene.idle(10);

        var shaft = util.select().position(4, 1, 4);
        var core = util.select().position(4, 2, 4);
        var skirt = util.select().fromTo(3, 2, 3, 5, 2, 5).substract(core);
        var mast = util.select().fromTo(4, 3, 4, 4, 7, 4);
        var cap = util.select().position(4, 8, 4);

        scene.world().showSection(shaft, Direction.DOWN);
        scene.world().showSection(core, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(60)
                .text("Drive the Tower Core from the shaft directly underneath it")
                .pointAt(util.vector().centerOf(4, 2, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.world().showSection(skirt, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.BLUE, "skirt", skirt, 60);
        scene.overlay().showText(60)
                .text("Surround the core with a complete 3x3 ring of Distant Casing")
                .pointAt(util.vector().centerOf(4, 2, 4))
                .placeNearTarget();
        scene.idle(70);

        scene.world().showSection(mast, Direction.UP);
        scene.idle(20);
        scene.overlay().showText(60)
                .text("Stack an unbroken mast of Tower Couplers; mast height sets the tier")
                .pointAt(util.vector().centerOf(4, 5, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(70);

        scene.world().showSection(cap, Direction.UP);
        scene.idle(15);
        scene.overlay().showText(55)
                .text("Finish the mast with an Ether Resonator")
                .pointAt(util.vector().centerOf(4, 8, 4))
                .placeNearTarget();
        scene.idle(65);

        scene.overlay().showControls(util.vector().centerOf(3, 2, 4), Pointing.DOWN, 45)
                .withItem(new ItemStack(ModItems.ETHER_BUCKET.get()));
        scene.overlay().showText(65)
                .text("Feed Ether through a side or top casing port; the bottom is reserved for rotation")
                .pointAt(util.vector().centerOf(3, 2, 4))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(75);

        scene.world().modifyBlocks(skirt,
                state -> state.hasProperty(TowerCasingBlock.POWERED)
                        ? state.setValue(TowerCasingBlock.POWERED, true) : state, false);
        scene.overlay().showText(55)
                .text("Powered casings open their illuminated inspection windows; this is cosmetic")
                .pointAt(util.vector().centerOf(3, 2, 4))
                .placeNearTarget();
        scene.idle(65);
    }

    private TowerScenes() {}
}
