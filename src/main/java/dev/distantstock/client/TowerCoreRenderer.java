package dev.distantstock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.distantstock.block.TowerCoreBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.resources.ResourceLocation;
import dev.distantstock.DistantStock;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * 底座里的以太：四面观察窗上的一条液面。
 *
 * <p>底座是 3×3 里中间那一格，四边各站着一个远仓机壳 —— 所以这四个窗口平时正好被机壳挡住。
 * 机壳通上红石就变成窗户，那才是你看见这条液面的时候。这不是巧合：美术包里那张
 * `tower/fluid.png`（带框的暗色窗芯）此前没有任何模型用过，它本来就是留给它的。
 *
 * <p>画的是四片贴在玻璃芯外缘的面，高度按罐里的量走。不做真正的立方体液面：底座内部是实心的
 * 机壳与贯穿的轴，透视进去只会看到它们；而玩家要的是「一眼看到还剩多少」。
 */
public final class TowerCoreRenderer extends SafeBlockEntityRenderer<TowerCoreBlockEntity> {
    private static final PartialModel SHAFT = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            DistantStock.MODID, "block/tower/tower_core_shaft"));

    /** Force the kinetic partial into the model bake before the first tower is ever rendered. */
    public static void registerModels() {
    }
    /**
     * 模型用 0..16 像素，方块实体渲染器用 0..1 方块 —— 这里按像素写，出口处除一次。
     *
     * <p>上一版忘了除：五像素高的窗口变成了五格高，四片液面飘在天上，被玩家一眼看见
     * （"天上莫名其妙出来一堆流体"）。数字本身没错，单位错了。
     */
    private static final float PIXEL = 1f / 16f;
    /** 玻璃芯在贴图里占第 4..12 像素；窗口画在方块坐标 3..13，所以一格贴图 = 10/16 像素。 */
    private static final float GLASS_LOW = (3f + 4f * (10f / 16f)) * PIXEL;   // 5.5px
    private static final float GLASS_HIGH = (3f + 12f * (10f / 16f)) * PIXEL; // 10.5px
    /**
     * 液面贴在玻璃芯外面一丁点（1/16 像素）。
     *
     * <p>必须在**外面**：玻璃芯不透明、机壳那一格又是实心方块，画在里面等于画在墙后。步长比模型
     * 里那四片观察窗（0.02 像素）大，两者不会打架。
     */
    private static final float PROUD = 0.0625f * PIXEL;

    public TowerCoreRenderer(BlockEntityRendererProvider.Context context) {
        super();
    }

    @Override
    protected void renderSafe(TowerCoreBlockEntity be, float partialTick, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // Draw our own kinetic partial instead of delegating to Create's ShaftRenderer. The latter
        // deliberately returns early whenever Flywheel visualization is supported, assuming its BE
        // type has a registered visual. TowerCore is our type and has no Flywheel visual, so that
        // path made the shaft disappear on real Flywheel-enabled clients even though it worked in
        // a vanilla renderer smoke test. Rendering this cached partial directly is safe in both.
        KineticBlockEntityRenderer.renderRotatingBuffer(be,
                CachedBuffers.partial(SHAFT, be.getBlockState()), ms,
                buffer.getBuffer(RenderType.cutoutMipped()), light);
        int stored = be.ether();
        if (stored <= 0) {
            return;
        }
        FluidStack fluid = be.tank().getFluidInTank(0);
        if (fluid.isEmpty()) {
            return;
        }
        float ratio = Math.min(1f, stored / (float) TowerCoreBlockEntity.ETHER_CAPACITY);
        float top = GLASS_LOW + (GLASS_HIGH - GLASS_LOW) * ratio;

        IClientFluidTypeExtensions client = IClientFluidTypeExtensions.of(fluid.getFluid());
        FluidState state = fluid.getFluid().defaultFluidState();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(client.getStillTexture(fluid));
        int tint = client.getTintColor(fluid);
        float alpha = ((tint >> 24) & 0xFF) / 255f;
        float red = ((tint >> 16) & 0xFF) / 255f;
        float green = ((tint >> 8) & 0xFF) / 255f;
        float blue = (tint & 0xFF) / 255f;
        // 液面只露 sprite 底下那一段：液位低的时候看到的是底下那一点，而不是把整张贴图压扁 ——
        // 压扁的液面看着像进度条，不像液体。
        float vTop = sprite.getV1() - (sprite.getV1() - sprite.getV0()) * ratio;

        VertexConsumer out = buffer.getBuffer(RenderType.translucent());
        Matrix4f pose = ms.last().pose();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            boolean alongX = side.getAxis() == Direction.Axis.Z;
            // 这一面所在的平面：向外的坐标加上一小步。
            float plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? 1f + PROUD : -PROUD;
            float a0 = GLASS_LOW;
            float a1 = GLASS_HIGH;
            float ax0 = alongX ? a0 : plane;
            float ax1 = alongX ? a1 : plane;
            float az0 = alongX ? plane : a0;
            float az1 = alongX ? plane : a1;
            float nx = side.getStepX();
            float nz = side.getStepZ();
            // vanilla 的面顶点序：北/东从 a1 那头起，南/西从 a0 那头起。
            boolean fromHigh = side == Direction.NORTH || side == Direction.EAST;
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float vBottom = sprite.getV1();
            // 0 左上 1 左下 2 右下 3 右上，对应 BlockFaceUV 的四个角。
            put(out, pose, fromHigh ? ax1 : ax0, top, fromHigh ? az1 : az0, u0, vTop, nx, nz, red, green, blue, alpha, light, overlay);
            put(out, pose, fromHigh ? ax1 : ax0, GLASS_LOW, fromHigh ? az1 : az0, u0, vBottom, nx, nz, red, green, blue, alpha, light, overlay);
            put(out, pose, fromHigh ? ax0 : ax1, GLASS_LOW, fromHigh ? az0 : az1, u1, vBottom, nx, nz, red, green, blue, alpha, light, overlay);
            put(out, pose, fromHigh ? ax0 : ax1, top, fromHigh ? az0 : az1, u1, vTop, nx, nz, red, green, blue, alpha, light, overlay);
        }
    }

    private static void put(VertexConsumer out, Matrix4f pose, float x, float y, float z,
                            float u, float v, float nx, float nz,
                            float red, float green, float blue, float alpha,
                            int light, int overlay) {
        out.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(nx, 0f, nz);
    }

    /**
     * A little room around the base, so the four panes — which stand a sixteenth of a block outside
     * the block itself — are not clipped away when the camera is close to a neighbouring block.
     */
    @Override
    public AABB getRenderBoundingBox(TowerCoreBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(1);
    }
}
