package dev.distantstock.client.ponder;

import dev.distantstock.block.*;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import static dev.distantstock.client.ponder.PonderSteps.*;

public final class TowerScenes {
    public static void tower(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_tower","Building and supplying an Interlink Tower",7);

        scene.scaleSceneView(.7f);
        scene.setSceneOffsetY(-1f);
        reveal(scene,util.select().position(3,1,3));
        reveal(scene,util.select().position(3,2,3));
        note(scene,util,3,1,3,"The Tower Base receives rotation only from below. This demonstration uses a motor directly beneath it.");
        var ring=util.select().fromTo(2,2,2,4,2,4).substract(util.select().position(3,2,3));
        reveal(scene,ring);
        scene.overlay().showOutline(PonderPalette.BLUE,"base",ring,90);
        note(scene,util,3,2,2,"Surround the central base with eight Distant Casings, completing the required 3 by 3 foundation.");
        for(int y=3;y<=7;y++) { reveal(scene,util.select().position(3,y,3)); }
        note(scene,util,3,5,3,"Stack at least five consecutive Tower Couplers above the base. Gaps or other blocks interrupt the mast.");
        reveal(scene,util.select().position(3,8,3));
        note(scene,util,3,8,3,"Cap the mast with an Ether Resonator. Five couplers form tier I; taller valid masts increase capacity and range.");
        use(scene,util,2,2,3,createItem("wrench"));
        scene.world().modifyBlock(util.grid().at(2,2,3),s -> s.setValue(TowerCasingBlock.PORT,TowerCasingBlock.Port.WEST),false);
        note(scene,util,2,2,3,"Use a wrench on the desired casing face to open a fluid port. Connect Ether supply to that face; each casing has one port.");
        scene.world().setKineticSpeed(util.select().fromTo(3,1,3,3,2,3),32);
        scene.world().modifyBlockEntityNBT(util.select().position(3,8,3),BlockEntity.class,t -> t.putInt("Beam",1));
        note(scene,util,3,2,3,"A complete tower also requires sufficient rotational speed, stress capacity and Ether supply to operate.");
        reveal(scene,util.select().position(3,2,1));
        use(scene,util,3,2,1,ItemStack.EMPTY);
        scene.world().toggleRedstonePower(util.select().position(3,2,1));
        scene.world().modifyBlocks(ring,s -> s.setValue(TowerCasingBlock.POWERED,true),false);
        note(scene,util,3,2,2,"Redstone opens the inspection windows of connected casings. The windows do not change the tower's operation.");
        scene.markAsFinished();
    }

    private TowerScenes() {}
}
