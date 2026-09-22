package dev.distantstock;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRoutingTable;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.distantstock.block.LampState;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.createmod.catnip.animation.LerpedFloat;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class ChainDiagnosticsGameTests {

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void cacheRedstoneModeReleasesExactlyOnePerPulse(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 7));
        BlockPos pulsePos = cachePos.above();

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        h.assertTrue(chain != null && cache != null, "redstone replay fixture did not create block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 2000;
        h.onEachTick(() -> chain.setSpeed(128));
        attachLoopPort(level, chainPos, cachePos, cache, 180);
        cache.setReleaseDelaySeconds(0);

        for (int slot = 0; slot < 3; slot++) {
            ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            PackageItem.addAddress(parcel, "REDSTONE-REPLAY");
            cache.inventory.setStackInSlot(slot, parcel);
        }
        h.assertTrue(cache.cachedCount() == 3, "cache did not start with three parcels");

        level.setBlock(pulsePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.runAfterDelay(4, () -> {
            h.assertTrue(cache.cachedCount() == 2,
                    "first rising edge did not release exactly one parcel");

            // Keep the line high through an entire Frogport animation. No second parcel may leave.
            h.runAfterDelay(40, () -> {
                h.assertTrue(cache.cachedCount() == 2,
                        "sustained high redstone level released more than one parcel");
                level.setBlock(pulsePos, Blocks.AIR.defaultBlockState(), 3);

                h.runAfterDelay(3, () -> {
                    level.setBlock(pulsePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    h.runAfterDelay(4, () -> {
                        h.assertTrue(cache.cachedCount() == 1,
                                "second rising edge did not release exactly one additional parcel");
                        h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void diagnosticQuarantineCanOnlyBeExtractedFromBelow(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(pos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(diagnostic != null, "diagnostic Frogport did not create its block entity");

        ItemStack bad = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(bad, "MISSPELLED-ADDRESS");
        diagnostic.inventory.setStackInSlot(0, bad);

        var below = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var above = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        h.assertTrue(below != null, "diagnostic Frogport did not expose its quarantine output below");
        h.assertTrue(above == null, "diagnostic Frogport exposed automation on a non-bottom face");

        ItemStack extracted = below.extractItem(0, 1, false);
        h.assertTrue(!extracted.isEmpty() && diagnostic.inventory.getStackInSlot(0).isEmpty(),
                "bottom logistics interaction could not remove the quarantined parcel");
        ItemStack rejected = below.insertItem(0, bad.copy(), false);
        h.assertTrue(!rejected.isEmpty(), "bottom quarantine interface accepted an inserted parcel");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 360)
    public static void fullCacheRaisesErrorAndFatalAndon(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 7));
        BlockPos receiverPos = h.absolutePos(new BlockPos(3, 2, 5));
        String address = "FULL-CACHE-LINE";

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(receiverPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        FrogportBlockEntity receiver = (FrogportBlockEntity) level.getBlockEntity(receiverPos);
        h.assertTrue(chain != null && diagnostic != null && cache != null && receiver != null,
                "cache-full fixture did not create block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 1000;
        h.onEachTick(() -> chain.setSpeed(128));
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverPos, receiver, 90);
        attachLoopPort(level, chainPos, cachePos, cache, 180);
        receiver.addressFilter = address;
        receiver.acceptsPackages = true;
        receiver.filterChanged();

        // Keep the real receiver busy so the first health probe reaches its dynamic timeout.
        ItemStack busyMarker = new ItemStack(ModItems.PING_PACKAGE.get());
        h.onEachTick(() -> {
            receiver.animatedPackage = busyMarker;
            receiver.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
        });
        ChainDiagnostics.register(diagnostic);
        ChainDiagnostics.register(cache);
        ChainDiagnostics.tick(level.getServer());

        h.runAfterDelay(140, () -> {
            h.assertTrue(address.equals(cache.takeoverAddress()),
                    "fixture never reached cache takeover before testing full capacity");
            for (int slot = 0; slot < 18; slot++) {
                ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
                PackageItem.addAddress(parcel, address);
                cache.inventory.setStackInSlot(slot, parcel);
            }
            h.assertTrue(cache.isBackedUp(), "18 occupied native Frogport slots did not report backed up");
            ChainDiagnostics.tick(level.getServer());

            boolean event = EventRegistry.get(level.getServer()).active().stream()
                    .anyMatch(row -> EventRegistry.Codes.CHAIN_CACHE_FULL.equals(row.code())
                            && address.equals(row.detail()));
            h.assertTrue(event, "full cache did not raise CHAIN_CACHE_FULL");
            h.assertTrue(ChainDiagnostics.lampState(level, address) == LampState.FATAL,
                    "full cache did not elevate the brass Andon state to fatal/red");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void probeTimeoutExpandsWhenChainRunsSlower(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos receiverPos = h.absolutePos(new BlockPos(5, 2, 7));

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(receiverPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        FrogportBlockEntity receiver = (FrogportBlockEntity) level.getBlockEntity(receiverPos);
        h.assertTrue(chain != null && diagnostic != null && receiver != null,
                "dynamic-timeout fixture did not create block entities");
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverPos, receiver, 180);

        chain.setSpeed(128);
        long fast = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, receiverPos);
        chain.setSpeed(16);
        long slow = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, receiverPos);

        h.assertTrue(slow > fast,
                "probe timeout did not expand at lower chain speed: fast=" + fast + " slow=" + slow);
        h.assertTrue(fast >= 100 && slow <= 2400,
                "dynamic probe timeout escaped its safety bounds: fast=" + fast + " slow=" + slow);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void probeTimeoutExpandsWithPhysicalChainDistance(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos aPos = h.absolutePos(new BlockPos(2, 2, 5));
        BlockPos bPos = h.absolutePos(new BlockPos(7, 2, 5));
        BlockPos cPos = h.absolutePos(new BlockPos(12, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 3));
        BlockPos nearPos = h.absolutePos(new BlockPos(7, 2, 3));
        BlockPos farPos = h.absolutePos(new BlockPos(12, 2, 3));

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(aPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(bPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(cPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(nearPos, frogportBlock.defaultBlockState(), 3);
        level.setBlock(farPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity a = (ChainConveyorBlockEntity) level.getBlockEntity(aPos);
        ChainConveyorBlockEntity b = (ChainConveyorBlockEntity) level.getBlockEntity(bPos);
        ChainConveyorBlockEntity c = (ChainConveyorBlockEntity) level.getBlockEntity(cPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        FrogportBlockEntity near = (FrogportBlockEntity) level.getBlockEntity(nearPos);
        FrogportBlockEntity far = (FrogportBlockEntity) level.getBlockEntity(farPos);
        h.assertTrue(a != null && b != null && c != null && diagnostic != null && near != null && far != null,
                "physical-distance timeout fixture did not create block entities");

        a.prepareStats();
        b.prepareStats();
        c.prepareStats();
        a.addConnectionTo(bPos);
        b.addConnectionTo(aPos);
        b.addConnectionTo(cPos);
        c.addConnectionTo(bPos);
        attachLoopPort(level, aPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, bPos, nearPos, near, 0);
        attachLoopPort(level, cPos, farPos, far, 0);
        a.setSpeed(16);
        b.setSpeed(16);
        c.setSpeed(16);

        long nearTimeout = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, nearPos);
        long farTimeout = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, farPos);
        h.assertTrue(farTimeout > nearTimeout,
                "farther receiver did not receive a longer probe deadline: near=" + nearTimeout
                        + " far=" + farTimeout);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void probeTimeoutAccountsForSlowerIntermediateSegment(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos aPos = h.absolutePos(new BlockPos(2, 2, 5));
        BlockPos bPos = h.absolutePos(new BlockPos(7, 2, 5));
        BlockPos cPos = h.absolutePos(new BlockPos(12, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 3));
        BlockPos farPos = h.absolutePos(new BlockPos(12, 2, 3));

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(aPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(bPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(cPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(farPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity a = (ChainConveyorBlockEntity) level.getBlockEntity(aPos);
        ChainConveyorBlockEntity b = (ChainConveyorBlockEntity) level.getBlockEntity(bPos);
        ChainConveyorBlockEntity c = (ChainConveyorBlockEntity) level.getBlockEntity(cPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        FrogportBlockEntity far = (FrogportBlockEntity) level.getBlockEntity(farPos);
        h.assertTrue(a != null && b != null && c != null && diagnostic != null && far != null,
                "segment-speed timeout fixture did not create block entities");

        a.prepareStats();
        b.prepareStats();
        c.prepareStats();
        a.addConnectionTo(bPos);
        b.addConnectionTo(aPos);
        b.addConnectionTo(cPos);
        c.addConnectionTo(bPos);
        attachLoopPort(level, aPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, cPos, farPos, far, 0);

        a.setSpeed(64);
        b.setSpeed(64);
        c.setSpeed(64);
        long uniform = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, farPos);

        // Only the middle conveyor slows down. The deadline must lengthen even though the source
        // and destination speeds stay unchanged.
        b.setSpeed(8);
        long bottleneck = ChainDiagnostics.estimatedProbeTimeoutTicks(diagnostic, farPos);

        h.assertTrue(bottleneck > uniform,
                "slower intermediate chain segment did not lengthen the probe deadline: uniform="
                        + uniform + " bottleneck=" + bottleneck);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void congestedLoopIsolatedCachedProbedAndRecovered(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 7));
        BlockPos receiverAPos = h.absolutePos(new BlockPos(3, 2, 5));
        BlockPos receiverBPos = h.absolutePos(new BlockPos(7, 2, 5));
        String address = "BATCH-WASH-RECEIVE";

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(receiverAPos, frogportBlock.defaultBlockState(), 3);
        level.setBlock(receiverBPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        FrogportBlockEntity receiverA = (FrogportBlockEntity) level.getBlockEntity(receiverAPos);
        FrogportBlockEntity receiverB = (FrogportBlockEntity) level.getBlockEntity(receiverBPos);
        h.assertTrue(chain != null && diagnostic != null && cache != null
                        && receiverA != null && receiverB != null,
                "chain diagnostic fixture failed to create its block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 2000;
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverAPos, receiverA, 90);
        attachLoopPort(level, chainPos, receiverBPos, receiverB, 180);
        attachLoopPort(level, chainPos, cachePos, cache, 270);

        receiverA.addressFilter = address;
        receiverA.acceptsPackages = true;
        receiverA.filterChanged();
        receiverB.addressFilter = address;
        receiverB.acceptsPackages = true;
        receiverB.filterChanged();

        AtomicBoolean blocked = new AtomicBoolean(true);
        ItemStack busyMarker = new ItemStack(ModItems.PING_PACKAGE.get());
        h.onEachTick(() -> {
            chain.setSpeed(128);
            if (blocked.get()) {
                receiverA.animatedPackage = busyMarker;
                receiverB.animatedPackage = busyMarker;
                receiverA.animationProgress.startWithValue(0)
                        .chase(1, .1, LerpedFloat.Chaser.LINEAR);
                receiverB.animationProgress.startWithValue(0)
                        .chase(1, .1, LerpedFloat.Chaser.LINEAR);
            }
        });
        receiverA.animatedPackage = busyMarker;
        receiverB.animatedPackage = busyMarker;
        receiverA.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
        receiverB.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
        h.assertTrue(receiverA.isAnimationInProgress() && receiverB.isAnimationInProgress(),
                "test receivers were not held busy");

        h.assertTrue(((PackagePortTarget.ChainConveyorFrogportTarget) diagnostic.target)
                        .canSupport(diagnostic),
                "Create still rejects the diagnostic Frogport as a chain target");
        h.assertTrue(((PackagePortTarget.ChainConveyorFrogportTarget) cache.target)
                        .canSupport(cache),
                "Create still rejects the cache Frogport as a chain target");

        h.assertTrue(diagnostic.target.export(level, diagnosticPos,
                        new ItemStack(ModItems.PING_PACKAGE.get()), true),
                "diagnostic Frogport cannot inject a probe into the running loop");

        // GameTest places all blocks in one synchronous setup tick; explicit registration removes
        // any ambiguity about BlockEntity.onLoad ordering while still exercising the real runtime
        // registry and probe path from this point onward.
        ChainDiagnostics.register(diagnostic);
        ChainDiagnostics.register(cache);

        // Force the first scan now rather than waiting for the global clock phase.
        ChainDiagnostics.tick(level.getServer());

        h.runAfterDelay(350, () -> {
            String routeDump = chain.routingTable.entriesByDistance.stream()
                    .map(row -> row.port() + "->" + row.nextConnection() + (row.endOfRoute() ? "[end]" : ""))
                    .toList().toString();
            String eventDump = EventRegistry.get(level.getServer()).active().stream()
                    .filter(row -> "chain".equals(row.sourceType()))
                    .map(row -> row.code() + ":" + row.detail())
                    .toList().toString();
            String packageDump = chain.getLoopingPackages().stream()
                    .map(box -> BuiltInRegistries.ITEM.getKey(box.item.getItem()) + "@" + box.chainPosition
                            + ":" + PackageItem.getAddress(box.item))
                    .toList().toString();
            h.assertTrue(EventRegistry.get(level.getServer()).active().stream()
                            .anyMatch(row -> EventRegistry.Codes.CHAIN_PING_TIMEOUT.equals(row.code())
                                    && address.equals(row.detail())),
                    "probe did not reach timeout/fault state; routes=" + routeDump
                            + " packages=" + packageDump + " events=" + eventDump);
            h.assertTrue(address.equals(cache.takeoverAddress()),
                    "ping timeout did not make the cache Frogport take over the congested address; routes="
                            + routeDump + " packages=" + packageDump + " events=" + eventDump);
            h.assertTrue(ChainDiagnostics.recoveryAddress(receiverAPos).equals(receiverA.getFilterString()),
                    "first congested Frogport was not frozen onto a private recovery address");
            h.assertTrue(ChainDiagnostics.recoveryAddress(receiverBPos).equals(receiverB.getFilterString()),
                    "second same-address Frogport was not frozen together");
            h.assertTrue(ChainDiagnostics.lampState(level, address) == LampState.WARN_URGENT,
                    "congested address did not raise the urgent Andon state");

            long publicRoutes = chain.routingTable.entriesByDistance.stream()
                    .filter(ChainConveyorRoutingTable.RoutingTableEntry::endOfRoute)
                    .filter(row -> address.equals(row.port()))
                    .count();
            h.assertTrue(publicRoutes == 1,
                    "frozen receivers left stale public routes; expected only the cache takeover route, got "
                            + publicRoutes);

            // Feed a burst into the still-running loop. They should go to the cache instead of
            // endlessly circling the backed-up real receivers.
            for (int i = 0; i < 4; i++) {
                ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
                PackageItem.addAddress(parcel, address);
                chain.addLoopingPackage(new ChainConveyorPackage(20 + i * 6, parcel));
            }

            h.runAfterDelay(100, () -> {
                h.assertTrue(cache.cachedCount() > 0,
                        "cache Frogport did not absorb the burst while owning the failed address");
                int bufferedBeforeRecovery = cache.cachedCount();

                blocked.set(false);
                receiverA.animationProgress.startWithValue(0).chase(0, .1, LerpedFloat.Chaser.LINEAR);
                receiverB.animationProgress.startWithValue(0).chase(0, .1, LerpedFloat.Chaser.LINEAR);
                receiverA.animatedPackage = ItemStack.EMPTY;
                receiverB.animatedPackage = ItemStack.EMPTY;
                h.assertFalse(receiverA.isAnimationInProgress(), "receiver A remained busy after release");
                h.assertFalse(receiverB.isAnimationInProgress(), "receiver B remained busy after release");

                h.runAfterDelay(220, () -> {
                    h.assertTrue(cache.takeoverAddress().isBlank(),
                            "on-time recovery pings did not release the cache takeover");
                    h.assertTrue(address.equals(receiverA.getFilterString())
                                    && address.equals(receiverB.getFilterString()),
                            "real Frogports did not regain their original shared address");
                    h.assertTrue(EventRegistry.get(level.getServer()).active().stream()
                                    .noneMatch(row -> EventRegistry.Codes.CHAIN_PING_TIMEOUT.equals(row.code())
                                            && address.equals(row.detail())),
                            "CHAIN_PING_TIMEOUT stayed active after recovery");
                    h.assertTrue(cache.cachedCount() < bufferedBeforeRecovery,
                            "cache did not begin rate-limited replay after recovery");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void diagnosticFallbackNeverStealsAValidAddress(GameTestHelper h) {
        ChainConveyorRoutingTable table = new ChainConveyorRoutingTable();
        BlockPos normalExit = new BlockPos(4, 0, 0);
        BlockPos diagnosticExit = new BlockPos(-3, 0, 0);
        table.receivePortInfo("GOOD-LINE", normalExit);
        table.receivePortInfo(ChainDiagnostics.diagnosticAddress(new BlockPos(9, 2, 1)), diagnosticExit);

        ItemStack valid = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(valid, "GOOD-LINE");
        h.assertFalse(ChainDiagnostics.shouldCatchUnroutable(table.entriesByDistance, valid),
                "diagnostic fallback tried to steal a parcel that has a real Frogport route");

        ItemStack bad = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(bad, "MISSPELLED-LINE");
        h.assertTrue(ChainDiagnostics.shouldCatchUnroutable(table.entriesByDistance, bad),
                "missing-address parcel was not eligible for diagnostic fallback");
        h.assertTrue(ChainDiagnostics.diagnosticExit(table.entriesByDistance, bad).equals(diagnosticExit),
                "missing-address parcel did not choose the diagnostic Frogport route");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void playerRemovedPingBecomesStaleReturnProbe(GameTestHelper h) {
        BlockPos controller = new BlockPos(7, 3, 5);
        UUID id = UUID.randomUUID();
        ItemStack ping = PingPackageData.create(id, h.getLevel().dimension().location().toString(),
                controller, new BlockPos(20, 3, 5), "BATCH-WASH-RECEIVE",
                "BATCH-WASH-RECEIVE", 100, 400);

        var before = PingPackageData.read(ping);
        h.assertTrue(before != null && before.valid(), "fresh ping was not marked valid");
        PingPackageData.invalidateForPlayer(ping);
        var after = PingPackageData.read(ping);
        h.assertTrue(after != null && !after.valid(), "player-removed ping still counts as a live probe");
        h.assertTrue(ChainDiagnostics.diagnosticAddress(controller).equals(PackageItem.getAddress(ping)),
                "stale ping was not retargeted to its diagnostic Frogport for disposal");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unroutableParcelRaisesLoggerEventAndGaugeFaultState(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof DiagnosticFrogportBlockEntity,
                "diagnostic Frogport block entity was not created");
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) h.getLevel().getBlockEntity(pos);

        String address = "BAD-" + UUID.randomUUID().toString().substring(0, 8);
        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(parcel, address);
        ChainDiagnostics.unroutableCaptured(diagnostic, parcel);

        String sourcePrefix = EventRegistry.blockSource(h.getLevel(), pos) + "/a";
        boolean event = EventRegistry.get(h.getLevel().getServer()).active().stream()
                .anyMatch(row -> row.active()
                        && EventRegistry.Codes.CHAIN_NO_ROUTE.equals(row.code())
                        && "chain".equals(row.sourceType())
                        && row.sourceId().startsWith(sourcePrefix)
                        && address.equals(row.detail()));
        h.assertTrue(event, "unroutable parcel did not raise CHAIN_NO_ROUTE");
        h.assertTrue(ChainDiagnostics.lampState(h.getLevel(), address) == LampState.FATAL,
                "Factory Gauge address fault did not become a fatal brass-lamp state");
        h.succeed();
    }

    private static void attachLoopPort(net.minecraft.world.level.Level level, BlockPos chainPos,
                                       BlockPos frogPos, FrogportBlockEntity frog, float chainAngle) {
        var target = new PackagePortTarget.ChainConveyorFrogportTarget(
                chainPos.subtract(frogPos), chainAngle, (BlockPos) null, false);
        frog.target = target;
        target.setup(frog, level, frogPos);
        target.register(frog, level, frogPos);
    }

}
