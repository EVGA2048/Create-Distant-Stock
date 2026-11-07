package dev.distantstock.compat.createlazytick;

import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * CreateLazyTick 2.7.32（含）之前的机械手配方缓存认不出序列组装的“当前步骤”。
 *
 * <p>它的命中条件只比较手上的物品有没有换，然后就把上一次缓存的步骤配方原样还回去。序列组装
 * 每一步换的是工作物上的 {@code create:sequenced_assembly} 组件，不是物品本身，于是缓存会一直
 * 返回第一步的配方：机械手照它继续消耗同一种材料，进度却已经走进了下一步。玩家看到的就是
 * “装一个磨制以太石英却跳过了电子管”和“铜板那一步装石英也能过”。上游 issue #47 / #49 与
 * PR #48 记录得比这里清楚；2.7.33 才给重绑定的步骤补上 {@code matches()} 校验。
 *
 * <p>修法采用上游自己给出的临时方案：把 {@code processing.enable_cache_deployer} 关掉。这个
 * 开关在配置加载之后、服务器开始之前通过反射改内存值，不改文件，也不碰它的缓存实现——只要
 * 升级到 2.7.33，{@link #disableBrokenDeployerCache()} 就什么都不做，缓存照常工作。
 *
 * <p>为什么不写一个 mixin 去拦它：它的缓存检查在 {@code getRecipe} 的 HEAD 注入，要保证
 * “清缓存”先于“读缓存”执行，依赖两个模组 mixin 在同一点的注入顺序；顺序在不同 Mixin 版本里
 * 不是可以随便押的东西。一个开关没有这个风险。
 */
public final class CreateLazyTickCompat {
    private static final Logger LOG = LogManager.getLogger();

    private static final String MOD_ID = "createlazytick";
    private static final String CONFIG_CLASS = "net.pinkcats.createlazytick.config.ServerConfig";
    private static final String CACHE_FIELD = "ENABLE_CACHE_DEPLOYER";
    private static final int FIXED_MAJOR = 2;
    private static final int FIXED_MINOR = 7;
    private static final int FIXED_PATCH = 33;

    public static void disableBrokenDeployerCache() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        String version = version();
        if (version == null || !isBefore(version, FIXED_MAJOR, FIXED_MINOR, FIXED_PATCH)) {
            return;
        }
        try {
            Class<?> config = Class.forName(CONFIG_CLASS);
            Field field = config.getDeclaredField(CACHE_FIELD);
            field.setAccessible(true);
            Object value = field.get(null);
            Method set = value.getClass().getMethod("set", Object.class);
            set.invoke(value, Boolean.FALSE);
            // set() only touches cachedValue when the spec allows live changes; clear it so a
            // restart-required spec also reads the new value on the next getEnableCacheDeployer().
            value.getClass().getMethod("clearCache").invoke(value);
            LOG.warn("[DistantStock/CreateLazyTick] {} 的机械手配方缓存不会校验序列组装的当前步骤，"
                            + "会把同一种材料跨步骤重复使用（上游 #47，2.7.33 修复）。已临时关闭 "
                            + "processing.enable_cache_deployer；升级到 2.7.33+ 后本开关不再生效。",
                    version);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOG.warn("[DistantStock/CreateLazyTick] {} 的机械手配方缓存会错误推进序列组装，"
                            + "且无法自动关闭 processing.enable_cache_deployer；请升级到 2.7.33+，"
                            + "或手动把该配置设为 false。", version, exception);
        }
    }

    private static String version() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    /** Numeric-prefix comparison; CreateLazyTick versions look like {@code 2.7.32-6.0.10}. */
    private static boolean isBefore(String version, int major, int minor, int patch) {
        String[] parts = version.split("[.-]");
        try {
            int actualMajor = Integer.parseInt(parts[0]);
            int actualMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int actualPatch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (actualMajor != major) {
                return actualMajor < major;
            }
            if (actualMinor != minor) {
                return actualMinor < minor;
            }
            return actualPatch < patch;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private CreateLazyTickCompat() {
    }
}
