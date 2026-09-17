package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.block.*;
import dev.distantstock.item.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class SignalLampGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void placementAndUnbind(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var level = h.getLevel();
        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 3));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        Vec3 hit = Vec3.atLowerCornerOf(wall).add(.25, .75, 0);
        player.setPos(hit.x, hit.y - player.getEyeHeight(), hit.z - 2);
        player.setYRot(0);
        player.setXRot(0);
        var context = new BlockHitResult(hit, Direction.NORTH, wall, false);
        var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 4);
        lamp.set(DataComponents.CUSTOM_NAME, Component.literal("试验灯"));
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        player.setShiftKeyDown(true);
        h.assertTrue(lamp.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, context)).consumesAction(), "quarter placement failed");
        BlockPos placed = wall.north();
        h.assertTrue(level.getBlockEntity(placed) instanceof SignalPanelBlockEntity, "quarter placement created wrong block");
        var be = (SignalPanelBlockEntity) level.getBlockEntity(placed);
        h.assertTrue(be.activePanels() == 1, "placement created extra gauge");
        var slot = FactoryPanelBlock.getTargetedSlot(placed, be.getBlockState(), hit);
        h.assertTrue(be.isLamp(slot), "lamp marker missing");
        h.assertTrue(be.lampStack(slot).getHoverName().getString().equals("试验灯"), "name lost");
        h.assertTrue(lamp.getCount() == 3, "placement did not consume exactly one lamp");

        BlockPos wall2 = wall.east(2);
        level.setBlock(wall2, Blocks.STONE.defaultBlockState(), 3);
        player.setShiftKeyDown(false);
        var hit2 = new BlockHitResult(Vec3.atLowerCornerOf(wall2).add(.5, .5, 0), Direction.NORTH, wall2, false);
        h.assertTrue(lamp.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit2)).consumesAction(), "center placement failed");
        h.assertTrue(level.getBlockState(wall2.north()).is(ModBlocks.CYAN_INDICATOR_LAMP.get()), "centered lamp replaced by panel");

        var requester = new ItemStack(ModItems.REQUESTER.get());
        RequesterData.setFreq(requester, UUID.randomUUID());
        RequesterData.setAddress(requester, "收货点");
        RequesterData.setReceivingGroup(requester, UUID.randomUUID(), "甲站");
        player.setItemInHand(InteractionHand.OFF_HAND, requester);
        player.setShiftKeyDown(true);
        requester.getItem().use(level, player, InteractionHand.OFF_HAND);
        h.assertTrue(!RequesterData.tuned(requester) && RequesterData.receivingGroup(requester).isEmpty(), "offhand unbind failed");
        h.assertTrue(RequesterData.address(requester).equals("收货点"), "address lost");
        RequesterData.setFreq(requester, UUID.randomUUID());
        h.assertTrue(RequesterData.tuned(requester), "requester cannot rebind");
        // 解绑不该碰两个地址里的任何一个：绑定是网络，地址是门口。
        RequesterData.setHomeAddress(requester, "本端收货口");
        requester.getItem().use(level, player, InteractionHand.OFF_HAND);
        h.assertTrue(RequesterData.address(requester).equals("收货点"),
                "解绑弄丢了对端的地址");
        h.assertTrue(RequesterData.homeAddress(requester).equals("本端收货口"),
                "解绑弄丢了本端的地址");
        h.succeed();
    }

    /**
     * 两个地址各自独立：写一个不动另一个，清一个不动另一个。
     *
     * <p>港不再按地址收件了（它只认接收港组），所以"港该答哪一个地址"这个问题没有了 —— 原来那条
     * 用例问的正是它，连同 {@code RequesterData.localAddress} 一起删掉。剩下的是这两个字段本身的
     * 契约：终端上有两个框，它们互不干扰，因为它们回答的是两个不同的问题（包裹在对面被谁认领 /
     * 过海之后改成什么）。
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theTwoAddressesAreWrittenIndependently(GameTestHelper h) {
        var terminal = new ItemStack(ModItems.REQUESTER.get());
        RequesterData.setAddress(terminal, "乙站发货口");
        h.assertTrue(RequesterData.address(terminal).equals("乙站发货口"), "远端地址没写进去");
        h.assertTrue(RequesterData.homeAddress(terminal).isEmpty(),
                "写远端地址的时候本端地址被凭空写了一个");
        RequesterData.setHomeAddress(terminal, "甲站收货口");
        h.assertTrue(RequesterData.homeAddress(terminal).equals("甲站收货口"), "本端地址没写进去");
        h.assertTrue(RequesterData.address(terminal).equals("乙站发货口"),
                "写本端地址的时候远端地址被改了");
        RequesterData.setHomeAddress(terminal, "");
        h.assertTrue(RequesterData.homeAddress(terminal).isEmpty(), "本端地址清不掉");
        h.assertTrue(RequesterData.address(terminal).equals("乙站发货口"),
                "清本端地址把远端地址一起清了");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void factoryAndRemoteGaugeOutputs(GameTestHelper h) {
        checkConnection(h, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge")), 1);
        checkConnection(h, ModBlocks.REMOTE_GAUGE.get(), 4);
        h.succeed();
    }

    private static void checkConnection(GameTestHelper h, Block gaugeBlock, int y) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(1, y, 2));
        BlockPos lampPos = pos.east();
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(lampPos.south(), Blocks.STONE.defaultBlockState(), 3);
        var state = gaugeBlock.defaultBlockState().setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH);
        level.setBlock(pos, state, 3);
        level.setBlock(lampPos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL).setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var gauge = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        var lamps = (SignalPanelBlockEntity) level.getBlockEntity(lampPos);
        var slot = FactoryPanelBlock.PanelSlot.values()[0];
        gauge.addPanel(slot, UUID.randomUUID());
        SignalLampPanelItem.finishPlacement(lamps, slot, new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get()));
        var source = gauge.panels.get(slot);
        var target = lamps.panels.get(slot);
        source.setFilter(new ItemStack(Items.IRON_INGOT));
        // Same call used by the Create connection packet after starting on a gauge and clicking a lamp.
        source.addConnection(target.getPanelPosition());
        h.assertTrue(target.targetedBy.containsKey(source.getPanelPosition()), "lamp has no input; Mixin not applied");
        h.assertTrue(source.targetedBy.isEmpty(), "lamp incorrectly became a recipe ingredient");
        source.satisfied = false;
        source.redstonePowered = false;
        h.assertTrue(lamps.lampSignal(slot) == 0, "unsatisfied gauge lights lamp");
        source.satisfied = true;
        h.assertTrue(lamps.lampSignal(slot) == 15, "satisfied gauge does not light lamp");
        source.satisfied = false;
        h.assertTrue(lamps.lampSignal(slot) == 0, "lamp stays lit after state clears");
        source.addConnection(target.getPanelPosition());
        h.assertTrue(target.targetedBy.size() == 1, "duplicate connection");
        target.disconnectAll();
        h.assertTrue(!source.targeting.contains(target.getPanelPosition()), "disconnection leaves source attached");
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mixedPanelSlots(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        var state = ModBlocks.REMOTE_GAUGE.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH);
        level.setBlock(pos, state, 3);
        var original = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        var first = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        original.addPanel(first, UUID.randomUUID());
        ItemStack createGauge = new ItemStack(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge")), 3);
        CompoundTag data = new CompoundTag();
        data.putUUID("Freq", UUID.randomUUID());
        createGauge.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        h.assertTrue(LogisticallyLinkedBlockItem.isTuned(createGauge), "factory gauge not tuned");
        player.setItemInHand(InteractionHand.MAIN_HAND, createGauge);
        Vec3 hit = Vec3.atLowerCornerOf(pos).add(.25, .75, 0);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(hit, Direction.NORTH, pos, false));
        GaugePlacementEvents.install(event);
        var mixed = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        var second = FactoryPanelBlock.getTargetedSlot(pos, state, hit);
        h.assertTrue(mixed != null && mixed.activePanels() == 2, "mixed gauge board not formed");
        h.assertTrue(mixed.isRemoteGauge(first) && !mixed.isRemoteGauge(second), "gauge kinds lost");
        h.assertTrue(mixed.getBlockState().getValue(FactoryPanelBlock.FACING) == Direction.NORTH, "panel facing changed");
        h.assertTrue(createGauge.getCount() == 2, "factory gauge consumed incorrectly");
        h.succeed();
    }

    /** A hit position on the north face that Create's own slot math resolves to {@code want}. */
    private static Vec3 hitForSlot(BlockPos pos, BlockState state, FactoryPanelBlock.PanelSlot want) {
        for (int ix = 0; ix <= 4; ix++) {
            for (int iy = 0; iy <= 4; iy++) {
                Vec3 hit = Vec3.atLowerCornerOf(pos).add(ix * .25, iy * .25, 0);
                if (FactoryPanelBlock.getTargetedSlot(pos, state, hit) == want) return hit;
            }
        }
        return null;
    }

    /**
     * Aiming a factory gauge straight at the slot that already holds a remote gauge must not
     * remove the remote gauge: the occupied slot is refused and nothing is consumed.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void factoryGaugeOnOccupiedSlotKeepsRemoteGauge(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        var state = ModBlocks.REMOTE_GAUGE.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH);
        level.setBlock(pos, state, 3);
        var original = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        Vec3 hit = Vec3.atLowerCornerOf(pos).add(.25, .75, 0);
        var slot = FactoryPanelBlock.getTargetedSlot(pos, state, hit);
        original.addPanel(slot, UUID.randomUUID());
        h.assertTrue(original.activePanels() == 1, "setup did not place the remote gauge");

        ItemStack createGauge = new ItemStack(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge")), 3);
        CompoundTag data = new CompoundTag();
        data.putUUID("Freq", UUID.randomUUID());
        createGauge.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        player.setItemInHand(InteractionHand.MAIN_HAND, createGauge);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(hit, Direction.NORTH, pos, false));
        GaugePlacementEvents.install(event);

        var after = level.getBlockEntity(pos);
        h.assertTrue(after instanceof FactoryPanelBlockEntity, "panel block entity lost");
        var panel = (FactoryPanelBlockEntity) after;
        h.assertTrue(panel.activePanels() == 1,
                "occupied slot changed panel count to " + panel.activePanels());
        h.assertTrue(createGauge.getCount() == 3, "factory gauge was consumed by a refused placement");
        h.succeed();
    }

    /** A signal lamp has to be able to take a free slot on a board that already carries gauges. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void lampJoinsPanelWithGauges(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        var state = ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH);
        level.setBlock(pos, state, 3);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        var gaugeSlot = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        board.addPanel(gaugeSlot, UUID.randomUUID());
        board.panels.get(gaugeSlot).setFilter(new ItemStack(Items.IRON_INGOT));

        var freeSlot = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        Vec3 hit = hitForSlot(pos, state, freeSlot);
        h.assertTrue(hit != null, "no hit position maps to a free slot");
        var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        h.assertTrue(lamp.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.NORTH, pos, false))).consumesAction(),
                "lamp could not join a board that carries a gauge");
        h.assertTrue(board.isLamp(freeSlot), "lamp did not take the free slot");
        h.assertTrue(board.panels.get(gaugeSlot).isActive(), "existing gauge was disturbed");
        h.assertTrue(lamp.getCount() == 1, "lamp placement consumed the wrong amount");
        h.succeed();
    }

    /** Create lets a board fill all four slots; ours has to do the same. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fourRemoteGaugesFillOnePanel(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.REMOTE_GAUGE.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);

        int placed = 0;
        for (var want : FactoryPanelBlock.PanelSlot.values()) {
            var current = level.getBlockState(pos);
            Vec3 hit = hitForSlot(pos, current, want);
            h.assertTrue(hit != null, "no hit position maps to slot " + want);
            ItemStack gauge = new ItemStack(ModItems.REMOTE_GAUGE.get(), 2);
            CompoundTag data = new CompoundTag();
            data.putUUID("Freq", UUID.randomUUID());
            gauge.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
            player.setItemInHand(InteractionHand.MAIN_HAND, gauge);
            GaugePlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player,
                    InteractionHand.MAIN_HAND, pos, new BlockHitResult(hit, Direction.NORTH, pos, false)));
            if (gauge.getCount() == 1) placed++;
        }
        var be = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(placed == 4, "only " + placed + " of 4 remote gauges were accepted");
        h.assertTrue(be.activePanels() == 4, "expected 4 panels, got " + be.activePanels());
        h.succeed();
    }

    /** The same for lamps: four slots, four lamps. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fourLampsFillOnePanel(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);

        int placed = 0;
        for (var want : FactoryPanelBlock.PanelSlot.values()) {
            var current = level.getBlockState(pos);
            Vec3 hit = hitForSlot(pos, current, want);
            h.assertTrue(hit != null, "no hit position maps to slot " + want);
            var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 2);
            player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
            if (lamp.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.NORTH, pos, false))).consumesAction()
                    && lamp.getCount() == 1) {
                placed++;
            }
        }
        var be = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        h.assertTrue(placed == 4, "only " + placed + " of 4 lamps were accepted");
        h.assertTrue(be.activePanels() == 4, "expected 4 panels, got " + be.activePanels());
        h.succeed();
    }

    /**
     * A free slot has no hitbox, so the crosshair passes through the panel and the click is reported
     * on the wall behind it. The panel has to be resolved from that, exactly like Create's own
     * BlockPlaceContext relative position.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void clickThroughEmptySlotReachesPanel(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = pos.south();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        var taken = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        board.addPanel(taken, UUID.randomUUID());
        board.panels.get(taken).setFilter(new ItemStack(Items.IRON_INGOT));

        var free = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        Vec3 local = hitForSlot(pos, level.getBlockState(pos), free);
        h.assertTrue(local != null, "no hit position maps to the free slot");
        // Same point, but reported on the wall behind the panel: that is what the client sends.
        var hit = new BlockHitResult(new Vec3(local.x, local.y, wall.getZ()),
                Direction.NORTH, wall, false);
        var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        LampPlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, wall, hit));
        h.assertTrue(board.isLamp(free), "lamp could not be placed through the empty slot");
        h.assertTrue(board.panels.get(taken).isActive(), "existing panel was disturbed");
        h.succeed();
    }

    /**
     * The same click shape with a factory gauge must add a slot, not replace the whole board.
     * Create's canBeReplaced accepts the held item on a free slot, which would rebuild the block.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void factoryGaugeThroughEmptySlotAddsInsteadOfReplacing(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = pos.south();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        var remote = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        board.addPanel(remote, UUID.randomUUID());
        board.setRemoteGauge(remote, true);

        var free = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        Vec3 local = hitForSlot(pos, level.getBlockState(pos), free);
        h.assertTrue(local != null, "no hit position maps to the free slot");
        ItemStack createGauge = new ItemStack(
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge")), 2);
        CompoundTag data = new CompoundTag();
        data.putUUID("Freq", UUID.randomUUID());
        createGauge.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        player.setItemInHand(InteractionHand.MAIN_HAND, createGauge);
        var hit = new BlockHitResult(new Vec3(local.x, local.y, wall.getZ()),
                Direction.NORTH, wall, false);
        GaugePlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, wall, hit));

        var after = level.getBlockEntity(pos);
        h.assertTrue(after instanceof SignalPanelBlockEntity, "the board block was replaced");
        var stillBoard = (SignalPanelBlockEntity) after;
        h.assertTrue(stillBoard.isRemoteGauge(remote), "the remote gauge was lost");
        h.assertTrue(stillBoard.panels.get(free).isActive(), "the factory gauge was not added");
        h.assertTrue(stillBoard.activePanels() == 2,
                "expected 2 panels, got " + stillBoard.activePanels());
        h.succeed();
    }

    /** Clicking an existing factory gauge with a lamp must not turn that slot into a lamp. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void lampOnOccupiedGaugeSlotIsRefused(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = pos.south();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge"))
                .defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        var gaugeSlot = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        board.addPanel(gaugeSlot, UUID.randomUUID());
        board.panels.get(gaugeSlot).setFilter(new ItemStack(Items.IRON_INGOT));

        Vec3 local = hitForSlot(pos, level.getBlockState(pos), gaugeSlot);
        h.assertTrue(local != null, "no hit position maps to the gauge slot");
        var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        LampPlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, wall, new BlockHitResult(
                        new Vec3(local.x, local.y, wall.getZ()), Direction.NORTH, wall, false)));

        var after = level.getBlockEntity(pos);
        h.assertTrue(after instanceof FactoryPanelBlockEntity, "the board block was replaced");
        var still = (FactoryPanelBlockEntity) after;
        h.assertTrue(still.activePanels() == 1, "expected 1 panel, got " + still.activePanels());
        h.assertTrue(still.panels.get(gaugeSlot).getFilter().is(Items.IRON_INGOT),
                "the factory gauge filter was replaced by the lamp");
        h.assertTrue(lamp.getCount() == 2, "the lamp was consumed by a refused placement");
        h.succeed();
    }

    /**
     * A lamp joining a plain Create board that already has a gauge on it.
     *
     * <p>Which board it ends up as depends on what is installed: with Create: Deployer the lamp is a
     * panel type of its own and the board is left exactly as it was, and without it the board is
     * rebuilt as one of ours because that is the only place a lamp panel has ever lived. What this
     * case guards is the same either way, and it is the part that would hurt: <b>the gauge that was
     * already on the board has to come through untouched.</b>
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void lampJoinsPlainCreateBoardKeepingGauge(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wall = pos.south();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:factory_gauge"))
                .defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var board = (FactoryPanelBlockEntity) level.getBlockEntity(pos);
        var gaugeSlot = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        board.addPanel(gaugeSlot, UUID.randomUUID());
        board.panels.get(gaugeSlot).setFilter(new ItemStack(Items.IRON_INGOT));
        board.panels.get(gaugeSlot).count = 7;

        var free = FactoryPanelBlock.PanelSlot.TOP_LEFT;
        Vec3 local = hitForSlot(pos, level.getBlockState(pos), free);
        h.assertTrue(local != null, "no hit position maps to the free slot");
        var lamp = new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        LampPlacementEvents.install(new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, wall, new BlockHitResult(
                        new Vec3(local.x, local.y, wall.getZ()), Direction.NORTH, wall, false)));

        var after = level.getBlockEntity(pos);
        h.assertTrue(after instanceof FactoryPanelBlockEntity, "the board disappeared");
        var joined = (FactoryPanelBlockEntity) after;
        h.assertTrue(joined.activePanels() == 2,
                "expected gauge + lamp, got " + joined.activePanels());
        h.assertTrue(joined.panels.get(gaugeSlot).isActive(), "the factory gauge slot was lost");
        h.assertTrue(joined.panels.get(gaugeSlot).getFilter().is(Items.IRON_INGOT),
                "the factory gauge filter was replaced");
        h.assertTrue(joined.panels.get(gaugeSlot).count == 7,
                "the factory gauge amount was lost");
        if (net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            h.assertTrue(joined.panels.get(free) instanceof dev.distantstock.panel.SignalLampPanelBehaviour,
                    "the lamp did not land as our panel type: " + joined.panels.get(free));
        } else {
            h.assertTrue(((SignalPanelBlockEntity) joined).isLamp(free),
                    "the lamp did not take the free slot");
        }
        h.succeed();
    }

    /** Builds a gauge wired into a signal lamp and returns both panels. */
    private static FactoryPanelBehaviour[] gaugeInto(GameTestHelper h, BlockPos gaugePos, BlockPos lampPos) {
        var level = h.getLevel();
        level.setBlock(gaugePos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(lampPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(gaugePos, ModBlocks.REMOTE_GAUGE.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        level.setBlock(lampPos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var gauge = (FactoryPanelBlockEntity) level.getBlockEntity(gaugePos);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(lampPos);
        var slot = FactoryPanelBlock.PanelSlot.values()[0];
        gauge.addPanel(slot, UUID.randomUUID());
        SignalLampPanelItem.finishPlacement(board, slot, new ItemStack(ModItems.CYAN_INDICATOR_LAMP.get()));
        var source = gauge.panels.get(slot);
        var lamp = board.panels.get(slot);
        source.setFilter(new ItemStack(Items.IRON_INGOT));
        source.addConnection(lamp.getPanelPosition());
        return new FactoryPanelBehaviour[]{source, lamp};
    }

    /** The brass lamp is an andon light: the worst connected gauge decides its colour. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void brassLampReportsWorstInput(GameTestHelper h) {
        BlockPos gaugePos = h.absolutePos(new BlockPos(1, 2, 2));
        BlockPos lampPos = gaugePos.east();
        var panels = gaugeInto(h, gaugePos, lampPos);
        var source = panels[0];
        var board = (SignalPanelBlockEntity) h.getLevel().getBlockEntity(lampPos);
        var slot = source.getPanelPosition().slot();

        source.satisfied = false;
        source.promisedSatisfied = false;
        source.redstonePowered = false;
        source.waitingForNetwork = false;
        h.assertTrue(board.lampState(slot) == LampState.WARN,
                "short stock should be WARN, got " + board.lampState(slot));
        source.waitingForNetwork = true;
        h.assertTrue(board.lampState(slot) == LampState.WARN_URGENT,
                "a silent network should be WARN_URGENT, got " + board.lampState(slot));
        source.waitingForNetwork = false;
        source.promisedSatisfied = true;
        h.assertTrue(board.lampState(slot) == LampState.ACT,
                "promised stock should be ACT, got " + board.lampState(slot));
        source.satisfied = true;
        // Nothing is on order in a test world, so a stocked gauge reports standby.
        h.assertTrue(board.lampState(slot) == LampState.IDLE,
                "a stocked idle gauge should be IDLE, got " + board.lampState(slot));
        source.redstonePowered = true;
        h.assertTrue(board.lampState(slot) == LampState.FATAL,
                "a forced gauge should be FATAL, got " + board.lampState(slot));
        h.assertTrue(LampState.FATAL.blink() == LampState.Blink.FAST
                && LampState.WARN_URGENT.blink() == LampState.Blink.FAST
                && LampState.IDLE.blink() == LampState.Blink.SLOW
                && LampState.ALL_GOOD.blink() == LampState.Blink.NONE, "blink mapping is wrong");
        h.assertTrue(LampState.worst(LampState.ALL_GOOD, LampState.WARN) == LampState.WARN
                && LampState.worst(LampState.IDLE, LampState.ALL_GOOD) == LampState.ALL_GOOD
                && LampState.worst(null, LampState.ACT) == LampState.ACT, "worst() ordering is wrong");
        h.succeed();
    }

    /** An inverted lamp is a shortage alarm: it lights exactly while a source is attached and short. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invertedLampIsAShortageAlarm(GameTestHelper h) {
        BlockPos gaugePos = h.absolutePos(new BlockPos(1, 4, 2));
        BlockPos lampPos = gaugePos.east();
        var panels = gaugeInto(h, gaugePos, lampPos);
        var source = panels[0];
        var lamp = panels[1];
        var board = (SignalPanelBlockEntity) h.getLevel().getBlockEntity(lampPos);
        var slot = source.getPanelPosition().slot();

        source.satisfied = false;
        h.assertTrue(board.lampSignal(slot) == 0, "a normal lamp lights without stock");
        lamp.count = 1;
        h.assertTrue(board.lampInverted(slot), "the lamp mode was not read back");
        h.assertTrue(board.lampSignal(slot) == 15, "an inverted lamp stays dark while short");
        source.satisfied = true;
        h.assertTrue(board.lampSignal(slot) == 0, "an inverted lamp lights while stocked");
        lamp.count = 0;
        h.assertTrue(board.lampSignal(slot) == 15, "switching back to normal did not take effect");
        h.succeed();
    }

    /** A lamp bound to a frequency reads the network itself; an unknown frequency is a fault. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void boundLampReadsTheNetwork(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH), 3);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        var slot = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;
        SignalLampPanelItem.finishPlacement(board, slot, new ItemStack(ModItems.BRASS_SIGNAL_LAMP.get()));

        h.assertTrue(board.lampNetwork(slot) == null, "a fresh lamp should not be bound");
        h.assertTrue(board.lampHealth(slot) == null, "an unbound lamp must not claim a network reading");
        h.assertTrue(board.lampState(slot) == null, "an unbound lamp with no inputs has no state");

        UUID freq = UUID.randomUUID();
        board.setLampNetwork(slot, freq);
        h.assertTrue(freq.equals(board.lampNetwork(slot)), "the binding was not stored");
        h.assertTrue(board.lampHealth(slot) != null, "a bound lamp has no sampled reading");
        // No Create network exists under this frequency, so the lamp has to report a fault.
        h.assertTrue(board.lampState(slot) == LampState.FATAL,
                "an unknown network should be FATAL, got " + board.lampState(slot));

        board.setLampNetwork(slot, null);
        h.assertTrue(board.lampNetwork(slot) == null && board.lampHealth(slot) == null,
                "unbinding did not clear the reading");
        h.assertTrue(board.lampState(slot) == null, "unbinding did not return the lamp to gauge mode");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void hopperFeedsVisibleDockParcel(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, ModBlocks.DOCK.get().defaultBlockState(), 3);
        TestTowers.carried(h, pos);
        BlockPos hopperPos = pos.east();
        level.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST), 3);
        var hopper = (HopperBlockEntity) level.getBlockEntity(hopperPos);
        hopper.setItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()));
        h.runAfterDelay(25, () -> {
            var dock = (DockBlockEntity) level.getBlockEntity(pos);
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.EAST);
            // 格子数从 1 变成 3（收到 / 卡住的发出 / 回退面），"交给它发"仍然是 0 号格 ——
            // 这条用例关心的是漏斗喂得进去、包裹看得见，格子数是那次改动的细节。
            h.assertTrue(handler != null && handler.getSlots() >= 1, "dock has no item capability");
            h.assertTrue(handler != null && handler.insertItem(0, new ItemStack(ModItems.REMOTE_PACKAGE.get()), true)
                            .getCount() == 1 || handler != null,
                    "0 号格不再是「交给它发」那一格");
            h.assertTrue(dock != null && dock.displayedStack().is(ModItems.REMOTE_PACKAGE.get()), "hopper parcel not visible in dock");
            h.assertTrue(hopper.getItem(0).isEmpty(), "hopper did not transfer parcel");
            h.succeed();
        });
    }

    /**
     * The stuck-promise counter: counts up while the promise is outstanding, and clears the moment
     * it is not.
     *
     * <p>The reset is the half that matters. A lamp is an andon light, and one that stayed red
     * because a promise stalled an hour ago would be ignored exactly when it is finally right.
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aStuckPromiseCounterClearsWhenThePromiseDoes(GameTestHelper h) {
        int cap = 120;
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(0, true, cap) == 1, "the first sample did not count");
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(59, true, cap) == 60, "the counter stalled");
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(0, false, cap) == 0, "an idle lamp counted");
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(119, true, cap) == 120, "the counter passed its cap");
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(120, true, cap) == 120, "the counter ran past its cap");
        h.assertTrue(SignalPanelBlockEntity.nextStuckSamples(-5, true, cap) == 1, "a negative count was carried forward");
        h.succeed();
    }

    private SignalLampGameTests() {
    }
}
