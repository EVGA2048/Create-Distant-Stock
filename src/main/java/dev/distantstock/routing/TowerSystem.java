package dev.distantstock.routing;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which towers are one system, and which devices that system carries.
 *
 * <p>Pure arithmetic on purpose. Everything here runs off a list of towers handed in from outside,
 * which is what lets the rules be checked without a world — and the world is not a good place to
 * check them: two towers of a test arena sit ten blocks apart while a tower's reach is thirty-two,
 * so any two towers in one test level merge into a single system and every other test's devices
 * would be competing for the same budget.
 *
 * <p>A tower is identified by dimension and base position, never by a generated id. The position is
 * already unique, rebuilding there is the same tower, and a UUID would add a second, weaker kind of
 * identity that has to be kept in step with the first.
 */
public final class TowerSystem {

    /** Where a tower stands. Two of these are equal exactly when they are the same tower. */
    public record TowerId(String dimension, long packedPos) implements Comparable<TowerId> {
        public static TowerId of(ResourceKey<Level> dimension, BlockPos pos) {
            return new TowerId(dimension.location().toString(), pos.asLong());
        }

        @Override
        public int compareTo(TowerId other) {
            int byDimension = dimension.compareTo(other.dimension);
            return byDimension != 0 ? byDimension : Long.compare(packedPos, other.packedPos);
        }
    }

    /**
     * One tower as the merge sees it: where it stands, how far it reaches, how much it carries.
     *
     * <p>{@code running} is part of the identity of a working tower rather than a detail of it, and
     * it is the only field that can change without the tower being rebuilt. A stalled tower is
     * dropped from the system entirely — see {@link TowerActivation} for why a dimension whose
     * towers are all stalled goes back to behaving exactly as it did before there were towers.
     *
     * <p>{@code carrying} is the operator's switch, per tower. A tower with it off still stands,
     * still merges and still holds the system together — its reach is part of how the system is
     * shaped — but nothing is ever carried by it and its device budget is not counted. Switching
     * one tower off therefore cannot stall a system: the devices it would have carried are offered
     * to its neighbours, and if none reaches them they are simply off, which is the same answer a
     * tower that was never built gives.
     */
    public record Member(TowerId id, BlockPos base, int radius, int devices, boolean running,
                         boolean carrying) {
    }

    /**
     * A set of towers whose coverage overlaps, merged into one.
     *
     * <p>Identity is the sorted member list, so the same set of towers always produces an equal
     * system. That is not a formality: the readout and the activation snapshot compare systems
     * between ticks, and a value that came out of a hash set in a different order every time would
     * make an unchanged world look like it changed.
     */
    public record System(List<Member> members) {
        public System {
            members = List.copyOf(members);
        }

        /** The largest reach in the system, for the readout. Coverage itself is per member. */
        public int radius() {
            int radius = 0;
            for (Member member : members) {
                radius = Math.max(radius, member.radius());
            }
            return radius;
        }

        /**
         * The budget the whole system carries: every tower that is switched on adds its own count.
         *
         * <p>A tower the operator switched off contributes nothing, which is the whole of what that
         * switch does to the system: the neighbours keep their own budgets and the system's total
         * shrinks by exactly the one tower's share.
         */
        public int devices() {
            int devices = 0;
            for (Member member : members) {
                if (member.carrying()) {
                    devices += member.devices();
                }
            }
            return devices;
        }

        /** Whether any member reaches this position, carrying or not. */
        public boolean covers(BlockPos pos) {
            for (Member member : members) {
                if (withinReach(member, pos)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The member that reaches this position and stands closest to it.
         *
         * <p>Only members that are switched on: a tower with carrying off is absent from the choice
         * rather than a loser in it, so a device it would have taken is offered to whichever
         * neighbour also reaches it — and is refused, right here, when none does.
         */
        public Member nearest(BlockPos pos) {
            Member best = null;
            long bestDistance = Long.MAX_VALUE;
            for (Member member : members) {
                if (!member.carrying()) {
                    continue;
                }
                long distance = distanceSq(member.base(), pos);
                if (distance <= (long) member.radius() * member.radius() && distance < bestDistance) {
                    best = member;
                    bestDistance = distance;
                }
            }
            return best;
        }

        public List<TowerId> ids() {
            List<TowerId> ids = new ArrayList<>(members.size());
            for (Member member : members) {
                ids.add(member.id());
            }
            return List.copyOf(ids);
        }
    }

    /** A device the merge may carry: where it stands, in the same coordinates as the towers. */
    public record Device(BlockPos pos, String dimension) {
    }

    /** A device together with the tower the merge decided carries it. */
    public record Carried(Device device, TowerId carrier, long distanceSq) {
    }

    /**
     * Merges towers whose coverage touches into systems.
     *
     * <p>Two towers are one system when the distance between their bases is at most the sum of
     * their radii — the circles overlap, so a device could stand in both and there is no reason to
     * make it belong to one of them. Towers in different dimensions never merge.
     *
     * <p>Quadratic in the number of towers, which is single digits by design; a spatial index would
     * be more code than it could ever save.
     */
    public static List<System> merge(List<Member> towers) {
        List<Member> live = new ArrayList<>();
        for (Member member : towers) {
            if (member.running()) {
                live.add(member);
            }
        }
        live.sort(Comparator.comparing(Member::id));

        int[] parent = new int[live.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < live.size(); i++) {
            for (int j = i + 1; j < live.size(); j++) {
                if (overlap(live.get(i), live.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<Member>> groups = new HashMap<>();
        for (int i = 0; i < live.size(); i++) {
            groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(live.get(i));
        }
        List<System> systems = new ArrayList<>(groups.size());
        for (List<Member> members : groups.values()) {
            members.sort(Comparator.comparing(Member::id));
            systems.add(new System(members));
        }
        // Systems come out of a hash map, so their order is settled here by the first member of
        // each. Without it two identical worlds could hand the caller two different lists.
        systems.sort(Comparator.comparing(system -> system.members().getFirst().id()));
        return List.copyOf(systems);
    }

    /**
     * Picks the devices a set of systems carries.
     *
     * <p>A device is carried when a member reaches it and it wins a place in the system's budget.
     * When more devices are in reach than the budget allows, the ones that stay are the ones
     * closest to the tower that would carry them, and ties are broken by dimension and position.
     *
     * <p>The tie-break is not cosmetic. Activation is read every tick by every dock and every
     * packager, and the snapshot is rebuilt from scratch; without a total order on the devices, the
     * machine that loses its place could be a different one each rebuild, and a dock that switches
     * itself off and on at twenty hertz shreds any logistics chain running through it.
     */
    public static List<Carried> carry(List<System> systems, List<Device> devices) {
        List<Carried> carried = new ArrayList<>();
        for (System system : systems) {
            List<Carried> candidates = new ArrayList<>();
            for (Device device : devices) {
                Member carrier = system.nearest(device.pos());
                if (carrier == null) {
                    continue;
                }
                candidates.add(new Carried(device, carrier.id(), distanceSq(carrier.base(), device.pos())));
            }
            candidates.sort(Comparator
                    .comparingLong(Carried::distanceSq)
                    .thenComparing(carriedDevice -> carriedDevice.device().dimension())
                    .thenComparingLong(carriedDevice -> carriedDevice.device().pos().asLong()));
            carried.addAll(candidates.subList(0, Math.min(system.devices(), candidates.size())));
        }
        return List.copyOf(carried);
    }

    private static boolean withinReach(Member member, BlockPos pos) {
        return distanceSq(member.base(), pos) <= (long) member.radius() * member.radius();
    }

    private static boolean overlap(Member first, Member second) {
        if (!first.id().dimension().equals(second.id().dimension())) {
            return false;
        }
        long reach = (long) first.radius() + second.radius();
        return distanceSq(first.base(), second.base()) <= reach * reach;
    }

    private static long distanceSq(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dy = (long) from.getY() - to.getY();
        long dz = (long) from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != root) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int first, int second) {
        int a = find(parent, first);
        int b = find(parent, second);
        if (a != b) {
            parent[Math.max(a, b)] = Math.min(a, b);
        }
    }

    private TowerSystem() {
    }
}
