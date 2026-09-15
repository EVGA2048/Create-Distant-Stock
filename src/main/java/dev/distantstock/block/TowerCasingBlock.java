package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;

/**
 * The distant casing: the tower's 3x3 skirt and the decorative panel everywhere else.
 *
 * <p>A plain block for now. Its connected texture — an eight-neighbour mask per face, and a redstone
 * window that turns the centre see-through while the frame stays solid — is the next stage, and it
 * needs a texture selector this mod does not have yet.
 */
public final class TowerCasingBlock extends Block {
    public static final MapCodec<TowerCasingBlock> CODEC = simpleCodec(TowerCasingBlock::new);

    public TowerCasingBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
