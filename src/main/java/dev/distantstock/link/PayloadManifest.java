package dev.distantstock.link;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Minimal registry manifest used before the target attempts to decode a parcel. */
public record PayloadManifest(List<String> itemIds, List<String> componentIds) {
    public static final int MAX_ENTRIES = 2048;
    public static final PayloadManifest EMPTY = new PayloadManifest(List.of(), List.of());

    public PayloadManifest {
        itemIds = List.copyOf(itemIds);
        componentIds = List.copyOf(componentIds);
        if (itemIds.size() > MAX_ENTRIES || componentIds.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Payload manifest is too large");
        }
    }

    public static PayloadManifest fromPackage(ItemStack parcel) {
        Set<String> items = new TreeSet<>();
        Set<String> components = new TreeSet<>();
        collect(parcel, items, components, 0);
        if (PackageItem.isPackage(parcel)) {
            var contents = PackageItem.getContents(parcel);
            for (int slot = 0; slot < contents.getSlots(); slot++) {
                collect(contents.getStackInSlot(slot), items, components, 0);
            }
        }
        return new PayloadManifest(new ArrayList<>(items), new ArrayList<>(components));
    }

    public List<String> missingRegistryEntries() {
        List<String> missing = new ArrayList<>();
        for (String id : itemIds) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
                missing.add("item:" + id);
            }
        }
        for (String id : componentIds) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null || !BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(key)) {
                missing.add("component:" + id);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Registry entries this single stack references, in the same {@code item:} / {@code component:} form
     * as {@link #missingRegistryEntries()}, so a strip notice can be matched against package contents.
     * Nested containers, bundles and charged projectiles are walked exactly like the parcel manifest.
     */
    public static List<String> entriesOf(ItemStack stack) {
        Set<String> items = new TreeSet<>();
        Set<String> components = new TreeSet<>();
        collect(stack, items, components, 0);
        List<String> entries = new ArrayList<>(items.size() + components.size());
        for (String id : items) {
            entries.add("item:" + id);
        }
        for (String id : components) {
            entries.add("component:" + id);
        }
        return List.copyOf(entries);
    }

    private static void collect(ItemStack stack, Set<String> items, Set<String> components, int depth) {
        if (stack.isEmpty() || depth > 8) {
            return;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) {
            items.add(itemId.toString());
        }
        for (var type : stack.getComponents().keySet()) {
            ResourceLocation componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (componentId != null) {
                components.add(componentId.toString());
            }
        }
        var container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            container.nonEmptyItems().forEach(nested -> collect(nested, items, components, depth + 1));
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.items().forEach(nested -> collect(nested, items, components, depth + 1));
        }
        var projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null) {
            projectiles.getItems().forEach(nested -> collect(nested, items, components, depth + 1));
        }
    }
}
