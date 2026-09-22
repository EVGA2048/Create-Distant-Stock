package dev.distantstock.block;

import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Stores which condition linker owns this lamp and the fourth (buzzer enable) input. */
public final class StackLightBlockEntity extends BlockEntity {
    private BlockPos sourcePos;
    private ResourceLocation sourceDimension;
    private boolean buzzerEnabled;
    private long nextBuzzerTick;

    public StackLightBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STACK_LIGHT.get(), pos, state);
    }

    public boolean buzzerEnabled() {
        return buzzerEnabled;
    }

    public boolean buzzerActive() {
        return buzzerEnabled;
    }

    public BlockPos sourcePos() {
        return sourcePos;
    }

    public ResourceLocation sourceDimension() {
        return sourceDimension;
    }

    public boolean isBound() {
        return sourcePos != null && sourceDimension != null;
    }

    public void bind(ResourceLocation dimension, BlockPos source) {
        sourceDimension = dimension;
        sourcePos = source == null ? null : source.immutable();
        sync();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, StackLightBlockEntity be) {
        long now = level.getGameTime();
        if (!be.buzzerActive()) {
            be.nextBuzzerTick = now;
            return;
        }
        if (now < be.nextBuzzerTick) return;
        be.playBuzzerPulse(level);
    }

    public boolean bindIfFree(ResourceLocation dimension, BlockPos source) {
        if (isBound() && !accepts(dimension, source)) return false;
        bind(dimension, source);
        return true;
    }

    public boolean accepts(ResourceLocation dimension, BlockPos source) {
        return sourcePos != null && source.equals(sourcePos) && Objects.equals(sourceDimension, dimension);
    }

    public boolean applyFrom(ResourceLocation dimension, BlockPos source,
                             boolean red, boolean yellow, boolean green, boolean buzzer) {
        if (!accepts(dimension, source) || level == null || level.isClientSide) return false;
        buzzerEnabled = buzzer;
        StackLightBlock.apply(level, worldPosition, red, yellow, green);
        sync();
        return true;
    }

    private void playBuzzerPulse(Level level) {
        level.playSound(null, worldPosition, ModSounds.STACK_LIGHT_BUZZER.get(),
                SoundSource.BLOCKS, 0.72f, 1.0f);
        nextBuzzerTick = level.getGameTime() + 22; // 1.1 s cadence for the metro-door warning pulse.
    }

    public void unbindIf(ResourceLocation dimension, BlockPos source) {
        if (!accepts(dimension, source)) return;
        sourcePos = null;
        sourceDimension = null;
        buzzerEnabled = false;
        if (level != null && !level.isClientSide) {
            StackLightBlock.apply(level, worldPosition, false, false, false);
        }
        sync();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (sourcePos != null) {
            tag.putInt("SourceX", sourcePos.getX());
            tag.putInt("SourceY", sourcePos.getY());
            tag.putInt("SourceZ", sourcePos.getZ());
        }
        if (sourceDimension != null) tag.putString("SourceDimension", sourceDimension.toString());
        tag.putBoolean("BuzzerEnabled", buzzerEnabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        sourcePos = tag.contains("SourceX")
                ? new BlockPos(tag.getInt("SourceX"), tag.getInt("SourceY"), tag.getInt("SourceZ")) : null;
        sourceDimension = null;
        if (tag.contains("SourceDimension")) {
            sourceDimension = ResourceLocation.tryParse(tag.getString("SourceDimension"));
        }
        buzzerEnabled = tag.getBoolean("BuzzerEnabled");
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
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
