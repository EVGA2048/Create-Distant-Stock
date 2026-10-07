package dev.distantstock.item;

import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.distantstock.block.ConditionLinkerBlockEntity;
import dev.distantstock.block.StackLightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * Click-to-link item for operating-condition lights.
 *
 * <p>First click selects a stack light. The next placement creates a condition linker that remembers
 * that light, using the same configurable range as Create's Display Link.
 */
public final class ConditionLinkerItem extends BlockItem {
    private static final String ROOT = "DistantStockConditionTarget";

    public ConditionLinkerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();

        Optional<Target> selected = target(stack);
        if (player.isShiftKeyDown() && selected.isPresent()) {
            if (!level.isClientSide) {
                clearTarget(stack);
                player.displayClientMessage(Component.translatable("message.distantstock.condition_linker.cleared"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        BlockPos clicked = context.getClickedPos();
        if (selected.isEmpty()) {
            if (!(level.getBlockState(clicked).getBlock() instanceof StackLightBlock)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.distantstock.condition_linker.select_light"), true);
                }
                return InteractionResult.FAIL;
            }
            if (!level.isClientSide) {
                setTarget(stack, new Target(level.dimension().location(), clicked));
                player.displayClientMessage(Component.translatable("message.distantstock.condition_linker.selected",
                        clicked.getX(), clicked.getY(), clicked.getZ()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        Target target = selected.get();
        BlockPos placePos = level.getBlockState(clicked).canBeReplaced()
                ? clicked : clicked.relative(context.getClickedFace());
        int range = maxDistance();
        if (!target.dimension().equals(level.dimension().location())
                || !target.pos().closerThan(placePos, range)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(
                        "message.distantstock.condition_linker.too_far", range), true);
            }
            return InteractionResult.FAIL;
        }

        InteractionResult result = super.useOn(context);
        if (!level.isClientSide && result != InteractionResult.FAIL
                && level.getBlockEntity(placePos) instanceof ConditionLinkerBlockEntity linker) {
            linker.setTarget(target.dimension(), target.pos());
            ItemStack held = player.getItemInHand(context.getHand());
            if (!held.isEmpty()) clearTarget(held);
            player.displayClientMessage(Component.translatable("message.distantstock.condition_linker.linked"), true);
        }
        return result;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return target(stack).isPresent();
    }

    public static int maxDistance() {
        return AllConfigs.server().logistics.displayLinkRange.get();
    }

    public static Optional<Target> target(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.contains(ROOT)) return Optional.empty();
        CompoundTag data = root.getCompound(ROOT);
        ResourceLocation dimension = ResourceLocation.tryParse(data.getString("Dimension"));
        if (dimension == null || !data.contains("X")) return Optional.empty();
        return Optional.of(new Target(dimension,
                new BlockPos(data.getInt("X"), data.getInt("Y"), data.getInt("Z"))));
    }

    private static void setTarget(ItemStack stack, Target target) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag data = new CompoundTag();
        data.putString("Dimension", target.dimension().toString());
        data.putInt("X", target.pos().getX());
        data.putInt("Y", target.pos().getY());
        data.putInt("Z", target.pos().getZ());
        root.put(ROOT, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    private static void clearTarget(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        root.remove(ROOT);
        if (root.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    public record Target(ResourceLocation dimension, BlockPos pos) {
    }
}
