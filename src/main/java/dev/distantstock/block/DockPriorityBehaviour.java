package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Priority of one dock inside its receiving group. The highest priority tier that has room takes turns;
 * docks of equal priority share the load in a stable order.
 */
final class DockPriorityBehaviour extends BlockEntityBehaviour implements ValueSettingsBehaviour {
    static final BehaviourType<DockPriorityBehaviour> TYPE = new BehaviourType<>("distant_dock_priority");

    private final ValueBoxTransform slot = new DockValueBox(false);

    DockPriorityBehaviour(DockBlockEntity dock) {
        super(dock);
    }

    private DockBlockEntity dock() {
        return (DockBlockEntity) blockEntity;
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /** Distinct from the mode panel: Create routes value settings by this id. */
    @Override
    public int netId() {
        return 3;
    }

    @Override
    public boolean isActive() {
        return dock().canReceive();
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
        return new ValueSettingsBoard(Component.translatable("value.distantstock.priority"),
                DockBlockEntity.MAX_PRIORITY, 1, List.of(Component.translatable("value.distantstock.priority")),
                new ValueSettingsFormatter(settings -> Component.literal("P" + settings.value())));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean sneak) {
        dock().setPriority(settings.value());
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, dock().priority());
    }
}
