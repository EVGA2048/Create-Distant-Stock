package dev.distantstock.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps optional compatibility mixins completely invisible when their target addon is absent.
 *
 * Mixin config loading happens before normal mod lifecycle callbacks, so probing the target class
 * is more reliable here than asking ModList. No FluidLogistics class is linked from this plugin.
 */
public final class DistantStockMixinPlugin implements IMixinConfigPlugin {
    private boolean fluidLogisticsPresent;

    @Override
    public void onLoad(String mixinPackage) {
        fluidLogisticsPresent = resourcePresent("com/yision/fluidlogistics/item/FluidPackageItem.class");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".compat.FluidPackagerBlockEntityMixin")) {
            return fluidLogisticsPresent;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean resourcePresent(String path) {
        // Do not Class.forName() optional addon classes here. FluidPackageItem extends Create's
        // PackageItem, and resolving it during mixin config selection loads PackageItem before
        // other mods (notably Deployer) get a chance to transform it.
        return DistantStockMixinPlugin.class.getClassLoader().getResource(path) != null;
    }
}
