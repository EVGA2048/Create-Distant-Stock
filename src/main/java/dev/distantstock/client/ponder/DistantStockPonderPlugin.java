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
        ResourceLocation manual = id("manual");

        helper.forComponents(dock, requester, manual)
                .addStoryBoard("export", DistantStockScenes::export)
                .addStoryBoard("import", DistantStockScenes::receive);
        helper.forComponents(requester, gauge, dock)
                .addStoryBoard("tune", DistantStockScenes::tune);
        helper.forComponents(monitor, dock)
                .addStoryBoard("status", DistantStockScenes::status);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, path);
    }
}
