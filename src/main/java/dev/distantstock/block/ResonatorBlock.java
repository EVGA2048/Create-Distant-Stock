package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The ether resonator: the cap on a complete tower.
 *
 * <p>Its fixed body is a block model; the arms are a block entity renderer, because they turn about
 * the block's own centre and sweep wider than the block — about twelve units of radius — so they
 * cannot be baked into a static model and the renderer has to declare a bounding box larger than
 * one block.
 */
public final class ResonatorBlock extends BaseEntityBlock {
    public static final MapCodec<ResonatorBlock> CODEC = simpleCodec(ResonatorBlock::new);

    public ResonatorBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Ticking on both sides. The light's three appearances are chosen on the server, but the
     * renderer reads them on the client, and a client that never ticks would keep whatever it was
     * told when the chunk was baked.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.ETHER_RESONATOR.get(), ResonatorBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResonatorBlockEntity(pos, state);
    }

}
