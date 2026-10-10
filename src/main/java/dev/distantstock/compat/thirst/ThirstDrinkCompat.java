package dev.distantstock.compat.thirst;

import dev.ghen.thirst.content.purity.WaterPurity;
import dev.ghen.thirst.content.thirst.PlayerThirst;
import dev.ghen.thirst.foundation.common.capability.ModAttachment;
import dev.distantstock.item.DrinkableFluids;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Lets a tank of water count as water to Thirst Was Taken.
 *
 * <p>This is the only file in the mod that names a Thirst Was Taken type. It is registered from
 * {@code DistantStock}'s constructor inside a {@code ModList.isLoaded} branch, so a pack without the
 * mod never resolves this class at all -- the same arrangement the FluidLogistics compat uses, and
 * the reason neither of them is an {@code @EventBusSubscriber} (one of those would be loaded and
 * fail on a pack that does not have the mod).
 *
 * <p>What is installed is a {@link DrinkableFluids.WaterBridge}, one seam rather than five hooks.
 * With no bridge installed the tanks still drink, still empty into machines and still never source
 * the ground; what they lose is the part only Thirst can answer -- whether a fluid is water-family,
 * how clean it was, and what that does to the drinker.
 *
 * <p>Two things this deliberately does <em>not</em> do. It does not register the tanks as drinks
 * through {@code RegisterThirstValueEvent}: that registry is keyed by item, so all three tiers would
 * claim one fixed hydration regardless of what is inside them, and it would additionally arm
 * Thirst's own item-driven drink handler, which reads per-item maps the tanks are not in. And it
 * never writes a purity onto a stack: {@code WaterPurity.getPurity} writes the config default when
 * the component is absent, so a tank of plain water would be stamped "acceptable" the first time
 * anybody looked at it and would then refuse to mix with any other water. Everything here reads.
 */
public final class ThirstDrinkCompat {
    /**
     * One 250 mB sip is one water bottle, which is what Thirst's own hydration numbers are scaled to.
     * The purity steps are a balance choice rather than an API constraint: dirtier water is worth
     * less, and only purified water also quenches.
     */
    private static final int[] HYDRATION = {1, 2, 3, 4};
    private static final int[] QUENCH = {0, 0, 0, 1};

    public static void register() {
        DrinkableFluids.installWaterBridge(new DrinkableFluids.WaterBridge() {
            @Override
            public boolean hasPurity(FluidStack fluid) {
                return WaterPurity.hasPurity(fluid);
            }

            @Override
            public int purity(FluidStack fluid) {
                return purityOf(fluid);
            }

            @Override
            public void applyPurityEffects(Player player, int purity) {
                WaterPurity.givePurityEffects(player, purity);
            }

            @Override
            public void hydrate(Player player, int purity) {
                if (purity < 0 || purity >= HYDRATION.length) return;
                PlayerThirst thirst = player.getData(ModAttachment.PLAYER_THIRST.get());
                thirst.drink(HYDRATION[purity], QUENCH[purity]);
            }

            @Override
            public Component describePurity(FluidStack fluid) {
                int purity = purityOf(fluid);
                if (purity < 0) return null;
                return Component.translatable("item.distantstock.fluid_tank.purity",
                                WaterPurity.getPurityText(purity))
                        .withStyle(style -> style.withColor(WaterPurity.getPurityColor(purity)));
            }
        });
    }

    /**
     * Purity 0..3, or -1 when the fluid carries none.
     *
     * <p>Asked before {@code getPurity} every time, because that method answers a missing component
     * by writing the config's default onto the stack. On a tank's stored fluid that would be a
     * permanent mark -- the purity would survive the tank being emptied and refilled, and the strict
     * component comparison in the fluid capability would then refuse to top it up from a plain
     * water source. A copy is used besides, so even a method that wrote could not reach the tank.
     */
    private static int purityOf(FluidStack fluid) {
        FluidStack probe = fluid.copy();
        return WaterPurity.hasPurity(probe) ? WaterPurity.getPurity(probe) : -1;
    }

    private ThirstDrinkCompat() {
    }
}
