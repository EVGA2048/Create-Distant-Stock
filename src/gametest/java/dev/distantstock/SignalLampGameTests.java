package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.block.*;
import dev.distantstock.item.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
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
        RequesterData.setReceivingGroup(requester, UUID.randomUUID());
        player.setItemInHand(InteractionHand.OFF_HAND, requester);
        player.setShiftKeyDown(true);
        requester.getItem().use(level, player, InteractionHand.OFF_HAND);
        h.assertTrue(!RequesterData.tuned(requester) && RequesterData.receivingGroup(requester).isEmpty(), "offhand unbind failed");
        h.assertTrue(RequesterData.address(requester).equals("收货点"), "address lost");
        RequesterData.setFreq(requester, UUID.randomUUID());
        h.assertTrue(RequesterData.tuned(requester), "requester cannot rebind");
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
}
