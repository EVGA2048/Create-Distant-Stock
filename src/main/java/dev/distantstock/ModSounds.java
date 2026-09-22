package dev.distantstock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Distant Stock sound events. Keep sound identity stable so the asset can be replaced independently. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, DistantStock.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> STACK_LIGHT_BUZZER =
            SOUNDS.register("stack_light_buzzer", () ->
                    SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "stack_light_buzzer")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WALL_SOUNDER_B2 =
            SOUNDS.register("wall_sounder_b2", () ->
                    SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "wall_sounder_b2")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WALL_SOUNDER_F1 =
            SOUNDS.register("wall_sounder_f1", () ->
                    SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "wall_sounder_f1")));

    private ModSounds() {
    }
}
