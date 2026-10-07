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
        ResourceLocation diagnosticFrogport = id("diagnostic_frogport");
        ResourceLocation cacheFrogport = id("cache_frogport");
        ResourceLocation logger = id("logger");
        ResourceLocation stackLight = id("stack_light");
        ResourceLocation redSounder = id("red_wall_sounder");
        ResourceLocation orangeSounder = id("orange_wall_sounder");

        helper.forComponents(dock, requester, manual, remotePackager)
                .addStoryBoard("export", DeliveryScenes::export)
                .addStoryBoard("import", DeliveryScenes::receive);
        helper.forComponents(requester, gauge, dock)
                .addStoryBoard("tune", ControlScenes::tune);
        helper.forComponents(monitor, dock)
                .addStoryBoard("status", ControlScenes::status);
        helper.forComponents(towerCore, towerCoupler, etherResonator, towerCasing)
                .addStoryBoard("tower", TowerScenes::tower);
        helper.forComponents(remoteGauge, remoteRedstoneRequester, requester, manual)
                .addStoryBoard("replenish", ControlScenes::replenish);
        helper.forComponents(diagnosticFrogport, cacheFrogport)
                .addStoryBoard("diagnostics", DiagnosticScenes::chain);
        helper.forComponents(logger, stackLight, redSounder, orangeSounder)
                .addStoryBoard("logger", DiagnosticScenes::logger);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, path);
    }
}
