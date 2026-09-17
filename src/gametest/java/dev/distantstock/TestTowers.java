package dev.distantstock;

import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 测试里的"某座塔正带着这台设备"。
 *
 * <p>塔的硬门槛（没塔不能用）让每一个"这台港要能收发"的用例都必须先说明它被谁带着。测试世界不为
 * 每个用例搭一座真塔：gametest 全体共用一个 level 而且并行跑，一座真的、在转的塔会 claim 整个维度、
 * 把别的用例的设备全黑掉（{@link TowerActivation} 的注释里写着这件事）。所以这里显式钉住 ——
 * 规则本身由 {@code TowerActivationGameTests} 守着，其余用例只需要"这台设备能工作"这个前提。
 *
 * <p>钉住是**按坐标**的，而且不需要撤销：用例各占自己那一角，谁也碰不到谁。这正是当初把接缝做成
 * 按设备而不是按世界的原因。
 */
final class TestTowers {
    /**
     * 这台设备是开着的，而且**没有任何塔在替它记账**。
     *
     * <p>carrier 故意留空。填一个"某座塔"的 id 会把它带进扣费那条路（{@code TowerBilling} 会去
     * 查那座塔的方块实体，测试世界里当然查不到），于是一次正常的发货被当成"塔付不起以太"而卡住 ——
     * 那正是这两条用例第一次改完就红的原因。这里要说的是"这台设备可以工作"，不是"它的塔在扣费"。
     */
    static void carried(GameTestHelper h, BlockPos pos) {
        TowerActivation.pinDevice(TowerSystem.TowerId.of(h.getLevel().dimension(), pos), true, null);
    }

    private TestTowers() {
    }
}
