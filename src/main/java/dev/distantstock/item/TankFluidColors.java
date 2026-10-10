package dev.distantstock.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What colour the fluid in a tank should be drawn as.
 *
 * <p>One function, because two callers need the same answer and drifting apart is the failure mode
 * here: the item icon tints its fluid layer with this, and the durability bar -- which is the tank's
 * contents gauge -- colours itself with the same value. A tank whose window and whose bar disagreed
 * would read as two different fluids.
 *
 * <p>The fallback table is not optional. NeoForge gives vanilla water, lava and milk no client
 * extension at all ({@code FluidType#initializeClient} is empty for them), so
 * {@code getTintColor} answers the default white and a tank of water would paint itself blank. Every
 * fluid that arrives with a real extension still wins: a mod that bothered to say what its fluid
 * looks like is answered first.
 */
public final class TankFluidColors {
    /** {@code getTintColor}'s "no opinion" answer, and what a missing extension returns. */
    private static final int NO_TINT = 0xFFFFFFFF;

    /** Returned when there is nothing to tint: an empty tank draws its glass, not a colour. */
    public static final int NONE = -1;

    /**
     * ARGB to multiply the fluid layer by, or {@link #NONE} for an empty tank.
     *
     * <p>Safe to call from either side. On a dedicated server the client extension map is simply
     * empty, which lands every fluid in the fallback table -- the same colours, so the bar a server
     * sends and the icon a client draws still agree.
     */
    public static int of(FluidStack fluid) {
        if (fluid.isEmpty()) return NONE;
        int tint = IClientFluidTypeExtensions.of(fluid.getFluidType()).getTintColor(fluid);
        if (tint != NO_TINT) return tint;
        if (fluid.is(Tags.Fluids.WATER)) return 0xFF3F76E4;
        if (fluid.is(Tags.Fluids.LAVA)) return 0xFFD45B12;
        if (fluid.is(Tags.Fluids.MILK)) return 0xFFF6F2EC;
        if (fluid.is(Tags.Fluids.HONEY)) return 0xFFD98B1E;
        if (fluid.is(Tags.Fluids.POTION)) {
            return 0xFF000000 | fluid.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                    .getColor();
        }
        // Steel grey: deliberately dull, so an unrecognised fluid reads as "something is in here"
        // rather than pretending to be water.
        return 0xFF9FB6C4;
    }

    private TankFluidColors() {
    }
}
