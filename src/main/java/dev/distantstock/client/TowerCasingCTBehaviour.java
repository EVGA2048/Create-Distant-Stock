package dev.distantstock.client;

import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import dev.distantstock.DistantStock;
import dev.distantstock.block.TowerCasingBlock;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Picks which of the casing's two sheets a face is cut from.
 *
 * <p>The inactive and active sheets are both full 256-tile tables, drawn from the same panel
 * geometry, so the two differ only in what the middle of each tile is: painted metal, or glass.
 * Which one a face gets is the only decision here, and it is read from the block state, because
 * this runs while the chunk is being baked and has no world to look at. The state is kept honest
 * by {@link TowerCasingBlock}, which is where the redstone actually spreads.
 *
 * <p>Both sheets are cut from the same original — the plain casing texture the model names. That
 * is not a coincidence: Create only rewrites a quad whose sprite is the shift's own original, so
 * the model's texture has to be the one both shifts start from.
 */
public final class TowerCasingCTBehaviour extends ConnectedTextureBehaviour.Base {
    private static final ResourceLocation ORIGINAL =
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "block/tower/casing_inactive");

    public static final CTSpriteShiftEntry INACTIVE = shift("block/tower/ct_inactive");
    public static final CTSpriteShiftEntry ACTIVE = shift("block/tower/ct_active");

    private static CTSpriteShiftEntry shift(String sheet) {
        return CTSpriteShifter.getCT(TowerCasingCTType.INSTANCE, ORIGINAL,
                ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, sheet));
    }

    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction face, TextureAtlasSprite sprite) {
        return state.getValue(TowerCasingBlock.POWERED) ? ACTIVE : INACTIVE;
    }
}
