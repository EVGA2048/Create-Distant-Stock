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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Create-native hold-right-click value board for sound selection.
 *
 * <p>Only the number matters to the UI: 0 = silent, 1 = B2, 2 = F1.  The board deliberately does
 * not expose those internal sound names.
 */
final class WallSounderToneBehaviour extends BlockEntityBehaviour implements ValueSettingsBehaviour {
    static final BehaviourType<WallSounderToneBehaviour> TYPE =
            new BehaviourType<>("wall_sounder_tone");

    private final ValueBoxTransform slot = new FrontFaceTransform();

    WallSounderToneBehaviour(WallSounderBlockEntity sounder) {
        super(sounder);
    }

    private WallSounderBlockEntity sounder() {
        return (WallSounderBlockEntity) blockEntity;
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    @Override
    public int netId() {
        return 1;
    }

    @Override
    public boolean isActive() {
        return true;
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
        return new ValueSettingsBoard(Component.translatable("value.distantstock.wall_sounder"),
                2, 1, List.of(Component.translatable("value.distantstock.wall_sounder")),
                new ValueSettingsFormatter(settings -> Component.literal(Integer.toString(settings.value()))));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean sneak) {
        sounder().setToneIndex(settings.value());
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, sounder().toneIndex());
    }

    private static final class FrontFaceTransform extends ValueBoxTransform.Sided {
        @Override
        public float getScale() {
            // Match the proven dock value-box interaction: a radius of one block around the centre
            // makes every visible part of this small wall appliance a reliable hold-right-click target.
            return 2f;
        }

        @Override
        protected Vec3 getSouthLocation() {
            // Do not anchor the setting target to the artwork's glass face. The model only occupies a
            // shallow slice of the block and its collision hit can report the front, top or side face.
            // A centred target survives all of those hits and does not add a fake visible control.
            return new Vec3(.5, .5, .5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction side) {
            // The player is configuring the appliance, not a particular face of it. Create still calls
            // fromSide() before testHit(); the centred transform is invariant under that rotation.
            return side != null;
        }
    }
}
