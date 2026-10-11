package dev.distantstock.client;

import dev.distantstock.DistantStock;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * How the worn Resonant Quartz shell is drawn.
 *
 * <p>Vanilla armour is cutout, which cannot express a translucent plate, so the suit is drawn with a
 * blended render type instead — but which blended one is the whole point. {@code entityTranslucent}
 * is NO_CULL: the far side of every shell is drawn as well, and at a quarter opacity the suit reads
 * as an empty glass box with the world behind the player visible through its back. That is what
 * "my legs have gone transparent again" turned out to mean, twice.
 *
 * <p>{@code entityTranslucentCull} blends identically and covers exactly the same average area, but
 * drops the faces pointing away from the camera. So the shell reads as a frosted surface over
 * whatever it covers, and the reading at a distance — an area mean over the whole face, which does
 * not care how many surfaces fed into it — is untouched.
 *
 * <p>The wings are deliberately not drawn with this. They are single-sided membranes on their own
 * layer, and culling would make each one vanish from one side.
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

    private SuitRenderType() {
    }
}
