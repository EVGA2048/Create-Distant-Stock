package dev.distantstock.diagnostics;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRoutingTable;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.distantstock.block.LampState;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.event.EventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Active health layer for Create chain-conveyor package networks.
 *
 * <p>Normal routing stays Create's. Distant Stock only supplies a fallback diagnostic route when
 * Create has no matching destination, and temporarily swaps a failed address over to a cache
 * Frogport while private probes test the frozen real Frogports.</p>
 */
public final class ChainDiagnostics {
    /** One normal address is sampled every four seconds per chain network. */
    private static final long SCAN_INTERVAL = 80;
    /** Keep a large factory moving without flooding its chain bus with diagnostic parcels. */
    private static final int MAX_NORMAL_PROBES = 4;
    /** Once the Frogport mouth is free, stagger new health probes by half a second. */
    private static final long NORMAL_PROBE_LAUNCH_INTERVAL = 10;
    /** Large industrial rings can exceed the old 512-node safety cap. */
    private static final int MAX_GRAPH_CONVEYORS = 4096;
    /** One-second topology cache; live BE fields (speed/address/inventory) are still read every use. */
    private static final long GRAPH_CACHE_TICKS = 20;
    private static final long FALLBACK_PROBE_TIMEOUT = 300;
    private static final long RETRY_INTERVAL = 60;

    private static final String DIAG_PREFIX = "__distantstock_diag__/";
    private static final String RECOVERY_PREFIX = "__distantstock_probe__/";
    private static final String CACHE_PREFIX = "__distantstock_cache__/";

    private static final Set<DiagnosticFrogportBlockEntity> DIAGNOSTICS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<CacheFrogportBlockEntity> CACHES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Map<ControllerKey, RuntimeState> RUNTIME = new HashMap<>();
    private static final Map<UUID, Probe> PROBES = new HashMap<>();
    private static final Map<FaultKey, Fault> FAULTS = new LinkedHashMap<>();
    private static final Map<PortKey, FaultKey> FROZEN = new HashMap<>();
    private static final Map<AddressKey, Set<ControllerKey>> BAD_ADDRESSES = new HashMap<>();
    private static final Set<FaultKey> FULL_CACHES = new HashSet<>();
    /** Live Factory Gauge address -> Create network associations, refreshed by the gauge's own tick. */
    private static final Map<AddressKey, Map<UUID, Long>> ADDRESS_NETWORKS = new HashMap<>();
    private static final Map<ControllerKey, CachedGraph> GRAPH_CACHE = new HashMap<>();
    private static final Map<ControllerKey, Set<String>> KNOWN_ADDRESSES = new HashMap<>();
    private static final Map<AddressKey, LatencyStats> LATENCIES = new HashMap<>();

    public static String diagnosticAddress(BlockPos pos) {
        return DIAG_PREFIX + Long.toUnsignedString(pos.asLong(), 36);
    }

    public static String recoveryAddress(BlockPos pos) {
        return RECOVERY_PREFIX + Long.toUnsignedString(pos.asLong(), 36);
    }

    public static String cacheIdleAddress(BlockPos pos) {
        return CACHE_PREFIX + Long.toUnsignedString(pos.asLong(), 36);
    }

    public static boolean isDiagnosticAddress(String address) {
        return address != null && address.startsWith(DIAG_PREFIX);
    }

    public static boolean isRecoveryAddress(String address) {
        return address != null && address.startsWith(RECOVERY_PREFIX);
    }

    public static boolean isCacheIdleAddress(String address) {
        return address != null && address.startsWith(CACHE_PREFIX);
    }

    public static void register(DiagnosticFrogportBlockEntity be) {
        if (be != null) {
            DIAGNOSTICS.add(be);
            invalidateGraph(be.getLevel());
        }
    }

    public static void unregister(DiagnosticFrogportBlockEntity be) {
        DIAGNOSTICS.remove(be);
        if (be != null) invalidateGraph(be.getLevel());
    }

    /** Permanent removal of a diagnostic controller: release every route it may have intercepted. */
    public static void diagnosticRemoved(DiagnosticFrogportBlockEntity diagnostic) {
        if (diagnostic == null || !(diagnostic.getLevel() instanceof ServerLevel level)) return;
        ControllerKey key = new ControllerKey(level.dimension(), diagnostic.getBlockPos());
        long eventNow = System.currentTimeMillis();

        PROBES.entrySet().removeIf(entry -> entry.getValue().controller().equals(key));
        RUNTIME.remove(key);

        for (Fault fault : new ArrayList<>(FAULTS.values())) {
            if (!fault.key.controller().equals(key)) continue;
            FAULTS.remove(fault.key);
            thawTargets(level, fault);
            BlockPos cachePos = fault.activeCachePos;
            if (cachePos != null
                    && level.getBlockEntity(cachePos) instanceof CacheFrogportBlockEntity cache
                    && fault.key.address().equals(cache.takeoverAddress())) {
                cache.setTakeoverAddress("");
            }
            FULL_CACHES.remove(fault.key);
            String source = sourceId(level, diagnostic.getBlockPos(), fault.key.address());
            EventRegistry registry = EventRegistry.get(level.getServer());
            registry.clear(EventRegistry.Codes.CHAIN_PING_TIMEOUT, "chain", source, eventNow);
            registry.clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain", source + "/cache", eventNow);
        }

        for (String address : new ArrayList<>(diagnostic.unresolvedBadAddresses())) {
            AddressKey addressKey = new AddressKey(level.dimension(), address);
            Set<ControllerKey> owners = BAD_ADDRESSES.get(addressKey);
            if (owners != null) {
                owners.remove(key);
                if (owners.isEmpty()) BAD_ADDRESSES.remove(addressKey);
            }
            EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain",
                    sourceId(level, diagnostic.getBlockPos(), address), eventNow);
        }
    }

    public static void register(CacheFrogportBlockEntity be) {
        if (be != null) {
            CACHES.add(be);
            invalidateGraph(be.getLevel());
        }
    }

    public static void unregister(CacheFrogportBlockEntity be) {
        CACHES.remove(be);
        if (be != null) invalidateGraph(be.getLevel());
    }

    /**
     * Permanent cache removal is a fail-open boundary. A fault may have rewritten several normal
     * Frogports to private recovery addresses; leaving those mappings behind after the cache is
     * gone makes valid parcels lose their destination and fall into diagnostics. Thaw every fault
     * owned by this cache before the block itself deregisters from Create.
     */
    public static void cacheRemoved(CacheFrogportBlockEntity cache) {
        if (cache == null || !(cache.getLevel() instanceof ServerLevel level)) return;
        BlockPos cachePos = cache.getBlockPos();
        long now = level.getGameTime();
        List<Fault> owned = FAULTS.values().stream()
                .filter(fault -> fault.key.controller().dimension().equals(level.dimension())
                        && Objects.equals(fault.activeCachePos, cachePos))
                .toList();
        for (Fault fault : owned) {
            fault.activeCachePos = null;
            if (!assignNextCacheOwner(level, fault, cachePos)) {
                abandonCacheMitigation(level, fault, now);
            }
        }
        unregister(cache);
    }

    private static void abandonCacheMitigation(ServerLevel level, Fault fault, long now) {
        FAULTS.remove(fault.key);
        PROBES.entrySet().removeIf(entry -> entry.getValue().recovery()
                && entry.getValue().controller().equals(fault.key.controller())
                && entry.getValue().address().equals(fault.key.address()));

        thawTargets(level, fault);
        BlockPos cachePos = fault.activeCachePos;
        if (cachePos != null && level.getBlockEntity(cachePos) instanceof CacheFrogportBlockEntity cache
                && fault.key.address().equals(cache.takeoverAddress())) {
            cache.setTakeoverAddress("");
        }

        FULL_CACHES.remove(fault.key);
        EventRegistry registry = EventRegistry.get(level.getServer());
        long eventNow = System.currentTimeMillis();
        registry.clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                sourceId(level, fault.key.controller().pos(), fault.key.address()) + "/cache", eventNow);
        registry.clear(EventRegistry.Codes.CHAIN_PING_TIMEOUT, "chain",
                sourceId(level, fault.key.controller().pos(), fault.key.address()), eventNow);

        RuntimeState runtime = RUNTIME.computeIfAbsent(fault.key.controller(), ignored -> new RuntimeState());
        runtime.nextScanTick = now;
        DiagnosticFrogportBlockEntity controller = controller(fault.key.controller());
        if (controller != null) {
            controller.recordDiagnosticResult("cache_removed", fault.key.address());
        }
    }

    /**
     * A fault keeps one exclusive cache lease while that cache has room. Ownership only moves when
     * the active cache becomes full (see maintainFaults). This prevents one noisy address from
     * walking through the entire spare-cache pool and starving simultaneous independent faults.
     */
    public static void cacheAcceptedPackage(CacheFrogportBlockEntity cache) {
        // Intentionally no ownership rotation here.
    }

    private static boolean assignNextCacheOwner(ServerLevel level, Fault fault, BlockPos currentPos) {
        DiagnosticFrogportBlockEntity controller = controller(fault.key.controller());
        if (controller == null) return false;
        Graph graph = graph(controller);

        List<CacheFrogportBlockEntity> candidates = graph.caches().stream()
                .filter(candidate -> !candidate.getBlockPos().equals(currentPos))
                .filter(CacheFrogportBlockEntity::availableForLease)
                .filter(candidate -> !candidate.isBackedUp())
                .filter(candidate -> FAULTS.values().stream().noneMatch(other -> other != fault
                        && other.key.controller().dimension().equals(level.dimension())
                        && Objects.equals(other.activeCachePos, candidate.getBlockPos())))
                .sorted(Comparator.comparingLong(candidate -> candidate.getBlockPos().asLong()))
                .toList();
        if (candidates.isEmpty()) return false;

        long currentOrder = currentPos == null ? Long.MIN_VALUE : currentPos.asLong();
        CacheFrogportBlockEntity next = candidates.stream()
                .filter(candidate -> candidate.getBlockPos().asLong() > currentOrder)
                .findFirst().orElse(candidates.get(0));

        if (currentPos != null
                && level.getBlockEntity(currentPos) instanceof CacheFrogportBlockEntity current
                && fault.key.address().equals(current.takeoverAddress())) {
            current.setTakeoverAddress("");
        }
        fault.activeCachePos = next.getBlockPos();
        next.setTakeoverAddress(fault.key.address());
        FULL_CACHES.remove(fault.key);
        return true;
    }

    private static void thawTargets(ServerLevel level, Fault fault) {
        for (BlockPos pos : fault.targets) {
            if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog && frog.target != null) {
                // Deregister the private recovery address while FROZEN still controls getFilterString().
                frog.target.deregister(frog, level, pos);
            }
            FROZEN.remove(new PortKey(level.dimension(), pos));
            if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog && frog.target != null) {
                frog.target.register(frog, level, pos);
            }
        }
    }

    public static void observeGauge(FactoryPanelBehaviour gauge) {
        if (gauge == null || gauge.panelBE() == null || gauge.panelBE().getLevel() == null
                || gauge.panelBE().getLevel().isClientSide || gauge.network == null || !gauge.isActive()) return;
        String address = gauge.getFrogAddress();
        if (address == null || address.isBlank()) return;
        Level level = gauge.panelBE().getLevel();
        ADDRESS_NETWORKS.computeIfAbsent(new AddressKey(level.dimension(), address), ignored -> new HashMap<>())
                .put(gauge.network, level.getGameTime());
    }

    /** Whether a scoped Logger belongs to a live Factory Gauge that uses this Frogport address. */
    public static boolean addressUsedByNetwork(Level level, String address, UUID network) {
        if (level == null || address == null || address.isBlank() || network == null) return false;
        Map<UUID, Long> networks = ADDRESS_NETWORKS.get(new AddressKey(level.dimension(), address));
        if (networks == null) return false;
        Long lastSeen = networks.get(network);
        return lastSeen != null && level.getGameTime() - lastSeen <= 60;
    }

    /**
     * Called by the chain-conveyor mixin instead of PackageItem.matchAddress for the diagnostic
     * private port. A stale ping explicitly addressed home still matches normally; ordinary parcels
     * match the diagnostic only when Create has no real route for them.
     */
    public static boolean matchDiagnosticPort(ChainConveyorBlockEntity conveyor, ItemStack stack, String filter) {
        if (!isDiagnosticAddress(filter)) return PackageItem.matchAddress(stack, filter);
        if (PingPackageData.isPing(stack)) return PackageItem.matchAddress(stack, filter);
        return shouldCatchUnroutable(conveyor.routingTable.entriesByDistance, stack);
    }

    public static boolean shouldCatchUnroutable(
            Collection<ChainConveyorRoutingTable.RoutingTableEntry> entries, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !PackageItem.isPackage(stack) || PingPackageData.isPing(stack)) {
            return false;
        }
        boolean diagnosticReachable = false;
        for (var entry : entries) {
            String port = entry.port();
            if (isDiagnosticAddress(port)) {
                diagnosticReachable = true;
                continue;
            }
            if (isRecoveryAddress(port) || isCacheIdleAddress(port)) continue;
            if (PackageItem.matchAddress(stack, port)) return false;
        }
        return diagnosticReachable;
    }

    /** Fallback next chain connection for an otherwise unroutable parcel. */
    public static BlockPos diagnosticExit(Collection<ChainConveyorRoutingTable.RoutingTableEntry> entries,
                                          ItemStack stack) {
        if (!shouldCatchUnroutable(entries, stack)) return BlockPos.ZERO;
        for (var entry : entries) {
            if (isDiagnosticAddress(entry.port())) return entry.nextConnection();
        }
        return BlockPos.ZERO;
    }

    /** Override used only for ordinary Create Frogports that are temporarily frozen. */
    public static String frozenFilter(FrogportBlockEntity frog) {
        if (frog == null || frog.getLevel() == null || frog.getLevel().isClientSide) return null;
        FaultKey fault = FROZEN.get(new PortKey(frog.getLevel().dimension(), frog.getBlockPos()));
        return fault == null ? null : recoveryAddress(frog.getBlockPos());
    }

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        DIAGNOSTICS.removeIf(be -> be == null || be.isRemoved() || be.getLevel() == null);
        CACHES.removeIf(be -> be == null || be.isRemoved() || be.getLevel() == null);
        // Fault ownership is runtime state, while the Frogport inventory/takeover address is saved.
        // After a crash or hard kill, an orphaned cache must give the public address back instead of
        // competing with the restored real Frogports. Its cached parcels stay put for slow replay.
        for (CacheFrogportBlockEntity cache : new ArrayList<>(CACHES)) {
            if (cache.getLevel() == null || cache.takeoverAddress().isBlank()) continue;
            boolean owned = FAULTS.values().stream().anyMatch(fault -> fault.activeCachePos != null
                    && fault.key.controller().dimension().equals(cache.getLevel().dimension())
                    && fault.activeCachePos.equals(cache.getBlockPos())
                    && fault.key.address().equals(cache.takeoverAddress()));
            if (!owned) cache.setTakeoverAddress("");
        }
        ADDRESS_NETWORKS.entrySet().removeIf(entry -> {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            if (level == null) return true;
            entry.getValue().entrySet().removeIf(row -> level.getGameTime() - row.getValue() > 60);
            return entry.getValue().isEmpty();
        });

        refreshQuarantineFaults(server);

        expireProbes(server);

        List<DiagnosticFrogportBlockEntity> controllers = new ArrayList<>(DIAGNOSTICS);
        controllers.sort(Comparator.<DiagnosticFrogportBlockEntity, String>comparing(
                        be -> be.getLevel().dimension().location().toString())
                .thenComparingLong(be -> be.getBlockPos().asLong()));

        for (DiagnosticFrogportBlockEntity controller : controllers) {
            if (!(controller.getLevel() instanceof ServerLevel level)) continue;
            Graph graph = graph(controller);
            if (graph.conveyors().isEmpty()) {
                controller.setDiagnosticPlan("disconnected", "", 0, 0, 0, 0, 0, 0);
                controller.setDiagnosticHealth(0, false);
                controller.setDiagnosticInFlight(0);
                continue;
            }
            if (!isLeader(controller, graph)) {
                controller.setDiagnosticPlan("standby", "", 0, 0, 0, 0, 0, 0);
                controller.setDiagnosticHealth(0, false);
                controller.setDiagnosticInFlight(0);
                continue;
            }

            ControllerKey key = new ControllerKey(level.dimension(), controller.getBlockPos());
            RuntimeState runtime = RUNTIME.computeIfAbsent(key, ignored -> new RuntimeState());

            maintainFaults(controller, graph, key, level.getGameTime());

            int liveFaults = (int) FAULTS.keySet().stream()
                    .filter(fault -> fault.controller().equals(key)).count();
            liveFaults += (int) BAD_ADDRESSES.values().stream()
                    .filter(owners -> owners.contains(key)).count();
            boolean cacheMitigating = FAULTS.values().stream()
                    .anyMatch(fault -> fault.key.controller().equals(key)
                            && fault.mitigated() && fault.activeCachePos != null);
            controller.setDiagnosticHealth(liveFaults, cacheMitigating);

            Map<String, List<FrogportBlockEntity>> addresses = receiverAddresses(graph);
            KNOWN_ADDRESSES.put(key, Set.copyOf(addresses.keySet()));
            List<String> candidates = addresses.keySet().stream()
                    .filter(address -> !FAULTS.containsKey(new FaultKey(key, address)))
                    .sorted()
                    .toList();

            // Recovery is intentionally single-flight and takes display/launch priority. Ordinary
            // health probes already in the network are allowed to finish, but no new ones are added
            // while a recovery probe is active.
            Probe recovery = activeRecoveryProbe(key);
            int inFlight = probeCount(key);
            controller.setDiagnosticInFlight(inFlight);
            if (recovery != null) {
                Fault fault = FAULTS.get(new FaultKey(key, recovery.address()));
                int targets = fault == null ? 1 : Math.max(1, fault.targets.size());
                controller.setDiagnosticPlan("recovery", recovery.address(),
                        recovery.planIndex(), recovery.planTotal(), targets,
                        0, recovery.sentTick(), recovery.deadlineTick());
                continue;
            }

            int normalCount = normalProbeCount(key);
            Probe oldestNormal = oldestNormalProbe(key);
            if (candidates.isEmpty()) {
                if (oldestNormal != null) {
                    controller.setDiagnosticPlan("waiting", oldestNormal.address(),
                            oldestNormal.planIndex(), oldestNormal.planTotal(),
                            addresses.getOrDefault(oldestNormal.address(), List.of()).size(),
                            0, oldestNormal.sentTick(), oldestNormal.deadlineTick());
                } else {
                    runtime.nextScanTick = level.getGameTime() + SCAN_INTERVAL;
                    controller.setDiagnosticPlan("idle", "", 0, 0, 0,
                            runtime.nextScanTick, 0, 0);
                }
                continue;
            }

            // Four ordinary probes may be in flight together. Never send a second probe for the same
            // business address; concurrent slots are for widening coverage, not duplicate traffic.
            if (normalCount >= MAX_NORMAL_PROBES) {
                if (oldestNormal != null) {
                    controller.setDiagnosticPlan("waiting", oldestNormal.address(),
                            oldestNormal.planIndex(), oldestNormal.planTotal(),
                            addresses.getOrDefault(oldestNormal.address(), List.of()).size(),
                            0, oldestNormal.sentTick(), oldestNormal.deadlineTick());
                }
                continue;
            }

            int selectedIndex = -1;
            String address = null;
            for (int offset = 0; offset < candidates.size(); offset++) {
                int index = Math.floorMod(runtime.cursor + offset, candidates.size());
                String candidate = candidates.get(index);
                if (hasNormalProbe(key, candidate)) continue;
                selectedIndex = index;
                address = candidate;
                break;
            }

            // Every configured address is already represented by an in-flight normal probe.
            if (address == null) {
                if (oldestNormal != null) {
                    controller.setDiagnosticPlan("waiting", oldestNormal.address(),
                            oldestNormal.planIndex(), oldestNormal.planTotal(),
                            addresses.getOrDefault(oldestNormal.address(), List.of()).size(),
                            0, oldestNormal.sentTick(), oldestNormal.deadlineTick());
                }
                continue;
            }

            int planIndex = selectedIndex + 1;
            List<FrogportBlockEntity> addressTargets = addresses.get(address);
            if (level.getGameTime() < runtime.nextScanTick) {
                controller.setDiagnosticPlan("scheduled", address, planIndex, candidates.size(),
                        addressTargets.size(), runtime.nextScanTick, 0, 0);
                continue;
            }

            // Same-address Frogports are interchangeable to Create: the routing table may choose a
            // different receiver than list index 0. Budget against the slowest receiver so a healthy
            // far branch cannot time out merely because the probe happened to take that branch.
            FrogportBlockEntity target = addressTargets.stream()
                    .max(Comparator.comparingLong(candidate ->
                            estimatedProbeTimeoutTicks(controller, graph, candidate.getBlockPos())))
                    .orElse(addressTargets.get(0));
            if (sendProbe(controller, graph, key, address, target.getBlockPos(), address,
                    false, level.getGameTime(), planIndex, candidates.size(), addressTargets.size())) {
                runtime.cursor = Math.floorMod(selectedIndex + 1, candidates.size());
                runtime.nextScanTick = level.getGameTime() + NORMAL_PROBE_LAUNCH_INTERVAL;
                controller.setDiagnosticInFlight(normalCount + 1);
            } else {
                // The Frogport mouth itself still serialises physical injection. Retry soon without
                // consuming this polling slot or advancing the cursor.
                runtime.nextScanTick = level.getGameTime() + 10;
                controller.setDiagnosticPlan("busy", address, planIndex, candidates.size(),
                        addressTargets.size(), runtime.nextScanTick, 0, 0);
            }
        }

        clearOrphanedPersistentChainFaults(server);
    }

    /**
     * Rebuild no-route runtime state from each diagnostic Frogport's durable unresolved-address set.
     *
     * <p>The 18-slot quarantine is only a transport buffer. A package leaving that inventory for a
     * Packager below means "handled", not "fault repaired", so inventory presence must never decide
     * whether CHAIN_NO_ROUTE remains active.</p>
     */
    private static void refreshQuarantineFaults(MinecraftServer server) {
        for (DiagnosticFrogportBlockEntity diagnostic : DIAGNOSTICS) {
            if (!(diagnostic.getLevel() instanceof ServerLevel level)) continue;
            ControllerKey controller = new ControllerKey(level.dimension(), diagnostic.getBlockPos());
            Set<String> quarantinedNow = new LinkedHashSet<>();

            // Migration/safety net: old worlds may contain quarantined packages from before the
            // durable unresolved-address set existed. Seeing one still creates the durable fault.
            for (int slot = 0; slot < diagnostic.inventory.getSlots(); slot++) {
                ItemStack stack = diagnostic.inventory.getStackInSlot(slot);
                if (stack.isEmpty() || !PackageItem.isPackage(stack) || PingPackageData.isPing(stack)) continue;
                String address = PackageItem.getAddress(stack);
                if (address == null || address.isBlank()) address = "<blank>";
                AddressKey key = new AddressKey(level.dimension(), address);
                quarantinedNow.add(address);
                diagnostic.rememberBadAddress(address, ADDRESS_NETWORKS.containsKey(key));
            }

            for (String address : new ArrayList<>(diagnostic.unresolvedBadAddresses())) {
                AddressKey key = new AddressKey(level.dimension(), address);
                boolean stillConfigured = ADDRESS_NETWORKS.containsKey(key);
                if (stillConfigured) diagnostic.noteBadAddressGaugeBacked(address);

                BAD_ADDRESSES.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(controller);
                String source = sourceId(level, diagnostic.getBlockPos(), address);
                EventRegistry registry = EventRegistry.get(server);
                EventRegistry.Record active = registry.active(
                        EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", source).orElse(null);
                if (active == null) {
                    registry.raise(EventRegistry.Severity.ERROR, EventRegistry.Codes.CHAIN_NO_ROUTE,
                            "chain", source, address, diagnostic.createFrequency(), null,
                            System.currentTimeMillis());
                    active = registry.active(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", source).orElse(null);
                }

                // A one-off malformed parcel has no persistent configuration source to repair.
                // Once it has physically left the quarantine (normally into the Packager below)
                // and the operator has printed the incident slip, the incident is considered handled.
                // A future parcel with the same bad address will raise a fresh alarm.
                if (!diagnostic.badAddressWasGaugeBacked(address)
                        && !quarantinedNow.contains(address)
                        && active != null && active.printed()) {
                    clearBadAddress(level, controller, address, level.getGameTime());
                    continue;
                }

                // If this bad address came from a Factory Gauge, seeing the Gauge stop advertising
                // it for >60 ticks is positive evidence that the operator corrected/reconfigured it.
                // Non-gauge bad addresses stay faulted until a real receiver appears and a healthy
                // Ping clears them through clearBadAddress().
                if (diagnostic.badAddressWasGaugeBacked(address) && !stillConfigured) {
                    long lastSeen = diagnostic.badAddressGaugeLastSeen(address);
                    if (lastSeen != Long.MIN_VALUE && level.getGameTime() - lastSeen > 60) {
                        clearBadAddress(level, controller, address, level.getGameTime());
                    }
                }
            }
        }
    }

    /** Durable event rows must not outlive the runtime fault that created them after a hard restart. */
    private static void clearOrphanedPersistentChainFaults(MinecraftServer server) {
        EventRegistry registry = EventRegistry.get(server);
        Set<String> liveTimeouts = new HashSet<>();
        Set<String> liveFullCaches = new HashSet<>();
        for (Fault fault : FAULTS.values()) {
            ServerLevel level = server.getLevel(fault.key.controller().dimension());
            if (level == null) continue;
            String base = sourceId(level, fault.key.controller().pos(), fault.key.address());
            liveTimeouts.add(base);
            if (FULL_CACHES.contains(fault.key)) liveFullCaches.add(base + "/cache");
        }

        long eventNow = System.currentTimeMillis();
        for (EventRegistry.Record record : new ArrayList<>(registry.active())) {
            if (!"chain".equals(record.sourceType())) continue;
            if (EventRegistry.Codes.CHAIN_PING_TIMEOUT.equals(record.code())
                    && !liveTimeouts.contains(record.sourceId())) {
                registry.clear(record.code(), record.sourceType(), record.sourceId(), eventNow);
            } else if (EventRegistry.Codes.CHAIN_CACHE_FULL.equals(record.code())
                    && !liveFullCaches.contains(record.sourceId())) {
                registry.clear(record.code(), record.sourceType(), record.sourceId(), eventNow);
            }
        }
    }

    private static void expireProbes(MinecraftServer server) {
        List<Probe> expired = PROBES.values().stream()
                .filter(probe -> {
                    ServerLevel level = server.getLevel(probe.controller().dimension());
                    return level == null || level.getGameTime() > probe.deadlineTick();
                })
                .toList();
        for (Probe probe : expired) {
            PROBES.remove(probe.id());
            handleTimeout(server, probe);
        }
    }

    private static void handleTimeout(MinecraftServer server, Probe probe) {
        ServerLevel level = server.getLevel(probe.controller().dimension());
        if (level == null) return;
        DiagnosticFrogportBlockEntity controller = controller(probe.controller());
        if (controller == null) return;
        long now = level.getGameTime();

        if (probe.recovery()) {
            Fault fault = FAULTS.get(new FaultKey(probe.controller(), probe.address()));
            if (fault != null) fault.nextRetryTick = now + RETRY_INTERVAL;
            controller.recordDiagnosticResult("recovery_timeout", probe.address());
            return;
        }

        controller.recordDiagnosticResult("timeout", probe.address());

        Graph graph = graph(controller);
        List<FrogportBlockEntity> targets = receiverAddresses(graph).getOrDefault(probe.address(), List.of());
        FaultKey key = new FaultKey(probe.controller(), probe.address());
        if (FAULTS.containsKey(key)) return;

        CacheFrogportBlockEntity cache = graph.caches().stream()
                // A cache lease is exclusive by fault/address. Never reuse a frog that still
                // contains parcels from an earlier incident, even if its public route is blank.
                // Those parcels may leave by replay, bottom extraction or manual removal; reuse is
                // allowed only after the inventory is observably empty.
                .filter(CacheFrogportBlockEntity::availableForNewLease)
                .filter(candidate -> FAULTS.values().stream().noneMatch(other ->
                        other.key.controller().dimension().equals(level.dimension())
                                && Objects.equals(other.activeCachePos, candidate.getBlockPos())))
                .min(Comparator.comparingLong(be -> be.getBlockPos().asLong()))
                .orElse(null);

        Fault fault = new Fault(key, new LinkedHashSet<>(), cache == null ? null : cache.getBlockPos(),
                now + RETRY_INTERVAL);
        for (FrogportBlockEntity target : targets) {
            fault.targets.add(target.getBlockPos());
        }
        FAULTS.put(key, fault);

        if (cache != null && !fault.targets.isEmpty()) {
            for (BlockPos targetPos : fault.targets) {
                if (level.getBlockEntity(targetPos) instanceof FrogportBlockEntity frog
                        && frog.target != null) {
                    // Remove the public business address before FROZEN changes getFilterString().
                    frog.target.deregister(frog, level, targetPos);
                }
                FROZEN.put(new PortKey(level.dimension(), targetPos), key);
                if (level.getBlockEntity(targetPos) instanceof FrogportBlockEntity frog) {
                    if (frog.target != null) frog.target.register(frog, level, targetPos);
                }
            }
            cache.setTakeoverAddress(probe.address());
        }

        EventRegistry.get(server).raise(EventRegistry.Severity.ERROR,
                EventRegistry.Codes.CHAIN_PING_TIMEOUT, "chain", sourceId(level, probe.controller().pos(), probe.address()),
                probe.address(), controller.createFrequency(), null, System.currentTimeMillis());
    }

    private static void maintainFaults(DiagnosticFrogportBlockEntity controller, Graph graph,
                                       ControllerKey controllerKey, long now) {
        List<Fault> faults = FAULTS.values().stream()
                .filter(fault -> fault.key.controller().equals(controllerKey))
                .toList();
        boolean recoveryInFlight = activeRecoveryProbe(controllerKey) != null;
        for (Fault fault : faults) {
            if (!(controller.getLevel() instanceof ServerLevel level)) continue;

            if (fault.activeCachePos != null && level.isLoaded(fault.activeCachePos)
                    && level.getBlockEntity(fault.activeCachePos) instanceof CacheFrogportBlockEntity cache) {
                // A cache is one physical Frogport and Create intentionally keeps one route per
                // address. Treat multiple Cache Frogports as a rotating pool instead: once the
                // current owner fills, hand the public address to the next available cache before
                // declaring the mitigation full.
                boolean backedUp = cache.isBackedUp();
                if (backedUp && assignNextCacheOwner(level, fault, cache.getBlockPos())) {
                    backedUp = false;
                }
                if (backedUp) {
                    if (FULL_CACHES.add(fault.key)) {
                        EventRegistry.get(level.getServer()).raise(EventRegistry.Severity.ERROR,
                                EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                                sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache",
                                fault.key.address(), controller.createFrequency(), null, System.currentTimeMillis());
                    }
                } else if (FULL_CACHES.remove(fault.key)) {
                    EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                            sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache",
                            System.currentTimeMillis());
                }
            }

            if (recoveryInFlight) continue;
            if (now < fault.nextRetryTick) continue;

            if (fault.mitigated()) {
                // Chunk unload means "unknown", not "healthy" and not "removed". Pause the
                // recovery round until every target can be inspected again.
                if (fault.targets.stream().anyMatch(pos -> !level.isLoaded(pos))) {
                    fault.nextRetryTick = now + RETRY_INTERVAL;
                    continue;
                }
                fault.targets.removeIf(pos -> {
                    boolean gone = !(level.getBlockEntity(pos) instanceof FrogportBlockEntity);
                    if (gone) FROZEN.remove(new PortKey(level.dimension(), pos));
                    return gone;
                });
                if (fault.targets.isEmpty()) {
                    restore(controller, fault, now);
                    continue;
                }
                BlockPos target = fault.targets.stream()
                        .filter(pos -> !fault.passed.contains(pos))
                        .findFirst().orElse(null);
                if (target == null) {
                    restore(controller, fault, now);
                    continue;
                }
                int recoveryIndex = fault.passed.size() + 1;
                if (sendProbe(controller, graph, controllerKey, fault.key.address(), target,
                        recoveryAddress(target), true, now,
                        recoveryIndex, Math.max(1, fault.targets.size()), Math.max(1, fault.targets.size()))) {
                    recoveryInFlight = true;
                } else {
                    fault.nextRetryTick = now + 10;
                }
            } else {
                List<FrogportBlockEntity> targets = receiverAddresses(graph)
                        .getOrDefault(fault.key.address(), List.of());
                if (targets.isEmpty()) {
                    fault.nextRetryTick = now + RETRY_INTERVAL;
                    continue;
                }
                BlockPos target = targets.get(0).getBlockPos();
                if (sendProbe(controller, graph, controllerKey, fault.key.address(), target,
                        fault.key.address(), true, now,
                        1, Math.max(1, targets.size()), targets.size())) {
                    recoveryInFlight = true;
                } else {
                    fault.nextRetryTick = now + 10;
                }
            }
        }
    }

    private static boolean sendProbe(DiagnosticFrogportBlockEntity controller, Graph graph, ControllerKey key,
                                     String originalAddress, BlockPos targetPos, String routingAddress,
                                     boolean recovery, long now,
                                     int planIndex, int planTotal, int targetCount) {
        UUID id = UUID.randomUUID();
        long timeout = estimatedProbeTimeoutTicks(controller, graph, targetPos);
        ItemStack ping = PingPackageData.create(id, key.dimension().location().toString(), key.pos(),
                targetPos, originalAddress, routingAddress, now, now + timeout);
        if (!controller.sendProbe(ping)) return false;
        long expected = Math.max(1, Math.round(Math.max(1.0, timeout - 60.0) / 1.5));
        PROBES.put(id, new Probe(id, key, originalAddress, targetPos, now, now + timeout, expected,
                recovery, planIndex, planTotal));
        controller.setDiagnosticPlan(recovery ? "recovery" : "waiting", originalAddress,
                planIndex, planTotal, targetCount, 0, now, now + timeout);
        return true;
    }

    /**
     * Dynamic probe timeout based on Create's actual chain geometry.
     * RoutingTableEntry.distance() is only a hop count; ConnectionStats.chainLength() is the real
     * block-scale length between chain conveyor nodes.
     */
    public static long estimatedProbeTimeoutTicks(DiagnosticFrogportBlockEntity controller, BlockPos targetPos) {
        if (controller == null || controller.getLevel() == null || targetPos == null) {
            return FALLBACK_PROBE_TIMEOUT;
        }
        return estimatedProbeTimeoutTicks(controller, graph(controller), targetPos);
    }

    private static long estimatedProbeTimeoutTicks(DiagnosticFrogportBlockEntity controller, Graph graph,
                                                   BlockPos targetPos) {
        if (controller == null || controller.getLevel() == null || graph == null || targetPos == null) {
            return FALLBACK_PROBE_TIMEOUT;
        }
        ChainConveyorBlockEntity source = owningConveyor(graph, controller.getBlockPos());
        ChainConveyorBlockEntity target = owningConveyor(graph, targetPos);
        if (source == null || target == null) return FALLBACK_PROBE_TIMEOUT;

        // Create does not route packages by physical travel time. Its routing table prefers the
        // smallest RoutingTableEntry.distance(), i.e. the fewest conveyor-to-conveyor hops. On a
        // large ring this can deliberately choose a physically much longer arc than a time-based
        // Dijkstra would. Estimate the route with the same primary metric, otherwise healthy probes
        // on factory-scale buses can be declared dead while they are still moving normally.
        double travelTicks = createRouteTravelTicks(graph, source, target);
        if (!Double.isFinite(travelTicks)) return FALLBACK_PROBE_TIMEOUT;

        // Local sprocket arcs/port positions are not part of ConnectionStats. Budget eight blocks
        // at the slower endpoint speed, then add 50% route/tick-phase headroom plus three seconds.
        double sourceBpt = Math.abs(source.getSpeed()) / 360.0;
        double targetBpt = Math.abs(target.getSpeed()) / 360.0;
        double endpointBpt;
        if (source == target) {
            endpointBpt = sourceBpt;
        } else {
            // If either endpoint is stopped, the probe cannot complete normally. Do not hide that
            // behind the speed of the other endpoint.
            if (sourceBpt < 1.0e-4 || targetBpt < 1.0e-4) return FALLBACK_PROBE_TIMEOUT;
            endpointBpt = Math.min(sourceBpt, targetBpt);
        }
        if (endpointBpt < 1.0e-4) return FALLBACK_PROBE_TIMEOUT;
        double localTicks = 8.0 / endpointBpt;
        double estimate = Math.ceil((travelTicks + localTicks) * 1.5 + 60.0);
        if (!Double.isFinite(estimate) || estimate >= Long.MAX_VALUE) return Long.MAX_VALUE;
        // There used to be a 2400-tick (120 s) ceiling here. That makes any legitimately slower
        // factory-scale route a guaranteed false timeout, so only keep the five-second floor.
        return Math.max(100, (long) estimate);
    }

    /**
     * Estimate the path Create itself will favour: minimum conveyor hop count first. Among equal-hop
     * alternatives use the slowest travel-time candidate so the deadline remains conservative when
     * Create's insertion order chooses either side of a ring.
     */
    private static double createRouteTravelTicks(Graph graph, ChainConveyorBlockEntity source,
                                                 ChainConveyorBlockEntity target) {
        if (source == target) return 0;

        Map<BlockPos, ChainConveyorBlockEntity> byPos = new HashMap<>();
        for (ChainConveyorBlockEntity conveyor : graph.conveyors()) {
            byPos.put(conveyor.getBlockPos(), conveyor);
        }
        BlockPos sourcePos = source.getBlockPos();
        BlockPos targetPos = target.getBlockPos();

        // First mirror RoutingTableEntry.distance(): one point per conveyor-to-conveyor hop.
        Map<BlockPos, Integer> hops = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        hops.put(targetPos, 0);
        queue.add(targetPos);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            ChainConveyorBlockEntity conveyor = byPos.get(pos);
            if (conveyor == null) continue;
            int nextHop = hops.get(pos) + 1;
            for (BlockPos relative : conveyor.connections) {
                BlockPos neighbour = pos.offset(relative);
                if (!byPos.containsKey(neighbour) || hops.containsKey(neighbour)) continue;
                hops.put(neighbour, nextHop);
                queue.addLast(neighbour);
            }
        }

        Integer sourceHops = hops.get(sourcePos);
        if (sourceHops == null) return Double.POSITIVE_INFINITY;

        // Then sum real chain travel time along minimum-hop routes. Taking the maximum of equal-hop
        // choices is intentional: Create breaks those ties by routing-table insertion order, not by
        // physical chain length, so the shorter-time branch is not guaranteed to be selected.
        Map<BlockPos, Double> worstTicks = new HashMap<>();
        worstTicks.put(targetPos, 0.0);
        for (int hop = 1; hop <= sourceHops; hop++) {
            for (Map.Entry<BlockPos, Integer> row : hops.entrySet()) {
                if (row.getValue() != hop) continue;
                ChainConveyorBlockEntity conveyor = byPos.get(row.getKey());
                if (conveyor == null) continue;
                double blocksPerTick = Math.abs(conveyor.getSpeed()) / 360.0;
                if (blocksPerTick < 1.0e-4) continue;
                conveyor.prepareStats();

                double worst = Double.NEGATIVE_INFINITY;
                for (BlockPos relative : conveyor.connections) {
                    BlockPos neighbour = conveyor.getBlockPos().offset(relative);
                    if (!Objects.equals(hops.get(neighbour), hop - 1)) continue;
                    Double tail = worstTicks.get(neighbour);
                    if (tail == null || !Double.isFinite(tail)) continue;
                    var stats = conveyor.connectionStats.get(relative);
                    double edge = stats == null
                            ? Math.sqrt(relative.distSqr(BlockPos.ZERO))
                            : stats.chainLength();
                    worst = Math.max(worst, edge / blocksPerTick + tail);
                }
                if (Double.isFinite(worst)) worstTicks.put(row.getKey(), worst);
            }
        }
        return worstTicks.getOrDefault(sourcePos, Double.POSITIVE_INFINITY);
    }

    private static ChainConveyorBlockEntity owningConveyor(Graph graph, BlockPos frogPos) {
        for (ChainConveyorBlockEntity conveyor : graph.conveyors()) {
            for (BlockPos relative : conveyor.loopPorts.keySet()) {
                if (conveyor.getBlockPos().offset(relative).equals(frogPos)) return conveyor;
            }
            for (BlockPos relative : conveyor.travelPorts.keySet()) {
                if (conveyor.getBlockPos().offset(relative).equals(frogPos)) return conveyor;
            }
        }
        return null;
    }

    /** Called before a Frogport would put a ping into its inventory. The ping is consumed here. */
    public static void pingArrived(FrogportBlockEntity frog, ItemStack stack) {
        PingPackageData.Data data = PingPackageData.read(stack);
        if (data == null || frog == null || !(frog.getLevel() instanceof ServerLevel level)) return;
        if (!data.valid()) return; // stale probe returning to its diagnostic Frogport: consume silently

        Probe probe = PROBES.remove(data.probeId());
        if (probe == null) return; // late or superseded probe; never allowed to recover a route
        if (!probe.controller().dimension().equals(level.dimension())) return;

        long now = level.getGameTime();
        if (now > probe.deadlineTick()) {
            handleTimeout(level.getServer(), probe);
            return;
        }

        recordLatency(level, probe.address(), Math.max(0, now - probe.sentTick()), probe.expectedTicks(), now);
        Fault fault = FAULTS.get(new FaultKey(probe.controller(), probe.address()));
        if (probe.recovery() && fault != null) {
            if (fault.mitigated() && !frog.getBlockPos().equals(probe.targetPos())) {
                fault.nextRetryTick = now + 10;
                return;
            }
            if (fault.mitigated()) {
                fault.passed.add(probe.targetPos());
                fault.nextRetryTick = now + 10;
                DiagnosticFrogportBlockEntity controller = controller(probe.controller());
                if (controller != null) controller.recordDiagnosticResult("recovery_ok", probe.address());
                if (fault.passed.containsAll(fault.targets)) {
                    if (controller != null) restore(controller, fault, now);
                }
            } else {
                DiagnosticFrogportBlockEntity controller = controller(probe.controller());
                if (controller != null) controller.recordDiagnosticResult("recovery_ok", probe.address());
                if (controller != null) restore(controller, fault, now);
            }
            return;
        }

        // A healthy, on-time normal probe also proves an address that previously did not exist now
        // has a receiver again.
        DiagnosticFrogportBlockEntity controller = controller(probe.controller());
        if (controller != null) controller.recordDiagnosticResult("ok", probe.address());
        clearBadAddress(level, probe.controller(), probe.address(), now);
    }

    /**
     * Player intervention explicitly invalidates the probe. The copied stack they receive is
     * rewritten to the diagnostic private address, so putting it back merely returns it for disposal.
     */
    public static void playerRemovedPing(net.minecraft.server.level.ServerPlayer player, ItemStack stack) {
        PingPackageData.Data data = PingPackageData.read(stack);
        if (data == null) return;
        Probe probe = PROBES.remove(data.probeId());
        if (probe != null) {
            RuntimeState runtime = RUNTIME.computeIfAbsent(probe.controller(), ignored -> new RuntimeState());
            runtime.nextScanTick = player.level().getGameTime() + 20;
            Fault fault = FAULTS.get(new FaultKey(probe.controller(), probe.address()));
            if (fault != null) fault.nextRetryTick = player.level().getGameTime() + RETRY_INTERVAL;
            DiagnosticFrogportBlockEntity controller = controller(probe.controller());
            if (controller != null) controller.recordDiagnosticResult("skipped", probe.address());
        }
        PingPackageData.invalidateForPlayer(stack);
    }

    /** An ordinary package reached the diagnostic fallback because Create had no matching route. */
    public static void unroutableCaptured(DiagnosticFrogportBlockEntity controller, ItemStack stack) {
        if (controller == null || !(controller.getLevel() instanceof ServerLevel level)
                || stack == null || stack.isEmpty() || !PackageItem.isPackage(stack)) return;
        register(controller);
        String address = PackageItem.getAddress(stack);
        if (address == null || address.isBlank()) address = "<blank>";
        ControllerKey key = new ControllerKey(level.dimension(), controller.getBlockPos());
        AddressKey addressKey = new AddressKey(level.dimension(), address);
        BAD_ADDRESSES.computeIfAbsent(addressKey, ignored -> new LinkedHashSet<>())
                .add(key);
        controller.rememberBadAddress(address, ADDRESS_NETWORKS.containsKey(addressKey));
        controller.recordDiagnosticResult("no_route", address);
        EventRegistry.get(level.getServer()).raise(EventRegistry.Severity.ERROR,
                EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", sourceId(level, controller.getBlockPos(), address),
                address, controller.createFrequency(), null, System.currentTimeMillis());
    }

    private static void clearBadAddress(ServerLevel level, ControllerKey controller, String address, long now) {
        AddressKey key = new AddressKey(level.dimension(), address);
        Set<ControllerKey> owners = BAD_ADDRESSES.get(key);
        if (owners != null) {
            owners.remove(controller);
            if (owners.isEmpty()) BAD_ADDRESSES.remove(key);
        }
        DiagnosticFrogportBlockEntity loaded = controller(controller);
        if (loaded != null) loaded.clearBadAddress(address);
        EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain",
                sourceId(level, controller.pos(), address), System.currentTimeMillis());
    }

    private static void restore(DiagnosticFrogportBlockEntity controller, Fault fault, long now) {
        if (!(controller.getLevel() instanceof ServerLevel level)) return;
        FAULTS.remove(fault.key);
        thawTargets(level, fault);
        BlockPos cachePos = fault.activeCachePos;
        if (cachePos != null && level.getBlockEntity(cachePos) instanceof CacheFrogportBlockEntity cache
                && fault.key.address().equals(cache.takeoverAddress())) {
            cache.setTakeoverAddress("");
        }
        if (FULL_CACHES.remove(fault.key)) {
            EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                    sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache",
                    System.currentTimeMillis());
        }
        EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_PING_TIMEOUT, "chain",
                sourceId(level, controller.getBlockPos(), fault.key.address()), System.currentTimeMillis());
        controller.recordDiagnosticResult("recovered", fault.key.address());
    }

    /** Used by brass signal lamps wired to Factory Gauges. */
    public static LampState lampState(Level level, String address) {
        if (level == null || address == null || address.isBlank()) return null;
        AddressKey key = new AddressKey(level.dimension(), address);
        Set<ControllerKey> badOwners = BAD_ADDRESSES.get(key);
        if (badOwners != null && !badOwners.isEmpty()) {
            if (level instanceof ServerLevel serverLevel && level.getServer() != null) {
                EventRegistry registry = EventRegistry.get(level.getServer());
                List<EventRegistry.Record> active = new ArrayList<>();
                for (ControllerKey owner : badOwners) {
                    registry.active(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain",
                                    sourceId(serverLevel, owner.pos(), address))
                            .ifPresent(active::add);
                }
                LampState alarmState = SignalPanelBlockEntity.eventLevel(active);
                if (alarmState != null) return alarmState;
            }
            return LampState.FATAL;
        }
        boolean cacheFull = FULL_CACHES.stream().anyMatch(f ->
                f.controller().dimension().equals(level.dimension()) && f.address().equals(address));
        if (cacheFull) return LampState.FATAL;
        boolean fault = FAULTS.keySet().stream().anyMatch(f ->
                f.controller().dimension().equals(level.dimension()) && f.address().equals(address));
        return fault ? LampState.WARN_URGENT : null;
    }

    /** Reset runtime interception cleanly on server stop rather than leaving caches/frozen filters behind. */
    public static void stop() {
        for (Fault fault : new ArrayList<>(FAULTS.values())) {
            DiagnosticFrogportBlockEntity controller = controller(fault.key.controller());
            if (controller != null && controller.getLevel() instanceof ServerLevel level) {
                for (BlockPos pos : fault.targets) {
                    if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog && frog.target != null) {
                        frog.target.deregister(frog, level, pos);
                    }
                    FROZEN.remove(new PortKey(level.dimension(), pos));
                    if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog && frog.target != null) {
                        frog.target.register(frog, level, pos);
                    }
                }
                BlockPos cachePos = fault.activeCachePos;
                if (cachePos != null && level.getBlockEntity(cachePos) instanceof CacheFrogportBlockEntity cache
                        && fault.key.address().equals(cache.takeoverAddress())) {
                    cache.setTakeoverAddress("");
                }
            }
        }
        PROBES.clear();
        FAULTS.clear();
        FROZEN.clear();
        BAD_ADDRESSES.clear();
        FULL_CACHES.clear();
        ADDRESS_NETWORKS.clear();
        GRAPH_CACHE.clear();
        KNOWN_ADDRESSES.clear();
        LATENCIES.clear();
        RUNTIME.clear();
        DIAGNOSTICS.clear();
        CACHES.clear();
    }

    public static void forget(Level level) {
        if (level == null) return;
        DIAGNOSTICS.removeIf(be -> be.getLevel() == level);
        CACHES.removeIf(be -> be.getLevel() == level);
        RUNTIME.keySet().removeIf(key -> key.dimension().equals(level.dimension()));
        PROBES.values().removeIf(p -> p.controller().dimension().equals(level.dimension()));
        FAULTS.keySet().removeIf(k -> k.controller().dimension().equals(level.dimension()));
        FROZEN.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        BAD_ADDRESSES.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        FULL_CACHES.removeIf(k -> k.controller().dimension().equals(level.dimension()));
        ADDRESS_NETWORKS.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        GRAPH_CACHE.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        KNOWN_ADDRESSES.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        LATENCIES.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
    }

    private static int probeCount(ControllerKey key) {
        return (int) PROBES.values().stream().filter(p -> p.controller().equals(key)).count();
    }

    private static int normalProbeCount(ControllerKey key) {
        return (int) PROBES.values().stream()
                .filter(p -> p.controller().equals(key) && !p.recovery()).count();
    }

    private static boolean hasNormalProbe(ControllerKey key, String address) {
        return PROBES.values().stream().anyMatch(p -> p.controller().equals(key)
                && !p.recovery() && p.address().equals(address));
    }

    private static Probe oldestNormalProbe(ControllerKey key) {
        return PROBES.values().stream()
                .filter(p -> p.controller().equals(key) && !p.recovery())
                .min(Comparator.comparingLong(Probe::sentTick))
                .orElse(null);
    }

    private static Probe activeRecoveryProbe(ControllerKey key) {
        return PROBES.values().stream()
                .filter(p -> p.controller().equals(key) && p.recovery())
                .min(Comparator.comparingLong(Probe::sentTick))
                .orElse(null);
    }

    private static boolean isLeader(DiagnosticFrogportBlockEntity controller, Graph graph) {
        return graph.diagnostics().stream()
                .min(Comparator.comparingLong(be -> be.getBlockPos().asLong()))
                .map(be -> be == controller)
                .orElse(true);
    }

    private static DiagnosticFrogportBlockEntity controller(ControllerKey key) {
        for (DiagnosticFrogportBlockEntity be : DIAGNOSTICS) {
            if (be.getLevel() != null && be.getLevel().dimension().equals(key.dimension())
                    && be.getBlockPos().equals(key.pos())) return be;
        }
        return null;
    }

    private static Map<String, List<FrogportBlockEntity>> receiverAddresses(Graph graph) {
        Map<String, List<FrogportBlockEntity>> out = new LinkedHashMap<>();
        for (FrogportBlockEntity frog : graph.frogports()) {
            if (frog instanceof DiagnosticFrogportBlockEntity || frog instanceof CacheFrogportBlockEntity) continue;
            String address = frog.addressFilter;
            if (!frog.acceptsPackages || address == null || address.isBlank() || "*".equals(address)
                    || isDiagnosticAddress(address) || isRecoveryAddress(address)) continue;
            out.computeIfAbsent(address, ignored -> new ArrayList<>()).add(frog);
        }
        return out;
    }

    /** Cached traversal of the connected Create chain graph starting at this Frogport's target. */
    private static Graph graph(DiagnosticFrogportBlockEntity controller) {
        if (controller == null || controller.getLevel() == null || controller.target == null) return Graph.EMPTY;
        ControllerKey key = new ControllerKey(controller.getLevel().dimension(), controller.getBlockPos());
        long now = controller.getLevel().getGameTime();
        CachedGraph cached = GRAPH_CACHE.get(key);
        if (cached != null && now >= cached.builtTick() && now - cached.builtTick() <= GRAPH_CACHE_TICKS) {
            return cached.graph();
        }
        Graph built = buildGraph(controller);
        GRAPH_CACHE.put(key, new CachedGraph(now, built));
        return built;
    }

    private static Graph buildGraph(DiagnosticFrogportBlockEntity controller) {
        if (controller.getLevel() == null || controller.target == null) return Graph.EMPTY;
        if (!(controller.target.be(controller.getLevel(), controller.getBlockPos()) instanceof ChainConveyorBlockEntity start)) {
            return Graph.EMPTY;
        }

        LinkedHashMap<BlockPos, ChainConveyorBlockEntity> conveyors = new LinkedHashMap<>();
        ArrayDeque<ChainConveyorBlockEntity> queue = new ArrayDeque<>();
        conveyors.put(start.getBlockPos(), start);
        queue.add(start);

        while (!queue.isEmpty() && conveyors.size() < MAX_GRAPH_CONVEYORS) {
            ChainConveyorBlockEntity current = queue.removeFirst();
            for (BlockPos connection : current.connections) {
                BlockPos neighbourPos = current.getBlockPos().offset(connection);
                if (conveyors.containsKey(neighbourPos)) continue;
                if (controller.getLevel().getBlockEntity(neighbourPos) instanceof ChainConveyorBlockEntity neighbour) {
                    conveyors.put(neighbourPos, neighbour);
                    queue.addLast(neighbour);
                }
            }
        }

        LinkedHashMap<BlockPos, FrogportBlockEntity> frogs = new LinkedHashMap<>();
        for (ChainConveyorBlockEntity conveyor : conveyors.values()) {
            collectPorts(controller.getLevel(), conveyor, conveyor.loopPorts.keySet(), frogs);
            collectPorts(controller.getLevel(), conveyor, conveyor.travelPorts.keySet(), frogs);
        }

        List<DiagnosticFrogportBlockEntity> diagnostics = frogs.values().stream()
                .filter(DiagnosticFrogportBlockEntity.class::isInstance)
                .map(DiagnosticFrogportBlockEntity.class::cast).toList();
        List<CacheFrogportBlockEntity> caches = frogs.values().stream()
                .filter(CacheFrogportBlockEntity.class::isInstance)
                .map(CacheFrogportBlockEntity.class::cast).toList();
        return new Graph(List.copyOf(conveyors.values()), List.copyOf(frogs.values()), diagnostics, caches);
    }

    private static void collectPorts(Level level, ChainConveyorBlockEntity conveyor,
                                     Collection<BlockPos> relativePorts,
                                     Map<BlockPos, FrogportBlockEntity> out) {
        for (BlockPos relative : relativePorts) {
            BlockPos pos = conveyor.getBlockPos().offset(relative);
            if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog) out.put(pos, frog);
        }
    }

    public static void invalidateGraph(Level level) {
        if (level == null) return;
        GRAPH_CACHE.keySet().removeIf(key -> key.dimension().equals(level.dimension()));
    }

    private static void recordLatency(ServerLevel level, String address, long observed, long expected, long now) {
        if (level == null || address == null || address.isBlank()) return;
        LATENCIES.computeIfAbsent(new AddressKey(level.dimension(), address), ignored -> new LatencyStats())
                .add(observed, expected, now);
    }

    public static List<AddressHealth> addressHealth() {
        Set<AddressKey> keys = new LinkedHashSet<>();
        for (Map.Entry<ControllerKey, Set<String>> row : KNOWN_ADDRESSES.entrySet()) {
            for (String address : row.getValue()) keys.add(new AddressKey(row.getKey().dimension(), address));
        }
        keys.addAll(LATENCIES.keySet());
        keys.addAll(BAD_ADDRESSES.keySet());
        List<AddressHealth> out = new ArrayList<>();
        for (AddressKey key : keys) {
            boolean fault = BAD_ADDRESSES.containsKey(key) || FAULTS.keySet().stream().anyMatch(f ->
                    f.controller().dimension().equals(key.dimension()) && f.address().equals(key.address()));
            LatencyStats stats = LATENCIES.get(key);
            Health health = fault ? Health.FAULT
                    : stats == null || stats.samples == 0 ? Health.UNKNOWN
                    : stats.degraded() ? Health.DEGRADED : Health.HEALTHY;
            out.add(new AddressHealth(key.dimension().location().toString(), key.address(), health,
                    stats == null ? -1 : stats.ewmaTicks,
                    stats == null ? -1 : stats.ewmaExpectedTicks,
                    stats == null ? 0 : stats.samples));
        }
        out.sort(Comparator.comparing(AddressHealth::dimension).thenComparing(AddressHealth::address));
        return List.copyOf(out);
    }

    public static HealthSnapshot healthSnapshot() {
        List<AddressHealth> addresses = addressHealth();
        int healthy = (int) addresses.stream().filter(a -> a.health() == Health.HEALTHY).count();
        int degraded = (int) addresses.stream().filter(a -> a.health() == Health.DEGRADED).count();
        int faults = (int) addresses.stream().filter(a -> a.health() == Health.FAULT).count();
        int unknown = (int) addresses.stream().filter(a -> a.health() == Health.UNKNOWN).count();
        int availableCaches = (int) CACHES.stream().filter(CacheFrogportBlockEntity::availableForLease).count();
        int takeovers = (int) CACHES.stream().filter(c -> !c.takeoverAddress().isBlank()).count();
        return new HealthSnapshot(DIAGNOSTICS.size(), CACHES.size(), availableCaches, PROBES.size(),
                addresses.size(), healthy, degraded, faults, unknown,
                takeovers, FULL_CACHES.size(), FROZEN.size());
    }

    private static String sourceId(ServerLevel level, BlockPos controller, String address) {
        return EventRegistry.blockSource(level, controller) + "/a" + Integer.toUnsignedString(address.hashCode(), 36);
    }

    private record ControllerKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private record FaultKey(ControllerKey controller, String address) {
    }

    private record PortKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private record AddressKey(ResourceKey<Level> dimension, String address) {
    }

    private record Probe(UUID id, ControllerKey controller, String address, BlockPos targetPos,
                         long sentTick, long deadlineTick, long expectedTicks, boolean recovery,
                         int planIndex, int planTotal) {
    }

    private static final class RuntimeState {
        long nextScanTick;
        int cursor;
    }

    private static final class Fault {
        final FaultKey key;
        final Set<BlockPos> targets;
        final Set<BlockPos> passed = new LinkedHashSet<>();
        BlockPos activeCachePos;
        long nextRetryTick;

        Fault(FaultKey key, Set<BlockPos> targets, BlockPos cachePos, long nextRetryTick) {
            this.key = key;
            this.targets = targets;
            this.activeCachePos = cachePos;
            this.nextRetryTick = nextRetryTick;
        }

        boolean mitigated() {
            return activeCachePos != null;
        }
    }

    private record CachedGraph(long builtTick, Graph graph) {
    }

    private static final class LatencyStats {
        double ewmaTicks;
        double ewmaExpectedTicks;
        long lastTicks;
        long lastSuccessTick;
        int samples;

        void add(long observed, long expected, long now) {
            double alpha = samples == 0 ? 1.0 : 0.25;
            ewmaTicks = samples == 0 ? observed : ewmaTicks * (1.0 - alpha) + observed * alpha;
            ewmaExpectedTicks = samples == 0 ? expected : ewmaExpectedTicks * (1.0 - alpha) + expected * alpha;
            lastTicks = observed;
            lastSuccessTick = now;
            samples++;
        }

        boolean degraded() {
            return samples >= 3 && ewmaExpectedTicks > 0
                    && ewmaTicks > Math.max(ewmaExpectedTicks * 1.75, ewmaExpectedTicks + 40.0);
        }
    }

    public enum Health { HEALTHY, DEGRADED, FAULT, UNKNOWN }

    public record AddressHealth(String dimension, String address, Health health,
                                double latencyTicks, double expectedTicks, int samples) {
    }

    public record HealthSnapshot(int controllers, int caches, int availableCaches, int inFlightProbes,
                                 int addresses, int healthy, int degraded, int faults, int unknown,
                                 int cacheTakeovers, int fullCaches, int frozenPorts) {
    }

    private record Graph(List<ChainConveyorBlockEntity> conveyors, List<FrogportBlockEntity> frogports,
                         List<DiagnosticFrogportBlockEntity> diagnostics, List<CacheFrogportBlockEntity> caches) {
        static final Graph EMPTY = new Graph(List.of(), List.of(), List.of(), List.of());
    }

    private ChainDiagnostics() {
    }
}
