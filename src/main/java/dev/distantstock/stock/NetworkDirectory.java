package dev.distantstock.stock;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 主线程发布本地网络，IO 线程替换远端快照，GUI/HTTP 只读。 */
public final class NetworkDirectory {
    public record Entry(UUID freq, String server, int links) {
    }

    private static volatile List<Entry> local = List.of();
    private static volatile List<Entry> peer = List.of();

    public static void replaceLocal(Collection<Entry> entries) {
        local = List.copyOf(entries);
    }

    public static void replacePeer(Collection<Entry> entries) {
        peer = List.copyOf(entries);
    }

    public static List<Entry> local() {
        return local;
    }

    public static List<Entry> peer() {
        return peer;
    }

    public static List<Entry> visible(boolean host) {
        return host ? local : peer;
    }

    public static boolean contains(UUID freq, boolean host) {
        if (freq == null) {
            return false;
        }
        for (Entry entry : visible(host)) {
            if (freq.equals(entry.freq())) {
                return true;
            }
        }
        return false;
    }

    private NetworkDirectory() {
    }
}
