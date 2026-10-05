package dev.distantstock.block;

import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import dev.distantstock.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

/** A single-line Create flap display repurposed as a compact Minecraft-time clock. */
public final class FlapClockBlockEntity extends FlapDisplayBlockEntity {
    private boolean twentyFourHour = true;
    private boolean muted;
    private long lastChimeKey = Long.MIN_VALUE;
    private String shownText = "";

    public FlapClockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLAP_CLOCK.get(), pos, state);
        updateSpeed = false;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new ClockHourModeBehaviour(this));
    }

    /**
     * Internally two blocks wide so Create gives the line enough character cells for HH:MM.
     * The renderer compresses that width back into one physical block. Only one line is retained:
     * one Create flap line is already exactly half a block high.
     */
    @Override
    public void updateControllerStatus() {
        isController = true;
        xSize = 2;
        ySize = 1;
        ensureSingleLine();
    }

    private void ensureSingleLine() {
        if (lines == null || lines.isEmpty()) {
            initDefaultSections();
        }
        if (lines.size() > 1) {
            lines = new ArrayList<>(lines.subList(0, 1));
        }
        if (colour == null || colour.length < 2) {
            colour = new DyeColor[]{DyeColor.ORANGE, null};
        } else if (colour[0] == null) {
            colour[0] = DyeColor.ORANGE;
        }
        if (glowingLines == null || glowingLines.length < 2) {
            glowingLines = new boolean[2];
        }
        if (manualLines == null || manualLines.length < 2) {
            manualLines = new boolean[2];
        }
    }

    @Override
    public net.minecraft.core.Direction getDirection() {
        BlockState state = getBlockState();
        return state.hasProperty(FlapClockBlock.FACING)
                ? state.getValue(FlapClockBlock.FACING).getOpposite()
                : net.minecraft.core.Direction.NORTH;
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
        sendData();
    }

    public void toggleHourMode() {
        setTwentyFourHour(!twentyFourHour);
    }

    public void setTwentyFourHour(boolean value) {
        if (twentyFourHour == value) return;
        twentyFourHour = value;
        shownText = "";
        refreshTime(true);
        setChanged();
        sendData();
    }

    public void setDisplayColour(DyeColor dye) {
        if (dye == null) return;
        ensureSingleLine();
        if (colour[0] == dye) return;
        setColour(0, dye);
        setChanged();
        sendData();
    }

    public DyeColor displayColour() {
        ensureSingleLine();
        return colour[0] == null ? DyeColor.ORANGE : colour[0];
    }

    public String displayText() {
        if (level == null) return twentyFourHour ? "00:00" : "12:00";
        return NixieClockBlockEntity.formatTime(NixieClockBlockEntity.timeOfDayTicks(level), twentyFourHour);
    }

    private void refreshTime(boolean force) {
        if (level == null || level.isClientSide) return;
        String next = displayText();
        if (!force && next.equals(shownText)) return;
        shownText = next;
        ensureSingleLine();
        applyTextManually(0, Component.literal(next));
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;

        // Minecraft minutes move quickly; update only when the visible minute actually changes so
        // Create performs one genuine flap transition rather than replaying the same text every tick.
        refreshTime(false);

        if (!level.dimensionType().natural()) return;
        int slot = NixieClockBlockEntity.chimeSlot(NixieClockBlockEntity.timeOfDayTicks(level));
        if (slot < 0) return;
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        long key = day * 2L + slot;
        if (lastChimeKey == key) return;
        lastChimeKey = key;
        setChanged();
        if (muted) return;
        level.playSound(null, worldPosition, ModSounds.NIXIE_CLOCK_WESTMINSTER.get(),
                SoundSource.BLOCKS, 0.95f, 1.0f);
    }

    @Override
    public void initialize() {
        super.initialize();
        updateControllerStatus();
        refreshTime(true);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.write(tag, regs, clientPacket);
        tag.putBoolean("TwentyFourHour", twentyFourHour);
        tag.putBoolean("Muted", muted);
        tag.putLong("LastChimeKey", lastChimeKey);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider regs, boolean clientPacket) {
        super.read(tag, regs, clientPacket);
        twentyFourHour = !tag.contains("TwentyFourHour") || tag.getBoolean("TwentyFourHour");
        muted = tag.getBoolean("Muted");
        lastChimeKey = tag.contains("LastChimeKey") ? tag.getLong("LastChimeKey") : Long.MIN_VALUE;
        ensureSingleLine();
        shownText = "";
    }
}
