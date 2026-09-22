package dev.distantstock.compat.fluidlogistics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.yision.fluidlogistics.render.FluidPackageItemRenderer;
import dev.distantstock.DistantStock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Client-only calls into FluidLogistics, isolated so packs without it never resolve these classes. */
public final class FluidLogisticsClientCompat {
    public static final PartialModel REMOTE_FLUID_PACKAGE = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "item/remote_fluid_package"));
    public static final PartialModel REMOTE_FLUID_RIGGING = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath("create", "item/package/rigging_12x10"));

    private FluidLogisticsClientCompat() {
    }

    public static void registerModels() {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(FluidLogisticsCompat.REMOTE_FLUID_PACKAGE.get());
        AllPartialModels.PACKAGES.put(id, REMOTE_FLUID_PACKAGE);
        AllPartialModels.PACKAGE_RIGGING.put(id, REMOTE_FLUID_RIGGING);
    }

    public static void renderContentsForEntity(ItemStack stack, PoseStack pose,
                                               MultiBufferSource buffers, int light) {
        FluidPackageItemRenderer.renderFluidContentsForEntity(stack, pose, buffers, light);
    }
}
