package dev.distantstock.block;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Display-Link-fed message source that publishes onto a bound Create logistics network. */
public final class NetworkBroadcasterBlockEntity extends SmartBlockEntity implements BroadcastSource {
    private String template = "{1}";
    private String prefix = "网络广播器";
    private final String[] parameters = new String[AnnouncerBlockEntity.PARAMETER_COUNT];
    private int soundProfile;
    private LogisticallyLinkedBehaviour network;

    public NetworkBroadcasterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NETWORK_BROADCASTER.get(), pos, state);
        Arrays.fill(parameters, "");
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        network = new LogisticallyLinkedBehaviour(this, false);
        behaviours.add(network);
    }

    public UUID networkId() {
        return network == null ? null : network.freqId;
    }

    @Override public String template() { return template; }
    @Override public String prefix() { return prefix; }
    @Override public int radius() { return AnnouncerBlockEntity.DEFAULT_RADIUS; }
    @Override public int soundProfile() { return soundProfile; }
    @Override public List<String> parameters() { return List.of(parameters.clone()); }

    @Override
    public void configure(String nextTemplate, int ignoredRadius, int nextSoundProfile, String nextPrefix) {
        template = clean(nextTemplate, AnnouncerBlockEntity.MAX_TEMPLATE);
        if (template.isBlank()) template = "{1}";
        prefix = clean(nextPrefix, AnnouncerBlockEntity.MAX_PREFIX);
        soundProfile = Math.clamp(nextSoundProfile, 0, 2);
        setChanged();
        notifyUpdate();
    }

    @Override
    public void setParameter(int index, String text) {
        if (index < 0 || index >= parameters.length) return;
        String next = clean(text, AnnouncerBlockEntity.MAX_PARAMETER);
        if (parameters[index].equals(next)) return;
        parameters[index] = next;
        setChanged();
        notifyUpdate();
    }

    public String renderMessage() {
        String out = template;
        for (int i = 0; i < parameters.length; i++) out = out.replace("{" + (i + 1) + "}", parameters[i]);
        out = clean(out, AnnouncerBlockEntity.MAX_TEMPLATE + parameters.length * AnnouncerBlockEntity.MAX_PARAMETER);
        if (out.isBlank() || prefix.isBlank()) return out;
        return "[" + prefix + "] " + out;
    }

    public int broadcastToNetwork() {
        if (level == null || level.isClientSide) return 0;
        return NetworkBroadcastBus.send(networkId(), renderMessage(), soundProfile);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("Template", template);
        tag.putString("Prefix", prefix);
        tag.putInt("SoundProfile", soundProfile);
        for (int i = 0; i < parameters.length; i++) tag.putString("Param" + i, parameters[i]);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        template = clean(tag.contains("Template") ? tag.getString("Template") : "{1}", AnnouncerBlockEntity.MAX_TEMPLATE);
        if (template.isBlank()) template = "{1}";
        prefix = clean(tag.contains("Prefix") ? tag.getString("Prefix") : "网络广播器", AnnouncerBlockEntity.MAX_PREFIX);
        soundProfile = Math.clamp(tag.contains("SoundProfile") ? tag.getInt("SoundProfile") : 0, 0, 2);
        for (int i = 0; i < parameters.length; i++) parameters[i] = clean(tag.getString("Param" + i), AnnouncerBlockEntity.MAX_PARAMETER);
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ').strip();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
