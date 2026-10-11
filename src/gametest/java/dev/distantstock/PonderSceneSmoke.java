package dev.distantstock;

import com.mojang.logging.LogUtils;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Map;

/** Validate real registry states, localized captions and registration without opening a world. */
final class PonderSceneSmoke {
    static void check(Minecraft mc) throws Exception {
        var lines = Map.ofEntries(Map.entry("export",5),Map.entry("import",3),Map.entry("tune",5),
                Map.entry("status",5),Map.entry("tower",7),Map.entry("replenish",5),
                Map.entry("redstone",4),Map.entry("diagnostics",5),Map.entry("cache",4),
                Map.entry("logger",6),Map.entry("alarms",4));
        for (var entry : lines.entrySet()) {
            var resource = mc.getResourceManager().getResource(id("ponder/"+entry.getKey()+".nbt")).orElseThrow();
            try (var stream=resource.open()) {
                var nbt=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap());
                var palette=nbt.getList("palette",Tag.TAG_COMPOUND);
                for(int i=0;i<palette.size();i++) {
                    var state=palette.getCompound(i);
                    var key=ResourceLocation.parse(state.getString("Name"));
                    if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new AssertionError("Unknown Ponder block: "+key);
                    var block=BuiltInRegistries.BLOCK.get(key);
                    var properties=state.getCompound("Properties");
                    for(String name:properties.getAllKeys()) {
                        var property=block.getStateDefinition().getProperty(name);
                        if(property==null || property.getValue(properties.getString(name)).isEmpty())
                            throw new AssertionError(entry.getKey()+": invalid "+key+" "+name+"="+properties.getString(name));
                    }
                }
            }
            var language=net.minecraft.locale.Language.getInstance();
            String prefix="distantstock.ponder.distant_"+entry.getKey()+".";
            if(!language.has(prefix+"header")) throw new AssertionError("Missing title: "+prefix);
            for(int i=1;i<=entry.getValue();i++)
                if(!language.has(prefix+"text_"+i)) throw new AssertionError("Missing caption: "+prefix+i);
        }
        var seen=new HashSet<ResourceLocation>();
        for(var entry:PonderIndex.getSceneAccess().getRegisteredEntries()) {
            var board=entry.getValue();
            if(board.getNamespace().equals(DistantStock.MODID)) seen.add(board.getSchematicLocation());
        }
        if(seen.size()!=lines.size()) throw new AssertionError("Expected "+lines.size()+" registered scenes, found "+seen);
        for(String name:lines.keySet())
            if(!seen.contains(id(name))) throw new AssertionError("Unregistered storyboard: "+name);
        LogUtils.getLogger().info("DISTANTSTOCK_PONDER_RESOURCES_OK: {} scenes have valid registry states, registration and captions",seen.size());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantStock.MODID,path);
    }
}
