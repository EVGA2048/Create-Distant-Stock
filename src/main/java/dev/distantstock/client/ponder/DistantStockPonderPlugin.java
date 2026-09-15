package dev.distantstock.client.ponder;

import dev.distantstock.DistantStock;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class DistantStockPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return DistantStock.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation dock = id("dock");
        ResourceLocation requester = id("requester");
        ResourceLocation gauge = id("gauge");
        ResourceLocation monitor = id("monitor");
        ResourceLocation remotePackager = id("remote_packager");
        ResourceLocation manual = id("manual");
        ResourceLocation towerCore = id("tower_core");
        ResourceLocation towerCoupler = id("tower_coupler");
        ResourceLocation etherResonator = id("ether_resonator");
        ResourceLocation towerCasing = id("tower_casing");
        ResourceLocation remoteGauge = id("remote_gauge");
        ResourceLocation remoteRedstoneRequester = id("remote_redstone_requester");

        helper.forComponents(dock, requester, manual, remotePackager)
                .addStoryBoard("export", DistantStockScenes::export)
                .addStoryBoard("import", DistantStockScenes::receive);
        helper.forComponents(requester, gauge, dock)
                .addStoryBoard("tune", DistantStockScenes::tune);
        helper.forComponents(monitor, dock)
                .addStoryBoard("status", DistantStockScenes::status);
        // 塔的四个方块都挂同一场戏：玩家手上拿着哪一块，想学的都是同一座塔怎么立起来。
        helper.forComponents(towerCore, towerCoupler, etherResonator, towerCasing)
                .addStoryBoard("tower", DistantStockScenes::tower);
        helper.forComponents(remoteGauge, remoteRedstoneRequester, requester, manual)
                .addStoryBoard("replenish", DistantStockScenes::replenish);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, path);
    }
}
