package dev.distantstock;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.block.LampState;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.SignalLampPanelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class LampMonitorGameTests {

    private static final FactoryPanelBlock.PanelSlot SLOT = FactoryPanelBlock.PanelSlot.BOTTOM_LEFT;

    private static BlockState panelState() {
        return ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.NORTH);
    }

    /** Places a signal panel carrying one brass lamp, bound to a frequency. */
    private static SignalPanelBlockEntity boundLamp(GameTestHelper h, BlockPos pos, UUID freq) {
        var level = h.getLevel();
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, panelState(), 3);
        var board = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        SignalLampPanelItem.finishPlacement(board, SLOT,
                new ItemStack(ModItems.BRASS_SIGNAL_LAMP.get()));
        board.setLampNetwork(SLOT, freq);
        return board;
    }

    /**
     * A watch list has to survive a save/load. Losing it would quietly turn a configured lamp back
     * into a plain network lamp, which reads on screen as "the items were never added".
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void watchListSurvivesReload(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        UUID freq = UUID.randomUUID();
        SignalPanelBlockEntity board = boundLamp(h, pos, freq);
        board.monitor(SLOT).setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        board.monitor(SLOT).setItem(2, new ItemStack(Items.COPPER_INGOT));

        CompoundTag saved = board.saveWithoutMetadata(level.registryAccess());
        // A real chunk reload, so the restored behaviour sits on a block entity with a live level.
        level.removeBlock(pos, false);
        level.setBlock(pos, panelState(), 3);
        var reloaded = (SignalPanelBlockEntity) level.getBlockEntity(pos);
        reloaded.loadWithComponents(saved, level.registryAccess());

        h.assertTrue(reloaded.isLamp(SLOT), "the lamp itself was lost");
        h.assertTrue(freq.equals(reloaded.lampNetwork(SLOT)), "the network binding was lost");
        h.assertTrue(reloaded.monitor(SLOT).getItem(0).is(Items.IRON_INGOT),
                "the first watched item was lost");
        h.assertTrue(reloaded.monitor(SLOT).getItem(0).getCount() == 3,
                "the watched item count was lost");
        h.assertTrue(reloaded.monitor(SLOT).getItem(1).isEmpty(),
                "an empty watch slot gained an item");
        h.assertTrue(reloaded.monitor(SLOT).getItem(2).is(Items.COPPER_INGOT),
                "the second watched item lost its slot");
        h.succeed();
    }

    /**
     * An unreachable network outranks the watch list. Every watched item reads "no stock" with no
     * network behind it, so deferring to the list would show a plain shortage instead of the fault.
     */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void missingNetworkOutranksTheWatchList(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        SignalPanelBlockEntity board = boundLamp(h, pos, UUID.randomUUID());
        board.monitor(SLOT).setItem(0, new ItemStack(Items.IRON_INGOT));

        h.runAfterDelay(40, () -> {
            h.assertTrue(board.lampState(SLOT) == LampState.FATAL,
                    "a missing network should report a fault, got " + board.lampState(SLOT));
            h.succeed();
        });
    }

    /** The watch list is eight slots; the screen and the sync payload are sized to that. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void watchListHasEightSlots(GameTestHelper h) {
        SignalPanelBlockEntity board = boundLamp(h, h.absolutePos(new BlockPos(2, 2, 2)), UUID.randomUUID());
        h.assertTrue(SignalPanelBlockEntity.MONITOR_SLOTS == 8, "the watch list changed size");
        h.assertTrue(board.monitor(SLOT).getContainerSize() == SignalPanelBlockEntity.MONITOR_SLOTS,
                "the watch container is not sized to the watch list");
        h.succeed();
    }

    private LampMonitorGameTests() {
    }
}
