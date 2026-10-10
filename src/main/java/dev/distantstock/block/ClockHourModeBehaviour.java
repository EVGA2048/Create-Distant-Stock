package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/** Create-native value-settings board for choosing 12/24-hour clock mode. */
final class ClockHourModeBehaviour extends BlockEntityBehaviour implements ValueSettingsBehaviour {
    static final BehaviourType<ClockHourModeBehaviour> TYPE = new BehaviourType<>("clock_hour_mode");
    private final ValueBoxTransform slot = new ClockTransform();

    ClockHourModeBehaviour(com.simibubi.create.foundation.blockEntity.SmartBlockEntity clock) {
        super(clock);
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    @Override
    public int netId() {
        return 11;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean bypassesInput(ItemStack stack) {
        // Preserve the two explicit clock tools: dye recolours the display, wrench toggles mute.
        return DyeColor.getColor(stack) != null || stack.is(Tags.Items.TOOLS_WRENCH);
    }

    @Override
    public boolean testHit(Vec3 hit) {
        Vec3 local = hit.subtract(Vec3.atLowerCornerOf(getPos()));
        return slot.testHit(blockEntity.getLevel(), getPos(), blockEntity.getBlockState(), local);
    }

    @Override
    public ValueBoxTransform getSlotPositioning() {
        return slot;
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hit) {
        return new ValueSettingsBoard(
                Component.translatable("value.distantstock.clock.hour_mode"),
                1, 1,
                List.of(Component.translatable("value.distantstock.clock.hour_mode")),
                new ValueSettingsFormatter(settings -> Component.translatable(
                        settings.value() == 0
                                ? "value.distantstock.clock.12h"
                                : "value.distantstock.clock.24h")));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean sneak) {
        setTwentyFourHour(settings.value() != 0);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, twentyFourHour() ? 1 : 0);
    }

    private boolean twentyFourHour() {
        if (blockEntity instanceof NixieClockBlockEntity clock) return clock.twentyFourHour();
        return true;
    }

    private void setTwentyFourHour(boolean value) {
        if (blockEntity instanceof NixieClockBlockEntity clock) clock.setTwentyFourHour(value);
    }

    private static final class ClockTransform extends ValueBoxTransform.Sided {
        @Override
        public float getScale() {
            return 2f;
        }

        @Override
        protected Vec3 getSouthLocation() {
            // The control belongs to the whole compact clock face, not a tiny painted hotspot.
            return new Vec3(.5, .5, .5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction side) {
            return side != null;
        }
    }
}
