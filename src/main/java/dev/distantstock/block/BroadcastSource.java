package dev.distantstock.block;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Common editable message source used by local and network broadcasters. */
public interface BroadcastSource {
    BlockPos getBlockPos();
    String template();
    String prefix();
    int radius();
    int soundProfile();
    List<String> parameters();
    void setParameter(int index, String text);
    void configure(String template, int radius, int soundProfile, String prefix);
}
