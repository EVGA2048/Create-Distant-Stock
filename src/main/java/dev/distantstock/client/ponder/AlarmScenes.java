package dev.distantstock.client.ponder;

import dev.distantstock.block.StackLightBlock;
import dev.distantstock.block.WallSounderBlock;
import dev.distantstock.item.ModItems;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.world.item.ItemStack;
import static dev.distantstock.client.ponder.PonderSteps.*;

public final class AlarmScenes {
    public static void alarms(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_alarms","Connecting condition indicators",7);
        reveal(scene,util.select().fromTo(2,1,4,2,2,4));
        reveal(scene,util.select().position(2,2,3));
        use(scene,util,2,2,3,new ItemStack(ModItems.CONDITION_LINKER.get()));
        note(scene,util,2,2,3,"Use a Condition Linker on a stack light to select its target, then place the linker within range.");
        reveal(scene,util.select().position(2,1,1));
        scene.overlay().showLine(PonderPalette.BLUE,util.vector().centerOf(2,1,1),util.vector().centerOf(2,2,3),90);
        note(scene,util,2,1,1,"The coloured sides independently control the red, yellow and green lamps. The uncoloured side enables the buzzer.");
        reveal(scene,util.select().position(2,1,0));
        use(scene,util,2,1,0,ItemStack.EMPTY);
        scene.world().toggleRedstonePower(util.select().position(2,1,0));
        scene.world().modifyBlock(util.grid().at(2,2,3),s -> s.setValue(StackLightBlock.RED,true),false);
        note(scene,util,2,1,1,"A signal on the red side lights the red lamp. These inputs represent supplied conditions; the light does not diagnose the network itself.");
        reveal(scene,util.select().fromTo(5,1,3,5,2,4));
        use(scene,util,5,1,3,ItemStack.EMPTY);
        scene.world().toggleRedstonePower(util.select().position(5,1,3));
        scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.POWERED,true).setValue(WallSounderBlock.LIT,true),false);
        // Visual demonstration only: do not play alarm samples automatically in Ponder.
        for(int i=0;i<3;i++) {
            scene.idle(2);
            scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.LIT,false),false);
            scene.idle(3);
            scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.LIT,true),false);
            scene.idle(2);
            scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.LIT,false),false);
            scene.idle(23);
            scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.LIT,true),false);
        }
        note(scene,util,5,2,3,"Wall sounders use a separate redstone input to activate their light and sound cycle. Hold right click on the tone control to select a sound.");
        scene.world().toggleRedstonePower(util.select().position(5,1,3));
        scene.world().modifyBlock(util.grid().at(5,2,3),s -> s.setValue(WallSounderBlock.POWERED,false).setValue(WallSounderBlock.LIT,false),false);
        scene.markAsFinished();
    }
    private AlarmScenes() {}
}
