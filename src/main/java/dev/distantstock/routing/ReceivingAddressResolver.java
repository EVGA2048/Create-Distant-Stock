package dev.distantstock.routing;

import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One answer for every player-facing receiving-address lookup.
 *
 * <p>A receiving address is now network-wide. A local group and a remote group with the same name
 * are not "two choices"; they are a conflict, and no caller may silently prefer one side.
 *
 * <p>The old {@code server·name} spelling is still accepted as input for save compatibility, but
 * it cannot bypass a conflict: once the entry is found, its bare name is checked across local and
 * remote directories before a result is returned.
 */
public final class ReceivingAddressResolver {
    public enum Kind {
        LOCAL,
        REMOTE,
        CONFLICT,
        UNKNOWN
    }

    public record Match(Kind kind, DockGroup local, RemoteGroups.Entry remote, String name) {
        public UUID groupId() {
            return local != null ? local.id() : remote != null ? remote.group() : null;
        }
    }

    public static Match resolve(MinecraftServer server, String input) {
        return resolve(server, DistantNetworkDirectory.LEGACY_NETWORK_ID, input);
    }

    public static Match resolve(MinecraftServer server, UUID distantNetworkId, String input) {
        if (server == null || input == null || input.isBlank()) {
            return new Match(Kind.UNKNOWN, null, null, "");
        }

        UUID scope = distantNetworkId == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : distantNetworkId;
        DockGroupDirectory locals = DockGroupDirectory.get(server);
        RemoteGroups remotes = RemoteGroups.get(server);
        List<DockGroup> directlyLocal = locals.named(scope, input);
        List<RemoteGroups.Entry> directlyRemote = remotes.matchingInput(scope, input);
        UUID effectiveScope = scope;

        Set<String> canonicalNames = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        directlyLocal.forEach(group -> canonicalNames.add(group.name()));
        directlyRemote.forEach(entry -> canonicalNames.add(entry.name()));

        if (canonicalNames.isEmpty()) {
            return new Match(Kind.UNKNOWN, null, null, input.trim());
        }
        if (canonicalNames.size() != 1) {
            return new Match(Kind.CONFLICT, null, null, input.trim());
        }

        String canonical = canonicalNames.iterator().next();
        List<DockGroup> local = locals.named(effectiveScope, canonical);
        List<RemoteGroups.Entry> remote = remotes.named(effectiveScope, canonical);
        Set<UUID> identities = new LinkedHashSet<>();
        local.forEach(group -> identities.add(group.id()));
        remote.forEach(entry -> identities.add(entry.group()));
        if (identities.size() > 1) {
            return new Match(Kind.CONFLICT, null, null, canonical);
        }
        if (local.size() == 1) {
            return new Match(Kind.LOCAL, local.getFirst(), null, local.getFirst().name());
        }
        if (remote.size() == 1) {
            return new Match(Kind.REMOTE, null, remote.getFirst(), remote.getFirst().name());
        }
        return new Match(Kind.UNKNOWN, null, null, canonical);
    }

    /** Whether a stored group id currently sits behind an ambiguous network-wide name. */
    public static boolean conflicted(MinecraftServer server, UUID groupId) {
        if (server == null || groupId == null) {
            return false;
        }
        DockGroupDirectory locals = DockGroupDirectory.get(server);
        RemoteGroups remotes = RemoteGroups.get(server);
        DockGroup localGroup = locals.find(groupId).orElse(null);
        RemoteGroups.Entry remoteGroup = localGroup == null ? remotes.find(groupId).orElse(null) : null;
        if (localGroup == null && remoteGroup == null) {
            return false;
        }
        String name = localGroup != null ? localGroup.name() : remoteGroup.name();
        UUID scope = localGroup != null ? localGroup.distantNetworkId() : remoteGroup.distantNetworkId();
        Set<UUID> identities = new LinkedHashSet<>();
        locals.named(scope, name).stream().map(DockGroup::id).forEach(identities::add);
        remotes.named(scope, name).stream().map(RemoteGroups.Entry::group).forEach(identities::add);
        return identities.size() > 1;
    }

    /**
     * Whether one local group may claim this name.
     *
     * <p>Used for renaming: UNKNOWN is free, and the group's own current name is still its own.
     * Any remote row, other local group, or conflict reserves the name.
     */
    public static boolean mayClaimLocalName(MinecraftServer server, UUID self, String proposed) {
        DockGroup current = server == null || self == null
                ? null : DockGroupDirectory.get(server).find(self).orElse(null);
        UUID scope = current == null
                ? DistantNetworkDirectory.LEGACY_NETWORK_ID : current.distantNetworkId();
        Match match = resolve(server, scope, proposed);
        return match.kind() == Kind.UNKNOWN
                || (match.kind() == Kind.LOCAL && match.local() != null
                && match.local().id().equals(self));
    }

    private ReceivingAddressResolver() {
    }
}
