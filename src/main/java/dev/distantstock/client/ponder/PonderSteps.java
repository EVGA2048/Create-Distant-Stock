package dev.distantstock.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Shared pacing only; each storyboard owns its geometry and teaching sequence. */
final class PonderSteps {
    static ItemStack createItem(String name) {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", name)));
    }

    static CreateSceneBuilder begin(SceneBuilder builder, String id, String title, int size) {
        var scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, size);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    static void reveal(CreateSceneBuilder scene, Selection selection) {
        scene.world().showSection(selection, Direction.DOWN);
        scene.idle(15);
    }

    static void note(CreateSceneBuilder scene, SceneBuildingUtil util, int x, int y, int z, String text) {
        scene.overlay().showText(90).text(text)
                .pointAt(util.vector().centerOf(x, y, z)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }

    static void use(CreateSceneBuilder scene, SceneBuildingUtil util, int x, int y, int z, ItemStack item) {
        scene.overlay().showControls(util.vector().topOf(x, y, z), Pointing.DOWN, 50)
                .rightClick().withItem(item);
    }

    /** An illustrative handoff, not a simulated cross-server connection. */
    static void parcel(CreateSceneBuilder scene, ItemStack item, Vec3 from, Vec3 to) {
        var entity = scene.world().createItemEntity(from, Vec3.ZERO, item);
        scene.world().modifyEntity(entity, e -> e.setNoGravity(true));
        for (int frame = 1; frame <= 24; frame++) {
            Vec3 p = from.lerp(to, frame / 24.0);
            scene.world().modifyEntity(entity, e -> { e.setPos(p); e.setDeltaMovement(Vec3.ZERO); });
            scene.idle(1);
        }
        scene.world().modifyEntity(entity, e -> e.discard());
    }

    private PonderSteps() {}
}
