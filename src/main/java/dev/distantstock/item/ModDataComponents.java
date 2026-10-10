package dev.distantstock.item;

import dev.distantstock.DistantStock;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item data owned by Distant Stock. */
public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, DistantStock.MODID);

    /** The real fluid carried by a resonant medium canister. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> CANISTER_FLUID =
            COMPONENTS.register("canister_fluid", () -> DataComponentType.<SimpleFluidContent>builder()
                    .persistent(SimpleFluidContent.CODEC)
                    .networkSynchronized(SimpleFluidContent.STREAM_CODEC)
                    .build());

    private ModDataComponents() {
    }
}
