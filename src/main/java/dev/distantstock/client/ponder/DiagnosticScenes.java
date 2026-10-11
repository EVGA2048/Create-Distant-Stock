package dev.distantstock.client.ponder;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import dev.distantstock.block.*;
import dev.distantstock.item.ModItems;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import static dev.distantstock.client.ponder.PonderSteps.*;

public final class DiagnosticScenes {
    public static void chain(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_diagnostics","Diagnosing a chain route",9);

        scene.scaleSceneView(.85f);
        reveal(scene,util.select().fromTo(0,1,5,6,3,5));
        scene.world().setKineticSpeed(util.select().fromTo(0,1,5,6,3,5),32);
        reveal(scene,util.select().fromTo(6,1,2,6,2,2));

        note(scene,util,6,2,2,"Connect the destination Frogport to the chain and set its address. Ordinary parcel routing remains managed by Create.");
        reveal(scene,util.select().fromTo(4,1,6,4,2,7));
        use(scene,util,4,2,6,new ItemStack(ModItems.DIAGNOSTIC_FROGPORT.get()));
        note(scene,util,4,2,6,"Before placement, use the Diagnostic Frogport item on an existing Stock Link to bind its logging network.");
        use(scene,util,2,3,5,new ItemStack(ModItems.DIAGNOSTIC_FROGPORT.get()));
        reveal(scene,util.select().fromTo(2,1,2,2,2,2));
        note(scene,util,2,2,2,"Select a chain target, then place the Diagnostic Frogport on a supported position within reach, as with a normal Frogport.");
        scene.world().modifyBlockEntity(util.grid().at(2,3,5),ChainConveyorBlockEntity.class,
                be -> be.addTravellingPackage(new ChainConveyorPackage(0,new ItemStack(ModItems.PING_PACKAGE.get())),util.grid().at(4,0,0)));
        note(scene,util,2,3,5,"Ping parcels probe the connected route. The Diagnostic Frogport also receives parcels confirmed to have no destination.");
        scene.world().modifyBlockEntity(util.grid().at(2,3,5),ChainConveyorBlockEntity.class,be -> be.clearContent());
        note(scene,util,2,2,2,"Investigate the recorded route events. A congested valid destination is handled by a Cache Frogport instead.");
        scene.markAsFinished();
    }

    public static void cache(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_cache","Buffering a congested destination",9);

        scene.scaleSceneView(.85f);
        reveal(scene,util.select().fromTo(0,1,5,6,3,5));
        scene.world().setKineticSpeed(util.select().fromTo(0,1,5,6,3,5),32);
        reveal(scene,util.select().fromTo(6,1,2,6,2,2));

        reveal(scene,util.select().fromTo(2,1,2,2,2,2));
        note(scene,util,6,2,2,"A destination can remain valid while its output is blocked. Keep its normal address and resolve the local obstruction.");
        reveal(scene,util.select().fromTo(4,1,2,4,2,2));
        note(scene,util,4,2,2,"Connect a Cache Frogport to the same chain route. It temporarily accepts traffic for a congested destination.");
        use(scene,util,4,2,2,ItemStack.EMPTY);
        note(scene,util,4,2,2,"Open the Cache Frogport to inspect its 54-slot buffer and remove individual parcels when required.");
        scene.world().modifyBlockEntity(util.grid().at(2,3,5),ChainConveyorBlockEntity.class,
                be -> be.addTravellingPackage(new ChainConveyorPackage(2,new ItemStack(ModItems.REMOTE_PACKAGE.get())),util.grid().at(4,0,0)));
        note(scene,util,6,2,2,"After the route recovers, retained parcels are resent automatically according to the configured replay conditions.");
        scene.world().modifyBlockEntity(util.grid().at(2,3,5),ChainConveyorBlockEntity.class,be -> be.clearContent());
        scene.markAsFinished();
    }

    public static void logger(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_logger","Inspecting and acknowledging alarms",7);

        reveal(scene,util.select().fromTo(2,1,4,4,2,4));
        reveal(scene,util.select().position(3,2,3));
        use(scene,util,3,2,3,new ItemStack(ModItems.REQUESTER.get()));
        note(scene,util,3,2,3,"Use a tuned terminal to select the network monitored by the Logger.");
        use(scene,util,3,2,3,new ItemStack(ModItems.LOGGER_PAPER_ROLL.get()));
        scene.world().modifyBlockEntityNBT(util.select().position(3,2,3),BlockEntity.class,t -> t.putInt("PaperRemaining",LoggerBlockEntity.PAPER_CAPACITY));
        note(scene,util,3,2,3,"Install a replacement paper roll with right click before printing incident records.");
        scene.world().modifyBlock(util.grid().at(3,2,3),s -> s.setValue(LoggerBlock.STATUS,LoggerBlock.Status.ERROR),false);
        scene.world().modifyBlockEntityNBT(util.select().position(3,2,3),BlockEntity.class,t -> t.putString("DisplayCode","ER"));
        use(scene,util,3,2,3,ItemStack.EMPTY);
        note(scene,util,3,2,3,"An empty-hand right click opens the event list. Opening the interface does not acknowledge or print an event.");
        scene.world().modifyBlock(util.grid().at(3,2,3),s -> s.setValue(LoggerBlock.STATUS,LoggerBlock.Status.ERROR_ACK),false);
        scene.world().modifyBlockEntityNBT(util.select().position(3,2,3),BlockEntity.class,t -> t.putString("DisplayCode","AC"));
        note(scene,util,3,2,3,"Choose the acknowledgement action on the event row to silence it. AC indicates that its incident record still requires printing.");
        scene.world().modifyBlock(util.grid().at(3,2,3),s -> s.setValue(LoggerBlock.PRINTED,true),false);
        scene.world().modifyBlockEntityNBT(util.select().position(3,2,3),BlockEntity.class,t -> t.putString("DisplayCode","ER"));
        scene.overlay().showControls(util.vector().topOf(3,2,3),net.createmod.catnip.math.Pointing.DOWN,50)
                .withItem(new ItemStack(ModItems.EVENT_RECEIPT.get()));
        note(scene,util,3,2,3,"Use the print action in the interface to obtain the incident record. Paper is required; acknowledgement alone does not clear the fault.");
        scene.world().modifyBlock(util.grid().at(3,2,3),s -> s.setValue(LoggerBlock.PRINTED,false).setValue(LoggerBlock.STATUS,LoggerBlock.Status.NORMAL),false);
        scene.world().modifyBlockEntityNBT(util.select().position(3,2,3),BlockEntity.class,t -> t.putString("DisplayCode","OK"));
        note(scene,util,3,2,3,"Resolve the underlying fault at its source. The active alarm then clears when its recovery condition is met.");
        scene.markAsFinished();
    }

    private DiagnosticScenes() {}
}
