package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class GaugeBlock extends WallPanelBlock {
    public static final MapCodec<GaugeBlock> CODEC = simpleCodec(GaugeBlock::new);

    public GaugeBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<GaugeBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GaugeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.GAUGE.get(), GaugeBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof RequesterItem && RequesterData.tuned(stack)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof GaugeBlockEntity be) {
                be.setFreq(RequesterData.freq(stack));
                if (!RequesterData.address(stack).isEmpty()) {
                    be.setAddress(RequesterData.address(stack));
                }
                player.displayClientMessage(Component.translatable("gui.distantstock.tuned")
                        .append(Component.literal(" " + RequesterData.shortFreq(be.freq()))), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        open(level, pos, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        open(level, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void open(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new RequesterMenu(id, inv, pos),
                    Component.translatable("gui.distantstock.title")
            ), buf -> {
                buf.writeBoolean(true);
                buf.writeBlockPos(pos);
            });
        }
    }
}
