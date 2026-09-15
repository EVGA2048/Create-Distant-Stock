package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import dev.distantstock.routing.DockMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Value Settings for the remote dock. Create only offers this panel to {@link
 * com.simibubi.create.foundation.blockEntity.SmartBlockEntity} behaviours, which is why the dock extends
 * that base class. Small numbers only: the dock deliberately has no screen.
 */
final class DockModeBehaviour extends BlockEntityBehaviour implements ValueSettingsBehaviour {
    static final BehaviourType<DockModeBehaviour> TYPE = new BehaviourType<>("distant_dock_mode");

    // Derived from the enum, not written out beside it. See DockMode.translationKey.
    private static final List<Component> OPTIONS = java.util.Arrays.stream(DockMode.values())
            .map(mode -> (Component) Component.translatable(mode.translationKey()))
            .toList();

    private final ValueBoxTransform slot = new DockValueBox(true);

    DockModeBehaviour(DockBlockEntity dock) {
        super(dock);
    }

    private DockBlockEntity dock() {
        return (DockBlockEntity) blockEntity;
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /** Settings are routed by this id; leaving it at 0 made Create edit the wrong panel. */
    @Override
    public int netId() {
        return 2;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean testHit(Vec3 hit) {
        // Create passes a world-space hit; value boxes work in block space, so drop the block offset.
        Vec3 local = hit.subtract(Vec3.atLowerCornerOf(getPos()));
        return slot.testHit(blockEntity.getLevel(), getPos(), blockEntity.getBlockState(), local);
    }

    @Override
    public ValueBoxTransform getSlotPositioning() {
        return slot;
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hit) {
        return new ValueSettingsBoard(Component.translatable("value.distantstock.dock_mode"), 0, 1, OPTIONS,
                new ValueSettingsFormatter(settings -> (net.minecraft.network.chat.MutableComponent)
                        Component.literal(clamp(settings.row()) == getValueSettings().row() ? "\u2714" : "")));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean sneak) {
        dock().setMode(DockMode.values()[clamp(settings.row())]);
    }

    @Override
    public ValueSettings getValueSettings() {
        // The enum's own order is the panel's order, which is what makes the derived list safe.
        return new ValueSettings(clamp(dock().mode().ordinal()), 0);
    }

    private static int clamp(int row) {
        return Math.max(0, Math.min(OPTIONS.size() - 1, row));
    }
}
