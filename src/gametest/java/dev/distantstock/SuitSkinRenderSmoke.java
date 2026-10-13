package dev.distantstock;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.distantstock.client.SuitRenderType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;

/** Draw real player/leggings geometry to a GPU framebuffer, rather than only checking state names. */
final class SuitSkinRenderSmoke {
    static void check() throws Exception {
        var mc = Minecraft.getInstance();
        var target = new TextureTarget(160, 200, true, Minecraft.ON_OSX);
        RenderSystem.backupProjectionMatrix();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        int previousLightmap = RenderSystem.getShaderTexture(2);
        NativeImage white = new NativeImage(16, 16, false);
        white.fillRect(0, 0, 16, 16, -1);
        try (var lightmap = new DynamicTexture(white);
             var memory = new ByteBufferBuilder(262144)) {
            view.identity().translate(0, 0, -2000);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 160, 200, 0, 1000, 3000),
                    VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            Lighting.setupForEntityInInventory();
            RenderSystem.setShaderTexture(2, lightmap.getId());
            var player = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            var leggings = new HumanoidModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            leggings.setAllVisible(false);
            leggings.body.visible = leggings.leftLeg.visible = leggings.rightLeg.visible = true;
            var pose = new PoseStack();
            pose.translate(80, 48, 0);
            pose.scale(80, 80, 80);
            var buffers = MultiBufferSource.immediate(memory);
            Path output = Path.of("suit-skin-validation");
            Files.createDirectories(output);
            for (int phase : new int[]{0, 6}) {
                try (var dark = draw(target, buffers, player, leggings, pose, phase, 0);
                     var bright = draw(target, buffers, player, leggings, pose, phase, 1)) {
                    // In the middle of each leg, a solid skin must hide a changing background even
                    // when the armour over it is translucent. A glass-only leg changes with the sky.
                    for (int x : new int[]{70, 90}) {
                        int a = dark.getPixelRGBA(x, 135);
                        int b = bright.getPixelRGBA(x, 135);
                        if (a != b) throw new AssertionError("Background leaked through skin at phase " + phase);
                    }
                    dark.writeToFile(output.resolve("skin-under-leggings-phase-" + phase + ".png"));
                }
            }
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.setShaderTexture(2, previousLightmap);
            Lighting.setupFor3DItems();
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static NativeImage draw(TextureTarget target, MultiBufferSource.BufferSource buffers,
                                    PlayerModel<?> player, HumanoidModel<?> leggings, PoseStack pose,
                                    int phase, float background) {
        target.setClearColor(background, 0, background, 1);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        player.renderToBuffer(pose, buffers.getBuffer(SuitRenderType.skin(
                ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"))),
                15728880, OverlayTexture.NO_OVERLAY, -1);
        leggings.renderToBuffer(pose, buffers.getBuffer(SuitRenderType.of(ResourceLocation.fromNamespaceAndPath(
                "distantstock", "textures/models/armor/ether_casing_layer_2_phase_" + phase + ".png"))),
                15728880, OverlayTexture.NO_OVERLAY, -1);
        buffers.endBatch();
        return Screenshot.takeScreenshot(target);
    }
}
