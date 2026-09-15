package dev.distantstock.client;

import com.simibubi.create.foundation.block.connected.CTType;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import dev.distantstock.DistantStock;
import net.minecraft.resources.ResourceLocation;

/**
 * The casing's connected-texture layout: one 16x16 sheet of 16-pixel tiles, indexed by the eight
 * neighbours around the face being drawn.
 *
 * <p>Create's own types index a handful of tiles with a formula — the corners only, or the four
 * edges only. This one is the whole lookup table: every one of the 256 combinations is its own
 * tile at {@code (mask % 16, mask / 16)}, which is how the art was drawn. Create's stock
 * {@code CTSpriteShiftEntry} already reads a sheet that way, so the sheet size is all it takes to
 * describe it.
 *
 * <p>The bit order is the art's, not Create's: left 1, right 2, up 4, down 8, then the corners at
 * 16, 32, 64, 128. Create's own cross type puts up first, and copying that would swap the sides
 * for the top and bottom of every tile — a casing wall that tiles correctly along one axis and
 * wrongly along the other, which reads as an art bug rather than a mapping one. Reading the four
 * single-bit tiles back out of {@code ct_inactive.png} settles it: mask 1 drops the left border,
 * mask 4 the top.
 */
public final class TowerCasingCTType implements CTType {
    public static final TowerCasingCTType INSTANCE = new TowerCasingCTType();
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "tower_casing");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public int getSheetSize() {
        return 16;
    }

    @Override
    public ConnectedTextureBehaviour.ContextRequirement getContextRequirement() {
        return ConnectedTextureBehaviour.ContextRequirement.builder().all().build();
    }

    @Override
    public int getTextureIndex(ConnectedTextureBehaviour.CTContext ctx) {
        return (ctx.left ? 1 : 0) | (ctx.right ? 2 : 0)
                | (ctx.up ? 4 : 0) | (ctx.down ? 8 : 0)
                | (ctx.topLeft ? 16 : 0) | (ctx.topRight ? 32 : 0)
                | (ctx.bottomLeft ? 64 : 0) | (ctx.bottomRight ? 128 : 0);
    }

    private TowerCasingCTType() {
    }
}
