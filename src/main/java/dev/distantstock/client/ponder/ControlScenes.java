package dev.distantstock.client.ponder;

import dev.distantstock.block.*;
import dev.distantstock.item.ModItems;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import static dev.distantstock.client.ponder.PonderSteps.*;

public final class ControlScenes {
    public static void tune(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_tune","Binding a local warehouse",7);

        reveal(scene,util.select().fromTo(1,1,3,1,2,4));
        use(scene,util,1,2,3,new ItemStack(ModItems.DOCK.get()));
        note(scene,util,1,2,3,"Before placing a Distant Dock, use its item on an existing Stock Link to copy the local logistics network.");
        reveal(scene,util.select().position(4,1,2));
        use(scene,util,1,2,3,new ItemStack(ModItems.REQUESTER.get()));
        note(scene,util,1,2,3,"Tune a portable terminal to the local network, then create or join a Distant Network in its interface.");
        use(scene,util,4,1,2,new ItemStack(ModItems.REQUESTER.get()));
        note(scene,util,4,1,2,"Use that terminal on the placed dock to add its local warehouse to the selected Distant Network.");
        use(scene,util,4,1,2,ItemStack.EMPTY);
        note(scene,util,4,1,2,"Configure the dock's mode, receiving address and destination in its own interface. Terminal binding does not change these settings.");
        reveal(scene,util.select().position(5,1,4));
        note(scene,util,5,1,4,"A Request Desk provides a fixed ordering terminal. It does not need to be inserted into the parcel transport path.");
        scene.markAsFinished();
    }

    public static void status(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_status","Reading and resolving dock states",7);

        reveal(scene,util.select().position(4,1,2));
        note(scene,util,4,1,2,"Green indicates a ready dock. The indicator reports the dock's current operating state.");
        scene.world().modifyBlock(util.grid().at(4,1,2),s -> s.setValue(DockBlock.STATUS,DockStatus.SENDING),false);
        note(scene,util,4,1,2,"Cyan indicates an active parcel transfer.");
        scene.world().modifyBlock(util.grid().at(4,1,2),s -> s.setValue(DockBlock.STATUS,DockStatus.BLOCKED),false);
        note(scene,util,4,1,2,"Orange indicates a blocked return path. Check the extraction path and available storage space.");
        scene.world().modifyBlock(util.grid().at(4,1,2),s -> s.setValue(DockBlock.STATUS,DockStatus.FAULT),false);
        reveal(scene,util.select().fromTo(2,1,3,2,2,4));
        use(scene,util,2,2,3,ItemStack.EMPTY);
        note(scene,util,2,2,3,"For a red fault state, inspect the link and tower information in the Monitor before attempting recovery.");
        use(scene,util,4,1,2,createItem("wrench"));
        scene.world().modifyBlock(util.grid().at(4,1,2),s -> s.setValue(DockBlock.STATUS,DockStatus.STANDBY),false);
        note(scene,util,4,1,2,"After resolving the cause, use a wrench on the dock to clear its recorded fault. Unresolved faults can recur.");
        scene.markAsFinished();
    }

    public static void replenish(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_replenish","Maintaining remote stock",7);

        reveal(scene,util.select().fromTo(4,1,3,4,2,4));
        reveal(scene,util.select().fromTo(2,1,2,2,2,3));
        use(scene,util,2,2,2,createItem("stock_link"));
        note(scene,util,2,2,2,"Bind the active gauge panel to the local logistics network whose stock it should monitor.");
        use(scene,util,2,2,2,new ItemStack(Items.IRON_INGOT));
        note(scene,util,2,2,2,"Select an item in the filter and configure its target stock in the panel interface.");
        use(scene,util,2,2,2,new ItemStack(ModItems.REQUESTER.get()));
        note(scene,util,2,2,2,"Use a tuned terminal on this panel to select the remote source warehouse. Each panel has its own binding.");
        reveal(scene,util.select().fromTo(4,1,1,5,2,1));
        note(scene,util,5,2,1,"Set a receiving address matching the local dock. Orders replenish that destination when local stock is below target.");
        parcel(scene,new ItemStack(ModItems.REMOTE_PACKAGE.get()),new Vec3(5.5,2.4,1.5),new Vec3(5.5,1.3,1.5));
        note(scene,util,4,1,1,"Extract and unpack the delivery into the monitored inventory. Only then does the local stock level increase.");
        scene.markAsFinished();
    }

    public static void redstone(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_redstone","Requesting on a redstone pulse",7);

        reveal(scene,util.select().position(2,1,2));
        use(scene,util,2,1,2,new ItemStack(ModItems.REQUESTER.get()));
        note(scene,util,2,1,2,"Bind the Distant Redstone Requester to a remote warehouse using a tuned terminal.");
        use(scene,util,2,1,2,ItemStack.EMPTY);
        note(scene,util,2,1,2,"Configure the order contents and receiving address before supplying a redstone signal.");
        reveal(scene,util.select().position(2,1,3));
        use(scene,util,2,1,3,ItemStack.EMPTY);
        scene.world().toggleRedstonePower(util.select().fromTo(2,1,2,2,1,3));
        scene.effects().indicateRedstone(util.grid().at(2,1,2));
        note(scene,util,2,1,2,"A rising redstone edge submits one configured order. A sustained signal does not continuously submit orders.");
        scene.world().toggleRedstonePower(util.select().fromTo(2,1,2,2,1,3));
        scene.idle(20);
        reveal(scene,util.select().fromTo(5,1,2,5,2,3));
        note(scene,util,5,2,2,"Parcels arrive at the addressed dock and must be extracted. This device triggers orders; it does not monitor stock targets.");
        scene.markAsFinished();
    }

    private ControlScenes() {}
}
