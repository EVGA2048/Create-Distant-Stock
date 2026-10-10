package dev.distantstock.item;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;

/**
 * A tank's fluid capability: the stock component-backed tank, plus the icon.
 *
 * <p>Every way fluid enters or leaves a tank goes through here -- Create's spout and item drain, the
 * basin and the fluid tank, and this mod's own pouring and drinking -- because they all ask for
 * {@code Capabilities.FluidHandler.ITEM} rather than reaching into the item. That makes these two
 * hooks the one place the icon level has to be maintained, instead of a rule each caller has to
 * remember. {@code FluidHandlerItemStack} calls exactly one of them on every mutation: the fluid setter
 * when something is left, the empty setter when the tank runs dry.
 *
 * <p>The strictness about components is inherited on purpose and not overridden. Two batches of the
 * same fluid carrying different data do not mix, which matters here because one of those batches is
 * water and the other is water whose purity Thirst Was Taken is tracking: merging them would average
 * the purity away without telling anyone. The tooltip says so instead.
 */
public final class TankItemHandler extends FluidHandlerItemStack {
    public TankItemHandler(ItemStack container) {
        super(ModDataComponents.CANISTER_FLUID, container,
                ((PortableFluidTankItem) container.getItem()).capacity());
    }

    @Override
    protected void setFluid(FluidStack fluid) {
        super.setFluid(fluid);
        PortableFluidTankItem.writeModelIndex(getContainer(), fluid, getTankCapacity(0));
    }

    @Override
    protected void setContainerToEmpty() {
        super.setContainerToEmpty();
        PortableFluidTankItem.writeModelIndex(getContainer(), FluidStack.EMPTY, getTankCapacity(0));
    }
}
