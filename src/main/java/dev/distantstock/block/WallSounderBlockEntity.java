package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Timing/state for both colour variants of the V4 wall sounder.
 *
 * <p>The lamp is intentionally independent of the selected tone.  While redstone stays high the
 * lamp always uses the same industrial double-flash: 100 ms on, 150 ms off, 100 ms on, 1150 ms off.
 */
public final class WallSounderBlockEntity extends SmartBlockEntity {
    public static final int FLASH_PERIOD_TICKS = 30;
    private int toneIndex = 1;
    private long poweredSince = Long.MIN_VALUE;
    private long nextSoundTick = Long.MIN_VALUE;
    private boolean lastPowered;

    public WallSounderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WALL_SOUNDER.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new WallSounderToneBehaviour(this));
    }

    public int toneIndex() {
        return toneIndex;
    }

    public void setToneIndex(int value) {
        int next = Math.max(0, Math.min(2, value));
        if (toneIndex == next) return;
        toneIndex = next;
        if (level != null && !level.isClientSide && getBlockState().getValue(WallSounderBlock.POWERED)) {
            nextSoundTick = level.getGameTime() + 2;
        }
        notifyUpdate();
    }

    public void powerChanged(boolean powered) {
        if (level == null || level.isClientSide) return;
        long now = level.getGameTime();
        lastPowered = powered;
        if (powered) {
            poweredSince = now;
            nextSoundTick = now;
        } else {
            poweredSince = Long.MIN_VALUE;
            nextSoundTick = Long.MIN_VALUE;
        }
        notifyUpdate();
    }

    public static boolean flashOn(long elapsedTicks) {
        long phase = Math.floorMod(elapsedTicks, FLASH_PERIOD_TICKS);
        // 闪—闪----- : 2t on, 3t off, 2t on, 23t off.
        return phase < 2 || (phase >= 5 && phase < 7);
    }

    public static void tickSounder(Level level, BlockPos pos, BlockState state, WallSounderBlockEntity be) {
        be.tick();
        if (level.isClientSide) return;
        boolean powered = state.getValue(WallSounderBlock.POWERED);
        long now = level.getGameTime();

        // Covers placement into an already-powered circuit and chunk reload while power is present.
        if (powered != be.lastPowered || (powered && be.poweredSince == Long.MIN_VALUE)) {
            be.lastPowered = powered;
            be.poweredSince = powered ? now : Long.MIN_VALUE;
            be.nextSoundTick = powered ? now : Long.MIN_VALUE;
        }

        boolean lit = powered && flashOn(now - be.poweredSince);
        if (state.getValue(WallSounderBlock.LIT) != lit) {
            state = state.setValue(WallSounderBlock.LIT, lit);
            level.setBlock(pos, state, 3);
        }

        if (!powered || be.toneIndex == 0) return;
        if (be.nextSoundTick == Long.MIN_VALUE || now >= be.nextSoundTick) {
            // A little more presence than the logger chime, while staying below the normal 1.0
            // volume ceiling so a nearby wall sounder reads clearly without becoming a jump scare.
            level.playSound(null, pos, be.selectedSound(), SoundSource.BLOCKS, 0.95f, 1.0f);
            be.nextSoundTick = now + be.repeatTicks();
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Tone", toneIndex);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        toneIndex = Math.max(0, Math.min(2, tag.contains("Tone") ? tag.getInt("Tone") : 1));
    }

    private SoundEvent selectedSound() {
        return toneIndex == 2 ? ModSounds.WALL_SOUNDER_F1.get() : ModSounds.WALL_SOUNDER_B2.get();
    }

    private int repeatTicks() {
        return toneIndex == 2 ? 90 : 30;
    }
}
