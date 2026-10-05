package dev.distantstock;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import dev.distantstock.block.AnnouncerBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.display.AnnouncerDisplayTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class AnnouncerGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void templateParametersRenderAsPlainText(GameTestHelper h) {
        BlockPos relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, ModBlocks.ANNOUNCER.get().defaultBlockState());
        BlockPos absolute = h.absolutePos(relative);
        h.assertTrue(h.getLevel().getBlockEntity(absolute) instanceof AnnouncerBlockEntity,
                "announcer block did not create its block entity");
        AnnouncerBlockEntity be = (AnnouncerBlockEntity) h.getLevel().getBlockEntity(absolute);
        be.configure("反应堆读数异常：{1}；值班：{2}", 999, 1, "E1仓库");
        be.setParameter(0, "520 MW");
        be.setParameter(1, "轻而易举啊");
        h.assertTrue("反应堆读数异常：520 MW；值班：轻而易举啊".equals(be.renderMessage()),
                "template parameters were not substituted literally: " + be.renderMessage());
        h.assertTrue("[E1仓库] 反应堆读数异常：520 MW；值班：轻而易举啊".equals(be.renderBroadcastMessage()),
                "announcer prefix was not formatted with fixed square brackets: " + be.renderBroadcastMessage());
        h.assertTrue(be.radius() == AnnouncerBlockEntity.MAX_RADIUS,
                "announcer radius was not clamped to the safe maximum");
        h.assertTrue(be.soundProfile() == 1,
                "announcer sound profile was not stored");
        be.configure(be.template(), be.radius(), 2);
        h.assertTrue(be.soundProfile() == 2,
                "announcer did not accept the relay buzzer profile");
        be.configure(be.template(), be.radius(), 99);
        h.assertTrue(be.soundProfile() == 2,
                "announcer sound profile was not clamped to a valid choice");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void displayLinkExposesFourParameterRows(GameTestHelper h) {
        DisplayTarget target = DisplayTarget.BY_BLOCK.get(ModBlocks.ANNOUNCER.get());
        h.assertTrue(target instanceof AnnouncerDisplayTarget,
                "announcer was not registered as a Create Display Link target");
        var stats = target.provideStats(null);
        h.assertTrue(stats.maxRows() == AnnouncerBlockEntity.PARAMETER_COUNT,
                "Display Link sees " + stats.maxRows() + " rows instead of four parameters");
        h.assertTrue(stats.maxColumns() == AnnouncerBlockEntity.MAX_PARAMETER,
                "Display Link target width does not match announcer parameter capacity");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void networkBroadcasterUsesCreateNetworkAndDisplayLink(GameTestHelper h) {
        BlockPos relative = new BlockPos(2, 1, 2);
        h.setBlock(relative, ModBlocks.NETWORK_BROADCASTER.get().defaultBlockState());
        BlockPos absolute = h.absolutePos(relative);
        h.assertTrue(h.getLevel().getBlockEntity(absolute) instanceof dev.distantstock.block.NetworkBroadcasterBlockEntity,
                "network broadcaster did not create its block entity");
        var be = (dev.distantstock.block.NetworkBroadcasterBlockEntity) h.getLevel().getBlockEntity(absolute);
        h.assertTrue(be.networkId() != null, "network broadcaster did not expose a Create logistics network UUID");
        h.assertTrue(com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
                        h.getLevel(), absolute,
                        com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.TYPE) != null,
                "network broadcaster is missing Create LogisticallyLinkedBehaviour");
        DisplayTarget target = DisplayTarget.BY_BLOCK.get(ModBlocks.NETWORK_BROADCASTER.get());
        h.assertTrue(target instanceof AnnouncerDisplayTarget,
                "network broadcaster was not registered as a Display Link target");
        h.succeed();
    }

    private AnnouncerGameTests() {
    }
}
