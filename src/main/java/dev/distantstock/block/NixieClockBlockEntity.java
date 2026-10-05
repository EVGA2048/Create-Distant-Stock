package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/** State and Minecraft-time chime logic for the wall-mounted Nixie Clock. */
public final class NixieClockBlockEntity extends SmartBlockEntity {
    private DyeColor color = DyeColor.ORANGE;
    private boolean twentyFourHour = true;
    private boolean muted;
    /** Persisted so unloading/reloading a chunk inside Create's short trigger window cannot replay it. */
    private long lastChimeKey = Long.MIN_VALUE;

    public NixieClockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NIXIE_CLOCK.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new ClockHourModeBehaviour(this));
    }

    public DyeColor color() {
        return color;
    }

    public boolean twentyFourHour() {
        return twentyFourHour;
    }

    public boolean muted() {
        return muted;
    }

    public void toggleMuted() {
        muted = !muted;
        setChanged();
        notifyUpdate();
    }

    public void setColor(DyeColor next) {
        if (next == null || next == color) return;
        color = next;
        setChanged();
        notifyUpdate();
    }

    public void toggleHourMode() {
        setTwentyFourHour(!twentyFourHour);
    }

    public void setTwentyFourHour(boolean value) {
        if (twentyFourHour == value) return;
        twentyFourHour = value;
        setChanged();
        notifyUpdate();
    }

    /** Create's clock conversion: 0 game ticks is 06:00, 1000 ticks is one Minecraft hour. */
    public static int timeOfDayTicks(Level level) {
        long scale = level.dimensionType().natural() ? 1L : 24L;
        return (int) Math.floorMod(level.getDayTime() * scale, 24000L);
    }

    public static String formatTime(int timeOfDayTicks, boolean twentyFourHour) {
        int ticks = Math.floorMod(timeOfDayTicks, 24000);
        int hour24 = (ticks / 1000 + 6) % 24;
        int minute = (ticks % 1000) * 60 / 1000;
        int hour = twentyFourHour ? hour24 : hour24 % 12;
        if (!twentyFourHour && hour == 0) hour = 12;
        return twentyFourHour
                ? String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                : String.format(Locale.ROOT, "%d:%02d", hour, minute);
    }

    public String displayText() {
        if (level == null) return twentyFourHour ? "00:00" : "12:00";
        return formatTime(timeOfDayTicks(level), twentyFourHour);
    }

    /**
     * Same two windows as Create's Cuckoo Clock: noon, and dusk when sleeping becomes available.
     * Returns 0/1 for the two chimes, or -1 outside either window.
     */
    public static int chimeSlot(int timeOfDayTicks) {
        int ticks = Math.floorMod(timeOfDayTicks, 24000);
        int hour = (ticks / 1000 + 6) % 24;
        int minute = (ticks % 1000) * 60 / 1000;
        if (hour == 12 && minute < 5) return 0;
        if (hour == 18 && minute > 31 && minute < 36) return 1;
        return -1;
    }

    public static void tickClock(Level level, BlockPos pos, BlockState state, NixieClockBlockEntity be) {
        be.tick();
        if (level.isClientSide || !level.dimensionType().natural()) return;

        int slot = chimeSlot(timeOfDayTicks(level));
        if (slot < 0) return;
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        long key = day * 2L + slot;
        if (be.lastChimeKey == key) return;

        be.lastChimeKey = key;
        be.setChanged();
        if (be.muted) return;
        level.playSound(null, pos, ModSounds.NIXIE_CLOCK_WESTMINSTER.get(),
                SoundSource.BLOCKS, 0.95f, 1.0f);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Color", color.ordinal());
        tag.putBoolean("TwentyFourHour", twentyFourHour);
        tag.putBoolean("Muted", muted);
        tag.putLong("LastChimeKey", lastChimeKey);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        int index = Math.clamp(tag.contains("Color") ? tag.getInt("Color") : DyeColor.ORANGE.ordinal(),
                0, DyeColor.values().length - 1);
        color = DyeColor.values()[index];
        twentyFourHour = !tag.contains("TwentyFourHour") || tag.getBoolean("TwentyFourHour");
        muted = tag.getBoolean("Muted");
        lastChimeKey = tag.contains("LastChimeKey") ? tag.getLong("LastChimeKey") : Long.MIN_VALUE;
    }
}
