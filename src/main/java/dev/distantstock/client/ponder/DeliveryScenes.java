package dev.distantstock.client.ponder;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import dev.distantstock.block.*;
import dev.distantstock.item.ModItems;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import static dev.distantstock.client.ponder.PonderSteps.*;

public final class DeliveryScenes {
    public static void export(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_export","Sending parcels through a Distant Dock",7);

        var storage = util.select().fromTo(1, 1, 3, 1, 3, 4);
        reveal(scene, storage);
        note(scene,util,1,2,3,"Place the Distant Packager against its source inventory, with a Stock Link above it.");
        reveal(scene, util.select().fromTo(1,1,0,1,1,1));
        reveal(scene, util.select().fromTo(1,1,2,4,2,2));
        note(scene,util,1,2,2,"An extracting funnel in front of the packager places completed parcels onto the belt below.");
        reveal(scene, util.select().fromTo(5,1,2,5,2,3));
        use(scene,util,5,2,2,ItemStack.EMPTY);
        note(scene,util,5,2,2,"Open the bound dock and select Send mode. Configure a default destination when parcels do not already carry one.");
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        scene.world().modifyBlockEntity(util.grid().at(1,2,3),PackagerBlockEntity.class,be -> {
            be.heldBox=parcel.copy(); be.animationTicks=PackagerBlockEntity.CYCLE; be.animationInward=false;
        });
        scene.idle(PackagerBlockEntity.CYCLE + 10);
        scene.world().modifyBlockEntity(util.grid().at(1,2,3),PackagerBlockEntity.class,be -> be.heldBox=ItemStack.EMPTY);
        scene.world().flapFunnel(util.grid().at(1,2,2),true);
        scene.world().setKineticSpeed(util.select().fromTo(1,1,0,1,1,1),-32);
        scene.world().setKineticSpeed(util.select().fromTo(1,1,2,4,1,2),-32);
        scene.world().createItemOnBelt(util.grid().at(1,1,2),Direction.UP,parcel);
        scene.idle(48);
        // Create removes only items over the specified segment, not the entire controller belt.
        for (int x = 1; x <= 4; x++) scene.world().removeItemsFromBelt(util.grid().at(x,1,2));
        scene.world().flapFunnel(util.grid().at(4,2,2),false);
        scene.idle(8);
        sendParcel(scene,util.grid().at(5,2,2),parcel);
        note(scene,util,4,2,2,"The inserting funnel feeds the dock from the side. The dock transfers one parcel at a time.");
        scene.overlay().showOutline(PonderPalette.RED,"fallback",util.select().fromTo(5,1,2,5,1,3),90);
        note(scene,util,5,1,2,"Provide an extraction path below the dock for returned parcels. This hopper sends them to a separate chest.");
        scene.markAsFinished();
    }

    public static void receive(SceneBuilder builder, SceneBuildingUtil util) {
        var scene=begin(builder,"distant_import","Extracting received parcels",7);

        reveal(scene,util.select().position(3,3,3));
        use(scene,util,3,3,3,ItemStack.EMPTY);
        note(scene,util,3,3,3,"On a bound dock, open the configuration and set Receive or Bidirectional mode and a receiving address.");
        reveal(scene,util.select().position(3,1,3));
        reveal(scene,util.select().position(3,2,3));
        note(scene,util,3,2,3,"Place a chute below the dock, leading directly into an inventory. Received parcels must be extracted.");
        scene.overlay().showLine(PonderPalette.GREEN,new Vec3(3.5,3.0,3.0),new Vec3(3.5,1.8,3.0),40);
        parcel(scene,new ItemStack(ModItems.REMOTE_PACKAGE.get()),new Vec3(3.5,3.1,3.5),new Vec3(3.5,1.8,3.5));
        scene.overlay().showOutline(PonderPalette.GREEN,"arrival",util.select().position(3,1,3),90);
        note(scene,util,3,1,3,"The container receives a parcel, not unpacked contents. Continue with local parcel transport and unpacking.");
        scene.markAsFinished();
    }

    private DeliveryScenes() {}
}
