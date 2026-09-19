package dev.distantstock.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Owns the link to one stack light and samples the four labelled redstone sides. */
public final class ConditionLinkerBlockEntity extends BlockEntity {
    private BlockPos targetPos;
    private ResourceLocation targetDimension;

    public ConditionLinkerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONDITION_LINKER.get(), pos, state);
    }

    public BlockPos targetPos() {
        return targetPos;
    }

    public ResourceLocation targetDimension() {
        return targetDimension;
    }

    public void setTarget(ResourceLocation dimension, BlockPos pos) {
        targetDimension = dimension;
        targetPos = pos == null ? null : pos.immutable();
        setChanged();
        bindTarget();
        sampleAndSend();
        sync();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ConditionLinkerBlockEntity be) {
        if (level.isClientSide || level.getGameTime() % 4 != 0) return;
        be.bindTarget();
        be.sampleAndSend();
    }

    private ServerLevel targetLevel() {
        if (!(level instanceof ServerLevel serverLevel) || targetDimension == null || targetPos == null) return null;
        if (!serverLevel.dimension().location().equals(targetDimension)) return null;
        return serverLevel;
    }

    private void bindTarget() {
        ServerLevel targetLevel = targetLevel();
        if (targetLevel == null || !targetLevel.isLoaded(targetPos)) return;
        if (targetLevel.getBlockEntity(targetPos) instanceof StackLightBlockEntity light
                && !light.isBound()) {
            light.bindIfFree(level.dimension().location(), worldPosition);
        }
    }

    public void sampleAndSend() {
        ServerLevel targetLevel = targetLevel();
        if (targetLevel == null || !targetLevel.isLoaded(targetPos)) return;
        if (!(targetLevel.getBlockEntity(targetPos) instanceof StackLightBlockEntity light)
                || !light.accepts(level.dimension().location(), worldPosition)) {
            return;
        }

        BlockState state = getBlockState();
        boolean red = powered(ConditionLinkerBlock.worldSide(state, Direction.NORTH));
        boolean yellow = powered(ConditionLinkerBlock.worldSide(state, Direction.EAST));
        boolean green = powered(ConditionLinkerBlock.worldSide(state, Direction.SOUTH));
        boolean buzzer = powered(ConditionLinkerBlock.worldSide(state, Direction.WEST));
        light.applyFrom(level.dimension().location(), worldPosition, red, yellow, green, buzzer);
    }

    private boolean powered(Direction side) {
        return level != null && level.getSignal(worldPosition.relative(side), side) > 0;
    }

    public void detachTarget() {
        ServerLevel targetLevel = targetLevel();
        if (targetLevel != null && targetLevel.isLoaded(targetPos)
                && targetLevel.getBlockEntity(targetPos) instanceof StackLightBlockEntity light) {
            light.unbindIf(level.dimension().location(), worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (targetPos != null) {
            tag.putInt("TargetX", targetPos.getX());
            tag.putInt("TargetY", targetPos.getY());
            tag.putInt("TargetZ", targetPos.getZ());
        }
        if (targetDimension != null) tag.putString("TargetDimension", targetDimension.toString());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetPos = tag.contains("TargetX")
                ? new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ")) : null;
        targetDimension = tag.contains("TargetDimension")
                ? ResourceLocation.tryParse(tag.getString("TargetDimension")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
