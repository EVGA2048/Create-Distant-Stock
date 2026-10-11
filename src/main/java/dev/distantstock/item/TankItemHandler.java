package dev.distantstock.item;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack;

/**
 * A tank's fluid capability: the stock component-backed tank, and nothing else.
 *
 * <p>Every way fluid enters or leaves a tank goes through here -- Create's spout and item drain, the
 * basin and the fluid tank, and this mod's own pouring and drinking -- because they all ask for
 * {@code Capabilities.FluidHandler.ITEM} rather than reaching into the item. That used to be worth
 * subclassing for: the icon's level was a component that had to be rewritten on every mutation, and
 * these two hooks were the one place that could be done once for every caller. The icon is now drawn
 * from the fluid on the stack, so there is nothing left to keep in step; the class stays because the
 * capacity is per tier and the component key is this mod's own.
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
}
