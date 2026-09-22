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

/** Create-native hold-right-click release-delay control for the cache Frogport. */
final class CacheFrogportReleaseBehaviour extends BlockEntityBehaviour implements ValueSettingsBehaviour {
    private static final int[] RELEASE_DELAYS = {0, 5, 10, 15, 30};
    static final BehaviourType<CacheFrogportReleaseBehaviour> TYPE =
            new BehaviourType<>("cache_frogport_release");

    private final ValueBoxTransform slot = new FrogTransform();

    CacheFrogportReleaseBehaviour(CacheFrogportBlockEntity cache) {
        super(cache);
    }

    private CacheFrogportBlockEntity cache() {
        return (CacheFrogportBlockEntity) blockEntity;
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    @Override
    public int netId() {
        return 7;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean mayInteract(Player player) {
        return player != null && player.isShiftKeyDown();
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
                Component.translatable("value.distantstock.cache_frogport.release"),
                RELEASE_DELAYS.length - 1, 1,
                List.of(Component.translatable("value.distantstock.cache_frogport.release")),
                new ValueSettingsFormatter(settings -> RELEASE_DELAYS[Math.max(0,
                        Math.min(RELEASE_DELAYS.length - 1, settings.value()))] == 0
                        ? Component.translatable("value.distantstock.cache_frogport.redstone")
                        : Component.translatable("value.distantstock.cache_frogport.seconds",
                                RELEASE_DELAYS[Math.max(0, Math.min(RELEASE_DELAYS.length - 1, settings.value()))])));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean sneak) {
        int index = Math.max(0, Math.min(RELEASE_DELAYS.length - 1, settings.value()));
        cache().setReleaseDelaySeconds(RELEASE_DELAYS[index]);
    }

    @Override
    public ValueSettings getValueSettings() {
        int current = cache().releaseDelaySeconds();
        int index = 0;
        for (int i = 0; i < RELEASE_DELAYS.length; i++) {
            if (RELEASE_DELAYS[i] == current) {
                index = i;
                break;
            }
        }
        return new ValueSettings(0, index);
    }

    private static final class FrogTransform extends ValueBoxTransform.Sided {
        @Override
        public float getScale() {
            return 2f;
        }

        @Override
        protected Vec3 getSouthLocation() {
            return new Vec3(.5, .5, .5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction side) {
            return side != null;
        }
    }
}
