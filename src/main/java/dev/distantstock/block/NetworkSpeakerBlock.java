package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/** Passive receive-only endpoint for {@link NetworkBroadcasterBlock}. */
public final class NetworkSpeakerBlock extends BaseEntityBlock implements IWrenchable, IBE<NetworkSpeakerBlockEntity> {
    public static final MapCodec<NetworkSpeakerBlock> CODEC = simpleCodec(NetworkSpeakerBlock::new);

    /**
     * Direction the speaker's note-box face points.  It deliberately uses the six-way property:
     * floor, ceiling and all four walls are valid mounting surfaces.
     */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public NetworkSpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // The clicked face already points away from the supporting surface.  Therefore clicking
        // the underside of a ceiling yields DOWN, exactly where the note-box face should point.
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public Class<NetworkSpeakerBlockEntity> getBlockEntityClass() {
        return NetworkSpeakerBlockEntity.class;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntityType<? extends NetworkSpeakerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.NETWORK_SPEAKER.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkSpeakerBlockEntity(pos, state);
    }
}
