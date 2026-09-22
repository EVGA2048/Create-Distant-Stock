package dev.distantstock.diagnostics;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRoutingTable;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.distantstock.block.LampState;
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
        if (be != null) DIAGNOSTICS.add(be);
    }

    public static void unregister(DiagnosticFrogportBlockEntity be) {
        DIAGNOSTICS.remove(be);
    }

    public static void register(CacheFrogportBlockEntity be) {
        if (be != null) CACHES.add(be);
    }

    public static void unregister(CacheFrogportBlockEntity be) {
        CACHES.remove(be);
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
        // competing with the restored real Frogports. Its 18 cached parcels stay put for slow replay.
        for (CacheFrogportBlockEntity cache : new ArrayList<>(CACHES)) {
            if (cache.getLevel() == null || cache.takeoverAddress().isBlank()) continue;
            boolean owned = FAULTS.values().stream().anyMatch(fault -> fault.cachePos != null
                    && fault.key.controller().dimension().equals(cache.getLevel().dimension())
                    && fault.cachePos.equals(cache.getBlockPos())
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
                continue;
            }
            if (!isLeader(controller, graph)) {
                controller.setDiagnosticPlan("standby", "", 0, 0, 0, 0, 0, 0);
                controller.setDiagnosticHealth(0, false);
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
                            && fault.mitigated && fault.cachePos != null);
            controller.setDiagnosticHealth(liveFaults, cacheMitigating);

            // One diagnostic Frogport runs one probe at a time. This makes the cycle deterministic
            // and prevents a recovery probe and a normal health probe from competing for its mouth.
            Probe active = activeProbe(key);
            if (active != null) {
                Fault fault = FAULTS.get(new FaultKey(key, active.address()));
                int targets = active.recovery() && fault != null
                        ? Math.max(1, fault.targets.size())
                        : receiverAddresses(graph).getOrDefault(active.address(), List.of()).size();
                controller.setDiagnosticPlan(active.recovery() ? "recovery" : "waiting",
                        active.address(), active.planIndex(), active.planTotal(), targets,
                        0, active.sentTick(), active.deadlineTick());
                continue;
            }

            Map<String, List<FrogportBlockEntity>> addresses = receiverAddresses(graph);
            List<String> candidates = addresses.keySet().stream()
                    .filter(address -> !FAULTS.containsKey(new FaultKey(key, address)))
                    .sorted()
                    .toList();

            if (candidates.isEmpty()) {
                runtime.nextScanTick = level.getGameTime() + SCAN_INTERVAL;
                controller.setDiagnosticPlan("idle", "", 0, 0, 0,
                        runtime.nextScanTick, 0, 0);
                continue;
            }

            runtime.cursor = Math.floorMod(runtime.cursor, candidates.size());
            int planIndex = runtime.cursor + 1;
            String address = candidates.get(runtime.cursor);
            List<FrogportBlockEntity> addressTargets = addresses.get(address);

            if (level.getGameTime() < runtime.nextScanTick) {
                controller.setDiagnosticPlan("scheduled", address, planIndex, candidates.size(),
                        addressTargets.size(), runtime.nextScanTick, 0, 0);
                continue;
            }

            runtime.cursor++;
            FrogportBlockEntity target = addressTargets.get(0);
            if (sendProbe(controller, key, address, target.getBlockPos(), address,
                    false, level.getGameTime(), planIndex, candidates.size(), addressTargets.size())) {
                runtime.nextScanTick = level.getGameTime() + SCAN_INTERVAL;
            } else {
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

            // Migration/safety net: old worlds may contain quarantined packages from before the
            // durable unresolved-address set existed. Seeing one still creates the durable fault.
            for (int slot = 0; slot < diagnostic.inventory.getSlots(); slot++) {
                ItemStack stack = diagnostic.inventory.getStackInSlot(slot);
                if (stack.isEmpty() || !PackageItem.isPackage(stack) || PingPackageData.isPing(stack)) continue;
                String address = PackageItem.getAddress(stack);
                if (address == null || address.isBlank()) address = "<blank>";
                AddressKey key = new AddressKey(level.dimension(), address);
                diagnostic.rememberBadAddress(address, ADDRESS_NETWORKS.containsKey(key));
            }

            for (String address : new ArrayList<>(diagnostic.unresolvedBadAddresses())) {
                AddressKey key = new AddressKey(level.dimension(), address);
                boolean stillConfigured = ADDRESS_NETWORKS.containsKey(key);
                if (stillConfigured) diagnostic.noteBadAddressGaugeBacked(address);

                BAD_ADDRESSES.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(controller);
                String source = sourceId(level, diagnostic.getBlockPos(), address);
                EventRegistry registry = EventRegistry.get(server);
                if (registry.active(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", source).isEmpty()) {
                    registry.raise(EventRegistry.Severity.ERROR, EventRegistry.Codes.CHAIN_NO_ROUTE,
                            "chain", source, address, diagnostic.createFrequency(), null,
                            level.getGameTime());
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

        long now = server.overworld().getGameTime();
        for (EventRegistry.Record record : new ArrayList<>(registry.active())) {
            if (!"chain".equals(record.sourceType())) continue;
            if (EventRegistry.Codes.CHAIN_PING_TIMEOUT.equals(record.code())
                    && !liveTimeouts.contains(record.sourceId())) {
                registry.clear(record.code(), record.sourceType(), record.sourceId(), now);
            } else if (EventRegistry.Codes.CHAIN_CACHE_FULL.equals(record.code())
                    && !liveFullCaches.contains(record.sourceId())) {
                registry.clear(record.code(), record.sourceType(), record.sourceId(), now);
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
                .filter(candidate -> candidate.takeoverAddress().isBlank()
                        || candidate.takeoverAddress().equals(probe.address()))
                .min(Comparator.comparingLong(be -> be.getBlockPos().asLong()))
                .orElse(null);

        Fault fault = new Fault(key, new LinkedHashSet<>(), cache == null ? null : cache.getBlockPos(),
                cache != null, now + RETRY_INTERVAL);
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
                probe.address(), controller.createFrequency(), null, now);
    }

    private static void maintainFaults(DiagnosticFrogportBlockEntity controller, Graph graph,
                                       ControllerKey controllerKey, long now) {
        List<Fault> faults = FAULTS.values().stream()
                .filter(fault -> fault.key.controller().equals(controllerKey))
                .toList();
        for (Fault fault : faults) {
            if (!(controller.getLevel() instanceof ServerLevel level)) continue;

            if (fault.cachePos != null && level.isLoaded(fault.cachePos)
                    && level.getBlockEntity(fault.cachePos) instanceof CacheFrogportBlockEntity cache) {
                if (cache.isBackedUp()) {
                    if (FULL_CACHES.add(fault.key)) {
                        EventRegistry.get(level.getServer()).raise(EventRegistry.Severity.ERROR,
                                EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                                sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache",
                                fault.key.address(), controller.createFrequency(), null, now);
                    }
                } else if (FULL_CACHES.remove(fault.key)) {
                    EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                            sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache", now);
                }
            }

            if (hasRecoveryProbe(controllerKey, fault.key.address())) continue;
            if (now < fault.nextRetryTick) continue;

            if (fault.mitigated) {
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
                if (!sendProbe(controller, controllerKey, fault.key.address(), target,
                        recoveryAddress(target), true, now,
                        recoveryIndex, Math.max(1, fault.targets.size()), Math.max(1, fault.targets.size()))) {
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
                if (!sendProbe(controller, controllerKey, fault.key.address(), target,
                        fault.key.address(), true, now,
                        1, Math.max(1, targets.size()), targets.size())) {
                    fault.nextRetryTick = now + 10;
                }
            }
        }
    }

    private static boolean sendProbe(DiagnosticFrogportBlockEntity controller, ControllerKey key,
                                     String originalAddress, BlockPos targetPos, String routingAddress,
                                     boolean recovery, long now,
                                     int planIndex, int planTotal, int targetCount) {
        UUID id = UUID.randomUUID();
        long timeout = estimatedProbeTimeoutTicks(controller, targetPos);
        ItemStack ping = PingPackageData.create(id, key.dimension().location().toString(), key.pos(),
                targetPos, originalAddress, routingAddress, now, now + timeout);
        if (!controller.sendProbe(ping)) return false;
        PROBES.put(id, new Probe(id, key, originalAddress, targetPos, now, now + timeout,
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
        Graph graph = graph(controller);
        ChainConveyorBlockEntity source = owningConveyor(graph, controller.getBlockPos());
        ChainConveyorBlockEntity target = owningConveyor(graph, targetPos);
        if (source == null || target == null) return FALLBACK_PROBE_TIMEOUT;

        double travelTicks = shortestTravelTicks(graph, source, target);
        if (!Double.isFinite(travelTicks)) return FALLBACK_PROBE_TIMEOUT;

        // Local sprocket arcs/port positions are not part of ConnectionStats. Budget eight blocks
        // at the slower endpoint speed, then add 50% route/tick-phase headroom plus three seconds.
        double sourceBpt = Math.abs(source.getSpeed()) / 360.0;
        double targetBpt = Math.abs(target.getSpeed()) / 360.0;
        double endpointBpt;
        if (source == target) {
            endpointBpt = sourceBpt;
        } else if (sourceBpt < 1.0e-4 || targetBpt < 1.0e-4) {
            endpointBpt = Math.max(sourceBpt, targetBpt);
        } else {
            endpointBpt = Math.min(sourceBpt, targetBpt);
        }
        if (endpointBpt < 1.0e-4) return FALLBACK_PROBE_TIMEOUT;
        double localTicks = 8.0 / endpointBpt;
        long estimate = (long) Math.ceil((travelTicks + localTicks) * 1.5 + 60.0);
        return Math.max(100, Math.min(2400, estimate));
    }

    private static double shortestTravelTicks(Graph graph, ChainConveyorBlockEntity source,
                                              ChainConveyorBlockEntity target) {
        if (source == target) return 0;
        record Node(BlockPos pos, double ticks) {}
        Map<BlockPos, ChainConveyorBlockEntity> byPos = new HashMap<>();
        for (ChainConveyorBlockEntity conveyor : graph.conveyors()) {
            byPos.put(conveyor.getBlockPos(), conveyor);
        }

        Map<BlockPos, Double> best = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::ticks));
        best.put(source.getBlockPos(), 0.0);
        queue.add(new Node(source.getBlockPos(), 0.0));

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.ticks() > best.getOrDefault(node.pos(), Double.POSITIVE_INFINITY)) continue;
            if (node.pos().equals(target.getBlockPos())) return node.ticks();
            ChainConveyorBlockEntity conveyor = byPos.get(node.pos());
            if (conveyor == null) continue;
            double blocksPerTick = Math.abs(conveyor.getSpeed()) / 360.0;
            if (blocksPerTick < 1.0e-4) continue;
            conveyor.prepareStats();
            for (BlockPos relative : conveyor.connections) {
                BlockPos neighbour = conveyor.getBlockPos().offset(relative);
                if (!byPos.containsKey(neighbour)) continue;
                var stats = conveyor.connectionStats.get(relative);
                double edge = stats == null
                        ? Math.sqrt(relative.distSqr(BlockPos.ZERO))
                        : stats.chainLength();
                double candidate = node.ticks() + edge / blocksPerTick;
                if (candidate >= best.getOrDefault(neighbour, Double.POSITIVE_INFINITY)) continue;
                best.put(neighbour, candidate);
                queue.add(new Node(neighbour, candidate));
            }
        }
        return Double.POSITIVE_INFINITY;
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

        Fault fault = FAULTS.get(new FaultKey(probe.controller(), probe.address()));
        if (probe.recovery() && fault != null) {
            if (fault.mitigated && !frog.getBlockPos().equals(probe.targetPos())) {
                fault.nextRetryTick = now + 10;
                return;
            }
            if (fault.mitigated) {
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
                address, controller.createFrequency(), null, level.getGameTime());
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
                sourceId(level, controller.pos(), address), now);
    }

    private static void restore(DiagnosticFrogportBlockEntity controller, Fault fault, long now) {
        if (!(controller.getLevel() instanceof ServerLevel level)) return;
        FAULTS.remove(fault.key);
        for (BlockPos pos : fault.targets) {
            if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog && frog.target != null) {
                // Deregister the private recovery address while the frozen mapping still exists.
                frog.target.deregister(frog, level, pos);
            }
            FROZEN.remove(new PortKey(level.dimension(), pos));
            if (level.getBlockEntity(pos) instanceof FrogportBlockEntity frog) {
                if (frog.target != null) frog.target.register(frog, level, pos);
            }
        }
        if (fault.cachePos != null && level.getBlockEntity(fault.cachePos) instanceof CacheFrogportBlockEntity cache) {
            cache.setTakeoverAddress("");
        }
        if (FULL_CACHES.remove(fault.key)) {
            EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_CACHE_FULL, "chain",
                    sourceId(level, controller.getBlockPos(), fault.key.address()) + "/cache", now);
        }
        EventRegistry.get(level.getServer()).clear(EventRegistry.Codes.CHAIN_PING_TIMEOUT, "chain",
                sourceId(level, controller.getBlockPos(), fault.key.address()), now);
        controller.recordDiagnosticResult("recovered", fault.key.address());
    }

    /** Used by brass signal lamps wired to Factory Gauges. */
    public static LampState lampState(Level level, String address) {
        if (level == null || address == null || address.isBlank()) return null;
        AddressKey key = new AddressKey(level.dimension(), address);
        if (BAD_ADDRESSES.containsKey(key)) return LampState.FATAL;
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
                if (fault.cachePos != null && level.getBlockEntity(fault.cachePos) instanceof CacheFrogportBlockEntity cache) {
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
    }

    private static boolean hasNormalProbe(ControllerKey key) {
        return PROBES.values().stream().anyMatch(p -> p.controller().equals(key) && !p.recovery());
    }

    private static Probe activeProbe(ControllerKey key) {
        return PROBES.values().stream()
                .filter(p -> p.controller().equals(key))
                .min(Comparator.comparingLong(Probe::sentTick))
                .orElse(null);
    }

    private static boolean hasRecoveryProbe(ControllerKey key, String address) {
        return PROBES.values().stream().anyMatch(p -> p.controller().equals(key)
                && p.recovery() && p.address().equals(address));
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

    /** Traverse the actual connected Create chain graph starting at this Frogport's target. */
    private static Graph graph(DiagnosticFrogportBlockEntity controller) {
        if (controller.getLevel() == null || controller.target == null) return Graph.EMPTY;
        if (!(controller.target.be(controller.getLevel(), controller.getBlockPos()) instanceof ChainConveyorBlockEntity start)) {
            return Graph.EMPTY;
        }

        LinkedHashMap<BlockPos, ChainConveyorBlockEntity> conveyors = new LinkedHashMap<>();
        ArrayDeque<ChainConveyorBlockEntity> queue = new ArrayDeque<>();
        conveyors.put(start.getBlockPos(), start);
        queue.add(start);

        while (!queue.isEmpty() && conveyors.size() < 512) {
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
                         long sentTick, long deadlineTick, boolean recovery,
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
        final BlockPos cachePos;
        final boolean mitigated;
        long nextRetryTick;

        Fault(FaultKey key, Set<BlockPos> targets, BlockPos cachePos, boolean mitigated, long nextRetryTick) {
            this.key = key;
            this.targets = targets;
            this.cachePos = cachePos;
            this.mitigated = mitigated;
            this.nextRetryTick = nextRetryTick;
        }
    }

    private record Graph(List<ChainConveyorBlockEntity> conveyors, List<FrogportBlockEntity> frogports,
                         List<DiagnosticFrogportBlockEntity> diagnostics, List<CacheFrogportBlockEntity> caches) {
        static final Graph EMPTY = new Graph(List.of(), List.of(), List.of(), List.of());
    }

    private ChainDiagnostics() {
    }
}
