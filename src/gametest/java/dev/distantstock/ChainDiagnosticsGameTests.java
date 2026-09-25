package dev.distantstock;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRoutingTable;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.PackagePortItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortMenu;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.foundation.item.SmartInventory;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.block.DiagnosticFrogportBlockEntity;
import dev.distantstock.block.LampState;
import dev.distantstock.block.LoggerBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SpecialFrogportInteractionEvents;
import dev.distantstock.diagnostics.ChainDiagnostics;
import dev.distantstock.diagnostics.PingPackageData;
import dev.distantstock.event.EventRegistry;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.RequesterData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.createmod.catnip.animation.LerpedFloat;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class ChainDiagnosticsGameTests {

    @GameTest(template = "empty")
    public static void specialFrogportItemsUseCreatePackagePortPlacementLifecycle(GameTestHelper h) {
        var level = h.getLevel();
        h.assertTrue(ModItems.DIAGNOSTIC_FROGPORT.get() instanceof PackagePortItem,
                "diagnostic Frogport is not a Create PackagePortItem; placement will not flush the previous target");
        h.assertTrue(ModItems.CACHE_FROGPORT.get() instanceof PackagePortItem,
                "cache Frogport is not a Create PackagePortItem; placement will not flush the previous target");

        ItemStack diagnosticStack = new ItemStack(ModItems.DIAGNOSTIC_FROGPORT.get());
        ItemStack untunedNormalized = dev.distantstock.client.SpecialFrogportSelection
                .normalizeForCreateCheck(diagnosticStack);
        h.assertTrue(untunedNormalized.getItem() == ModItems.DIAGNOSTIC_FROGPORT.get(),
                "untuned diagnostic Frogport entered chain targeting before a Create network was bound");

        UUID frequency = UUID.randomUUID();
        RequesterData.setFreq(diagnosticStack, frequency);
        ItemStack tunedNormalized = dev.distantstock.client.SpecialFrogportSelection
                .normalizeForCreateCheck(diagnosticStack);
        h.assertTrue(BuiltInRegistries.ITEM.getKey(tunedNormalized.getItem())
                        .equals(ResourceLocation.fromNamespaceAndPath("create", "package_frogport")),
                "tuned diagnostic Frogport did not enter Create chain targeting");

        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        ((dev.distantstock.block.DiagnosticFrogportBlock) ModBlocks.DIAGNOSTIC_FROGPORT.get())
                .setPlacedBy(level, pos, level.getBlockState(pos), null, diagnosticStack);
        DiagnosticFrogportBlockEntity placed = (DiagnosticFrogportBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(placed != null && frequency.equals(placed.createFrequency()),
                "diagnostic Frogport did not persist its tuned Create frequency into the block entity");
        h.succeed();
    }

    /** Old worlds saved cache Frogports with Inventory.Size=18; loading must migrate to 54 safely. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyEighteenSlotCacheMigratesToFiftyFourWithoutLosingParcels(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(pos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(cache != null, "cache Frogport did not create its block entity");

        SmartInventory legacy = new SmartInventory(18, cache,
                (slot, stack) -> PackageItem.isPackage(stack));
        ItemStack first = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        ItemStack last = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(first, "LEGACY-FIRST");
        PackageItem.addAddress(last, "LEGACY-LAST");
        legacy.setStackInSlot(0, first);
        legacy.setStackInSlot(17, last);

        net.minecraft.nbt.CompoundTag old = new net.minecraft.nbt.CompoundTag();
        old.put("Inventory", legacy.serializeNBT(level.registryAccess()));
        old.putBoolean("AcceptsPackages", true);
        old.putString("AddressFilter", "");
        cache.loadWithComponents(old, level.registryAccess());

        h.assertTrue(cache.inventory.getSlots() == CacheFrogportBlockEntity.CACHE_SLOTS,
                "legacy 18-slot cache stayed at " + cache.inventory.getSlots() + " slots after load");
        h.assertTrue("LEGACY-FIRST".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(0))),
                "legacy slot 0 parcel was lost during 18 -> 54 migration");
        h.assertTrue("LEGACY-LAST".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(17))),
                "legacy slot 17 parcel was lost during 18 -> 54 migration");

        net.minecraft.nbt.CompoundTag saved = cache.saveWithoutMetadata(level.registryAccess());
        h.assertTrue(saved.getCompound("Inventory").getInt("Size") == CacheFrogportBlockEntity.CACHE_SLOTS,
                "migrated cache did not persist Inventory.Size=54");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void multipleCacheFrogportsMitigateIndependentAddresses(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 2));
        BlockPos cacheAPos = h.absolutePos(new BlockPos(4, 2, 7));
        BlockPos cacheBPos = h.absolutePos(new BlockPos(6, 2, 7));
        BlockPos receiverAPos = h.absolutePos(new BlockPos(2, 2, 5));
        BlockPos receiverBPos = h.absolutePos(new BlockPos(8, 2, 5));
        String addressA = "MULTI-CACHE-A";
        String addressB = "MULTI-CACHE-B";

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cacheAPos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cacheBPos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(receiverAPos, frogportBlock.defaultBlockState(), 3);
        level.setBlock(receiverBPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        DiagnosticFrogportBlockEntity diagnostic = (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        CacheFrogportBlockEntity cacheA = (CacheFrogportBlockEntity) level.getBlockEntity(cacheAPos);
        CacheFrogportBlockEntity cacheB = (CacheFrogportBlockEntity) level.getBlockEntity(cacheBPos);
        FrogportBlockEntity receiverA = (FrogportBlockEntity) level.getBlockEntity(receiverAPos);
        FrogportBlockEntity receiverB = (FrogportBlockEntity) level.getBlockEntity(receiverBPos);
        h.assertTrue(chain != null && diagnostic != null && cacheA != null && cacheB != null
                        && receiverA != null && receiverB != null,
                "multi-cache fixture failed to create its block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 2500;
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverAPos, receiverA, 72);
        attachLoopPort(level, chainPos, receiverBPos, receiverB, 144);
        attachLoopPort(level, chainPos, cacheAPos, cacheA, 216);
        attachLoopPort(level, chainPos, cacheBPos, cacheB, 288);
        receiverA.addressFilter = addressA;
        receiverB.addressFilter = addressB;
        receiverA.acceptsPackages = receiverB.acceptsPackages = true;
        receiverA.filterChanged();
        receiverB.filterChanged();

        ItemStack busy = new ItemStack(ModItems.PING_PACKAGE.get());
        h.onEachTick(() -> {
            chain.setSpeed(128);
            receiverA.animatedPackage = busy;
            receiverB.animatedPackage = busy;
            receiverA.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
            receiverB.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
        });
        ChainDiagnostics.register(diagnostic);
        ChainDiagnostics.register(cacheA);
        ChainDiagnostics.register(cacheB);
        ChainDiagnostics.tick(level.getServer());

        h.runAfterDelay(760, () -> {
            java.util.Set<String> owners = java.util.Set.of(cacheA.takeoverAddress(), cacheB.takeoverAddress());
            h.assertTrue(owners.contains(addressA) && owners.contains(addressB),
                    "two failed addresses did not receive two independent caches: " + owners);
            h.assertTrue(!cacheA.takeoverAddress().equals(cacheB.takeoverAddress()),
                    "both caches were assigned to the same failed address");

            CacheFrogportBlockEntity ownerA = addressA.equals(cacheA.takeoverAddress()) ? cacheA : cacheB;
            CacheFrogportBlockEntity ownerB = ownerA == cacheA ? cacheB : cacheA;

            // Real packages, not direct inventory seeding: both addresses enter the running Create
            // chain and must be consumed by the cache that owns that public takeover route.
            ItemStack parcelA = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            ItemStack parcelB = new ItemStack(ModItems.REMOTE_PACKAGE.get());
            PackageItem.addAddress(parcelA, addressA);
            PackageItem.addAddress(parcelB, addressB);
            chain.addLoopingPackage(new ChainConveyorPackage(20, parcelA));
            chain.addLoopingPackage(new ChainConveyorPackage(40, parcelB));

            h.runAfterDelay(100, () -> {
                h.assertTrue(countAddress(ownerA, addressA) > 0,
                        "address A package did not enter the cache that owns A");
                h.assertTrue(countAddress(ownerA, addressB) == 0,
                        "cache A consumed a package belonging to address B");
                h.assertTrue(countAddress(ownerB, addressB) > 0,
                        "address B package did not enter the cache that owns B");
                h.assertTrue(countAddress(ownerB, addressA) == 0,
                        "cache B consumed a package belonging to address A");

                int bBefore = ownerB.cachedCount();
                BlockPos ownerAPos = ownerA.getBlockPos();
                level.destroyBlock(ownerAPos, false);

                h.assertTrue(addressA.equals(receiverA.getFilterString()),
                        "removing cache A did not thaw address A");
                h.assertTrue(ChainDiagnostics.recoveryAddress(receiverBPos).equals(receiverB.getFilterString()),
                        "removing cache A incorrectly thawed address B");
                h.assertTrue(addressB.equals(ownerB.takeoverAddress()),
                        "removing one cache cleared the other cache's takeover");

                ItemStack parcelBAfter = new ItemStack(ModItems.REMOTE_PACKAGE.get());
                PackageItem.addAddress(parcelBAfter, addressB);
                chain.addLoopingPackage(new ChainConveyorPackage(60, parcelBAfter));
                h.runAfterDelay(100, () -> {
                    h.assertTrue(ownerB.cachedCount() > bBefore,
                            "surviving cache stopped accepting B after cache A was removed");
                    h.assertTrue(countAddress(ownerB, addressB) == ownerB.cachedCount(),
                            "surviving cache contains parcels from an address it does not own");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void cacheFrogportMenuOpensAndSlotsAreIndependentlyAccessible(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(pos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(cache != null, "cache Frogport did not create its block entity");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var result = level.getBlockState(pos).useWithoutItem(level, player, hit);
        h.assertTrue(result.consumesAction(), "empty-hand click on cache Frogport was not consumed");

        // FakePlayer does not guarantee a network-backed menu open, so validate the exact server
        // menu factory as well. These are the 54 real SmartInventory slots used by real players.
        var menu = dev.distantstock.menu.CacheFrogportMenu.server(1, player.getInventory(), cache);
        h.assertTrue(menu.slots.size() >= CacheFrogportBlockEntity.CACHE_SLOTS,
                "cache menu did not expose all 54 cache slots");

        ItemStack a = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        ItemStack b = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        ItemStack c = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(a, "UI-A");
        PackageItem.addAddress(b, "UI-B");
        PackageItem.addAddress(c, "UI-C");
        menu.getSlot(0).set(a);
        menu.getSlot(1).set(b);
        menu.getSlot(2).set(c);

        h.assertTrue("UI-A".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(0))),
                "menu slot 0 did not persist independently");
        h.assertTrue("UI-B".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(1))),
                "menu slot 1 did not persist independently");
        h.assertTrue("UI-C".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(2))),
                "menu slot 2 did not persist independently");

        ItemStack removed = menu.getSlot(1).remove(1);
        h.assertTrue("UI-B".equals(PackageItem.getAddress(removed)),
                "taking one cached parcel returned the wrong slot");
        h.assertTrue(cache.inventory.getStackInSlot(1).isEmpty(),
                "taking slot 1 did not clear exactly that cache slot");
        h.assertTrue("UI-A".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(0)))
                        && "UI-C".equals(PackageItem.getAddress(cache.inventory.getStackInSlot(2))),
                "taking one cached parcel disturbed neighbouring cached parcels");
        menu.removed(player);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void diagnosticFrogportItemCopiesCreateNetworkFromStockLink(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        UUID frequency = UUID.randomUUID();
        BlockPos linkPos = h.absolutePos(new BlockPos(2, 2, 2));

        level.setBlock(linkPos, BuiltInRegistries.BLOCK
                .get(ResourceLocation.fromNamespaceAndPath("create", "stock_link"))
                .defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        var link = (com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity)
                level.getBlockEntity(linkPos);
        h.assertTrue(link != null, "Create stock link did not create its block entity");

        var logistics = new com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork(frequency);
        com.simibubi.create.Create.LOGISTICS.logisticsNetworks.put(frequency, logistics);
        try {
            com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.remove(link.behaviour);
            link.behaviour.freqId = frequency;
            logistics.loadedLinks.add(net.minecraft.core.GlobalPos.of(level.dimension(), linkPos));
            com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.keepAlive(link.behaviour);

            ItemStack diagnostic = new ItemStack(ModItems.DIAGNOSTIC_FROGPORT.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, diagnostic);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(linkPos), Direction.UP, linkPos, false);
            var result = diagnostic.getItem().useOn(
                    new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));

            h.assertTrue(result.consumesAction(),
                    "diagnostic Frogport could not copy a valid Create logistics network");
            h.assertTrue(frequency.equals(RequesterData.freq(diagnostic)),
                    "diagnostic Frogport reported binding success but stored the wrong Create frequency");
        } finally {
            com.simibubi.create.Create.LOGISTICS.logisticsNetworks.remove(frequency);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void diagnosticFrogportPushesUnroutablePackageIntoPackagerBelow(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos packagerPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos diagnosticPos = packagerPos.above();

        var packagerBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "packager"));
        level.setBlock(packagerPos, packagerBlock.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.NORTH), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);

        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        h.assertTrue(diagnostic != null, "diagnostic Frogport did not create its block entity");
        h.assertTrue(level.getBlockEntity(packagerPos)
                        instanceof com.simibubi.create.content.logistics.packager.PackagerBlockEntity,
                "Create packager fixture did not create its block entity");

        ItemStack bad = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(bad, "MISSPELLED-PACKAGER-ROUTE");
        h.assertTrue(diagnostic.inventory.insertItem(0, bad, false).isEmpty(),
                "could not seed the diagnostic quarantine with an unroutable package");

        diagnostic.lazyTick();

        h.assertTrue(diagnostic.inventory.getStackInSlot(0).isEmpty(),
                "diagnostic Frogport did not hand the unroutable package to the Packager below");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void noRouteAlarmSurvivesPackageHandoffToPackager(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos packagerPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos diagnosticPos = packagerPos.above();
        BlockPos loggerPos = h.absolutePos(new BlockPos(5, 2, 2));

        var packagerBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "packager"));
        level.setBlock(packagerPos, packagerBlock.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.NORTH), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(loggerPos, ModBlocks.LOGGER.get().defaultBlockState(), 3);

        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(loggerPos);
        h.assertTrue(diagnostic != null && logger != null, "no-route persistence fixture failed");

        UUID frequency = UUID.randomUUID();
        diagnostic.setCreateFrequency(frequency);
        logger.setCreateFrequency(frequency);
        h.assertTrue(logger.installPaperRoll(), "could not install Logger paper roll for recovery-state test");
        String address = "BAD-" + UUID.randomUUID();
        ItemStack bad = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(bad, address);

        ChainDiagnostics.unroutableCaptured(diagnostic, bad);
        h.assertTrue(diagnostic.inventory.insertItem(0, bad, false).isEmpty(),
                "could not seed diagnostic quarantine");
        diagnostic.lazyTick();
        h.assertTrue(diagnostic.inventory.getStackInSlot(0).isEmpty(),
                "Packager did not accept the quarantined package");

        ChainDiagnostics.tick(level.getServer());
        EventRegistry.Record alarm = EventRegistry.get(level.getServer()).active().stream()
                .filter(row -> EventRegistry.Codes.CHAIN_NO_ROUTE.equals(row.code()))
                .filter(row -> address.equals(row.detail()))
                .findFirst().orElse(null);
        h.assertTrue(alarm != null,
                "CHAIN_NO_ROUTE cleared merely because the package left the diagnostic inventory");
        h.assertTrue(logger.status() == dev.distantstock.block.LoggerBlock.Status.ERROR,
                "Logger returned to OK while the no-route fault was still unresolved: " + logger.status());
        h.assertTrue(ChainDiagnostics.lampState(level, address) == LampState.FATAL,
                "Andon/lamp state cleared while the no-route fault was still unresolved");

        h.assertTrue(EventRegistry.get(level.getServer()).acknowledge(alarm.id(), level.getGameTime()),
                "could not acknowledge the one-off no-route alarm");
        h.assertTrue(logger.status() == dev.distantstock.block.LoggerBlock.Status.ERROR_ACK,
                "Logger did not enter AC immediately after acknowledgement: " + logger.status());
        h.assertTrue(ChainDiagnostics.lampState(level, address) == LampState.FATAL_ACK,
                "Andon/lamp did not enter acknowledged-fault state");

        ChainDiagnostics.tick(level.getServer());
        h.assertTrue(EventRegistry.get(level.getServer())
                        .active(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", alarm.sourceId()).isPresent(),
                "ACK alone cleared a one-off no-route fault before its incident slip was printed");
        h.assertTrue(EventRegistry.get(level.getServer()).markPrinted(alarm.id(), level.getGameTime()),
                "could not mark the handled one-off no-route incident as printed");
        ChainDiagnostics.tick(level.getServer());
        h.assertTrue(EventRegistry.get(level.getServer())
                        .active(EventRegistry.Codes.CHAIN_NO_ROUTE, "chain", alarm.sourceId()).isEmpty(),
                "printed one-off no-route fault did not clear after the parcel was handled");
        h.assertTrue(logger.status() == dev.distantstock.block.LoggerBlock.Status.NORMAL,
                "Logger stayed faulted after the printed one-off incident was resolved: " + logger.status());
        h.assertTrue(ChainDiagnostics.lampState(level, address) == null,
                "Andon/lamp stayed faulted after the handled one-off fault was resolved");

        h.succeed();
    }

    @GameTest(template = "empty")
    public static void specialFrogportGoggleTooltipsReserveIconRowAndCacheHasDetails(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 2));
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);

        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        h.assertTrue(diagnostic != null && cache != null, "special Frogport goggle fixture failed");

        java.util.List<net.minecraft.network.chat.Component> diagnosticTip = new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.chat.Component> cacheTip = new java.util.ArrayList<>();
        h.assertTrue(diagnostic.addToGoggleTooltip(diagnosticTip, false),
                "diagnostic Frogport did not provide goggle tooltip");
        h.assertTrue(cache.addToGoggleTooltip(cacheTip, false),
                "cache Frogport did not provide goggle tooltip");

        h.assertTrue(diagnosticTip.size() >= 3 && diagnosticTip.get(0).getString().isEmpty(),
                "diagnostic Frogport title does not reserve the goggle icon row: " + diagnosticTip);
        h.assertTrue(cacheTip.size() >= 5 && cacheTip.get(0).getString().isEmpty(),
                "cache Frogport tooltip is missing or does not reserve the goggle icon row: " + cacheTip);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void emptyHandRightClickOpensSpecialFrogportInventory(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 2));
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // Diagnostic Frogport still uses the NeoForge event bridge for empty-hand menu access.
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, diagnosticPos,
                new BlockHitResult(Vec3.atCenterOf(diagnosticPos), Direction.UP, diagnosticPos, false));
        SpecialFrogportInteractionEvents.openInventory(event);
        h.assertTrue(event.isCanceled(), "empty-hand diagnostic Frogport click was not consumed");

        // Cache Frogport owns its menu path directly so its sneak interaction can remain reserved
        // for release-delay Value Settings.
        var cacheResult = level.getBlockState(cachePos).useWithoutItem(level, player,
                new BlockHitResult(Vec3.atCenterOf(cachePos), Direction.UP, cachePos, false));
        h.assertTrue(cacheResult.consumesAction(), "empty-hand cache Frogport click was not consumed");
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        h.assertTrue(cache != null, "cache Frogport disappeared during empty-hand interaction");
        h.assertTrue(cache.createMenu(7, player.getInventory(), player)
                        instanceof dev.distantstock.menu.CacheFrogportMenu,
                "cache Frogport interaction does not target the 54-slot CacheFrogportMenu");

        player.setShiftKeyDown(true);
        var sneakResult = level.getBlockState(cachePos).useWithoutItem(level, player,
                new BlockHitResult(Vec3.atCenterOf(cachePos), Direction.UP, cachePos, false));
        h.assertTrue(!sneakResult.consumesAction(),
                "sneaking cache click was stolen by inventory UI; Value Settings cannot long-press");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void diagnosticChainFaultCarriesExplicitLoggerFrequency(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos loggerPos = h.absolutePos(new BlockPos(4, 2, 2));
        BlockPos otherLoggerPos = h.absolutePos(new BlockPos(6, 2, 2));
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(loggerPos, ModBlocks.LOGGER.get().defaultBlockState(), 3);
        level.setBlock(otherLoggerPos, ModBlocks.LOGGER.get().defaultBlockState(), 3);

        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        LoggerBlockEntity logger = (LoggerBlockEntity) level.getBlockEntity(loggerPos);
        LoggerBlockEntity other = (LoggerBlockEntity) level.getBlockEntity(otherLoggerPos);
        h.assertTrue(diagnostic != null && logger != null && other != null,
                "logger-scope fixture failed to create block entities");

        UUID frequency = UUID.randomUUID();
        diagnostic.setCreateFrequency(frequency);
        logger.setCreateFrequency(frequency);
        other.setCreateFrequency(UUID.randomUUID());

        String address = "SCOPE-" + UUID.randomUUID();
        ItemStack bad = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(bad, address);
        ChainDiagnostics.unroutableCaptured(diagnostic, bad);

        EventRegistry registry = EventRegistry.get(level.getServer());
        EventRegistry.Record record = registry.active().stream()
                .filter(row -> EventRegistry.Codes.CHAIN_NO_ROUTE.equals(row.code()))
                .filter(row -> address.equals(row.detail()))
                .findFirst().orElse(null);
        h.assertTrue(record != null, "diagnostic Frogport did not raise CHAIN_NO_ROUTE");
        h.assertTrue(frequency.equals(record.createFrequency()),
                "chain fault did not carry the diagnostic Frogport's explicit Create frequency");
        h.assertTrue(logger.visible(record), "Logger bound to the same Create network cannot see the chain fault");
        h.assertTrue(!other.visible(record), "Logger on another Create network leaked the chain fault");

        registry.clear(record.code(), record.sourceType(), record.sourceId(), level.getGameTime());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cacheReplayDelayUsesOnlyFiveApprovedModes(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(pos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(cache != null, "cache Frogport did not create its block entity");

        int[][] cases = {{0, 0}, {3, 5}, {7, 5}, {12, 10}, {16, 15}, {27, 30}, {100, 30}};
        for (int[] pair : cases) {
            cache.setReleaseDelaySeconds(pair[0]);
            h.assertTrue(cache.releaseDelaySeconds() == pair[1],
                    "cache replay delay did not snap " + pair[0] + " to approved mode " + pair[1]
                            + "; got " + cache.releaseDelaySeconds());
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void diagnosticFrogportPublishesPlannedPollingState(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos receiverAPos = h.absolutePos(new BlockPos(3, 2, 5));
        BlockPos receiverBPos = h.absolutePos(new BlockPos(7, 2, 5));

        var chainBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "chain_conveyor"));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(chainPos, chainBlock.defaultBlockState(), 3);
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(receiverAPos, frogportBlock.defaultBlockState(), 3);
        level.setBlock(receiverBPos, frogportBlock.defaultBlockState(), 3);

        ChainConveyorBlockEntity chain = (ChainConveyorBlockEntity) level.getBlockEntity(chainPos);
        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        FrogportBlockEntity receiverA = (FrogportBlockEntity) level.getBlockEntity(receiverAPos);
        FrogportBlockEntity receiverB = (FrogportBlockEntity) level.getBlockEntity(receiverBPos);
        h.assertTrue(chain != null && diagnostic != null && receiverA != null && receiverB != null,
                "planned-polling fixture failed to create block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 200;
        h.onEachTick(() -> chain.setSpeed(128));
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverAPos, receiverA, 90);
        attachLoopPort(level, chainPos, receiverBPos, receiverB, 180);

        receiverA.addressFilter = "A-POLL";
        receiverA.acceptsPackages = true;
        receiverA.filterChanged();
        receiverB.addressFilter = "B-POLL";
        receiverB.acceptsPackages = true;
        receiverB.filterChanged();
        ChainDiagnostics.register(diagnostic);
        ChainDiagnostics.tick(level.getServer());

        h.assertTrue("waiting".equals(diagnostic.diagnosticPhase()),
                "diagnostic Frogport did not enter waiting-for-Ping state: " + diagnostic.diagnosticPhase());
        h.assertTrue("A-POLL".equals(diagnostic.diagnosticAddress()),
                "planned polling did not start at sorted first address: " + diagnostic.diagnosticAddress());
        h.assertTrue(diagnostic.diagnosticPlanIndex() == 1 && diagnostic.diagnosticPlanTotal() == 2,
                "planned polling progress is wrong: " + diagnostic.diagnosticPlanIndex()
                        + "/" + diagnostic.diagnosticPlanTotal());

        java.util.List<net.minecraft.network.chat.Component> goggles = new java.util.ArrayList<>();
        h.assertTrue(diagnostic.addToGoggleTooltip(goggles, false),
                "diagnostic Frogport did not provide goggle information");
        h.assertTrue(goggles.stream().map(net.minecraft.network.chat.Component::getString)
                        .anyMatch(line -> line.contains("A-POLL")),
                "goggle information did not expose the active polling address: " + goggles);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void specialFrogportsExposeInspectablePackageMenus(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 2));
        level.setBlock(diagnosticPos, ModBlocks.DIAGNOSTIC_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);

        DiagnosticFrogportBlockEntity diagnostic =
                (DiagnosticFrogportBlockEntity) level.getBlockEntity(diagnosticPos);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        h.assertTrue(diagnostic != null && cache != null, "special Frogport block entities were not created");

        var diagnosticMenu = diagnostic.createMenu(1, player.getInventory(), player);
        var cacheMenu = cache.createMenu(2, player.getInventory(), player);
        h.assertTrue(diagnosticMenu instanceof com.simibubi.create.content.logistics.packagePort.PackagePortMenu,
                "diagnostic Frogport did not create Create's PackagePortMenu");
        h.assertTrue(cacheMenu instanceof dev.distantstock.menu.CacheFrogportMenu,
                "cache Frogport did not create its six-row package menu");
        h.assertTrue(diagnosticMenu.slots.size() == 54,
                "diagnostic Frogport menu is not 18 device slots + 36 player slots: " + diagnosticMenu.slots.size());
        h.assertTrue(cacheMenu.slots.size() == CacheFrogportBlockEntity.CACHE_SLOTS + 36,
                "cache Frogport menu does not expose every buffered parcel: " + cacheMenu.slots.size());
        h.assertTrue(diagnostic.inventory.getSlots() == 18,
                "diagnostic Frogport no longer uses Create's native 18 slots");
        h.assertTrue(cache.inventory.getSlots() == CacheFrogportBlockEntity.CACHE_SLOTS,
                "cache Frogport did not expand to " + CacheFrogportBlockEntity.CACHE_SLOTS + " slots");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cacheFrogportFastIntakeBypassesVanillaCatchSerialization(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos cachePos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos normalPos = h.absolutePos(new BlockPos(5, 2, 2));
        var frogportBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "package_frogport"));
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(normalPos, frogportBlock.defaultBlockState(), 3);

        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        FrogportBlockEntity normal = (FrogportBlockEntity) level.getBlockEntity(normalPos);
        h.assertTrue(cache != null && normal != null, "fast-intake fixture did not create Frogports");

        ItemStack first = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(first, "BURST-CACHE");
        cache.startAnimation(first, false);
        h.assertTrue(cache.cachedCount() == 1,
                "cache Frogport did not store an incoming package immediately");
        h.assertFalse(cache.isAnimationInProgress(),
                "cache Frogport still serializes incoming packages behind the vanilla catch animation");

        ItemStack second = first.copy();
        cache.startAnimation(second, false);
        h.assertTrue(cache.cachedCount() == 2,
                "cache Frogport could not accept a second package immediately after the first");

        normal.startAnimation(first.copy(), false);
        h.assertTrue(normal.isAnimationInProgress(),
                "control Frogport unexpectedly stopped using Create's normal catch animation");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cacheFrogportCanOffloadPackagesToContainerBelow(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos cachePos = h.absolutePos(new BlockPos(3, 3, 3));
        BlockPos chestPos = cachePos.below();
        level.setBlock(cachePos, ModBlocks.CACHE_FROGPORT.get().defaultBlockState(), 3);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        CacheFrogportBlockEntity cache = (CacheFrogportBlockEntity) level.getBlockEntity(cachePos);
        h.assertTrue(cache != null, "cache offload fixture did not create its block entity");

        ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
        PackageItem.addAddress(parcel, "MANUAL-RECOVERY-ADDRESS");
        cache.inventory.setStackInSlot(0, parcel);
        cache.lazyTick();

        var below = level.getCapability(Capabilities.ItemHandler.BLOCK, chestPos, Direction.UP);
        h.assertTrue(below != null, "chest below cache did not expose an item handler");
        h.assertTrue(cache.cachedCount() == 0,
                "cache Frogport did not hand its buffered parcel to the container below");
        boolean found = false;
        for (int slot = 0; slot < below.getSlots(); slot++) {
            ItemStack stack = below.getStackInSlot(slot);
            if (!stack.isEmpty() && "MANUAL-RECOVERY-ADDRESS".equals(PackageItem.getAddress(stack))) {
                found = true;
                break;
            }
        }
        h.assertTrue(found, "offloaded parcel lost its original address or never reached the container");
        h.succeed();
    }

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
            for (int slot = 0; slot < CacheFrogportBlockEntity.CACHE_SLOTS; slot++) {
                ItemStack parcel = new ItemStack(ModItems.REMOTE_PACKAGE.get());
                PackageItem.addAddress(parcel, address);
                cache.inventory.setStackInSlot(slot, parcel);
            }
            h.assertTrue(cache.isBackedUp(), CacheFrogportBlockEntity.CACHE_SLOTS
                    + " occupied cache slots did not report backed up");
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

    @GameTest(template = "empty", timeoutTicks = 360)
    public static void breakingCacheImmediatelyThawsFrozenReceivers(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos chainPos = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos diagnosticPos = h.absolutePos(new BlockPos(5, 2, 3));
        BlockPos cachePos = h.absolutePos(new BlockPos(5, 2, 7));
        BlockPos receiverPos = h.absolutePos(new BlockPos(3, 2, 5));
        String address = "CACHE-REMOVAL-RECOVERY";

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
                "cache-removal fixture did not create block entities");

        chain.setSpeed(128);
        chain.preventSpeedUpdate = 1000;
        h.onEachTick(() -> chain.setSpeed(128));
        attachLoopPort(level, chainPos, diagnosticPos, diagnostic, 0);
        attachLoopPort(level, chainPos, receiverPos, receiver, 90);
        attachLoopPort(level, chainPos, cachePos, cache, 180);
        receiver.addressFilter = address;
        receiver.acceptsPackages = true;
        receiver.filterChanged();

        ItemStack busyMarker = new ItemStack(ModItems.PING_PACKAGE.get());
        java.util.concurrent.atomic.AtomicBoolean blocked = new java.util.concurrent.atomic.AtomicBoolean(true);
        h.onEachTick(() -> {
            if (blocked.get()) {
                receiver.animatedPackage = busyMarker;
                receiver.animationProgress.startWithValue(0).chase(1, .1, LerpedFloat.Chaser.LINEAR);
            }
        });
        ChainDiagnostics.register(diagnostic);
        ChainDiagnostics.register(cache);
        ChainDiagnostics.tick(level.getServer());

        h.runAfterDelay(150, () -> {
            h.assertTrue(address.equals(cache.takeoverAddress()),
                    "fixture never reached cache takeover");
            h.assertTrue(ChainDiagnostics.recoveryAddress(receiverPos).equals(receiver.getFilterString()),
                    "receiver was not frozen before cache removal");

            blocked.set(false);
            level.destroyBlock(cachePos, false);

            h.assertTrue(address.equals(receiver.getFilterString()),
                    "breaking the cache left the real Frogport frozen on its private recovery address");
            h.assertTrue(EventRegistry.get(level.getServer()).active().stream()
                            .noneMatch(row -> EventRegistry.Codes.CHAIN_CACHE_FULL.equals(row.code())
                                    && address.equals(row.detail())),
                    "cache-full event survived after its cache block was removed");
            // Filter restoration is synchronous; Create rebuilds the conveyor routing table on a
            // later tick. Assert the actual public route after that normal refresh window.
            h.runAfterDelay(8, () -> {
                long publicRoutes = chain.routingTable.entriesByDistance.stream()
                        .filter(ChainConveyorRoutingTable.RoutingTableEntry::endOfRoute)
                        .filter(row -> address.equals(row.port()))
                        .count();
                h.assertTrue(publicRoutes >= 1,
                        "breaking the cache did not restore the original public destination route");
                h.succeed();
            });
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

    private static int countAddress(CacheFrogportBlockEntity cache, String address) {
        int count = 0;
        for (int slot = 0; slot < cache.inventory.getSlots(); slot++) {
            ItemStack stack = cache.inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && address.equals(PackageItem.getAddress(stack))) {
                count++;
            }
        }
        return count;
    }

}
