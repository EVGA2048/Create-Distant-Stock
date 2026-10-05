package dev.distantstock.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import dev.distantstock.ModSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;

/** Redstone-triggered local PA terminal. Display Links feed its four template parameters. */
public final class AnnouncerBlockEntity extends SmartBlockEntity implements BroadcastSource {
    public static final int PARAMETER_COUNT = 4;
    public static final int DEFAULT_RADIUS = 48;
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 128;
    public static final int MAX_TEMPLATE = 256;
    public static final int MAX_PARAMETER = 96;
    public static final int MAX_PREFIX = 32;
    public static final String DEFAULT_PREFIX = "黄铜广播器";

    private String template = "{1}";
    private String prefix = DEFAULT_PREFIX;
    private final String[] parameters = new String[PARAMETER_COUNT];
    private int radius = DEFAULT_RADIUS;
    private int soundProfile = 0;

    public AnnouncerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANNOUNCER.get(), pos, state);
        Arrays.fill(parameters, "");
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    public String template() {
        return template;
    }

    public String prefix() {
        return prefix;
    }

    public int radius() {
        return radius;
    }

    public int soundProfile() {
        return soundProfile;
    }

    public List<String> parameters() {
        return List.of(parameters.clone());
    }

    public void configure(String nextTemplate, int nextRadius) {
        configure(nextTemplate, nextRadius, soundProfile, prefix);
    }

    public void configure(String nextTemplate, int nextRadius, int nextSoundProfile) {
        configure(nextTemplate, nextRadius, nextSoundProfile, prefix);
    }

    public void configure(String nextTemplate, int nextRadius, int nextSoundProfile, String nextPrefix) {
        template = clean(nextTemplate, MAX_TEMPLATE);
        if (template.isBlank()) template = "{1}";
        prefix = cleanPrefix(nextPrefix);
        radius = Math.clamp(nextRadius, MIN_RADIUS, MAX_RADIUS);
        soundProfile = Math.clamp(nextSoundProfile, 0, 2);
        setChanged();
        notifyUpdate();
    }

    public void setParameter(int index, String text) {
        if (index < 0 || index >= PARAMETER_COUNT) return;
        String next = clean(text, MAX_PARAMETER);
        if (parameters[index].equals(next)) return;
        parameters[index] = next;
        setChanged();
        notifyUpdate();
    }

    public String parameter(int index) {
        return index < 0 || index >= PARAMETER_COUNT ? "" : parameters[index];
    }

    /** Plain-text substitution only: Display Link output can never become a command or click event. */
    public String renderMessage() {
        String out = template;
        for (int i = 0; i < PARAMETER_COUNT; i++) {
            out = out.replace("{" + (i + 1) + "}", parameters[i]);
        }
        return clean(out, MAX_TEMPLATE + PARAMETER_COUNT * MAX_PARAMETER);
    }

    public String renderBroadcastMessage() {
        String message = renderMessage();
        if (message.isBlank() || prefix.isBlank()) return message;
        return "[" + prefix + "] " + message;
    }

    /** Called only on a redstone rising edge. Returns the number of players that received it. */
    public int broadcast() {
        if (!(level instanceof ServerLevel serverLevel)) return 0;
        String text = renderBroadcastMessage();
        if (text.isBlank()) return 0;

        double cx = worldPosition.getX() + .5;
        double cy = worldPosition.getY() + .5;
        double cz = worldPosition.getZ() + .5;
        double max = (double) radius * radius;
        int sent = 0;
        for (var player : serverLevel.players()) {
            if (player.distanceToSqr(cx, cy, cz) > max) continue;
            player.sendSystemMessage(Component.literal(text));
            sent++;
        }
        if (sent > 0) {
            var cue = switch (soundProfile) {
                case 1 -> ModSounds.ANNOUNCER_IPPHONE.get();
                case 2 -> ModSounds.ANNOUNCER_RELAY.get();
                default -> ModSounds.ANNOUNCER_HMI.get();
            };
            serverLevel.playSound(null, worldPosition, cue, SoundSource.BLOCKS, 1.15f, 1.0f);
        }
        return sent;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("Template", template);
        tag.putString("Prefix", prefix);
        tag.putInt("Radius", radius);
        tag.putInt("SoundProfile", soundProfile);
        for (int i = 0; i < PARAMETER_COUNT; i++) tag.putString("Param" + i, parameters[i]);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        template = clean(tag.contains("Template") ? tag.getString("Template") : "{1}", MAX_TEMPLATE);
        if (template.isBlank()) template = "{1}";
        prefix = tag.contains("Prefix") ? cleanPrefix(tag.getString("Prefix")) : DEFAULT_PREFIX;
        radius = Math.clamp(tag.contains("Radius") ? tag.getInt("Radius") : DEFAULT_RADIUS,
                MIN_RADIUS, MAX_RADIUS);
        soundProfile = Math.clamp(tag.contains("SoundProfile") ? tag.getInt("SoundProfile") : 0, 0, 2);
        for (int i = 0; i < PARAMETER_COUNT; i++) {
            parameters[i] = clean(tag.getString("Param" + i), MAX_PARAMETER);
        }
    }

    private static String cleanPrefix(String text) {
        return clean(text, MAX_PREFIX);
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ').strip();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
