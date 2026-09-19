package dev.distantstock.routing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent Distant Stock networks and the Create logistics networks that joined them.
 *
 * <p>Create's own logistics network remains local to one world. This directory is the layer above
 * it: one Distant Stock network may contain Create networks from several Transerver nodes.
 */
public final class DistantNetworkDirectory extends SavedData {
    /** Old saves have no Distant Stock network field. They continue to live in this hidden scope. */
    public static final UUID LEGACY_NETWORK_ID =
            UUID.fromString("d157a17c-570c-4d3c-9a0c-000000000002");
    public static final String LEGACY_NETWORK_NAME = "Legacy";
    public static final int MAX_NAME_LENGTH = 48;

    private static final String DATA_NAME = "distantstock_networks";
    private static final Factory<DistantNetworkDirectory> FACTORY =
            new Factory<>(DistantNetworkDirectory::new, DistantNetworkDirectory::load);
    private static final SecureRandom RANDOM = new SecureRandom();
    // Easy to read from chat / screenshots. I, L and O are omitted; 1 remains valid.
    private static final char[] CODE_ALPHABET =
            "123456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    public record Network(UUID id, String name, UUID ownerNode, UUID ownerPlayer,
                          String joinCode, boolean legacy) {
        public Network {
            if (id == null) throw new IllegalArgumentException("network id is null");
            name = normalizeName(name);
            // Only the authority that created a real network owns its join code. The hidden
            // legacy scope and replicas learned from another server deliberately carry no code.
            joinCode = joinCode == null || joinCode.isBlank() ? "" : normalizeCode(joinCode);
        }

        public boolean authoritativeOn(UUID node) {
            return !legacy && ownerNode != null && ownerNode.equals(node);
        }

        public boolean ownedBy(UUID player) {
            return !legacy && ownerPlayer != null && ownerPlayer.equals(player);
        }

        /** A replica never receives the secret join code. */
        public Network replica() {
            return legacy ? this : new Network(id, name, ownerNode, null, "", false);
        }
    }

    private final Map<UUID, Network> networks = new LinkedHashMap<>();
    /** One Create logistics network belongs to one Distant Stock network. */
    private final Map<RemoteNetworkId, UUID> memberships = new LinkedHashMap<>();

    public DistantNetworkDirectory() {
        networks.put(LEGACY_NETWORK_ID,
                new Network(LEGACY_NETWORK_ID, LEGACY_NETWORK_NAME, null, null, "", true));
    }

    public static DistantNetworkDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Network create(String name, UUID ownerNode, UUID ownerPlayer, RemoteNetworkId firstMember) {
        if (ownerNode == null) {
            throw new IllegalArgumentException("owner node is null");
        }
        if (ownerPlayer == null) {
            throw new IllegalArgumentException("owner player is null");
        }
        if (firstMember == null) {
            throw new IllegalArgumentException("first member is null");
        }
        if (memberships.containsKey(firstMember)) {
            throw new IllegalArgumentException("Create network already belongs to a Distant Stock network");
        }
        String normalized = normalizeName(name);
        boolean duplicate = networks.values().stream()
                .filter(network -> !network.legacy())
                .anyMatch(network -> network.name().equalsIgnoreCase(normalized));
        if (duplicate) {
            throw new IllegalArgumentException("Distant Stock network already exists: " + normalized);
        }
        Network network = new Network(UUID.randomUUID(), normalized, ownerNode, ownerPlayer,
                newJoinCode(), false);
        networks.put(network.id(), network);
        memberships.put(firstMember, network.id());
        setDirty();
        return network;
    }

    public Optional<Network> find(UUID id) {
        return Optional.ofNullable(networks.get(id));
    }

    public Optional<Network> findByCode(String code) {
        String wanted;
        try {
            wanted = normalizeCode(code);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return networks.values().stream()
                .filter(network -> !network.joinCode().isEmpty())
                .filter(network -> network.joinCode().equalsIgnoreCase(wanted))
                .findFirst();
    }

    public List<Network> all() {
        List<Network> out = new ArrayList<>(networks.values());
        out.sort(Comparator.comparing(Network::legacy).thenComparing(Network::name));
        return List.copyOf(out);
    }

    /** The explicit membership, if this Create network has joined one. */
    public Optional<UUID> networkOf(RemoteNetworkId member) {
        return member == null ? Optional.empty() : Optional.ofNullable(memberships.get(member));
    }

    /**
     * The routing scope of a Create network.
     *
     * <p>Unmigrated networks behave exactly like old saves: all of them share the hidden legacy
     * scope until the player deliberately joins a real Distant Stock network.
     */
    public UUID scopeOf(RemoteNetworkId member) {
        return networkOf(member).orElse(LEGACY_NETWORK_ID);
    }

    public boolean attach(RemoteNetworkId member, UUID distantNetwork) {
        if (member == null || distantNetwork == null || !networks.containsKey(distantNetwork)) {
            return false;
        }
        UUID previous = memberships.get(member);
        if (previous != null && !previous.equals(distantNetwork)) {
            // Moving between two formal Distant Stock networks must be an explicit leave + join.
            // Silent reassignment would let a stale/replayed join packet steal a warehouse from
            // the network the player currently manages.
            return false;
        }
        memberships.put(member, distantNetwork);
        if (!distantNetwork.equals(previous)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean detach(RemoteNetworkId member) {
        if (member == null || memberships.remove(member) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * The authority owner must keep at least one Create network on the authority node.
     *
     * <p>Otherwise the Distant Stock network still exists (and its join code still exists) but the
     * creator has no local member through which the terminal can open its management page. Deleting
     * a network or transferring ownership are separate operations; "leave" must not accidentally
     * impersonate either of them.
     */
    public boolean wouldOrphanAuthority(RemoteNetworkId member, UUID actor, UUID localNode) {
        if (member == null || actor == null || localNode == null) {
            return false;
        }
        UUID networkId = memberships.get(member);
        Network network = networkId == null ? null : networks.get(networkId);
        if (network == null || !network.ownedBy(actor) || !network.authoritativeOn(localNode)) {
            return false;
        }
        long localMembers = memberships.entrySet().stream()
                .filter(entry -> networkId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(candidate -> localNode.equals(candidate.nodeId()))
                .count();
        return localMembers <= 1;
    }

    /** Stores the public half of a network learned from its authority node. */
    public Network rememberReplica(UUID id, String name, UUID ownerNode) {
        if (id == null || id.equals(LEGACY_NETWORK_ID) || ownerNode == null) {
            throw new IllegalArgumentException("invalid network replica");
        }
        Network current = networks.get(id);
        if (current != null && !current.joinCode().isEmpty()) {
            // Never replace our authoritative row with a replica that deliberately lacks its code.
            return current;
        }
        Network replica = new Network(id, name, ownerNode, null, "", false);
        networks.put(id, replica);
        setDirty();
        return replica;
    }

    public String resetJoinCode(UUID id, UUID ownerPlayer, UUID localNode) {
        Network current = networks.get(id);
        if (current == null || !current.ownedBy(ownerPlayer) || !current.authoritativeOn(localNode)) {
            throw new IllegalArgumentException("not the network owner");
        }
        String nextCode = newJoinCode();
        Network next = new Network(current.id(), current.name(), current.ownerNode(),
                current.ownerPlayer(), nextCode, false);
        networks.put(id, next);
        setDirty();
        return nextCode;
    }

    public Optional<Network> networkForOwnedMember(RemoteNetworkId member, UUID ownerPlayer) {
        UUID id = memberships.get(member);
        Network network = id == null ? null : networks.get(id);
        return network != null && network.ownedBy(ownerPlayer) ? Optional.of(network) : Optional.empty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag networkRows = new ListTag();
        for (Network network : networks.values()) {
            if (network.legacy()) continue;
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", network.id());
            row.putString("Name", network.name());
            if (network.ownerNode() != null) row.putUUID("OwnerNode", network.ownerNode());
            if (network.ownerPlayer() != null) row.putUUID("OwnerPlayer", network.ownerPlayer());
            if (!network.joinCode().isEmpty()) row.putString("JoinCode", network.joinCode());
            networkRows.add(row);
        }
        tag.put("Networks", networkRows);

        ListTag memberRows = new ListTag();
        memberships.forEach((member, distantNetwork) -> {
            CompoundTag row = new CompoundTag();
            row.put("Member", member.save());
            row.putUUID("Network", distantNetwork);
            memberRows.add(row);
        });
        tag.put("Memberships", memberRows);
        return tag;
    }

    public static DistantNetworkDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        DistantNetworkDirectory directory = new DistantNetworkDirectory();
        ListTag networks = tag.getList("Networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < networks.size(); i++) {
            CompoundTag row = networks.getCompound(i);
            if (!row.hasUUID("Id") || !row.hasUUID("OwnerNode")) continue;
            try {
                Network network = new Network(row.getUUID("Id"), row.getString("Name"),
                        row.getUUID("OwnerNode"),
                        row.hasUUID("OwnerPlayer") ? row.getUUID("OwnerPlayer") : null,
                        row.getString("JoinCode"), false);
                directory.networks.put(network.id(), network);
            } catch (IllegalArgumentException ignored) {
            }
        }

        ListTag memberships = tag.getList("Memberships", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberships.size(); i++) {
            CompoundTag row = memberships.getCompound(i);
            if (!row.hasUUID("Network") || !row.contains("Member", Tag.TAG_COMPOUND)) continue;
            RemoteNetworkId.read(row.getCompound("Member")).ifPresent(member -> {
                UUID network = row.getUUID("Network");
                if (directory.networks.containsKey(network)) {
                    directory.memberships.put(member, network);
                }
            });
        }
        return directory;
    }

    public static String normalizeCode(String value) {
        String raw = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (raw.length() != 8) {
            throw new IllegalArgumentException("join code must contain 8 characters");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("join code contains invalid characters");
            }
        }
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    private String newJoinCode() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            StringBuilder raw = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                raw.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
            String code = normalizeCode(raw.toString());
            if (networks.values().stream().noneMatch(network -> code.equals(network.joinCode()))) {
                return code;
            }
        }
        throw new IllegalStateException("Could not allocate Distant Stock join code");
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Distant Stock network name must not be blank");
        }
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Distant Stock network name is too long");
        }
        return normalized;
    }
}
