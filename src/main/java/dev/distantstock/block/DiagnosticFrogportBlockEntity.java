package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

public final class DiagnosticFrogportBlockEntity extends FrogportBlockEntity {
    private final IItemHandler quarantineExtraction = new IItemHandler() {
        @Override
        public int getSlots() {
            return inventory.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // The underside is a quarantine OUTPUT only. Packages enter only from the chain.
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return inventory.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return inventory.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };
    public DiagnosticFrogportBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DIAGNOSTIC_FROGPORT.get(), pos, state);
        acceptsPackages = true;
    }

    @Override
    public String getFilterString() {
        return ChainDiagnostics.diagnosticAddress(worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ChainDiagnostics.register(this);
    }

    @Override
    public void startAnimation(ItemStack stack, boolean depositing) {
        if (!depositing && !PingPackageData.isPing(stack)) {
            ChainDiagnostics.unroutableCaptured(this, stack);
        }
        super.startAnimation(stack, depositing);
    }

    @Override
    public void tryPullingFromOwnAndAdjacentInventories() {
        // A diagnostic frog emits only probes created by ChainDiagnostics. It must never steal a
        // player's ordinary parcels from a chest below it and accidentally put them on the network.
    }

    public boolean sendProbe(ItemStack stack) {
        if (level == null || level.isClientSide || target == null || stack == null || stack.isEmpty()
                || isAnimationInProgress()) return false;
        if (!target.export(level, worldPosition, stack, true)) return false;
        super.startAnimation(stack, true);
        return true;
    }

    public IItemHandler exposedItemHandler() {
        return itemHandler;
    }

    public IItemHandler quarantineExtractionHandler() {
        return quarantineExtraction;
    }
}
