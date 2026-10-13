package dev.distantstock.client;

import dev.distantstock.DistantStock;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render the resonant shell with blending and its visible wearer with cutout skin.
 * PlayerModel normally uses a translucent skin pass. Keeping an opaque skin in that same pass as
 * depth-writing glass allows batching to occlude it when the shell is drawn first. Cutout skin
 * retains transparent texels while establishing the body before translucent armour is composited.
 * Back-face culling only controls the shell's rear faces; it cannot fix missing skin underneath.
 */
public final class SuitRenderType {
    /** The prefix every one of the suit's armour textures shares. */
    private static final String SUIT = "textures/models/armor/ether_casing_layer_";

    /** True for the suit's own textures, so nothing else in the game is affected. */
    public static boolean isSuit(ResourceLocation texture) {
        return DistantStock.MODID.equals(texture.getNamespace())
                && texture.getPath().startsWith(SUIT);
    }

    /** Blended and culled for the suit, plain vanilla cutout for everything else. */
    public static RenderType of(ResourceLocation texture) {
        return isSuit(texture) ? RenderType.entityTranslucentCull(texture)
                : RenderType.armorCutoutNoCull(texture);
    }

    /** Ordinary visible skin; callers must preserve vanilla invisibility and cloak handling. */
    public static RenderType skin(ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }

    private SuitRenderType() {
    }
}
